package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiRecentsTest {
    @Test
    fun pushAddsToFront() {
        assertEquals(listOf("😂", "👍"), EmojiRecents.push(listOf("👍"), "😂"))
    }

    @Test
    fun pushMovesExistingToFrontWithoutDuplicates() {
        assertEquals(listOf("👍", "😂", "🔥"), EmojiRecents.push(listOf("😂", "👍", "🔥"), "👍"))
    }

    @Test
    fun pushCapsAtMax() {
        val result = EmojiRecents.push(listOf("a", "b", "c"), "d", max = 3)
        assertEquals(listOf("d", "a", "b"), result)
    }

    @Test
    fun skinToneVariantsStripVariationSelector() {
        val variants = EmojiSkinTones.variants("✌️")
        assertEquals(5, variants.size)
        assertEquals("✌\uD83C\uDFFB", variants.first())
        assertTrue(EmojiSkinTones.variants("🔥").isEmpty())
    }

    @Test
    fun categoriesAreSubstantial() {
        assertEquals(8, SignalEmojiCategories.size)
        assertTrue(SignalEmojiCategories.all { it.emojis.size >= 60 })
        assertTrue(EmojiKeywords.size >= 150)
    }
}
