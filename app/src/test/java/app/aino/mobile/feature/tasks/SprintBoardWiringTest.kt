package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

    private fun state(tasks: List<Task>): SprintBoardUiState {
        val states = listOf(
            WorkflowState(id = 1, key = "pending", name = "New", category = "open", color = "#6b7280", isInitial = true, sortOrder = 1),
            WorkflowState(id = 2, key = "in_progress", name = "In Progress", category = "in_progress", color = "#f59e0b", sortOrder = 2),
            WorkflowState(id = 3, key = "in_review", name = "In Review", category = "in_review", color = "#3b82f6", sortOrder = 3),
            WorkflowState(id = 4, key = "done", name = "Done", category = "done", color = "#10b981", isTerminal = true, sortOrder = 4),
        )
        return SprintBoardUiState(
            planScope = PlanScope(
                userId = 1,
                role = "employee",
                teamName = "Payments",
                agileEnabled = true,
                sprints = listOf(sprint),
                selectedSprintId = sprint.id,
                agile = AgileConfig(workflowStates = states),
            ),
            sprintLoading = false,
            tickets = tasks.associateBy { it.id },
            boardTicketIds = tasks.map { it.id },
            board = SprintBoardResponse(
                sprint = sprint,
                columns = states.map { state ->
                    SprintBoardColumn(state.id, tasksForColumn(tasks, state).size, state.wipLimit, accepting = true)
                },
            ),
        )
    }

    private fun show(ui: SprintBoardUiState) {
        val vm = viewModel()
        val boardVm = SprintBoardViewModel(TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) }), vm.ticketSession)
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                SprintBoard(ui = TaskUiState(planScope = ui.planScope, tab = TaskTab.Sprint), boardUi = ui, viewModel = vm, boardViewModel = boardVm, onOpen = {})
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
                    Task(id = 7, title = "Ship it", workflowStateKey = "pending", workflowStateId = 1, sprintId = 12, container = "sprint"),
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

    @Test
    fun theSprintBoardRendersServerColumnCountsAndExcludesCancelledFromTheSharedFixture() {
        val board = Json { ignoreUnknownKeys = true }.decodeFromString<SprintBoardResponse>(
            requireNotNull(javaClass.classLoader?.getResource("contracts/sprint-board.json")).readText(),
        )
        val ui = state(tasks = emptyList()).copy(
            board = board.copy(tickets = emptyList()),
            tickets = board.tickets.associateBy { it.id },
            boardTicketIds = board.tickets.map { it.id },
            planScope = PlanScope(
                selectedSprintId = board.sprint.id,
                filters = TaskFilters(status = "done"),
                agile = AgileConfig(
                workflowStates = listOf(
                    WorkflowState(id = 101, key = "todo", name = "To Do", wipLimit = 1),
                    WorkflowState(id = 102, key = "in_progress", name = "In Progress"),
                    WorkflowState(id = 103, key = "cancelled", name = "Cancelled"),
                ),
                features = AgileFeatures(wipLimits = true),
                ),
            ),
        )
        show(ui)

        compose.onNodeWithText("1/1").assertIsDisplayed()
        compose.onNodeWithText("Cancelled").assertDoesNotExist()
        compose.onNodeWithText("Review the release plan").assertDoesNotExist()
    }

    @Test
    fun cancelledSprintTicketsSitInACollapsedSectionThatOpensToReadableRows() {
        val open = Task(id = 7, title = "Ship it", workflowStateKey = "pending", workflowStateId = 1, sprintId = 12, container = "sprint")
        val cancelled = Task(id = 9, title = "Dropped idea", workflowStateKey = "cancelled", sprintId = 12, container = "sprint")
        var opened: Task? = null
        val ui = state(tasks = listOf(open)).let {
            it.copy(tickets = it.tickets + (cancelled.id to cancelled), cancelledTicketIds = listOf(cancelled.id))
        }
        val vm = viewModel()
        val boardVm = SprintBoardViewModel(TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) }), vm.ticketSession)
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                SprintBoard(ui = TaskUiState(planScope = ui.planScope, tab = TaskTab.Sprint), boardUi = ui, viewModel = vm, boardViewModel = boardVm, onOpen = { opened = it })
            }
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Cancelled"))
        compose.onNodeWithText("Dropped idea").assertDoesNotExist()
        compose.onNodeWithText("Cancelled").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Dropped idea"))
        compose.onNodeWithText("Dropped idea").assertIsDisplayed().performClick()

        org.junit.Assert.assertEquals(9L, opened?.id)
    }

    private fun actionSheetState(board: SprintBoardResponse): SprintBoardUiState {
        val states = listOf(
            WorkflowState(id = 101, key = "todo", name = "To Do"),
            WorkflowState(id = 102, key = "in_progress", name = "In Progress"),
            WorkflowState(id = 103, key = "cancelled", name = "Cancelled"),
        )
        val ui = state(board.tickets)
        return ui.copy(board = board.copy(tickets = emptyList()), planScope = ui.planScope.copy(selectedSprintId = board.sprint.id, agile = AgileConfig(workflowStates = states)))
    }

    @Test
    fun theTicketActionSheetRendersCapabilityTransitions() {
        val board = Json { ignoreUnknownKeys = true }.decodeFromString<SprintBoardResponse>(
            requireNotNull(javaClass.classLoader?.getResource("contracts/sprint-board.json")).readText(),
        )
        val vm = viewModel()

        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                val boardUi = actionSheetState(board)
                TaskActionsSheet(board.tickets.single(), TaskUiState(planScope = boardUi.planScope), boardUi, SprintBoardViewModel(TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) }), vm.ticketSession), onOpen = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("In Progress").assertIsEnabled()
        compose.onNodeWithText("Cancelled").assertIsNotEnabled()
        compose.onNodeWithText("This ticket cannot move to that status.").assertIsDisplayed()
    }

    @Test
    fun theTicketActionSheetFailsClosedWhenCapabilitiesAreMissing() {
        val board = Json { ignoreUnknownKeys = true }.decodeFromString<SprintBoardResponse>(
            requireNotNull(javaClass.classLoader?.getResource("contracts/sprint-board.json")).readText(),
        )
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                val vm = viewModel()
                val boardUi = actionSheetState(board)
                TaskActionsSheet(board.tickets.single().copy(capabilities = null), TaskUiState(planScope = boardUi.planScope), boardUi, SprintBoardViewModel(TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) }), vm.ticketSession), onOpen = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("In Progress").assertIsNotEnabled()
        compose.onAllNodesWithText("This action is not available.").assertCountEquals(3)
        compose.onNodeWithText("Cancel ticket").assertIsNotEnabled()
    }
}
