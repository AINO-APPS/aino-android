package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectOnlyPlanningTest {
    @Test
    fun missingProjectMetadataNeverFallsBackToTeamPlanningRoutes() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val requests = CopyOnWriteArrayList<String>()
        val session = TicketSession()
        try {
            val repository = TaskRepository(ApiClient { request ->
                requests += request.path
                ApiResponse(200, emptyMap(), "{}".toByteArray())
            })
            val shell = TasksShellViewModel(repository, ticketSession = session)
            shell.bind(1, "super_admin", "Team", agileEnabled = true, teamId = 11)
            val deadline = System.currentTimeMillis() + 5_000
            while (!shell.ui.value.teamResolved && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(shell.ui.value.teamResolved)
            assertTrue(shell.ui.value.planScope.projectScoped)
            assertNull(shell.ui.value.planScope.selectedProjectId)
            assertTrue("Forbidden fallback requests: $requests", requests.none(::isLegacyPlanningRequest))
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun everyPlanningQueryUsesProjectOwnershipAndNeverLegacyTeamParameters() {
        val paths = CopyOnWriteArrayList<String>()
        val repository = TaskRepository(ApiClient {
            paths += it.path
            ApiResponse(200, emptyMap(), """{"tasks":[],"sprints":[]}""".toByteArray())
        })
        val scope = PlanScope(projectScoped = true, selectedProjectId = 3, selectedTeamId = 11, teamId = 11)
        repository.loadPlanTasks(scope.teamScope)
        repository.loadSprintTasks(21, TaskFilters(), scope.teamScope)
        repository.loadCancelledSprintTasks(21, scope.teamScope)
        repository.loadProjectSprints(3)
        assertTrue(paths.all { "project_id=3" in it })
        assertTrue(paths.none(::isLegacyPlanningRequest))
    }

    private fun isLegacyPlanningRequest(path: String): Boolean =
        "team_id=" in path || "unassigned=" in path || path == "sprints" ||
            path == "agile/config" || path == "org/teams" || path == "tasks/available-sprints" ||
            Regex("(^|/)(scheduled|timer|sprint-config|transfer)(/|\\?|$)").containsMatchIn(path)
}
