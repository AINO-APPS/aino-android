package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.InMemoryAinoDao
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

/**
 * Signal-style list refresh: a transient failure keeps the saved list without a
 * banner and retries on its own; only a final answer earns a notice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRefreshNoticeTest {
    private val requests = CopyOnWriteArrayList<ApiRequest>()
    private val dao = InMemoryAinoDao()
    private val main = UnconfinedTestDispatcher()

    /** Scripted outcomes for `GET chat/conversations`; empty means success. */
    private val listFailures = ConcurrentLinkedQueue<(ApiRequest) -> Throwable>()
    @Volatile private var listGate: CountDownLatch? = null
    private val conversationsJson =
        """[{"id":5,"updated_at":"2026-10-06T09:00:00Z","is_group":false,"other_user_id":8,"other_full_name":"Asha Kumar","unread_count":0}]"""

    @Before fun setUp() = Dispatchers.setMain(main)
    @After fun tearDown() = Dispatchers.resetMain()

    private val network: (ApiRequest) -> Throwable = { ApiError.Network(it.method, it.path, java.io.IOException("connection reset")) }
    private val server: (ApiRequest) -> Throwable = { ApiError.Http(502, """{"error":"Bad gateway"}""", it.method, it.path) }
    private val forbidden: (ApiRequest) -> Throwable = { ApiError.Http(403, """{"error":"Not allowed"}""", it.method, it.path) }

    private val api = ApiClient { request ->
        requests += request
        if (request.method == "GET" && request.path == "chat/conversations") {
            listGate?.await(5, TimeUnit.SECONDS)
            listFailures.poll()?.let { throw it(request) }
        }
        val body = when {
            request.path == "chat/conversations" -> conversationsJson
            request.path.startsWith("chat/presence") || request.path.startsWith("presence") -> """{"8":{"presence":"online"}}"""
            request.method != "GET" -> """{"ok":true}"""
            else -> "[]"
        }
        ApiResponse(200, mapOf("Content-Type" to listOf("application/json")), body.toByteArray())
    }

    private fun viewModel(retryDelaysMs: List<Long> = emptyList()) = ChatViewModel(
        ChatRepository(api),
        { scope -> ChatCache(scope, ScopedCache(scope, dao)) },
        listRetryDelayMs = 0,
        transientRetryDelaysMs = retryDelaysMs,
    ).also { it.setScope(1, 7) }

    private fun ChatViewModel.settled() = also { await { !ui.value.loading && ui.value.conversations.isNotEmpty() } }

    private fun listLoads() = requests.count { it.method == "GET" && it.path == "chat/conversations" }

    @Test fun `a single dropped connection is retried without a notice`() {
        listFailures += network
        val vm = viewModel().settled()
        assertEquals(2, listLoads())
        assertNull(vm.ui.value.message)
        assertEquals(false, vm.ui.value.fromCache)
    }

    @Test fun `an unreachable server keeps the saved list silently`() {
        val vm = viewModel().settled()
        val before = listLoads()
        listFailures += network
        listFailures += network
        vm.refresh()
        await { !vm.ui.value.loading && listLoads() == before + 2 }
        assertNull(vm.ui.value.message)
        assertNull(vm.ui.value.error)
        assertEquals(true, vm.ui.value.fromCache)
        assertEquals(1, vm.ui.value.conversations.size)

        vm.refresh()
        await { !vm.ui.value.loading && !vm.ui.value.fromCache }
        assertNull(vm.ui.value.message)
    }

    @Test fun `a transient failure retries on its own`() {
        val vm = viewModel(retryDelaysMs = listOf(200)).settled()
        val before = listLoads()
        listFailures += server
        vm.refresh()
        await { listLoads() == before + 2 && !vm.ui.value.loading && !vm.ui.value.fromCache }
        assertNull(vm.ui.value.message)
    }

    @Test fun `a final error shows the stale notice until a refresh succeeds`() {
        val vm = viewModel().settled()
        val before = listLoads()
        listFailures += forbidden
        vm.refresh()
        await { !vm.ui.value.loading && vm.ui.value.message != null }
        assertEquals(ChatViewModel.STALE_LIST_NOTICE, vm.ui.value.message)
        assertEquals(before + 1, listLoads())

        vm.refresh()
        await { !vm.ui.value.loading && vm.ui.value.message == null }
        assertEquals(false, vm.ui.value.fromCache)
    }

    @Test fun `a refresh requested mid-flight runs once more afterwards`() {
        val vm = viewModel().settled()
        val before = listLoads()
        val gate = CountDownLatch(1)
        listGate = gate
        vm.refresh()
        await { vm.ui.value.loading && listLoads() == before + 1 }
        vm.refresh()
        vm.refresh()
        listGate = null
        gate.countDown()
        await { !vm.ui.value.loading && listLoads() == before + 2 }
        Thread.sleep(100)
        assertEquals(before + 2, listLoads())
    }

    @Test fun `a failed refresh keeps the presence already known`() {
        val vm = viewModel().settled()
        val presence = vm.ui.value.presence
        assertEquals("online", presence[8L]?.presence)
        listFailures += forbidden
        vm.refresh()
        await { !vm.ui.value.loading && vm.ui.value.message != null }
        assertEquals(presence, vm.ui.value.presence)
    }

    private fun await(timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for condition")
            Thread.sleep(10)
        }
    }
}
