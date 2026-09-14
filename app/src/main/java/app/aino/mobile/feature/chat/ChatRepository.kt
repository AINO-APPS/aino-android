package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.net.URLEncoder
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

    private inline fun <reified T, reified R> mutate(path: String, body: T): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest("POST", path, body = bytes)))
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
}

class ChatFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)