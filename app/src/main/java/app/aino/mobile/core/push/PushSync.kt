package app.aino.mobile.core.push

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.db.AinoDatabase
import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.MessageEntity
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.db.SessionScopeStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ResponseCache
import java.time.Instant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Push wake (Signal behaviour: the data is local before the notification is
 * tapped). A chat push pulls the thread's new rows into the tenant+user scoped
 * Room cache, so tapping the notification opens the thread from disk at once.
 * The HTTP response cache is kept in step for the warm (cache-only) readers.
 * Best effort; the app revalidates on open anyway.
 */
object PushSync {
    private val updates = MutableSharedFlow<Long>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Conversations whose stored rows a push wake just wrote (the chat screen merges them in). */
    val threadUpdates: SharedFlow<Long> = updates.asSharedFlow()

    suspend fun prefetchChat(context: Context, conversationId: Long, onThreadStored: () -> Unit = {}) {
        val container = AppContainer.get(context)
        // Cold process started by FCM: the Activity never ran, restore the signed-in scope.
        val scope = SessionScopeStore(context).read() ?: return
        val responses = container.responses
        if (responses.scope == null) responses.scope = "${scope.tenantId}_${scope.userId}"
        val cache = ScopedCache(scope, AinoDatabase.get(context).dao())
        syncChatThread(container.api, cache, scope, conversationId, responses)
        updates.tryEmit(conversationId)
        onThreadStored()
        syncConversationRow(container.api, cache, conversationId)
    }


    /** Clear / delete for me: the warm thread page must never repaint the old history. */
    fun forgetThread(context: Context, conversationId: Long) {
        AppContainer.get(context).responses.put(threadPagePath(conversationId), "[]".toByteArray())
    }

    /** Rows of the newest thread page the app keeps (`ChatRepository.loadMessages`). */
    internal const val THREAD_PAGE = 50

    /** One `?after=` request; a delta this large may skip rows, so the stored thread restarts from it. */
    internal const val DELTA_PAGE = 100

    internal fun threadPagePath(conversationId: Long) = "chat/conversations/$conversationId/messages?limit=$THREAD_PAGE"
}

/**
 * Fetches the rows after the newest stored message (the latest page when none
 * is stored) and writes them to Room. Returns the rows written.
 */
// @api GET chat/conversations/:id/messages
internal suspend fun syncChatThread(
    api: ApiClient,
    cache: ScopedCache,
    scope: CacheScope,
    conversationId: Long,
    responses: ResponseCache? = null,
): List<MessageEntity> {
    val lastId = cache.messageSnapshot(conversationId).maxOfOrNull(MessageEntity::messageId)
    val threadPath = PushSync.threadPagePath(conversationId)
    var body = if (lastId == null) {
        api.execute(ApiRequest(path = threadPath)).bodyAsString() // also recorded as the warm thread page
    } else {
        api.execute(ApiRequest(path = "chat/conversations/$conversationId/messages?limit=${PushSync.DELTA_PAGE}&after=$lastId")).bodyAsString()
    }
    var rows = messageEntitiesOf(body, scope, conversationId)
    var latestPage = lastId == null
    // `after` returns the OLDEST rows past the cursor: a full batch may stop short of the
    // pushed message, so store the newest page instead of an old window.
    if (lastId != null && rows.size >= PushSync.DELTA_PAGE) {
        body = api.execute(ApiRequest(path = threadPath)).bodyAsString()
        rows = messageEntitiesOf(body, scope, conversationId)
        latestPage = true
    }
    // Nothing stored, or a delta that may not reach the stored rows: restart from the server page.
    if (latestPage || rows.size >= PushSync.THREAD_PAGE) cache.replaceMessages(conversationId, rows)
    else if (rows.isNotEmpty()) cache.upsertMessages(rows)
    if (lastId != null && !latestPage && rows.isNotEmpty()) {
        responses?.get(threadPath)?.toString(Charsets.UTF_8)?.let { cached ->
            responses.put(threadPath, mergeMessagesJson(cached, body).toByteArray())
        }
    }
    return rows
}

/**
 * Refreshes the conversation list (warm cache) and the stored row's unread
 * count / ordering, so the list and the unread divider match before the tap.
 */
internal suspend fun syncConversationRow(api: ApiClient, cache: ScopedCache, conversationId: Long) {
    val list = runCatching { api.execute(ApiRequest(path = "chat/conversations")).bodyAsString() }.getOrNull() ?: return
    val stored = cache.conversationSnapshot().firstOrNull { it.conversationId == conversationId } ?: return
    val row = runCatching {
        Json.parseToJsonElement(list).jsonArray.firstOrNull { (it as? JsonObject)?.long("id") == conversationId }?.jsonObject
    }.getOrNull() ?: return
    val unread = row["unread_count"]?.jsonPrimitive?.intOrNull ?: stored.unreadCount
    val updatedAt = (row.string("last_message_at") ?: row.string("updated_at"))?.let(::epochMs) ?: stored.updatedAtEpochMs
    cache.upsertConversations(listOf(stored.copy(unreadCount = unread.coerceAtLeast(0), updatedAtEpochMs = updatedAt)))
}

/**
 * Server message rows as Room entities. The full row is kept as the payload,
 * exactly as the chat screen stores the rows it loads itself.
 */
internal fun messageEntitiesOf(json: String, scope: CacheScope, conversationId: Long): List<MessageEntity> =
    runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull().orEmpty().mapNotNull { element ->
        val row = element as? JsonObject ?: return@mapNotNull null
        val id = row.long("id") ?: return@mapNotNull null
        val createdAt = row.string("created_at") ?: return@mapNotNull null
        val rowConversation = row.long("conversation_id") ?: conversationId
        if (rowConversation != conversationId) return@mapNotNull null
        MessageEntity(
            tenantId = scope.tenantId,
            userId = scope.userId,
            messageId = id,
            conversationId = conversationId,
            senderId = row.long("sender_id") ?: 0,
            body = when {
                row.string("deleted_at") != null -> "Message deleted"
                else -> row.string("content")?.takeIf(String::isNotBlank) ?: row.string("file_name").orEmpty()
            },
            createdAtEpochMs = epochMs(createdAt) ?: 0,
            deliveryState = "sent",
            payloadJson = row.toString(),
        )
    }

/** Highest message id in a cached thread page, or null when it holds none. */
internal fun lastMessageId(json: String): Long? =
    runCatching { Json.parseToJsonElement(json).jsonArray.mapNotNull(::messageId).maxOrNull() }.getOrNull()

/**
 * Appends a `?after=` delta to a cached thread page: de-duplicated by id
 * (the delta wins), oldest first, trimmed to the newest [keep] messages.
 */
internal fun mergeMessagesJson(cached: String?, delta: String, keep: Int = 50): String {
    val old = cached?.let { runCatching { Json.parseToJsonElement(it).jsonArray }.getOrNull() }.orEmpty()
    val new = Json.parseToJsonElement(delta).jsonArray
    val byId = sortedMapOf<Long, JsonElement>()
    (old + new).forEach { element -> messageId(element)?.let { byId[it] = element } }
    return JsonArray(byId.values.toList().takeLast(keep)).toString()
}

private fun messageId(element: JsonElement): Long? =
    runCatching { element.jsonObject["id"]?.jsonPrimitive?.longOrNull }.getOrNull()

private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun epochMs(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
