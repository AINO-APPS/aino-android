package app.aino.mobile.core.call

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** `GET chat/calls/:callId`; `status` ∈ ringing | answered | declined | missed | ended. */
@Serializable
data class CallStatusDto(
    val id: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("caller_id") val callerId: Long? = null,
    @SerialName("call_type") val callType: String = "voice",
    val status: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    val duration: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable private data class CallConversationBody(val conversationId: Long)

/** Call lifecycle HTTP fallbacks that core (ring service, push, reconcile) needs without the chat feature. */
class CallApi(private val api: ApiClient) {
    /** Callee ack for a device woken by push without a socket (WS `call_ringing` equivalent). */
    fun ringing(callId: Long, conversationId: Long) {
        // @api POST chat/calls/:callId/ringing
        api.execute(ApiRequest("POST", "chat/calls/$callId/ringing", body = body(conversationId)))
    }

    /** Caller abort before `call_started` delivered an id, when the socket cannot carry `call_cancel`. */
    fun cancel(conversationId: Long) {
        api.execute(ApiRequest("POST", "chat/calls/cancel", body = body(conversationId)))
    }

    fun status(callId: Long): CallStatusDto {
        // @api GET chat/calls/:callId
        val raw = JSON.parseToJsonElement(api.execute(ApiRequest("GET", "chat/calls/$callId")).bodyAsString())
        val element = (raw as? JsonObject)?.get("call") as? JsonObject ?: raw
        return JSON.decodeFromJsonElement(element)
    }

    private fun body(conversationId: Long): ByteArray = JSON.encodeToString(CallConversationBody(conversationId)).toByteArray()

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
