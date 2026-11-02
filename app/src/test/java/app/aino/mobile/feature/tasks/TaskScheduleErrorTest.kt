package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
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
    private val message =
        "This ticket is in a sprint. Take it out of the sprint before planning it onto a day."

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun schedulingASprintTicketShowsTheServerMessage() {
        val repository = TaskRepository(
            ApiClient { request ->
                throw ApiError.Http(
                    409,
                    """{"error":"$message","code":"IN_SPRINT"}""",
                    request.method,
                    request.path,
                )
            },
        )
        val viewModel = TaskViewModel(repository)

        viewModel.schedule(7, "2026-11-02")

        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.ui.value.error == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(message, viewModel.ui.value.error)
    }
}
