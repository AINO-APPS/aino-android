package app.aino.mobile.feature.meeting

import app.aino.mobile.core.call.webrtc.IceCandidateSignal
import app.aino.mobile.core.realtime.RealtimeEnvelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Every client meeting frame is `{type, data:{meetingId, …}}` (server `wsHandlers/meeting*.ts`). */
fun meetingFrame(type: String, meetingId: Long, extra: JsonObjectBuilder.() -> Unit = {}): RealtimeEnvelope =
    RealtimeEnvelope(type, buildJsonObject {
        put("meetingId", meetingId)
        extra()
    })

/** Mesh offer/answer: `sdp` is the RTCSessionDescription object, not a bare string. */
fun meetingDescriptionSignal(type: String, sdp: String): JsonObject = buildJsonObject {
    put("type", type)
    putJsonObject("sdp") {
        put("type", type)
        put("sdp", sdp)
    }
}

fun meetingCandidateSignal(candidate: IceCandidateSignal): JsonObject = buildJsonObject {
    put("type", "candidate")
    putJsonObject("candidate") {
        put("candidate", candidate.candidate)
        candidate.sdpMid?.let { put("sdpMid", it) }
        put("sdpMLineIndex", candidate.sdpMLineIndex)
    }
}

sealed interface MeetingSignal {
    data class Offer(val sdp: String) : MeetingSignal
    data class Answer(val sdp: String) : MeetingSignal
    data class Candidate(val candidate: IceCandidateSignal) : MeetingSignal
}

/** Accepts both the web's `{sdp:{type,sdp}}` object and a bare SDP string. */
fun parseMeetingSignal(signal: JsonObject): MeetingSignal? {
    fun sdp(): String? = when (val raw = signal["sdp"]) {
        is JsonObject -> (raw["sdp"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> raw.contentOrNull
        else -> null
    }?.takeIf(String::isNotBlank)
    return when ((signal["type"] as? JsonPrimitive)?.contentOrNull) {
        "offer" -> sdp()?.let(MeetingSignal::Offer)
        "answer" -> sdp()?.let(MeetingSignal::Answer)
        "candidate", "ice-candidate" -> {
            val c = signal["candidate"] as? JsonObject ?: return null
            val text = (c["candidate"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
            MeetingSignal.Candidate(
                IceCandidateSignal(
                    text,
                    (c["sdpMid"] as? JsonPrimitive)?.contentOrNull,
                    (c["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0,
                ),
            )
        }
        else -> null
    }
}

/** UMS:761 — a lexicographic STRING comparison, not numeric ("9" > "10"). */
fun meetingPolite(selfId: Long, remoteId: Long): Boolean = selfId.toString() > remoteId.toString()

/** `state/quality.ts` video cap by remote peer count: ≤3 → 500k, ≤6 → 300k, else 150k. */
fun meetingVideoBitrate(remotePeers: Int): Int = when {
    remotePeers <= 3 -> 500_000
    remotePeers <= 6 -> 300_000
    else -> 150_000
}

/** `MeetingRoom.css` ≤768px grid by tile count (self included): columns / visible rows. */
fun meetingGridColumns(count: Int): Int = when {
    count <= 2 -> 1
    count <= 6 -> 2
    count <= 9 -> 3
    else -> 4
}

fun meetingGridRows(count: Int): Int = when {
    count <= 1 -> 1
    count <= 4 -> 2
    else -> 3
}

/** Web `formatDuration`: `m:ss`, or `h:mm:ss` past an hour. */
fun meetingTimer(elapsedSeconds: Long): String {
    val s = elapsedSeconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.US, h, m, sec) else "%d:%02d".format(java.util.Locale.US, m, sec)
}

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
internal fun JsonObject.float(key: String): Float? = (this[key] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()
