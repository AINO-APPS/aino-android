package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {
    @Test
    fun derivesDirectGroupAndSelfTitles() {
        assertEquals("Asha K", ChatConversation(1, otherFullName = "Asha K", otherUsername = "asha").title())
        assertEquals("asha", ChatConversation(1, otherUsername = "asha").title())
        assertEquals("Mobile Team", ChatConversation(2, isGroup = true, groupName = "Mobile Team").title())
        assertEquals("Group", ChatConversation(2, isGroup = true).title())
        assertEquals("Note to self", ChatConversation(3, isSelfChat = true).title())
    }

    @Test
    fun derivesLastMessagePreviewWithPrivacySafeFallbacks() {
        assertEquals("hello", ChatConversation(1, lastMessage = "hello").preview())
        assertEquals("Attachment: plan.pdf", ChatConversation(1, lastFileName = "plan.pdf").preview())
        assertEquals("Message deleted", ChatConversation(1, lastMessage = "secret", lastDeleted = "2026-09-14T00:00:00Z").preview())
        assertEquals("No messages yet", ChatConversation(1).preview())
    }

    @Test
    fun mapsIntoTheExactTenantAndUserCacheScope() {
        val conversation = ChatConversation(
            id = 9,
            updatedAt = "2026-09-14T10:00:00Z",
            otherFullName = "Asha K",
            otherAvatar = "/uploads/asha.png",
            unreadCount = 3,
        )
        val entity = conversation.toEntity(CacheScope(7, 4))
        assertEquals(7, entity.tenantId)
        assertEquals(4, entity.userId)
        assertEquals(9, entity.conversationId)
        assertEquals("Asha K", entity.title)
        assertEquals(3, entity.unreadCount)
        assertTrue(entity.updatedAtEpochMs > 0)
    }

    @Test
    fun unreadCountsClampNegativeServerValues() {
        assertEquals(5, totalUnread(listOf(ChatConversation(1, unreadCount = 5), ChatConversation(2, unreadCount = -2))))
    }

    @Test
    fun labelsPresenceAndWorkMode() {
        assertEquals("Offline", presenceLabel(null))
        assertEquals("Offline", presenceLabel(ChatPresence("offline", "available", "office")))
        assertEquals("Remote", presenceLabel(ChatPresence("online", "available", "remote")))
        assertEquals("Do not disturb", presenceLabel(ChatPresence("online", "do_not_disturb", null)))
    }

    @Test
    fun refreshesOnlyForConversationListEvents() {
        assertTrue(shouldRefreshConversationList("chat_message"))
        assertTrue(shouldRefreshConversationList("chat_group_created"))
        assertTrue(shouldRefreshConversationList("chat_conv_archived"))
        assertFalse(shouldRefreshConversationList("chat_typing"))
        assertFalse(shouldRefreshConversationList("chat_read_receipt"))
        assertFalse(shouldRefreshConversationList("task_updated"))
    }

    @Test
    fun mapsThreadMessageIntoTheExactCacheScope() {
        val message = ChatMessage(
            id = 77,
            senderId = 9,
            content = "Hello",
            createdAt = "2026-09-15T04:00:00Z",
            senderName = "Asha K",
        )
        val entity = message.toEntity(CacheScope(3, 4), fallbackConversationId = 12)
        assertEquals(3, entity.tenantId)
        assertEquals(4, entity.userId)
        assertEquals(12, entity.conversationId)
        assertEquals(9, entity.senderId)
        assertEquals("Hello", entity.body)
        assertEquals("sent", entity.deliveryState)
        assertTrue(entity.createdAtEpochMs > 0)
    }

    @Test
    fun threadBodyHonoursDeletedAndAttachmentPrecedence() {
        assertEquals("Message deleted", ChatMessage(1, senderId = 2, content = "hidden", createdAt = "2026-09-15T00:00:00Z", deletedAt = "2026-09-15T01:00:00Z").body())
        assertEquals("Attachment: report.pdf", ChatMessage(2, senderId = 2, createdAt = "2026-09-15T00:00:00Z", fileName = "report.pdf").body())
    }

    @Test
    fun reconcilesQueuedMessagesOneToOneByOwnEchoTime() {
        val queued = listOf(
            QueuedMessage("one", 12, 4, "one", 1_000),
            QueuedMessage("two", 12, 4, "two", 2_000),
        )
        val messages = listOf(
            ChatMessage(9, senderId = 4, content = "one", createdAt = java.time.Instant.ofEpochMilli(1_500).toString()),
        )
        assertEquals(listOf("two"), reconcileQueuedMessages(queued, messages, 4).map { it.clientMessageId })
        assertEquals(queued, reconcileQueuedMessages(queued, messages, 99))
    }
}