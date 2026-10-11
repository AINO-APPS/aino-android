package app.aino.mobile.feature.chat

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import app.aino.mobile.core.designsystem.theme.AinoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatPollInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun resultsStayReactiveAndExposeGroupedVotersOnDevice() {
        val poll = mutableStateOf(ChatPoll(42, 5, 1, "Lunch?", listOf("Soup", "Salad")))
        val message = ChatMessage(100, 5, 2, "Lunch?", "2026-10-10T10:00:00Z", formatType = "poll")
        compose.setContent {
            AinoTheme {
                val colors = signalColors
                Box(Modifier.fillMaxSize().background(colors.background).statusBarsPadding().padding(16.dp)) {
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(SignalDimens.bubbleCorner))
                            .background(colors.incoming)
                            .padding(horizontal = SignalDimens.bubbleHPad, vertical = SignalDimens.bubbleTopPad),
                    ) {
                        ChatPollCard(message, poll.value, PollUiState(), 1, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("View votes").assertIsDisplayed()
        screenshot("poll-card-empty")
        compose.onNodeWithText("View votes").performClick()
        compose.onNodeWithText("No votes yet. Results will appear here when someone votes.").assertExists()
        screenshot("poll-votes-empty")
        compose.runOnIdle { poll.value = poll.value.copy(votes = mapOf(0 to listOf(PollVoter(1, "Asha")), 1 to listOf(PollVoter(2, "Ben")))) }
        compose.onNodeWithText("You").assertExists()
        compose.onNodeWithText("Ben").assertExists()
        compose.onAllNodesWithText("Tied for most votes").assertCountEquals(2)
        compose.onNodeWithText("2 votes · 2 people").assertIsDisplayed()
        screenshot("poll-votes-live-tie")
    }

    @Test fun creatorScrollsWithTenOptionsAndKeepsTheDraftAfterFailure() {
        val state = mutableStateOf(PollCreationState())
        var closes = 0
        compose.setContent { AinoTheme { PollCreatorDialog({ _, _, _ -> }, { closes++ }, state.value) } }
        compose.onNodeWithText("Question").performTextInput("Lunch?")
        compose.onNodeWithText("Option 1").performTextInput("Soup")
        compose.onNodeWithText("Option 2").performScrollTo().performTextInput("Salad")
        repeat(8) { compose.onNodeWithText("+ Add option").performScrollTo().performClick() }
        compose.onNodeWithText("Option 10").performScrollTo().performTextInput("Rice")
        compose.onNodeWithText("Maximum of 10 options").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Send").assertIsEnabled()
        screenshot("poll-creator-ten-options")
        compose.runOnIdle { state.value = PollCreationState(error = "Could not create poll. Try again.") }
        compose.onNodeWithText("Rice").performScrollTo().assertExists()
        compose.onNodeWithText("Could not create poll. Try again.").performScrollTo().assertExists()
        assertEquals(0, closes)
        screenshot("poll-creator-failed-draft")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val frames = java.util.concurrent.CountDownLatch(1)
        instrumentation.runOnMainSync {
            val choreographer = android.view.Choreographer.getInstance()
            choreographer.postFrameCallback {
                choreographer.postFrameCallback { frames.countDown() }
            }
        }
        check(frames.await(3, java.util.concurrent.TimeUnit.SECONDS)) { "Poll screenshot frames did not render" }
        instrumentation.waitForIdleSync()
        // Compose semantics can settle before SurfaceFlinger presents the GPU frame.
        Thread.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Could not capture poll screenshot" }
        try {
            val directory = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "poll-screenshots")
            check(directory.isDirectory || directory.mkdirs())
            FileOutputStream(File(directory, "$name.png")).use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }
}
