package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListSnippetTest {
    @Test
    fun mediaSnippetsCarryAKindInsteadOfAnEmoji() {
        assertEquals(PreviewSnippet(PreviewKind.Photo, "Photo"), ChatConversation(1, lastFileName = "IMG.jpg", lastFileType = "image/jpeg").previewSnippet())
        assertEquals(PreviewSnippet(PreviewKind.Gif, "GIF"), ChatConversation(1, lastFileName = "a.gif", lastFileType = "image/gif").previewSnippet())
        assertEquals(PreviewSnippet(PreviewKind.Video, "Video"), ChatConversation(1, lastFileName = "v.mp4", lastFileType = "video/mp4").previewSnippet())
        assertEquals(PreviewSnippet(PreviewKind.Voice, "Voice message"), ChatConversation(1, lastFileName = "v.m4a", lastFileType = "audio/mp4").previewSnippet())
        assertEquals(PreviewSnippet(PreviewKind.File, "plan.pdf"), ChatConversation(1, lastFileName = "plan.pdf", lastFileType = "application/pdf").previewSnippet())
        assertEquals(PreviewSnippet(PreviewKind.Poll, "Lunch?"), ChatConversation(1, lastMessage = "Lunch?", lastFormatType = "poll").previewSnippet())
        assertEquals(PreviewKind.Deleted, ChatConversation(1, lastDeleted = "2026-10-08T00:00:00Z").previewSnippet().kind)
        assertEquals(PreviewSnippet(null, "hello"), ChatConversation(1, lastMessage = "hello").previewSnippet())
    }

    @Test
    fun aCaptionReplacesTheMediaWordButKeepsTheKind() {
        val snippet = ChatConversation(1, lastMessage = "Look", lastFileName = "a.png", lastFileType = "image/png").previewSnippet()
        assertEquals(PreviewSnippet(PreviewKind.Photo, "Look"), snippet)
    }

    @Test
    fun textPreviewStillUsesGlyphsForNotifications() {
        assertEquals("📷 Photo", ChatConversation(1, lastFileName = "x.jpg", lastFileType = "image/jpeg").preview())
        assertEquals("📊 Lunch?", ChatConversation(1, lastMessage = "Lunch?", lastFormatType = "poll").preview())
        assertEquals("Message deleted", ChatConversation(1, lastDeleted = "2026-10-08T00:00:00Z").preview())
    }

    @Test
    fun onlyTheUsersOwnLiveLastMessageGetsATick() {
        val mine = ChatConversation(1, lastSenderId = 5, lastMessageAt = "2026-10-08T10:00:00Z")
        assertTrue(mine.lastIsMine(5))
        assertFalse(mine.lastIsMine(6))
        assertFalse(mine.copy(lastDeleted = "2026-10-08T10:01:00Z").lastIsMine(5))
        assertFalse(ChatConversation(1, lastSenderId = 5).lastIsMine(5))
        assertFalse(mine.lastIsMine(null))
    }

    @Test
    fun tickPrefersReadOverDeliveredOverSent() {
        val base = ChatConversation(1, lastSenderId = 5, lastMessageAt = "2026-10-08T10:00:00Z")
        assertEquals(DeliveryTick.Sent, listDeliveryTick(base))
        assertEquals(DeliveryTick.Delivered, listDeliveryTick(base.copy(lastMessageDelivered = true)))
        assertEquals(DeliveryTick.Read, listDeliveryTick(base.copy(lastMessageDelivered = true, lastMessageRead = true)))
        assertEquals(DeliveryTick.Sending, listDeliveryTick(base, pending = true))
    }

    @Test
    fun liveReceiptsPatchTheListRow() {
        val row = ChatConversation(1, lastSenderId = 5, lastMessageAt = "2026-10-08T10:00:00Z")
        val list = listOf(row, ChatConversation(2))

        val delivered = applyListDelivered(list, ChatDeliveredEvent(messageId = 9, conversationId = 1, userId = 7), currentUserId = 5)
        assertTrue(delivered[0].lastMessageDelivered)
        assertFalse(delivered[0].lastMessageRead)

        val readEarlier = applyListRead(delivered, ChatReadReceiptEvent(1, 7, "2026-10-08T09:59:00Z"), currentUserId = 5)
        assertFalse("a read mark older than the message does not cover it", readEarlier[0].lastMessageRead)

        val read = applyListRead(delivered, ChatReadReceiptEvent(1, 7, "2026-10-08T10:00:05Z"), currentUserId = 5)
        assertTrue(read[0].lastMessageRead)

        // My own read receipt (another device) never marks my message as read.
        assertFalse(applyListRead(list, ChatReadReceiptEvent(1, 5, "2026-10-08T11:00:00Z"), 5)[0].lastMessageRead)
    }

    @Test
    fun aNewLastMessageResetsTheTicks() {
        val read = ChatConversation(1, lastSenderId = 5, lastMessageRead = true, lastMessageDelivered = true)
        val incoming = decodeChatRealtime<ChatRealtimeMessage>(
            kotlinx.serialization.json.Json.parseToJsonElement("""{"id":2,"conversationId":1,"senderId":6,"content":"hi","createdAt":"2026-10-08T10:05:00Z"}"""),
        )!!
        val next = applyListLastMessage(listOf(read), incoming)[0]
        assertEquals(6L, next.lastSenderId)
        assertFalse(next.lastMessageRead)
        assertFalse(next.lastMessageDelivered)
    }

    @Test
    fun collageMembersComeFromTheRichestSource() {
        val conversation = ChatConversation(
            1, isGroup = true,
            groupMemberAvatars = listOf("/a.png"),
            groupMemberPreviews = listOf(GroupMemberPreview("Ana", "/a.png"), GroupMemberPreview("Ben", null)),
        )
        assertEquals(listOf("Ana", "Ben"), conversation.avatarMembers().map { it.name })
        assertEquals(listOf("Zed"), conversation.avatarMembers(listOf(ConversationMember(9, fullName = "Zed"))).map { it.name })
        assertNull(conversation.copy(groupMemberPreviews = null).avatarMembers().single().name)
    }
}
