package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommentMentionsTest {
    private val users = listOf(
        AssignableUser(2, username = "anna", fullName = "Anna Joseph"),
        AssignableUser(3, username = "ann", fullName = "Ann Mary"),
        AssignableUser(4, username = "vishnu", fullName = "Vishnu V R"),
        AssignableUser(5, username = "vishnu2", fullName = "Vishnu V R"),
    )

    @Test
    fun typedFullNameAndUsernameBecomeMentionChips() {
        val text = "@Anna Joseph please check, cc @ann"
        val mentions = resolveCommentMentions(text, emptyMap(), users)
        assertEquals(mapOf("Anna Joseph" to 2L, "ann" to 3L), mentions)
        val html = plainTextToHtml(text, mentions)
        assertTrue(html.contains("data-user-id=\"2\""))
        assertTrue(html.contains("data-user-id=\"3\""))
    }

    @Test
    fun pickedMentionsAreKeptAndNotDuplicated() {
        val picked = mapOf("Ann Mary" to 3L)
        assertEquals(picked, resolveCommentMentions("@Ann Mary hi", picked, users))
    }

    @Test
    fun aPrefixOfALongerWordIsNotAMention() {
        assertEquals(mapOf("anna" to 2L), resolveCommentMentions("thanks @anna!", emptyMap(), users))
        assertTrue(resolveCommentMentions("thanks @annabel", emptyMap(), users).isEmpty())
        assertTrue(resolveCommentMentions("mail me at me@anna", emptyMap(), users).isEmpty())
    }

    @Test
    fun ambiguousNamesAreLeftForThePicker() {
        // Two people are called "Vishnu V R"; only their usernames are unique.
        assertEquals(mapOf("vishnu2" to 5L), resolveCommentMentions("@Vishnu V R and @vishnu2", emptyMap(), users))
    }

    @Test
    fun matchingIsCaseInsensitiveButKeepsTheTypedSpelling() {
        val mentions = resolveCommentMentions("@VISHNU2 ping", emptyMap(), users)
        assertEquals(mapOf("VISHNU2" to 5L), mentions)
        assertTrue(plainTextToHtml("@VISHNU2 ping", mentions).contains("data-user-id=\"5\""))
    }
}
