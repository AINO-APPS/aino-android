package app.aino.mobile.feature.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Signal-style call history wording, shared by the in-thread call chip, the
 * Calls tab and the conversation-info call history. Direction comes from the
 * caller id versus the signed-in user.
 */
data class CallHistoryLabel(
    /** "Outgoing voice call", "Missed video call", ... */
    val title: String,
    /** Duration (`m:ss`), "No answer", "Declined", "Ongoing", or null. */
    val detail: String?,
    val outgoing: Boolean,
    val video: Boolean,
    /** Only a call that rang here unanswered is shown in the danger colour. */
    val danger: Boolean,
)

fun callHistoryLabel(
    callType: String?,
    status: String?,
    durationSeconds: Int?,
    callerId: Long?,
    currentUserId: Long?,
): CallHistoryLabel {
    val video = callType == "video"
    val kind = if (video) "video call" else "voice call"
    val outgoing = callerId != null && callerId == currentUserId
    val direction = if (outgoing) "Outgoing $kind" else "Incoming $kind"
    val duration = durationSeconds?.takeIf { it > 0 }?.let(::formatCallHistoryDuration)
    return when (status) {
        "ringing", "answered" -> CallHistoryLabel(direction, "Ongoing", outgoing, video, danger = false)
        "missed", "no_answer" ->
            if (outgoing) CallHistoryLabel(direction, "No answer", true, video, danger = false)
            else CallHistoryLabel("Missed $kind", null, false, video, danger = true)
        "declined", "rejected" ->
            if (outgoing) CallHistoryLabel(direction, "Declined", true, video, danger = false)
            else CallHistoryLabel("Declined $kind", null, false, video, danger = false)
        else -> CallHistoryLabel(direction, duration, outgoing, video, danger = false)
    }
}

/** `m:ss`, or `h:mm:ss` past an hour. */
fun formatCallHistoryDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun CallLog.historyLabel(currentUserId: Long?): CallHistoryLabel =
    callHistoryLabel(callType, status, duration, callerId, currentUserId)

/** A chat `system` message whose `metadata.type` is `call` (server call history row). */
fun isCallHistoryMessage(formatType: String?, metadata: JsonElement?): Boolean =
    formatType == "system" && (metadata as? JsonObject)?.string("type") == "call"

/** The call chip label for a call-history system message, or null for any other message. */
fun ChatMessage.callHistoryLabel(currentUserId: Long?): CallHistoryLabel? {
    if (!isCallHistoryMessage(formatType, metadata)) return null
    val meta = metadata as JsonObject
    return callHistoryLabel(
        callType = meta.string("callType"),
        status = meta.string("status"),
        durationSeconds = meta.string("duration")?.toDoubleOrNull()?.toInt(),
        // The server sends the message as the caller; metadata wins when present.
        callerId = meta.string("callerId")?.toLongOrNull() ?: senderId,
        currentUserId = currentUserId,
    )
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Call-end bursts (socket `call_ended` + the history `chat_message`) reload the call lists once. */
internal const val CALLS_RELOAD_DEBOUNCE_MS = 600L
