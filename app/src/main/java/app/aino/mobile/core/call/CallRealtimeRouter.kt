package app.aino.mobile.core.call

import app.aino.mobile.core.realtime.RealtimeEnvelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

sealed interface CallRealtimeEvent {
    val conversationId: Long?

    data class Incoming(
        val callId: Long,
        override val conversationId: Long,
        val callerId: Long?,
        val callerName: String,
        val callerAvatar: String?,
        val callType: String,
        val isGroup: Boolean,
        /** Group-call (huddle) rings carry the meeting to join instead of a 1:1 call. */
        val meetingCode: String? = null,
        val meetingId: Long? = null,
    ) : CallRealtimeEvent

    data class Started(val callId: Long, override val conversationId: Long, val callType: String = "voice") : CallRealtimeEvent
    data class Accepted(val callId: Long, override val conversationId: Long, val userId: Long?) : CallRealtimeEvent
    data class Rejected(val callId: Long, override val conversationId: Long) : CallRealtimeEvent
    data class Ended(val callId: Long?, override val conversationId: Long?, val reason: String?) : CallRealtimeEvent
    data class HandledElsewhere(val callId: Long, override val conversationId: Long, val action: String) : CallRealtimeEvent
    data class Busy(override val conversationId: Long, val reason: String?) : CallRealtimeEvent
    data class Error(override val conversationId: Long?, val reason: String?) : CallRealtimeEvent
    data class PeerReady(val callId: Long, override val conversationId: Long, val userId: Long?) : CallRealtimeEvent
    data class Reconnect(val callId: Long, override val conversationId: Long, val userId: Long?) : CallRealtimeEvent
    data class Signal(
        override val conversationId: Long,
        val fromUserId: Long?,
        val signalId: String?,
        val signal: JsonObject,
    ) : CallRealtimeEvent
}

object CallRealtimeRouter {
    fun decode(envelope: RealtimeEnvelope): CallRealtimeEvent? {
        val data = envelope.data as? JsonObject ?: return null
        return when (envelope.type) {
            "call_incoming" -> CallRealtimeEvent.Incoming(
                callId = data.positiveLong("callId") ?: return null,
                conversationId = data.positiveLong("conversationId") ?: return null,
                callerId = data.positiveLong("callerId"),
                callerName = data.string("callerName").orEmpty(),
                callerAvatar = data.string("callerAvatar"),
                callType = if (data.string("callType") == "video") "video" else "voice",
                isGroup = data.boolean("isGroup") ?: false,
                meetingCode = data.string("meetingCode"),
                meetingId = data.positiveLong("meetingId"),
            )
            "call_started" -> idPair(data)?.let {
                CallRealtimeEvent.Started(it.first, it.second, if (data.string("callType") == "video") "video" else "voice")
            }
            "call_accepted" -> idPair(data)?.let { CallRealtimeEvent.Accepted(it.first, it.second, data.positiveLong("userId")) }
            "call_rejected" -> idPair(data)?.let { CallRealtimeEvent.Rejected(it.first, it.second) }
            "call_ended" -> CallRealtimeEvent.Ended(data.positiveLong("callId"), data.positiveLong("conversationId"), data.string("reason"))
            "call_handled_elsewhere" -> idPair(data)?.let {
                val action = data.string("action") ?: return null
                if (action !in setOf("accepted", "rejected")) return null
                CallRealtimeEvent.HandledElsewhere(it.first, it.second, action)
            }
            "call_busy" -> data.positiveLong("conversationId")?.let { CallRealtimeEvent.Busy(it, data.string("reason")) }
            "call_error" -> CallRealtimeEvent.Error(data.positiveLong("conversationId"), data.string("reason"))
            "call_peer_ready" -> idPair(data)?.let { CallRealtimeEvent.PeerReady(it.first, it.second, data.positiveLong("userId")) }
            "call_reconnect" -> idPair(data)?.let { CallRealtimeEvent.Reconnect(it.first, it.second, data.positiveLong("userId")) }
            "call_signal" -> {
                val signal = data["signal"] as? JsonObject ?: return null
                CallRealtimeEvent.Signal(
                    conversationId = data.positiveLong("conversationId") ?: return null,
                    fromUserId = data.positiveLong("fromUserId"),
                    signalId = signal.string("signalId"),
                    signal = signal,
                )
            }
            else -> null
        }
    }

    private fun idPair(data: JsonObject): Pair<Long, Long>? {
        val callId = data.positiveLong("callId") ?: return null
        val conversationId = data.positiveLong("conversationId") ?: return null
        return callId to conversationId
    }
}

private fun JsonObject.positiveLong(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
private fun JsonObject.boolean(name: String): Boolean? = this[name]?.jsonPrimitive?.booleanOrNull