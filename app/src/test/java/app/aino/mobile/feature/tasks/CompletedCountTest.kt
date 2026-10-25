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
                """{"tasks":[{"id":4,"title":"Ship"}],"completed_count":3}"""
            request.path == "tasks/carry-forward" -> """{"carried":0}"""
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
        assertEquals(false, vm.ui.value.completedOpen)
    }

    @Test
    fun selectingTicketsHidesTheNewTicketFab() {
        val idle = TaskUiState(tab = TaskTab.Backlog)
        assertEquals(true, idle.showNewTicketFab)
        assertEquals(false, idle.copy(selectedIds = setOf(4L)).showNewTicketFab)
        assertEquals(false, idle.copy(searchOpen = true).showNewTicketFab)
        assertEquals(false, idle.copy(tab = TaskTab.Sprint).showNewTicketFab)
    }
}
