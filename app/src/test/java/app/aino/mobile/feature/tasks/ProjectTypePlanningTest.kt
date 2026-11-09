package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTypePlanningTest {
    private lateinit var session: TicketSession
    private val requests = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
        requests.clear()
    }

    @After
    fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    @Test
    fun scrumPlanTabsComeFromTheServerAndRemainStableWithoutASprint() {
        val shell = shell(projects = scrumProject)
        shell.bind(1, "employee", "Team", agileEnabled = false)

        await { shell.ui.value.teamResolved && shell.ui.value.planScope.projectScoped }

        assertEquals(listOf("backlog", "sprint", "reports"), shell.ui.value.visibleTabs.map(TaskTab::key))
        assertTrue(TaskUiState(planScope = shell.ui.value.planScope).sprintTabVisible)
        assertFalse(requests.any { it == "tasks/scheduled" || it == "tasks/carry-forward" || it.contains("sprint-config") })
    }

    @Test
    fun missingProjectTabsFallBackWithoutARefreshFailureOrScheduledTab() {
        val shell = shell(projects = """[{"id":3,"key":"PLT","name":"Platform","capabilities":{"canCreateTickets":true}}]""")
        shell.bind(1, "employee", "Team", agileEnabled = false)

        await { shell.ui.value.teamResolved && shell.ui.value.planScope.projectScoped }

        assertEquals(listOf(TaskTab.Backlog, TaskTab.Sprint, TaskTab.Reports), shell.ui.value.visibleTabs)
        assertFalse(shell.ui.value.visibleTabs.any { it.key == "scheduled" })
    }

    @Test
    fun serverOrderAndSprintAvailabilityOverrideTheAgileFeature() {
        val shell = shell(projects = scrumProject.replace(
            """["backlog","sprint","reports"]""", """["reports","backlog"]""",
        ))
        shell.bind(1, "employee", "Team", agileEnabled = true)
        await { shell.ui.value.teamResolved && shell.ui.value.planScope.projectScoped }

        assertEquals(listOf(TaskTab.Reports, TaskTab.Backlog), shell.ui.value.visibleTabs)
        shell.selectTab(TaskTab.Sprint)
        assertEquals(TaskTab.Backlog, shell.ui.value.tab)
        assertFalse(requests.any { it.startsWith("sprints") || it.startsWith("agile/config") })
    }

    @Test
    fun unsupportedFuturePlanningViewsDoNotEnableUpcomingScreens() {
        val shell = shell(projects = scrumProject.replace(
            """["backlog","sprint","reports"]""", """["board","calendar","list"]""",
        ))
        shell.bind(1, "employee", "Team", agileEnabled = true)
        await { shell.ui.value.teamResolved && shell.ui.value.planScope.projectScoped }

        assertEquals(listOf(TaskTab.Backlog), shell.ui.value.visibleTabs)
        assertFalse(requests.any { it.startsWith("sprints") })
    }

    @Test
    fun hiddenTabsFallBackToAVisibleServerTabEvenWithoutBacklog() {
        assertEquals(TaskTab.Reports, normalizeTab(TaskTab.Sprint, listOf(TaskTab.Reports)))
    }

    private fun shell(projects: String): TasksShellViewModel {
        val accessibleProjects = projects
        val api = ApiClient { request ->
            requests += request.path
            val body = when (request.path) {
                "projects?include_archived=1", "projects/mine" -> accessibleProjects
                "projects" -> "[]"
                "user/preferences" -> "{}"
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        }
        val repository = TaskRepository(api)
        PlanViewModel(repository, session)
        return TasksShellViewModel(repository, ticketSession = session)
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        fun ready() = condition() && requests.any { it.startsWith("tasks/backlog") }
        while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for project plan state", ready())
    }

    private companion object {
        const val scrumProject =
            """[{"id":3,"key":"PLT","name":"Platform","planTabs":["backlog","sprint","reports"],"capabilities":{"canCreateTickets":true}}]"""
    }
}
