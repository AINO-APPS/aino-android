package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ChatModelsTest {
    @Test
    fun decodesRealtimeChatPayloadsAndRejectsMalformedFrames() {
        val typing = decodeChatRealtime<ChatTypingEvent>(
            Json.parseToJsonElement("""{"conversationId":9,"userId":4}"""),
        )
        val receipt = decodeChatRealtime<ChatReadReceiptEvent>(
            Json.parseToJsonElement("""{"conversationId":9,"userId":8,"readAt":"2026-09-17T04:00:00Z"}"""),
        )

        assertEquals(ChatTypingEvent(9, 4), typing)
        assertEquals(8L, receipt?.userId)
        assertNull(decodeChatRealtime<ChatTypingEvent>(Json.parseToJsonElement("""{"conversationId":"bad"}""")))
        assertNull(decodeChatRealtime<ChatTypingEvent>(null))
    }

    @Test
    fun realtimeReactionPatchIsIdempotentAndScopedToOneMessage() {
        val original = listOf(
            ChatMessage(1, 9, 4, "one", "2026-09-17T04:00:00Z"),
            ChatMessage(2, 9, 8, "two", "2026-09-17T04:01:00Z"),
        )
        val add = ChatReactionEvent(2, 9, 4, "Asha", "👍", "added")
        val once = applyRealtimeReaction(original, add)
        val twice = applyRealtimeReaction(once, add)

        assertEquals(original[0], twice[0])
        assertEquals(listOf(ChatReaction("👍", 4, "Asha")), twice[1].reactions)

        val removed = applyRealtimeReaction(twice, add.copy(action = "removed"))
        assertTrue(removed[1].reactions.isEmpty())
    }

    @Test
    fun realtimeReceiptReplacesOnlyTheSameUserCursor() {
        val current = listOf(
            ReadReceipt(4, "2026-09-17T03:00:00Z", "Asha"),
            ReadReceipt(8, "2026-09-17T03:10:00Z", "Ben"),
        )
        val updated = applyRealtimeReceipt(
            current,
            ChatReadReceiptEvent(9, 4, "2026-09-17T04:00:00Z"),
        )

        assertEquals(2, updated.size)
        assertEquals("2026-09-17T04:00:00Z", updated.single { it.userId == 4L }.lastReadAt)
        assertEquals("Asha", updated.single { it.userId == 4L }.fullName)
        assertEquals("Ben", updated.single { it.userId == 8L }.fullName)
    }

    @Test
    fun typingEnvelopeMatchesTheServerCommandContract() {
        val envelope = typingEnvelope(42)
        assertEquals("chat_typing", envelope.type)
        assertEquals("42", envelope.data!!.jsonObject["conversationId"].toString())
    }

    @Test
    fun buildsChronologicalThreadWithDateSeparatorsAndMessageGroups() {
        val messages = listOf(
            ChatMessage(1, 9, 4, "one", "2026-09-15T23:59:00Z"),
            ChatMessage(2, 9, 4, "two", "2026-09-16T00:01:00Z"),
            ChatMessage(3, 9, 4, "three", "2026-09-16T00:04:00Z"),
            ChatMessage(4, 9, 8, "reply", "2026-09-16T00:05:00Z"),
        )

        val items = buildThreadItems(messages, emptyList(), currentUserId = 4, zoneId = ZoneId.of("UTC"))

        assertEquals(6, items.size)
        assertTrue(items[0] is ThreadItem.DateSeparator)
        assertTrue(items[2] is ThreadItem.DateSeparator)
        val firstDayMessage = items[1] as ThreadItem.Message
        assertTrue(firstDayMessage.startsGroup)
        assertTrue(firstDayMessage.endsGroup)
        val second = items[3] as ThreadItem.Message
        val third = items[4] as ThreadItem.Message
        assertTrue(second.startsGroup)
        assertFalse(second.endsGroup)
        assertFalse(third.startsGroup)
        assertTrue(third.endsGroup)
        val reply = items[5] as ThreadItem.Message
        assertTrue(reply.startsGroup)
        assertTrue(reply.endsGroup)
    }

    @Test
    fun queuedMessagesParticipateInChronologyButBreakServerMessageGroups() {
        val messages = listOf(
            ChatMessage(1, 9, 4, "sent", "2026-09-16T09:00:00Z"),
            ChatMessage(2, 9, 4, "later", "2026-09-16T09:02:00Z"),
        )
        val queued = listOf(QueuedMessage("q", 9, 4, "queued", 1_789_549_260_000L)) // 2026-09-16 09:01 UTC

        val items = buildThreadItems(messages, queued, currentUserId = 4, zoneId = ZoneId.of("UTC"))
        val bubbles = items.drop(1)
        assertEquals(listOf("server-1", "queued-q", "server-2"), bubbles.map(ThreadItem::key))
        assertTrue((bubbles[0] as ThreadItem.Message).endsGroup)
        assertTrue((bubbles[2] as ThreadItem.Message).startsGroup)
    }

    @Test
    fun excludesQueuedMessagesFromAnotherSignedInUser() {
        val queued = listOf(
            QueuedMessage("mine", 9, 4, "mine", 1_757_927_400_000L),
            QueuedMessage("other", 9, 8, "other", 1_757_927_401_000L),
        )
        val items = buildThreadItems(emptyList(), queued, currentUserId = 4, zoneId = ZoneId.of("UTC"))
        assertEquals(listOf("date-2025-09-15", "queued-mine"), items.map(ThreadItem::key))
    }

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

        // A-100: these are real server events that the previous hardcoded set
        // ignored entirely, so the list went stale until a manual refresh.
        assertTrue(shouldRefreshConversationList("chat_pin"))
        assertTrue(shouldRefreshConversationList("chat_cleared"))
        assertTrue(shouldRefreshConversationList("chat_user_blocked"))
        assertTrue(shouldRefreshConversationList("chat_group_role_changed"))

        // Ephemeral and patch-only events must never cause a network reload.
        assertFalse(shouldRefreshConversationList("chat_media_job"))
        assertFalse(shouldRefreshConversationList("chat_poll_vote"))

        // Non-chat domains are not this predicate's concern.
        assertFalse(shouldRefreshConversationList("leave_update"))
        assertFalse(shouldRefreshConversationList("meeting_started"))

        // `chat_group_updated` is not a server event; it must not be revived.
        assertFalse(shouldRefreshConversationList("chat_group_updated"))
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

    @Test
    fun callHistoryChoosesPeerOrGroupTitle() {
        val outgoing = CallLog(1, 12, callerId = 4, status = "answered", createdAt = "2026-09-15T00:00:00Z", otherName = "Asha")
        val incoming = outgoing.copy(id = 2, callerId = 8, callerName = "Asha")
        val group = outgoing.copy(id = 3, isGroup = true, groupName = "Mobile Team")
        assertEquals("Asha", outgoing.title(4))
        assertEquals("Asha", incoming.title(4))
        assertEquals("Mobile Team", group.title(4))
    }
}