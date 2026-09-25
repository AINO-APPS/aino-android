package app.aino.mobile.feature.chat

import androidx.compose.ui.unit.dp
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

    @Test fun `bubble corners collapse only on the sender side inside a group`() {
        val single = messageBubbleShape(isMine = true, startsGroup = true, endsGroup = true)
        val middleMine = messageBubbleShape(isMine = true, startsGroup = false, endsGroup = false)
        val middleTheirs = messageBubbleShape(isMine = false, startsGroup = false, endsGroup = false)
        val size = androidx.compose.ui.geometry.Size(100f, 100f)
        val density = androidx.compose.ui.unit.Density(1f)
        assertEquals(18f, single.topEnd.toPx(size, density))
        assertEquals(18f, single.bottomEnd.toPx(size, density))
        assertEquals(4f, middleMine.topEnd.toPx(size, density))
        assertEquals(4f, middleMine.bottomEnd.toPx(size, density))
        assertEquals(18f, middleMine.topStart.toPx(size, density))
        assertEquals(4f, middleTheirs.topStart.toPx(size, density))
        assertEquals(18f, middleTheirs.topEnd.toPx(size, density))
        assertEquals(18.dp, SignalDimens.bubbleCorner)
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

    @Test fun `media box follows signal limits`() {
        assertEquals(240f to 180f, signalMediaSize(4f / 3f))
        val tall = signalMediaSize(9f / 20f)
        assertEquals(320f, tall.second)
        assertEquals(150f, tall.first)
        assertEquals(100f, signalMediaSize(5f).second)
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
}
