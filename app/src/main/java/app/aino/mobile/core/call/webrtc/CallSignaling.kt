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
    /** `audio-state`. */
    val muted: Boolean? = null,
    /** `video-state`. */
    val videoOff: Boolean? = null,
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

@Serializable
data class CallInitiateCommand(
    val conversationId: Long,
    val callType: String,
    val clientMsgId: String,
)

fun callInitiateEnvelope(conversationId: Long, callType: String, clientMsgId: String): RealtimeEnvelope {
    require(conversationId > 0 && callType in setOf("voice", "video") && clientMsgId.isNotBlank())
    return RealtimeEnvelope(
        "call_initiate",
        JSON.encodeToJsonElement(CallInitiateCommand(conversationId, callType, clientMsgId)),
    )
}

fun callSignalEnvelope(command: CallSignalCommand): RealtimeEnvelope {
    require(command.callId > 0 && command.conversationId > 0 && command.targetUserId > 0)
    require(command.signal.type in CALL_SIGNAL_TYPES)
    if (command.signal.type in setOf("offer", "answer")) require(!command.signal.sdp.isNullOrBlank())
    if (command.signal.type == "audio-state") require(command.signal.muted != null)
    if (command.signal.type == "video-state") require(command.signal.videoOff != null)
    return RealtimeEnvelope("call_signal", JSON.encodeToJsonElement(command))
}

private val CALL_SIGNAL_TYPES = setOf("offer", "answer", "ice-candidate", "audio-state", "video-state")

fun callSubscribeEnvelope(callId: Long, conversationId: Long): RealtimeEnvelope =
    RealtimeEnvelope("call_subscribe", JSON.encodeToJsonElement(CallReadyCommand(callId, conversationId)))

fun callReadyEnvelope(callId: Long, conversationId: Long): RealtimeEnvelope =
    RealtimeEnvelope("call_ready", JSON.encodeToJsonElement(CallReadyCommand(callId, conversationId)))

@Serializable
data class CallEndCommand(val callId: Long, val conversationId: Long, val clientMsgId: String)

@Serializable
data class CallCancelCommand(val conversationId: Long, val clientMsgId: String)

/** Hang-up of a call the server has assigned an id (`ringing` → missed, otherwise ended). */
fun callEndEnvelope(callId: Long, conversationId: Long, clientMsgId: String): RealtimeEnvelope =
    RealtimeEnvelope("call_end", JSON.encodeToJsonElement(CallEndCommand(callId, conversationId, clientMsgId)))

/** Caller abort before `call_started` delivered an id. */
fun callCancelEnvelope(conversationId: Long, clientMsgId: String): RealtimeEnvelope =
    RealtimeEnvelope("call_cancel", JSON.encodeToJsonElement(CallCancelCommand(conversationId, clientMsgId)))

@Serializable
data class HuddleDeclineCommand(val meetingId: Long, val clientMsgId: String)

/** Declining a group-call ring (`call_incoming` carrying a `meetingCode`). */
fun huddleDeclineEnvelope(meetingId: Long, nowMs: Long = System.currentTimeMillis()): RealtimeEnvelope =
    RealtimeEnvelope("huddle_decline", JSON.encodeToJsonElement(HuddleDeclineCommand(meetingId, "hd-$meetingId-$nowMs")))

private val JSON = Json { encodeDefaults = true; explicitNulls = false }