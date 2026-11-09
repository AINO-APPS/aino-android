package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class KanbanBoardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var session: TicketSession
    private lateinit var taskViewModel: TaskViewModel
    private val viewModels = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
        taskViewModel = TaskViewModel(TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), "{}".toByteArray())
        }), ticketSession = session)
        viewModels.put("tasks", taskViewModel)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        session.close()
        Dispatchers.resetMain()
    }

    @Test
    fun projectBoardShowsServerColumnOccupancyAndItsTickets() {
        val project = ProjectOption(
            id = 17,
            key = "FLOW",
            name = "Flow",
            type = "kanban",
            planTabs = listOf("board"),
            wipEnabled = true,
        )
        val state = WorkflowState(id = 21, key = "in_progress", name = "In Progress")
        val task = Task(
            id = 71,
            title = "Ship the board",
            projectId = project.id,
            workflowStateId = state.id,
            workflowStateKey = state.key,
        )
        val scope = PlanScope(projects = listOf(project), projectScoped = true, selectedProjectId = project.id)
        val board = ProjectBoardResponse(
            project = project,
            columns = listOf(ProjectBoardColumn(21, count = 2, limit = 3, accepting = true, state = state)),
            tickets = listOf(task),
        )
        show(
            ui = TaskUiState(planScope = scope, tab = TaskTab.KanbanBoard),
            boardUi = ProjectBoardUiState(
                planScope = scope,
                tickets = mapOf(task.id to task),
                boardTicketIds = listOf(task.id),
                board = board,
            ),
        )

        compose.onNodeWithText("In Progress").assertIsDisplayed()
        compose.onNodeWithText("2/3").assertIsDisplayed()
        compose.onNodeWithText("Ship the board").assertIsDisplayed()
    }

    @Test
    fun projectBoardErrorOffersAVisibleRetryAction() {
        val project = ProjectOption(id = 17, type = "kanban", planTabs = listOf("board"))
        val scope = PlanScope(projects = listOf(project), projectScoped = true, selectedProjectId = project.id)
        show(
            ui = TaskUiState(planScope = scope, tab = TaskTab.KanbanBoard),
            boardUi = ProjectBoardUiState(planScope = scope, boardError = "Network unavailable"),
        )

        compose.onNodeWithText("Network unavailable").assertIsDisplayed()
        compose.onNodeWithText("Could not load board").assertIsDisplayed()
        compose.onNodeWithText("Try again").assertIsDisplayed()
    }

    private fun show(ui: TaskUiState, boardUi: ProjectBoardUiState) {
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                KanbanBoardPage(
                    ui = ui,
                    boardUi = boardUi,
                    viewModel = taskViewModel,
                    onRefresh = {},
                    onRetry = {},
                    onOpen = {},
                )
            }
        }
    }
}
