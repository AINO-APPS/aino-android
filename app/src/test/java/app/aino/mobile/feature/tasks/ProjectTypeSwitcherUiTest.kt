package app.aino.mobile.feature.tasks

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelStore
import app.aino.mobile.ComposeTestHostActivity
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColorsDark
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp")
class ProjectTypeSwitcherUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private lateinit var session: TicketSession
    private val viewModels = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }

    @After fun tearDown() {
        viewModels.clear()
        session.close()
        Dispatchers.resetMain()
    }

    @Test fun switcherShowsEachProjectsTypeAndUpdatesTheSelectedPlanningContract() {
        showProjects("""[
            {"id":3,"key":"FLOW","name":"Flow","type":"kanban","planTabs":["board"],
                "kanban_backlog_enabled":false,"wip_enabled":true,
                "capabilities":{"canCreateTickets":true,"canChangeType":true}},
            {"id":4,"key":"OPS","name":"Operations","type":"task_list","planTabs":["list","board","calendar"],
                "is_archived":true,"wip_enabled":false,"capabilities":{"canChangeType":false}}
        ]""")

        compose.onNodeWithText("Kanban", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("FLOW", useUnmergedTree = true).performClick()
        compose.onNodeWithText("FLOW · Flow · Kanban").assertIsDisplayed()
        compose.onNodeWithText("OPS · Operations · Task list · Archived").assertIsDisplayed().performClick()
        compose.onNodeWithText("Task list", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(4L, session.planScope.value.selectedProjectId)
            assertEquals("task_list", session.planScope.value.projectType)
            assertEquals(listOf("list", "board", "calendar"), session.planScope.value.planTabs)
            assertEquals(false, session.planScope.value.canChangeType)
        }
    }

    @Test fun phaseTwoProjectsWithoutTypeFieldsStillRenderAndKeepLegacyPlanning() {
        showProjects("""[
            {"id":3,"key":"OLD","name":"Old project",
                "capabilities":{"canAdminister":true,"canCreateTickets":true}}
        ]""")

        compose.onNodeWithText("Scrum", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Scheduled").assertIsDisplayed()
        compose.onNodeWithText("OLD", useUnmergedTree = true).performClick()
        compose.onNodeWithText("OLD · Old project · Scrum").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("scrum", session.planScope.value.projectType)
            assertEquals(listOf("backlog", "sprint", "reports"), session.planScope.value.planTabs)
            assertEquals(false, session.planScope.value.canChangeType)
        }
    }

    private fun showProjects(projects: String) {
        val repository = TaskRepository(ApiClient { request ->
            val body = when (request.path) {
                "projects", "projects?include_archived=1", "projects/mine" -> projects
                "tasks/labels", "tasks/assignable-users" -> "[]"
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TaskViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        val scheduled = ScheduledViewModel(repository, session)
        val board = SprintBoardViewModel(repository, session)
        viewModels.put("shell", shell)
        viewModels.put("plan", plan)
        viewModels.put("scheduled", scheduled)
        viewModels.put("board", board)
        shell.bind(1, "employee", "Team", agileEnabled = false, teamId = 8)
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TasksScreen(shell, plan, scheduled, board, {}, {}, {})
            }
        }
        compose.waitUntil(5_000) { shell.ui.value.teamResolved }
    }
}
