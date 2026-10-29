package app.aino.mobile.feature.home

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.network.NETWORK_ERROR_MESSAGE
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
class DashboardViewModelTest {
    @Volatile private var offline = false

    private val api = ApiClient { request ->
        if (offline) throw ApiError.Network(request.method, "https://x/api/${request.path}", java.io.IOException("offline"))
        val body = when {
            request.path == "tracker/status" -> """{"state":"on_floor"}"""
            request.path.startsWith("calendar?") -> "[]"
            else -> "{}"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun DashboardViewModel.refreshAndWait() {
        refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (ui.value.loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun offlineRefreshKeepsTheSnapshotWithoutAnError() {
        val vm = DashboardViewModel(DashboardRepository(api)).also { it.setUser(7) }
        vm.refreshAndWait()
        assertEquals("on_floor", vm.ui.value.snapshot?.status?.state)

        offline = true
        vm.refreshAndWait()
        assertEquals("on_floor", vm.ui.value.snapshot?.status?.state)
        assertNull(vm.ui.value.error)
    }

    @Test
    fun offlineWithNothingToShowGivesOneCalmMessage() {
        offline = true
        val vm = DashboardViewModel(DashboardRepository(api)).also { it.setUser(7) }
        vm.refreshAndWait()
        assertNull(vm.ui.value.snapshot)
        assertEquals(NETWORK_ERROR_MESSAGE, vm.ui.value.error)
    }

    @Test
    fun refreshBeforeSignInIsANoOp() {
        // The ViewModel outlives sign-in; a token-less load would only produce
        // an error on the home screen after login.
        val vm = DashboardViewModel(DashboardRepository(api))
        vm.refreshAndWait()
        assertNull(vm.ui.value.snapshot)
        assertNull(vm.ui.value.error)
        assertEquals(false, vm.ui.value.loading)
    }

    @Test
    fun signingOutDropsTheUserSoTheNextRefreshWaits() {
        val vm = DashboardViewModel(DashboardRepository(api)).also { it.setUser(7) }
        vm.refreshAndWait()
        assertEquals("on_floor", vm.ui.value.snapshot?.status?.state)

        vm.reset()
        vm.refreshAndWait()
        assertNull(vm.ui.value.snapshot)
    }
}
