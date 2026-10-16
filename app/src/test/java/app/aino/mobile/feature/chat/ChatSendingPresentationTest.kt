package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.theme.AinoTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSendingPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    @Test fun `emoji has no outgoing fill on its first queued frame or after confirmation`() {
        assertPresentation("👍", startsGroup = true, endsGroup = true, bubbleExpected = false)
    }

    @Test fun `ordinary text keeps its clustered geometry and fill after confirmation`() {
        assertPresentation("A queued message", startsGroup = false, endsGroup = false, bubbleExpected = true)
    }

    @Test fun `four emoji still have an ordinary bubble during sending`() {
        assertPresentation("😀😀😀😀", startsGroup = true, endsGroup = true, bubbleExpected = true)
    }

    private fun assertPresentation(text: String, startsGroup: Boolean, endsGroup: Boolean, bubbleExpected: Boolean) {
        val confirmed = mutableStateOf(false)
        val time = "2026-10-16T10:00:00Z"
        val queued = QueuedMessage("local", 3, 1, text, Instant.parse(time).toEpochMilli())
        val sent = ChatMessage(7, 3, 1, text, time)
        var outgoing = androidx.compose.ui.graphics.Color.Unspecified
        compose.setContent {
            AinoTheme {
                val colors = signalColors
                outgoing = colors.outgoing
                Box(Modifier.fillMaxSize().background(colors.background)) {
                    if (confirmed.value) MessageBubble(
                        sent, isMine = true, showSender = startsGroup, startsGroup = startsGroup, endsGroup = endsGroup,
                        receipts = emptyList(), participantCount = 2,
                        onReply = {}, onReact = {}, onEdit = {}, onDelete = {}, onStar = {}, onPin = null,
                        onForward = {}, onCancel = {}, onRetry = {}, onVote = {}, onOpenMedia = {},
                    ) else QueuedBubble(queued, startsGroup, endsGroup)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val queuedBounds = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
        fun pixels(): androidx.compose.ui.graphics.PixelMap {
            lateinit var bitmap: android.graphics.Bitmap
            compose.runOnIdle {
                val view = compose.activity.window.decorView
                bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
            }
            return bitmap.asImageBitmap().toPixelMap()
        }
        val queuedPixels = pixels()
        fun hasFill(pixels: androidx.compose.ui.graphics.PixelMap): Boolean {
            var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                if (pixels[x, y] == outgoing && ++count > 50) return true
            }
            return false
        }
        assertEquals("queued fill", bubbleExpected, hasFill(queuedPixels))
        compose.runOnIdle { confirmed.value = true }
        compose.mainClock.advanceTimeByFrame()
        val sentBounds = compose.onNodeWithText(text).getUnclippedBoundsInRoot()
        val sentPixels = pixels()
        assertEquals("confirmed fill", bubbleExpected, hasFill(sentPixels))
        assertEquals(queuedBounds, sentBounds)
        val bodyBottom = with(compose.density) { (queuedBounds.bottom - 18.dp).roundToPx() }
        for (y in 0 until bodyBottom) for (x in 0 until queuedPixels.width) {
            assertTrue("body changed at $x,$y", queuedPixels[x, y] == sentPixels[x, y])
        }
    }
}
