package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReactionOverlayTest {
    @Test fun `quick reactions match aino-platform ReactionPicker verbatim`() {
        assertEquals(listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉", "👎", "💯"), DefaultQuickReactions)
    }

    @Test fun `picker categories contain every quick reaction and unique names`() {
        val emoji = DefaultEmojiCategories.flatMap { it.emojis }.toSet()
        assertTrue(DefaultQuickReactions.all(emoji::contains))
        assertEquals(DefaultEmojiCategories.size, DefaultEmojiCategories.map { it.name }.toSet().size)
    }

    @Test fun `reaction avatars come from senders, the 1-1 peer and members`() {
        val conversation = ChatConversation(id = 3, otherUserId = 9, otherAvatar = "/uploads/peer.png")
        val messages = listOf(
            ChatMessage(id = 1, senderId = 4, createdAt = "2024-01-01T00:00:00Z", senderAvatar = "/uploads/old-me.png"),
            ChatMessage(id = 2, senderId = 4, createdAt = "2024-01-02T00:00:00Z", senderAvatar = "/uploads/me.png"),
            ChatMessage(id = 3, senderId = 7, createdAt = "2024-01-02T00:00:00Z", senderAvatar = ""),
        )
        val members = listOf(ConversationMember(id = 7, avatar = "/uploads/member.png"), ConversationMember(id = 8))
        val avatars = reactionAvatarLookup(conversation, members, messages)
        assertEquals("/uploads/me.png", avatars[4])
        assertEquals("/uploads/peer.png", avatars[9])
        assertEquals("/uploads/member.png", avatars[7])
        assertNull(avatars[8])
    }

    @Test fun `members override stale sender avatars and groups ignore the peer field`() {
        val group = ChatConversation(id = 3, isGroup = true, otherUserId = 9, otherAvatar = "/uploads/peer.png")
        val messages = listOf(ChatMessage(id = 1, senderId = 4, createdAt = "2024-01-01T00:00:00Z", senderAvatar = "/uploads/old.png"))
        val avatars = reactionAvatarLookup(group, listOf(ConversationMember(id = 4, avatar = "/uploads/new.png")), messages)
        assertEquals("/uploads/new.png", avatars[4])
        assertNull(avatars[9])
    }

    @Test fun `realtime reaction keeps an avatar sent by the server`() {
        val message = ChatMessage(id = 1, senderId = 4, createdAt = "2024-01-01T00:00:00Z")
        val event = ChatReactionEvent(messageId = 1, conversationId = 3, userId = 9, fullName = "Jane", emoji = "👍", action = "added", avatar = "/a.png")
        assertEquals("/a.png", applyRealtimeReaction(listOf(message), event).single().reactions.single().avatar)
    }
}