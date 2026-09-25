package app.aino.mobile.feature.meeting

import kotlinx.serialization.json.JsonObject

enum class ChatDelivery { Sent, Sending, Uploading, Failed }

data class MeetingChatMessage(
    val id: Long? = null,
    val clientMsgId: String? = null,
    val senderId: Long? = null,
    val senderName: String? = null,
    val text: String? = null,
    val fileUrl: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val createdAt: String? = null,
    val system: Boolean = false,
    val delivery: ChatDelivery = ChatDelivery.Sent,
    val failureReason: String? = null,
) {
    val key: String get() = clientMsgId ?: "id-$id"
}

fun MeetingMessageDto.toChat(): MeetingChatMessage = MeetingChatMessage(
    id = id,
    clientMsgId = clientMsgId ?: clientMsgIdRest,
    senderId = senderId,
    senderName = senderName,
    text = text,
    fileUrl = fileUrl,
    fileName = fileName,
    fileSize = fileSize,
    createdAt = createdAt,
    system = system != null,
)

/** WS `meeting_message.message` (`{id, clientMsgId, sender_id, sender_name, text, created_at, file_*}`). */
fun JsonObject.toMeetingChat(): MeetingChatMessage = MeetingChatMessage(
    id = long("id"),
    clientMsgId = string("clientMsgId") ?: string("client_msg_id"),
    senderId = long("sender_id"),
    senderName = string("sender_name"),
    text = string("text"),
    fileUrl = string("file_url"),
    fileName = string("file_name"),
    fileSize = long("file_size"),
    createdAt = string("created_at"),
    system = this["system"] is JsonObject,
)

/**
 * Merges a server copy into the list: it replaces the optimistic row with the
 * same `clientMsgId` (or the same `id` on replay/history), otherwise appends.
 */
fun List<MeetingChatMessage>.mergeMessage(incoming: MeetingChatMessage): List<MeetingChatMessage> {
    val index = indexOfFirst {
        (incoming.clientMsgId != null && it.clientMsgId == incoming.clientMsgId) ||
            (incoming.id != null && it.id == incoming.id)
    }
    if (index < 0) return this + incoming
    return toMutableList().also { it[index] = incoming.copy(clientMsgId = incoming.clientMsgId ?: it[index].clientMsgId) }
}

fun List<MeetingChatMessage>.updateDelivery(
    clientMsgId: String,
    transform: (MeetingChatMessage) -> MeetingChatMessage,
): List<MeetingChatMessage> = map { if (it.clientMsgId == clientMsgId) transform(it) else it }

/** Web `normalizeChatText`: trimmed, max 5000 chars, blank → null. */
fun normalizeMeetingChat(text: String): String? = text.trim().take(5000).takeIf(String::isNotEmpty)

/** `MeetingChat.tsx` `formatFileSize`. */
fun meetingFileSize(bytes: Long?): String {
    val b = bytes ?: return ""
    return when {
        b < 1024 -> "$b B"
        b < 1048576 -> "%.1f KB".format(java.util.Locale.US, b / 1024.0)
        else -> "%.1f MB".format(java.util.Locale.US, b / 1048576.0)
    }
}
