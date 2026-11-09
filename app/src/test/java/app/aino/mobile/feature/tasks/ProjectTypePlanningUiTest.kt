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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProjectTypePlanningUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComposeTestHostActivity>()
    private val session = TicketSession()
    private val models = ViewModelStore()

    @After fun tearDown() {
        models.clear()
        session.close()
    }

    @Test fun noSprintKeepsScrumTabsAndOffersBacklogNavigation() {
        val repository = TaskRepository(ApiClient { request ->
            val body = when (request.path) {
                "projects", "projects?include_archived=1", "projects/mine" ->
                    """[{"id":3,"name":"Platform","planTabs":["backlog","sprint","reports"],"capabilities":{"canCreateTickets":true}}]"""
                "tasks/labels", "tasks/assignable-users" -> "[]"
                "tasks/backlog?limit=500&offset=0&project_id=3&include_completed=1&completed_since=0001-01-01" -> """{"tasks":[]}"""
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TaskViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        val board = SprintBoardViewModel(repository, session)
        models.put("shell", shell)
        models.put("plan", plan)
        models.put("board", board)
        shell.bind(1, "employee", "Team", agileEnabled = false)
        compose.setContent {
            CompositionLocalProvider(LocalWebColors provides WebColorsDark) {
                TasksScreen(shell, plan, board, {}, {}, {})
            }
        }
        compose.waitUntil(5_000) { shell.ui.value.teamResolved }
        listOf("Backlog", "Sprint", "Reports").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Scheduled").assertDoesNotExist()
        compose.onNodeWithText("Sprint").performClick()
        compose.onNodeWithText("No sprint yet").assertIsDisplayed()
        compose.onNodeWithText("Go to backlog").performClick()
        compose.runOnIdle { assertEquals(TaskTab.Backlog, shell.ui.value.tab) }
    }
}
