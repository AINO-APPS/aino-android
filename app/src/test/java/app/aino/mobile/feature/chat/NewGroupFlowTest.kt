package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class NewGroupFlowTest {
    @Test
    fun recentContactsComeFromDirectChatsMostRecentFirst() {
        val conversations = listOf(
            ChatConversation(1, otherUserId = 8, otherFullName = "Asha", lastMessageAt = "2026-09-14T08:00:00Z"),
            ChatConversation(2, otherUserId = 9, otherFullName = "Ravi", lastMessageAt = "2026-09-14T09:00:00Z"),
            ChatConversation(3, isGroup = true, groupName = "Team", lastMessageAt = "2026-09-14T10:00:00Z"),
            ChatConversation(4, isMeetingChat = true, otherUserId = 10, lastMessageAt = "2026-09-14T11:00:00Z"),
            ChatConversation(5, isSelfChat = true, otherUserId = 1, lastMessageAt = "2026-09-14T12:00:00Z"),
            ChatConversation(6, isBlocked = true, otherUserId = 11, lastMessageAt = "2026-09-14T12:00:00Z"),
            ChatConversation(7, otherUserId = 8, otherFullName = "Asha", lastMessageAt = "2026-09-13T08:00:00Z"),
        )
        val recents = recentGroupContacts(conversations, currentUserId = 1)
        assertEquals(listOf(9L, 8L), recents.map { it.id })
        assertEquals("Ravi", recents.first().display())
    }
}
