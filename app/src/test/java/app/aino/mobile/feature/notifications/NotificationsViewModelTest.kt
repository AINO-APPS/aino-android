package app.aino.mobile.feature.notifications

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
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

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val captured = mutableListOf<ApiRequest>()
    private var failDelete = false
    private val listBody = """{"notifications":[
        {"id":2,"type":"task","title":"A","link_task_id":9,"is_read":false},
        {"id":1,"type":"leave","title":"B","is_read":true}],"unread":1}"""

    private val cancelled = mutableListOf<Long>()
    private var now = 1_000L

    private fun viewModel() = NotificationsViewModel(
        NotificationsRepository(
            ApiClient { request ->
                captured += request
                if (failDelete && request.method == "DELETE") throw ApiError.Http(500, "{}", request.method, request.path)
                val body = if (request.method == "GET") listBody else """{"ok":true}"""
                ApiResponse(200, emptyMap(), body.toByteArray())
            },
        ),
        io = dispatcher,
        cancelTray = { cancelled += it },
        clock = { now },
    )

    @Test
    fun readDeleteAndReadAllRemoveTheTrayNotification() {
        val vm = viewModel()
        vm.refresh()
        vm.open(vm.ui.value.notifications.first())
        vm.markReadById(5)
        vm.delete(1)
        assertEquals(listOf(2L, 5L, 1L), cancelled)

        cancelled.clear()
        val fresh = viewModel()
        fresh.refresh()
        fresh.markAllRead()
        assertEquals(listOf(2L), cancelled)
    }

    @Test
    fun syncTimeIsTakenBeforeTheRequestAndFeedsTheTrayWindow() {
        val vm = viewModel()
        assertEquals(null, vm.ui.value.syncedAtMs)
        now = 5_000
        vm.refresh()
        assertEquals(5_000L, vm.ui.value.syncedAtMs)
        assertEquals(setOf(2L) to 0L, vm.ui.value.unreadTruthWindow())
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun refreshPublishesListAndUnreadCount() {
        val vm = viewModel()
        vm.refresh()
        assertEquals(2, vm.ui.value.notifications.size)
        assertTrue(vm.ui.value.loaded)
        assertFalse(vm.ui.value.loading)
        assertEquals(1, vm.unreadCount.value)
    }

    @Test
    fun onlyBellEventsTriggerARefetch() {
        val vm = viewModel()
        assertFalse(vm.onRealtimeEvent("chat_message", null))
        assertTrue(captured.isEmpty())
        for (type in NOTIFICATION_REFRESH_EVENTS) assertTrue(vm.onRealtimeEvent(type, null))
        assertEquals(NOTIFICATION_REFRESH_EVENTS.size, captured.count { it.method == "GET" })
    }

    @Test
    fun openMarksReadAndReturnsLink() {
        val vm = viewModel()
        vm.refresh()
        val link = vm.open(vm.ui.value.notifications.first())
        assertEquals("/tasks?task=9", link)
        assertEquals("notifications/2/read", captured.last().path)
        assertEquals(0, vm.unreadCount.value)
        assertTrue(vm.ui.value.notifications.all { it.isRead })
    }

    @Test
    fun deleteIsOptimisticAndRestoresOnFailure() {
        val vm = viewModel()
        vm.refresh()
        vm.delete(2)
        assertEquals(listOf(1L), vm.ui.value.notifications.map { it.id })
        assertEquals(0, vm.unreadCount.value)

        failDelete = true
        vm.refresh()
        vm.delete(2)
        // The failed delete refetches the server list.
        assertEquals(listOf(2L, 1L), vm.ui.value.notifications.map { it.id })
        assertEquals(1, vm.unreadCount.value)
    }

    @Test
    fun markAllReadAndReset() {
        val vm = viewModel()
        vm.refresh()
        vm.markAllRead()
        assertEquals("notifications/read-all", captured.last().path)
        assertEquals(0, vm.unreadCount.value)
        vm.reset()
        assertEquals(NotificationsUiState(), vm.ui.value)
        assertEquals(0, vm.unreadCount.value)
    }

    @Test
    fun pushTapMarksTheRowReadById() {
        val vm = viewModel()
        vm.refresh()
        vm.markReadById(2)
        assertEquals("notifications/2/read", captured.last().path)
        assertEquals(0, vm.unreadCount.value)

        // A row not loaded yet: mark it on the server, then reconcile by refetching.
        val cold = viewModel()
        captured.clear()
        cold.markReadById(77)
        assertEquals(listOf("POST notifications/77/read", "GET notifications"), captured.map { "${it.method} ${it.path}" })
    }
}
