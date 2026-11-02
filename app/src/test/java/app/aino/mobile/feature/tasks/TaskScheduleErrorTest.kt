package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
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

@OptIn(ExperimentalCoroutinesApi::class)
class TaskScheduleErrorTest {
    private val message = "Remove this ticket from its sprint before scheduling it."

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun rejectedWritesUseTheSameCapabilityReasonCopyAsDisabledControls() {
        val repository = TaskRepository(
            ApiClient { request ->
                when {
                    request.path.startsWith("tasks/backlog") -> ApiResponse(
                        200,
                        emptyMap(),
                        """{"tasks":[{"id":7,"title":"Ship","container":"backlog","capabilities":{"can":{"schedule":{"allowed":true}}}}]}""".toByteArray(),
                    )
                    request.path == "tasks/7/schedule" -> throw ApiError.Http(
                        409,
                        """{"error":"server wording varies","code":"IN_SPRINT"}""",
                        request.method,
                        request.path,
                    )
                    else -> ApiResponse(200, emptyMap(), """{"carried":0}""".toByteArray())
                }
            },
        )
        val viewModel = TaskViewModel(repository)
        viewModel.bind(userId = 1, role = "employee", teamName = null, agileEnabled = false)

        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.ui.value.backlog.none { it.id == 7L } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        viewModel.clearError()
        viewModel.schedule(7, "2026-11-02")

        val errorDeadline = System.currentTimeMillis() + 5_000
        while (viewModel.ui.value.error == null && System.currentTimeMillis() < errorDeadline) {
            Thread.sleep(10)
        }
        assertEquals(message, viewModel.ui.value.error)
    }
}
