package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
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
}