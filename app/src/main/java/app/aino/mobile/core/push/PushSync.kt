package app.aino.mobile.core.push

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.db.SessionScopeStore
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Slack-style push wake: a chat push pulls the new messages into the local
 * response cache right away, so opening the app (or the notification) shows
 * the thread instantly instead of loading it. Best effort; the app revalidates
 * on open anyway.
 */
object PushSync {
    suspend fun prefetchChat(context: Context, conversationId: Long) {
        val container = AppContainer.get(context)
        val responses = container.responses
        if (responses.scope == null) {
            // Cold process started by FCM: the Activity never ran, restore the signed-in scope.
            val scope = SessionScopeStore(context).read() ?: return
            responses.scope = "${scope.tenantId}_${scope.userId}"
        }
        val threadPath = "chat/conversations/$conversationId/messages?limit=$THREAD_PAGE"
        val cached = responses.get(threadPath)?.toString(Charsets.UTF_8)
        val lastId = cached?.let(::lastMessageId)
        if (lastId == null) {
            container.api.execute(ApiRequest(path = threadPath)) // cached as the thread snapshot
        } else {
            val delta = container.api.execute(ApiRequest(path = "chat/conversations/$conversationId/messages?limit=100&after=$lastId"))
            responses.put(threadPath, mergeMessagesJson(cached, delta.bodyAsString()).toByteArray())
        }
        container.api.execute(ApiRequest(path = "chat/conversations")) // previews + unread counts
    }

    private const val THREAD_PAGE = 50
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
