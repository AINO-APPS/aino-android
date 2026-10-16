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

    @Test fun `bubble corners follow Signal message clusters`() {
        val size = androidx.compose.ui.geometry.Size(100f, 100f)
        val density = androidx.compose.ui.unit.Density(1f)
        fun corners(shape: androidx.compose.foundation.shape.RoundedCornerShape) =
            listOf(shape.topStart, shape.topEnd, shape.bottomEnd, shape.bottomStart).map { it.toPx(size, density) }
        // topStart, topEnd, bottomEnd, bottomStart
        assertEquals(listOf(18f, 18f, 18f, 18f), corners(messageBubbleShape(isMine = true, startsGroup = true, endsGroup = true)))
        assertEquals(listOf(18f, 18f, 4f, 18f), corners(messageBubbleShape(isMine = true, startsGroup = true, endsGroup = false)))
        assertEquals(listOf(18f, 4f, 4f, 18f), corners(messageBubbleShape(isMine = true, startsGroup = false, endsGroup = false)))
        assertEquals(listOf(18f, 4f, 18f, 18f), corners(messageBubbleShape(isMine = true, startsGroup = false, endsGroup = true)))
        assertEquals(listOf(18f, 18f, 18f, 4f), corners(messageBubbleShape(isMine = false, startsGroup = true, endsGroup = false)))
        assertEquals(listOf(4f, 18f, 18f, 4f), corners(messageBubbleShape(isMine = false, startsGroup = false, endsGroup = false)))
        assertEquals(listOf(4f, 18f, 18f, 18f), corners(messageBubbleShape(isMine = false, startsGroup = false, endsGroup = true)))
        assertEquals(18.dp, SignalDimens.bubbleCorner)
    }

    @Test fun `chat bubbles follow the org theme like the web bubbles`() {
        val accent = androidx.compose.ui.graphics.Color(0xFFE91E63)
        val web = app.aino.mobile.core.designsystem.tokens.WebColorsDark.copy(primary = accent)
        val colors = orgChatColors(SignalDark, web)
        assertEquals(accent, colors.tickRead)
        assertEquals(accent, colors.primary)
        assertEquals(accent.copy(alpha = .16f), colors.outgoingBorder)
        assertEquals(1f, colors.outgoing.alpha, 0f)
        assertEquals(1f, colors.incoming.alpha, 0f)
        assertTrue(colors.outgoing != colors.incoming)
        assertTrue(colors.outgoing.red > colors.incoming.red)
        // Bubble text is the theme text, not white on a solid fill.
        assertEquals(colors.onIncoming, colors.onOutgoing)
        assertTrue(colors.tickDelivered != colors.tickMuted)
    }

    @Test fun `chat screens share the home background in both themes`() {
        val dark = app.aino.mobile.core.designsystem.tokens.WebColorsDark
        val light = app.aino.mobile.core.designsystem.tokens.WebColorsLight
        assertEquals(dark.bg, orgChatColors(SignalDark, dark).background)
        assertEquals(light.bg, orgChatColors(SignalLight, light).background)
        assertEquals(dark.bgElevated, orgChatColors(SignalDark, dark).surface)
        assertEquals(light.bgSecondary, orgChatColors(SignalLight, light).surface)
    }

    @Test fun `group sender names keep one Signal colour per person`() {
        assertEquals(signalNameColor(42, dark = false), signalNameColor(42, dark = false))
        assertTrue(signalNameColor(42, dark = false) != signalNameColor(42, dark = true))
        assertTrue((1L..36L).map { signalNameColor(it, dark = false) }.toSet().size > 20)
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

    @Test fun `queued and confirmed emoji use the same standalone eligibility`() {
        listOf("👍", "👨‍👩‍👧", "🇮🇳", "👍🏽", "❤️ ❤️", "😀😀😀").forEach { text ->
            assertTrue(isJumbomoji(text, hasQuotedReply = false))
            assertTrue(!isJumbomoji(text, hasQuotedReply = true))
        }
        listOf("😀😀😀😀", "Hello 😀", "123", "").forEach { text ->
            assertTrue(!isJumbomoji(text, hasQuotedReply = false))
        }
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
