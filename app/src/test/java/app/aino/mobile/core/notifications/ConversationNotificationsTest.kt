package app.aino.mobile.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationNotificationsTest {
    @Test
    fun stableIdNormalizesReadableConversationIds() {
        assertEquals("team_alpha-2026", conversationStableId("  Team_ALPHA 2026  "))
    }

    @Test
    fun stableIdTruncatesReadableIdsToThirtyTwoCharacters() {
        assertEquals(
            "abcdefghijklmnopqrstuvwxyz012345",
            conversationStableId("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"),
        )
    }

    @Test
    fun stableIdHashesIdsWithNoReadableCharacters() {
        assertEquals("4b3510b8d86ea785", conversationStableId("聊天"))
    }

    @Test
    fun stableIdIsDeterministic() {
        val conversationId = "💬💬"
        assertEquals(conversationStableId(conversationId), conversationStableId(conversationId))
    }
}