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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TaskOfflineTest {
    @Volatile private var offline = false
    private val backlogLoads = java.util.concurrent.atomic.AtomicInteger()

    private val api = ApiClient { request ->
        if (request.path.startsWith("tasks/backlog")) backlogLoads.incrementAndGet()
        if (offline) throw ApiError.Network(request.method, "https://x/api/${request.path}", java.io.IOException("offline"))
        val body = when {
            request.path.startsWith("tasks/backlog") -> """{"tasks":[{"id":4,"title":"Ship"}]}"""
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
    fun offlineReloadKeepsTheBacklogWithoutAnError() {
        val vm = TaskViewModel(TaskRepository(api))
        vm.bind(userId = 1, role = "employee", teamName = null, agileEnabled = false)
        waitFor { vm.ui.value.backlog.isNotEmpty() }
        assertEquals(listOf(4L), vm.ui.value.backlog.map { it.id })

        offline = true
        val before = backlogLoads.get()
        vm.pollVisible()
        waitFor { backlogLoads.get() > before && !vm.ui.value.backlogLoading }
        Thread.sleep(50)
        assertEquals(listOf(4L), vm.ui.value.backlog.map { it.id })
        assertNull(vm.ui.value.error)
    }
}
