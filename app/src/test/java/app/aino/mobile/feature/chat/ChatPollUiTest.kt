package app.aino.mobile.feature.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.theme.AinoTheme
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatPollUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private val message = ChatMessage(100, 5, 2, "Lunch?", "2026-10-10T10:00:00Z", formatType = "poll")
    private val poll = ChatPoll(42, 5, 1, "Lunch?", listOf("Soup", "Salad"))

    @Test fun `card actions disable unloaded pending and closed votes but allow retries`() {
        val loaded = mutableStateOf<ChatPoll?>(null)
        val state = mutableStateOf(PollUiState(loadError = "Could not load votes"))
        var retries = 0
        val picks = mutableListOf<Int>()
        compose.setContent { AinoTheme { ChatPollCard(message, loaded.value, state.value, 1, picks::add, { retries++ }) } }
        compose.onNodeWithText("Could not load votes").assertExists()
        compose.onNodeWithText("Votes unavailable").assertExists()
        compose.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { loaded.value = poll; state.value = PollUiState(voting = true, pendingOptionIndex = 0) }
        compose.onNodeWithText("Soup").assertIsNotEnabled()
        compose.onNodeWithText("Updating vote…").assertExists()
        compose.runOnIdle { state.value = PollUiState() }
        compose.onNodeWithText("Salad").performClick()
        assertEquals(listOf(1), picks)
        compose.runOnIdle { loaded.value = poll.copy(closedAt = "2026-10-10") }
        compose.onNodeWithText("Soup").assertIsNotEnabled()
        compose.onNodeWithText("Poll closed · Results only").assertExists()
        compose.onNodeWithText("View votes").performClick()
        compose.onNodeWithText("Poll votes").assertExists()
    }

    @Test fun `vote failures stay readable and permit a new option tap without a fabricated selection`() {
        var option: Int? = null
        compose.setContent { AinoTheme { ChatPollCard(message, poll,
            PollUiState(voteError = "Could not update vote."), 1, { option = it }, {}) } }
        compose.onNodeWithText("Could not update vote. Tap an option to try again.").assertExists()
        compose.onNodeWithText("Soup").assertIsNotSelected().assertIsEnabled().performClick()
        assertEquals(0, option)
    }

    @Test fun `selection mode selects messages instead of voting`() {
        var selected = 0
        var voted = 0
        compose.setContent { AinoTheme { ChatPollCard(message, poll, PollUiState(), 1,
            onVote = { voted++ }, onRetry = {}, interactionEnabled = false, onSelect = { selected++ }) } }
        compose.onNodeWithText("Soup").performClick()
        assertEquals(1, selected)
        assertEquals(0, voted)
        compose.onNodeWithText("View votes").assertIsNotEnabled()
    }

    @Test fun `view votes is grouped expandable and updates while open`() {
        val voters = (1L..8L).map { PollVoter(it, "Member $it") }
        val current = mutableStateOf(poll.copy(votes = mapOf(0 to voters)))
        compose.setContent { AinoTheme { ChatPollCard(message, current.value, PollUiState(), 1, {}, {}) } }
        compose.onNodeWithText("View votes").performClick()
        compose.onNodeWithText("You").assertExists()
        compose.onNodeWithText("No votes for this option").assertExists()
        compose.onNodeWithText("Show 3 more").performScrollTo().performClick()
        compose.onNodeWithText("Member 8").assertExists()
        compose.onNodeWithText("Show fewer").performScrollTo().performClick()
        compose.onNodeWithText("Member 8").assertDoesNotExist()
        compose.runOnIdle { current.value = poll.copy(votes = mapOf(0 to voters.take(1), 1 to voters.take(1))) }
        compose.onAllNodesWithText("Tied for most votes").assertCountEquals(2)
        compose.onNodeWithText("2 votes · 1 person").assertExists()
    }

    @Test fun `voter photos forward their URL with loading error and missing real-name initials even for You`() {
        MockWebServer().use { server ->
            val respond = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    respond.await(5, TimeUnit.SECONDS)
                    return MockResponse().setResponseCode(404)
                }
            }
            val current = mutableStateOf(poll.copy(votes = mapOf(0 to listOf(
                PollVoter(1, "Asha Mehta", server.url("/uploads/voter.png").toString()),
                PollVoter(2, "Ben Shah"),
                PollVoter(3, "Cara Patel", "  "),
                PollVoter(4, ""),
            ))))
            try {
                compose.setContent { AinoTheme { PollVotesDialog(current.value, PollUiState(), 1, {}, {}) } }
                compose.onNodeWithText("You").assertExists()
                compose.onNodeWithText("AM", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("Y", useUnmergedTree = true).assertDoesNotExist()
                compose.onNodeWithText("BS", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("CP", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("?", useUnmergedTree = true).assertExists()
                compose.waitUntil(5_000) { server.requestCount > 0 }
                assertEquals("/uploads/voter.png", server.takeRequest(1, TimeUnit.SECONDS)?.path)
                respond.countDown()
                compose.waitUntil(5_000) { AppContainer.get(compose.activity).mediaHttp.dispatcher.runningCallsCount() == 0 }
                compose.onNodeWithText("AM", useUnmergedTree = true).assertExists()
                compose.runOnIdle {
                    current.value = current.value.copy(votes = mapOf(0 to listOf(PollVoter(1, "Asha Mehta"))))
                }
                compose.onNodeWithText("You").assertExists()
                compose.onNodeWithText("AM", useUnmergedTree = true).assertExists()
                compose.onNodeWithText("BS", useUnmergedTree = true).assertDoesNotExist()
            } finally {
                respond.countDown()
                AppContainer.get(compose.activity).imageLoader.shutdown()
            }
        }
    }

    @Test fun `creator preserves failed draft disables duplicate submission and closes only on success`() {
        val state = mutableStateOf(PollCreationState())
        var closed = 0
        var sentQuestion: String? = null
        var sentOptions: List<String>? = null
        var sentMulti: Boolean? = null
        compose.setContent { AinoTheme { PollCreatorDialog(
            onSubmit = { question, options, multi -> sentQuestion = question; sentOptions = options; sentMulti = multi },
            onClose = { closed++ }, state = state.value,
        ) } }
        compose.onNodeWithText("Send").assertIsNotEnabled()
        compose.onNodeWithText("Question").performTextInput("Lunch?")
        compose.onNodeWithText("Option 1").performTextInput("Soup")
        compose.onNodeWithText("Option 2").performScrollTo().performTextInput("Salad")
        compose.onNodeWithText("Send").performClick()
        assertEquals("Lunch?", sentQuestion)
        assertEquals(listOf("Soup", "Salad"), sentOptions)
        assertEquals(false, sentMulti)
        assertEquals(0, closed)
        compose.runOnIdle { state.value = PollCreationState(sending = true) }
        compose.onNodeWithText("Sending").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Close poll creator").assertIsNotEnabled()
        compose.runOnIdle { state.value = PollCreationState(error = "Could not create poll. Try again.") }
        compose.onNodeWithText("Lunch?").assertExists()
        compose.onNodeWithText("Soup").assertExists()
        compose.onNodeWithText("Salad").assertExists()
        compose.onNodeWithText("Send").assertIsEnabled()
        assertEquals(0, closed)
        compose.runOnIdle { state.value = PollCreationState(createdPollId = 42) }
        compose.waitForIdle()
        assertEquals(1, closed)
    }
}
