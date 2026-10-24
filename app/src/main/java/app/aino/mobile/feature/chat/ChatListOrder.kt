package app.aino.mobile.feature.chat

/*
 * The single definition of chat-list ordering, plus the optimistic patch that
 * keeps a row current between list refreshes.
 *
 * Ordering is pinned first, then newest `lastMessageAt ?: updatedAt` descending.
 * The server list is advisory: the REST payload carries no ordering authority of
 * its own, so every install point runs the rows through [sortConversations].
 */

/**
 * Newest last message first, hoisting pinned chats. Ties break on id so the
 * order is total and a same-millisecond pair never swaps places between
 * recompositions (or between the rows and their Room `ORDER BY`).
 */
fun sortConversations(conversations: List<ChatConversation>): List<ChatConversation> =
    conversations.sortedWith(
        compareByDescending<ChatConversation> { it.isPinned }
            .thenByDescending { it.updatedAtEpochMs }
            .thenByDescending { it.id },
    )

/** The pipeline every list install point shares: clear the open thread's badge, then order. */
fun orderedConversations(conversations: List<ChatConversation>, visibleConversationId: Long?): List<ChatConversation> =
    sortConversations(zeroUnread(conversations, visibleConversationId))

/**
 * A message just arrived (or just echoed back after being sent): make its row the
 * newest one and carry its preview, sender flags and badge across.
 *
 * The message's server timestamp decides placement. A message older than the
 * row's current last message is a late/out-of-order or replayed delivery, so the
 * row keeps its place and its timestamp — only the fields that describe the
 * message itself are refreshed. A blank timestamp can't be placed at all, so it
 * is ignored the same way.
 *
 * Never touches [ChatConversation.isPinned] or [ChatConversation.isArchived]:
 * those transitions belong to the pin/archive events, and resurrecting an
 * archived chat into the main list would be wrong.
  *
  * A message in the conversation the user is reading clears its badge instead of
  * counting up, matching [zeroUnread].
  */
fun promoteConversation(
    conversations: List<ChatConversation>,
    message: ChatRealtimeMessage,
    currentUserId: Long? = null,
    visibleConversationId: Long? = null,
): List<ChatConversation> {
    val messageEpochMs = parseEpoch(message.createdAt)
    val patched = conversations.map { conversation ->
        if (conversation.id != message.conversationId) conversation
        else conversation.copy(
            lastMessageAt = if (messageEpochMs >= conversation.updatedAtEpochMs) message.createdAt else conversation.lastMessageAt,
            lastMessage = message.content,
            lastSenderId = message.senderId,
            lastFileName = null,
            lastFileType = message.fileType,
            lastFormatType = message.listFormatType(),
            lastDeleted = null,
            lastMessageRead = false,
            lastMessageDelivered = false,
            unreadCount = if (conversation.id == visibleConversationId) 0
            else conversation.unreadCount.coerceAtLeast(0) + 1,
        )
    }
    return sortConversations(patched)
}

/**
 * The list row's view of a message's format. View-once wins: the row holds no
 * message metadata, so the marker is what keeps the caption out of [ChatConversation.previewSnippet].
 */
private fun ChatRealtimeMessage.listFormatType(): String? = when {
    isViewOnce() && !fileType.isNullOrBlank() -> VIEW_ONCE_FORMAT
    else -> formatType
}

/** Server `metadata.viewOnce` on the realtime payload (the list's own row has no metadata). */
private fun ChatRealtimeMessage.isViewOnce(): Boolean =
    ((metadata as? kotlinx.serialization.json.JsonObject)?.get("viewOnce") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"