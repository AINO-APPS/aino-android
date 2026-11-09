package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskListPlanningTest {
    @Test fun taskListMapsServerCalendarToUpcomingAndKeepsServerTabOrderWithoutSprints() {
        val scope = PlanScope(
            projectScoped = true, selectedProjectId = 3,
            projects = listOf(ProjectOption(id = 3, type = "task_list", planTabs = listOf("list", "board", "calendar"))),
        )
        val ui = TaskUiState(planScope = scope)
        assertEquals(listOf("list", "board", "upcoming"), ui.visibleTabs.map(TaskTab::key))
        assertEquals("Upcoming", tabLabel(ui.visibleTabs.last()))
        assertEquals(TaskTab.Upcoming, TaskTab.fromKey("upcoming"))
        assertEquals(listOf("list", "board", "upcoming"), TaskShellUiState(planScope = scope).visibleTabs.map(TaskTab::key))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun taskListLoadsItsWorkflowWithoutTheAgileFeatureOrSprintReads() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val session = TicketSession()
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repository = TaskRepository(ApiClient { request ->
            paths += request.path
            val body = when {
                request.path.startsWith("projects") -> """[{
                    "id":3,"type":"task_list","planTabs":["list","board","calendar"],
                    "capabilities":{"canCreateTickets":true}
                }]"""
                request.path == "agile/config?project_id=3" -> """{
                    "workflowStates":[{"id":91,"key":"review","name":"Editorial review"}],
                    "workItemTypes":[{"id":92,"name":"Request"}]
                }"""
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        try {
            val shell = TaskViewModel(repository, ticketSession = session)
            shell.bind(1, "employee", null, agileEnabled = false)
            val deadline = System.currentTimeMillis() + 5_000
            while (shell.ui.value.agile.workflowStates.none { it.id == 91L } && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue(paths.contains("agile/config?project_id=3"))
            assertEquals("Editorial review", shell.ui.value.agile.workflowStates.single().name)
            assertFalse(paths.any { it.startsWith("sprints") || it.contains("available-sprints") })
        } finally {
            session.close()
            Dispatchers.resetMain()
        }
    }
}
