package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun loadConversations(): List<ChatConversation> =
        decode(api.execute(ApiRequest(path = "chat/conversations")))

    fun loadPresence(userIds: List<Long>): Map<Long, ChatPresence> {
        if (userIds.isEmpty()) return emptyMap()
        val ids = userIds.distinct().joinToString(",")
        val raw = json.parseToJsonElement(
            api.execute(ApiRequest(path = "chat/presence?userIds=$ids")).bodyAsString(),
        ).jsonObject
        return raw.mapNotNull { (key, value) ->
            key.toLongOrNull()?.let { it to json.decodeFromJsonElement<ChatPresence>(value) }
        }.toMap()
    }

    fun searchUsers(term: String): List<ChatUser> {
        if (term.trim().length < 2) return emptyList()
        return decode(api.execute(ApiRequest(path = "chat/search?q=${URLEncoder.encode(term.trim().take(100), "UTF-8")}")))
    }

    fun createDirect(userId: Long): ConversationCreated =
        mutate("chat/conversations", DirectConversationRequest(userId))

    fun markRead(conversationId: Long): ChatOk =
        mutate("chat/conversations/$conversationId/read", Unit)

    fun loadMessages(conversationId: Long, before: Long? = null): List<ChatMessage> {
        // @api GET chat/conversations/:conversationId/messages
        val suffix = before?.let { "?limit=50&before=$it" } ?: "?limit=50"
        return decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/messages$suffix")))
    }

    fun loadReadReceipts(conversationId: Long): List<ReadReceipt> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/read-status")))

    fun toggleReaction(messageId: Long, emoji: String): ChatOk =
        mutate("chat/messages/$messageId/reactions", ReactionRequest(emoji))

    fun editMessage(messageId: Long, content: String): ChatOk =
        mutate("chat/messages/$messageId", EditMessageRequest(content.trim()), method = "PUT")

    fun deleteMessage(messageId: Long): ChatOk =
        mutate("chat/messages/$messageId", Unit, method = "DELETE")

    fun toggleStar(messageId: Long): ToggleStarResponse =
        mutate("chat/messages/$messageId/star", Unit)

    fun toggleMessagePin(messageId: Long): ToggleMessagePinResponse =
        mutate("chat/messages/$messageId/pin", Unit)

    fun forwardMessage(messageId: Long, conversationIds: List<Long>): ChatOk {
        require(conversationIds.isNotEmpty()) { "Choose at least one conversation" }
        require(conversationIds.size <= 20) { "Choose no more than 20 conversations" }
        return mutate("chat/messages/$messageId/forward", ForwardMessageRequest(conversationIds.distinct()))
    }

    fun uploadFile(conversationId: Long, upload: ChatUpload): ChatMessage {
        val boundary = "aino-${UUID.randomUUID()}"
        val multipart = buildChatMultipart(upload, boundary)
        try {
            return decode(
                api.execute(
                    ApiRequest(
                        "POST",
                        "chat/conversations/$conversationId/files",
                        headers = mapOf("Content-Type" to multipart.contentType),
                        body = multipart.body,
                    ),
                ),
            )
        } catch (error: ApiError.Http) {
            val message = runCatching {
                json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: "File upload failed"
            throw ChatFailure(message, error.statusCode, error)
        }
    }

    fun cancelMediaJob(mediaJobId: Long): MediaJobResponse =
        mutate("chat/media-jobs/$mediaJobId/cancel", Unit)

    fun retryMediaJob(mediaJobId: Long): MediaJobResponse =
        mutate("chat/media-jobs/$mediaJobId/retry", Unit)

    fun loadCalls(): List<CallLog> = decode(api.execute(ApiRequest(path = "chat/calls")))

    fun loadConversationCalls(conversationId: Long): List<CallLog> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/calls")))

    fun loadMembers(conversationId: Long): List<ConversationMember> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/members")))

    fun togglePinConversation(conversationId: Long): TogglePinResponse =
        mutate("chat/conversations/$conversationId/pin", Unit)

    fun toggleFavouriteConversation(conversationId: Long): ToggleFavouriteResponse =
        mutate("chat/conversations/$conversationId/favourite", Unit)

    fun setMute(conversationId: Long, duration: String?): ToggleMuteResponse =
        mutate("chat/conversations/$conversationId/mute", MuteRequest(duration))

    fun toggleArchive(conversationId: Long): ToggleArchiveResponse =
        mutate("chat/conversations/$conversationId/archive", Unit)

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            val message = runCatching {
                json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: "Chat action failed"
            throw ChatFailure(message, error.statusCode, error)
        }
    }

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class ChatCache(private val scope: CacheScope, private val cache: ScopedCache) {
    suspend fun replace(conversations: List<ChatConversation>) =
        cache.replaceConversations(conversations.map { it.toEntity(scope) })

    suspend fun snapshot(): List<ChatConversation> =
        cache.conversationSnapshot().map { it.toCachedConversation() }

    suspend fun replaceMessages(conversationId: Long, messages: List<ChatMessage>) =
        cache.replaceMessages(conversationId, messages.map { it.toEntity(scope, conversationId) })

    suspend fun messageSnapshot(conversationId: Long): List<ChatMessage> =
        cache.messageSnapshot(conversationId).map { it.toCachedMessage() }
}

class ChatFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)