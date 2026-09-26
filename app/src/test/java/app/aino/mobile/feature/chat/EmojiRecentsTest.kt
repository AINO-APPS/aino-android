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
    fun emojiSearchRanksKeywordHitsAndFallsBackToUnicodeNames() {
        assertEquals("👍", searchEmoji("thumbs").first())
        assertTrue("😂" in searchEmoji("lol"))
        // Not in the keyword table: found through the Unicode name ("ROCKET").
        assertTrue("🚀" in searchEmoji("rocket"))
        assertTrue(searchEmoji("   ").isEmpty())
        assertTrue(searchEmoji("zzqqxx").isEmpty())
    }

    @Test
    fun insertAtSelectionReplacesRangeAndMovesCaret() {
        assertEquals("hi 👋 there" to 5, insertAtSelection("hi  there", 3, 3, "👋"))
        assertEquals("a🔥" to 3, insertAtSelection("abc", 1, 3, "🔥"))
        assertEquals("x😀" to 3, insertAtSelection("x", 9, 9, "😀"))
    }

    @Test
    fun categoriesAreSubstantial() {
        assertEquals(8, SignalEmojiCategories.size)
        assertTrue(SignalEmojiCategories.all { it.emojis.size >= 60 })
        assertTrue(EmojiKeywords.size >= 150)
    }
}
