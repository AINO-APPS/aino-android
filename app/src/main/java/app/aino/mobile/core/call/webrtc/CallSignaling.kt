package app.aino.mobile.core.call.webrtc

import app.aino.mobile.core.realtime.RealtimeEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement

@Serializable
data class IceCandidateSignal(
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int = 0,
)

@Serializable
data class CallSignal(
    val type: String,
    val sdp: String? = null,
    val candidate: IceCandidateSignal? = null,
    val signalId: String? = null,
)

@Serializable
data class CallSignalCommand(
    val callId: Long,
    val conversationId: Long,
    val targetUserId: Long,
    val signal: CallSignal,
)

@Serializable
data class CallReadyCommand(val callId: Long, val conversationId: Long)

fun callSignalEnvelope(command: CallSignalCommand): RealtimeEnvelope {
    require(command.callId > 0 && command.conversationId > 0 && command.targetUserId > 0)
    require(command.signal.type in setOf("offer", "answer", "ice-candidate"))
    if (command.signal.type in setOf("offer", "answer")) require(!command.signal.sdp.isNullOrBlank())
    return RealtimeEnvelope("call_signal", JSON.encodeToJsonElement(command))
}

fun callSubscribeEnvelope(callId: Long, conversationId: Long): RealtimeEnvelope =
    RealtimeEnvelope("call_subscribe", JSON.encodeToJsonElement(CallReadyCommand(callId, conversationId)))

fun callReadyEnvelope(callId: Long, conversationId: Long): RealtimeEnvelope =
    RealtimeEnvelope("call_ready", JSON.encodeToJsonElement(CallReadyCommand(callId, conversationId)))

private val JSON = Json { encodeDefaults = true }