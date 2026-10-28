package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real board, driven through the real ViewModel and repository.
 *
 * The unit tests pin the branch and the two composables in isolation; this one
 * proves the board actually wires them together — that an empty sprint renders
 * four bare headers plus one board-level state, and that a partially-empty
 * board renders a dashed slot per empty column instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SprintBoardWiringTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComposeTestHostActivity>()

    private val sprint = AvailableSprint(
        id = 12,
        name = "Sprint #1",
        status = "active",
        startDate = "2026-10-26",
        endDate = "2026-11-08",
    )

    private fun viewModel(): TaskViewModel {
        val client = ApiClient { _: ApiRequest ->
            ApiResponse(200, emptyMap(), "{}".toByteArray())
        }
        return TaskViewModel(TaskRepository(client))
    }

    private fun state(tasks: List<Task>) = TaskUiState(
        userId = 1,
        role = "employee",
        teamName = "Payments",
        agileEnabled = true,
        sprints = listOf(sprint),
        selectedSprintId = sprint.id,
        tab = TaskTab.Sprint,
        sprintLoading = false,
        tasks = tasks,
        agile = AgileConfig(
            workflowStates = listOf(
                WorkflowState(id = 1, key = "pending", name = "New", category = "open", color = "#6b7280", isInitial = true, sortOrder = 1),
                WorkflowState(id = 2, key = "in_progress", name = "In Progress", category = "in_progress", color = "#f59e0b", sortOrder = 2),
                WorkflowState(id = 3, key = "in_review", name = "In Review", category = "in_review", color = "#3b82f6", sortOrder = 3),
                WorkflowState(id = 4, key = "done", name = "Done", category = "done", color = "#10b981", isTerminal = true, sortOrder = 4),
            ),
        ),
    )

    private fun show(ui: TaskUiState) {
        val vm = viewModel()
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                SprintBoard(ui = ui, viewModel = vm, onOpen = {})
            }
        }
    }

    @Test
    fun anEmptySprintShowsOneBoardStateAndBareHeaders() {
        show(state(tasks = emptyList()))

        compose.onNodeWithText("New").assertIsDisplayed()
        // The board is a LazyColumn: the last header and the footer only exist
        // once scrolled into view.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Done"))
        compose.onNodeWithText("Done").assertIsDisplayed()
        // No per-column empty states anywhere on the board.
        compose.onNodeWithTag(EMPTY_COLUMN_TAG).assertDoesNotExist()

        // One board-level state, carrying the message and both actions.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("This sprint is empty"))
        compose.onNodeWithText("This sprint is empty").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Create ticket"))
        compose.onNodeWithText("Import from backlog").assertIsDisplayed()
        compose.onNodeWithText("Create ticket").assertIsDisplayed()
    }

    @Test
    fun aPartiallyEmptyBoardShowsADashedSlotPerEmptyColumn() {
        show(
            state(
                tasks = listOf(
                    Task(id = 7, title = "Ship it", workflowStateKey = "pending", workflowStateId = 1, sprintId = 12),
                ),
            ),
        )

        // New has a card, so nothing is empty at board level.
        compose.onNodeWithText("Ship it").assertIsDisplayed()
        compose.onNodeWithText("This sprint is empty").assertDoesNotExist()

        // The other three columns each get the thin dashed slot. The list only
        // composes what is on screen, so scroll to the last column before
        // counting rather than measuring from the top.
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag(EMPTY_COLUMN_TAG))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Done"))
        compose.onAllNodesWithTag(EMPTY_COLUMN_TAG)[0].assertIsDisplayed()
        compose.onAllNodesWithTag(EMPTY_COLUMN_TAG).assertCountEquals(3)
    }
}
