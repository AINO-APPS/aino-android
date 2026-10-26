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
import org.junit.Before
import org.junit.Test
import kotlinx.serialization.json.Json

/**
 * The "Completed (N)" section only renders when it knows N. The count arrives
 * with the first backlog page (`completed_count`, as web's `useBacklog` reads
 * it), so it must be applied on load — not only after the section is opened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CompletedCountTest {
    private val api = ApiClient { request ->
        val body = when {
            request.path.startsWith("tasks/backlog") ->
                """{"tasks":[{"id":4,"title":"Ship"},
                   {"id":5,"title":"Done 1","completed_at":"2026-10-25"},
                   {"id":6,"title":"Done 2","completed_at":"2026-10-25"},
                   {"id":7,"title":"Done 3","completed_at":"2026-10-25"}],"completed_count":3}"""
            request.path == "tasks/carry-forward" -> """{"carried":0}"""
            request.path == "tasks/4/detail" ->
                """{"id":4,"title":"Ship","priority":"high","workflow_state_id":5,"completed_at":"2026-10-26"}"""
            else -> "[]"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun backlogLoadSurfacesTheCompletedCount() {
        val vm = TaskViewModel(TaskRepository(api))
        vm.bind(userId = 1, role = "employee", teamName = null, agileEnabled = false)
        waitFor { vm.ui.value.backlog.isNotEmpty() }
        waitFor { vm.ui.value.completedCount == 3 }
        assertEquals(3, vm.ui.value.completedCount)
        assertEquals(true, vm.ui.value.completedExpanded)
        vm.toggleSelection(4)
        assertEquals(false, vm.ui.value.completedExpanded)
        assertEquals(3, vm.ui.value.completed.size)
        vm.clearSelection()
        assertEquals(true, vm.ui.value.completedExpanded)
        vm.toggleCompletedSection()
        vm.toggleSelection(4)
        vm.clearSelection()
        assertEquals(false, vm.ui.value.completedExpanded)
    }

    @Test
    fun selectingTicketsHidesTheNewTicketFab() {
        val idle = TaskUiState(tab = TaskTab.Backlog)
        assertEquals(true, idle.showNewTicketFab)
        assertEquals(false, idle.copy(selectedIds = setOf(4L)).showNewTicketFab)
        assertEquals(false, idle.copy(searchOpen = true).showNewTicketFab)
        assertEquals(false, idle.copy(tab = TaskTab.Sprint).showNewTicketFab)
    }

    @Test
    fun quickFilterResetPreservesAdvancedStateAndRealtimeReconcilesHiddenRows() {
        val vm = TaskViewModel(TaskRepository(api))
        vm.bind(userId = 1, role = "employee", teamName = null, agileEnabled = false)
        waitFor { vm.ui.value.planLoaded }
        vm.setFilters(TaskFilters(search = "Ship"))
        vm.setQuickFilters(BacklogQuickFilters(high = true, unscheduled = true))
        assertEquals(0, vm.ui.value.backlogTotal)
        assertEquals(4, vm.ui.value.planTasks.size)
        vm.onTaskEvent("task_updated", Json.parseToJsonElement(
            """{"taskId":4,"fields":{"priority":"high"}}""",
        ))
        assertEquals(1, vm.ui.value.backlogTotal)
        vm.setQuickFilters(BacklogQuickFilters())
        assertEquals("Ship", vm.ui.value.filters.search)
        assertEquals(1, vm.ui.value.backlogTotal)
        vm.toggleSelection(4)
        vm.onTaskEvent("task_updated", Json.parseToJsonElement(
            """{"taskId":4,"fields":{"workflow_state_id":5}}""",
        ))
        waitFor { vm.ui.value.completedCount == 1 }
        assertEquals(0, vm.ui.value.backlogTotal)
        assertEquals(1, vm.ui.value.completedCount)
        assertEquals(true, vm.ui.value.completedExpanded)
        assertEquals(true, vm.ui.value.selectedIds.isEmpty())
    }
}
