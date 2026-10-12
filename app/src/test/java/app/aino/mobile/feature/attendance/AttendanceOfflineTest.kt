package app.aino.mobile.feature.attendance

import androidx.test.core.app.ApplicationProvider
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Signal-style offline: a background refresh that cannot reach the server keeps the screen as it was. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AttendanceOfflineTest {
    @Volatile private var offline = false

    private val api = ApiClient { request ->
        if (offline) throw ApiError.Network(request.method, "https://x/api/${request.path}", java.io.IOException("offline"))
        val body = when {
            request.path == "tracker/status" -> """{"state":"on_floor"}"""
            request.path == "tracker/manual-entries" -> """[{"request_id":7}]"""
            request.path.startsWith("tracker/history") || request.path.startsWith("leaves") ||
                request.path.startsWith("leave-policy/holidays") || request.path == "tracker/overtime-requests" -> "[]"
            else -> "{}"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun AttendanceViewModel.refreshAndWait() {
        refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (ui.value.loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun offlineRefreshKeepsStatusAndRequestsWithoutAnError() {
        val vm = AttendanceViewModel(AttendanceRepository(api), LocationProvider(ApplicationProvider.getApplicationContext()))
        vm.refreshAndWait()
        assertEquals("on_floor", vm.ui.value.status?.state)
        assertEquals(listOf(7L), vm.ui.value.manualRequests.map { it.requestId })

        offline = true
        vm.refreshAndWait()
        assertEquals("on_floor", vm.ui.value.status?.state)
        assertEquals(listOf(7L), vm.ui.value.manualRequests.map { it.requestId })
        assertNull(vm.ui.value.error)
    }

    @Test
    fun offlineWithNothingLoadedNeverShowsTransportText() {
        offline = true
        val vm = AttendanceViewModel(AttendanceRepository(api), LocationProvider(ApplicationProvider.getApplicationContext()))
        vm.refreshAndWait()
        val error = vm.ui.value.error.orEmpty()
        assertTrue(error.isNotEmpty())
        assertTrue(!error.contains("https://"))
    }
}
