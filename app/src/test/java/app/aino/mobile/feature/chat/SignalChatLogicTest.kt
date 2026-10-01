package app.aino.mobile.feature.chat

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalChatLogicTest {
    private fun message(senderId: Long = 1, metadata: JsonObject? = null) =
        ChatMessage(id = 7, conversationId = 3, senderId = senderId, createdAt = "2024-01-01T00:00:00Z", metadata = metadata, fileType = "image/jpeg")

    private fun viewOnce(vararg viewers: Long) = JsonObject(
        mapOf("viewOnce" to JsonPrimitive(true), "viewedBy" to JsonArray(viewers.map { JsonPrimitive(it) })),
    )

    @Test fun `bubble corners follow the web notch on the first bubble of a block`() {
        val firstMine = messageBubbleShape(isMine = true, startsGroup = true, endsGroup = false)
        val middleMine = messageBubbleShape(isMine = true, startsGroup = false, endsGroup = false)
        val firstTheirs = messageBubbleShape(isMine = false, startsGroup = true, endsGroup = true)
        val size = androidx.compose.ui.geometry.Size(100f, 100f)
        val density = androidx.compose.ui.unit.Density(1f)
        // .myBubble { border-radius: 16px 4px 16px 16px }
        assertEquals(16f, firstMine.topStart.toPx(size, density))
        assertEquals(4f, firstMine.topEnd.toPx(size, density))
        assertEquals(16f, firstMine.bottomEnd.toPx(size, density))
        assertEquals(16f, firstMine.bottomStart.toPx(size, density))
        // .mine.grouped .myBubble { border-radius: 16px }
        assertEquals(16f, middleMine.topEnd.toPx(size, density))
        assertEquals(16f, middleMine.bottomEnd.toPx(size, density))
        // .theirBubble { border-radius: 4px 16px 16px 16px }
        assertEquals(4f, firstTheirs.topStart.toPx(size, density))
        assertEquals(16f, firstTheirs.topEnd.toPx(size, density))
        assertEquals(16.dp, SignalDimens.bubbleCorner)
    }

    @Test fun `chat palette follows the org accent like the web bubbles`() {
        val accent = androidx.compose.ui.graphics.Color(0xFFE91E63)
        val web = app.aino.mobile.core.designsystem.tokens.WebColorsDark.copy(primary = accent)
        val colors = orgChatColors(SignalDark, web)
        assertEquals(accent, colors.tickRead)
        assertEquals(accent, colors.primary)
        assertEquals(1f, colors.outgoing.alpha, 0f)
        assertEquals(1f, colors.incoming.alpha, 0f)
        assertTrue(colors.outgoing != colors.incoming)
        assertTrue(colors.outgoing.red > colors.incoming.red)
    }

    @Test fun `view once states for sender and recipient`() {
        assertTrue(!message().isViewOnce())
        assertEquals(ViewOnceState.Unopened, viewOnceState(message(metadata = viewOnce()), currentUserId = 2))
        assertEquals(ViewOnceState.Viewed, viewOnceState(message(metadata = viewOnce(2)), currentUserId = 2))
        assertEquals(ViewOnceState.SentUnviewed, viewOnceState(message(metadata = viewOnce()), currentUserId = 1))
        assertEquals(ViewOnceState.SentViewed, viewOnceState(message(metadata = viewOnce(2)), currentUserId = 1))
    }

    @Test fun `realtime view once patch appends the viewer once`() {
        val event = ChatViewOnceEvent(messageId = 7, conversationId = 3, viewerId = 2)
        val patched = applyViewOnce(listOf(message(metadata = viewOnce())), event)
        assertEquals(listOf(2L), patched.single().viewedBy())
        assertEquals(listOf(2L), applyViewOnce(patched, event).single().viewedBy())
        assertTrue(patched.single().isViewOnce())
    }

    @Test fun `search stepping clamps at both ends`() {
        assertEquals(-1, stepSearchMatch(-1, 0, 1))
        assertEquals(1, stepSearchMatch(0, 3, 1))
        assertEquals(2, stepSearchMatch(2, 3, 1))
        assertEquals(0, stepSearchMatch(0, 3, -1))
    }

    @Test fun `highlight marks every case-insensitive hit`() {
        val text = highlightTerm("Hello hello", "HELLO", androidx.compose.ui.graphics.Color.Yellow)
        assertEquals(2, text.spanStyles.size)
        assertEquals(0, highlightTerm("Hello", "h", androidx.compose.ui.graphics.Color.Yellow).spanStyles.size)
    }

    @Test fun `voice waveform is deterministic and bounded`() {
        val bars = voiceWaveform("https://x/a.m4a")
        assertEquals(bars, voiceWaveform("https://x/a.m4a"))
        assertTrue(bars.all { it in 0.2f..1f })
    }

    @Test fun `emoji backspace removes one character`() {
        assertEquals("ab", dropLastGrapheme("abc"))
        assertEquals("", dropLastGrapheme(""))
    }

    @Test fun `quoted media remains a quoted reply without text`() {
        assertTrue(message().copy(replyToId = 9, replyContent = null, replyFileUrl = "/image.png").hasQuotedReply())
        assertTrue(message().copy(replyToId = 9, replyFileType = "video/mp4").hasQuotedReply())
        assertTrue(!message().hasQuotedReply())
    }

    @Test fun `only short emoji messages get larger text`() {
        assertEquals(48.sp, emojiMessageSize("👍"))
        assertEquals(48.sp, emojiMessageSize("👨‍👩‍👧"))
        assertEquals(48.sp, emojiMessageSize("🇮🇳"))
        assertEquals(48.sp, emojiMessageSize("👍🏽"))
        assertEquals(40.sp, emojiMessageSize("❤️ ❤️"))
        assertEquals(32.sp, emojiMessageSize("😀😀😀"))
        assertEquals(SignalDimens.bodyText, emojiMessageSize("😀😀😀😀"))
        assertEquals(SignalDimens.bodyText, emojiMessageSize("Hello 😀"))
        assertEquals(SignalDimens.bodyText, emojiMessageSize("123"))
        assertEquals(SignalDimens.bodyText, emojiMessageSize(""))
    }
}
