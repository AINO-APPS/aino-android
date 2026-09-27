package app.aino.mobile.core.push

/** One line of a conversation notification (Signal `NotificationItem`). */
data class ChatNotificationLine(
    val text: String,
    val timestampMs: Long,
    /** Null for the signed-in user's own inline replies. */
    val senderKey: String?,
    val senderName: String,
    val senderAvatar: String? = null,
)

/**
 * Per-conversation notification backlog, so each new push re-posts the
 * conversation's MessagingStyle with the recent lines stacked (Signal keeps the
 * unread thread in one notification rather than replacing the text).
 * Process-local: after the process dies the next push starts a fresh stack,
 * which matches Signal's behaviour once the notification was dismissed.
 */
object ChatNotificationHistory {
    const val MAX_LINES = 8
    private val lines = mutableMapOf<Long, List<ChatNotificationLine>>()

    @Synchronized
    fun append(conversationId: Long, line: ChatNotificationLine): List<ChatNotificationLine> =
        appendLine(lines[conversationId].orEmpty(), line).also { lines[conversationId] = it }

    @Synchronized
    fun get(conversationId: Long): List<ChatNotificationLine> = lines[conversationId].orEmpty()

    @Synchronized
    fun clear(conversationId: Long) { lines.remove(conversationId) }

    @Synchronized
    fun clearAll() = lines.clear()
}

/** Appends [line] keeping the newest [max] lines, oldest first. */
internal fun appendLine(
    current: List<ChatNotificationLine>,
    line: ChatNotificationLine,
    max: Int = ChatNotificationHistory.MAX_LINES,
): List<ChatNotificationLine> = (current + line).takeLast(max)

/** Group pushes carry "{sender}: {preview}"; the MessagingStyle row already shows the sender. */
internal fun stripSenderPrefix(body: String, senderName: String, isGroup: Boolean): String {
    if (!isGroup || senderName.isBlank()) return body
    val prefix = "$senderName: "
    return if (body.startsWith(prefix)) body.removePrefix(prefix) else body
}
