package app.aino.mobile.feature.chat

/*
 * Chat-list row receipts kept live between list refreshes, mirroring the web
 * client's conversation reducers: a delivery receipt marks the last message
 * delivered, and a read receipt from someone else covering it marks it read.
 *
 * The arrival itself (a new last message, its preview and its position) is
 * `promoteConversation` in ChatListOrder.kt.
 */

/** Someone's device received a message; only the current user's own last message shows it. */
fun applyListDelivered(conversations: List<ChatConversation>, event: ChatDeliveredEvent, currentUserId: Long?): List<ChatConversation> =
    conversations.map { conversation ->
        if (conversation.id != event.conversationId || event.userId == currentUserId || conversation.lastSenderId != currentUserId) conversation
        else conversation.copy(lastMessageDelivered = true)
    }

/** Someone else read the conversation up to [ChatReadReceiptEvent.readAt]. */
fun applyListRead(conversations: List<ChatConversation>, event: ChatReadReceiptEvent, currentUserId: Long?): List<ChatConversation> =
    conversations.map { conversation ->
        val last = conversation.lastMessageAt
        val covers = last == null || runCatching { java.time.Instant.parse(event.readAt) >= java.time.Instant.parse(last) }.getOrDefault(true)
        if (conversation.id != event.conversationId || event.userId == currentUserId || conversation.lastSenderId != currentUserId || !covers) conversation
        else conversation.copy(lastMessageRead = true, lastMessageDelivered = true)
    }
