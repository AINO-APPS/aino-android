package app.aino.mobile.feature.profile

import androidx.test.core.app.ApplicationProvider
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProfileViewModelOfflineTest {
    private val online = ApiClient { ApiResponse(200, emptyMap(), """{"id":1,"username":"asha","full_name":"Asha"}""".toByteArray()) }
    private val offline = ApiClient { request ->
        throw ApiError.Network(request.method, "https://x/api/${request.path}", java.io.IOException("offline"))
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun ProfileViewModel.refreshAndWait() {
        refresh()
        val deadline = System.currentTimeMillis() + 5_000
        while (ui.value.loading && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun offlineOpenPaintsTheCachedProfileWithoutAnError() {
        val vm = ProfileViewModel(ProfileRepository(offline), ApplicationProvider.getApplicationContext(), warm = ProfileRepository(online))
        vm.refreshAndWait()
        assertEquals("Asha", vm.ui.value.user?.fullName)
        assertEquals("Asha", vm.ui.value.draftName)
        assertNull(vm.ui.value.loadError)
    }

    @Test
    fun offlineWithNothingCachedShowsACalmMessage() {
        val vm = ProfileViewModel(ProfileRepository(offline), ApplicationProvider.getApplicationContext())
        vm.refreshAndWait()
        assertNull(vm.ui.value.user)
        assertEquals(NETWORK_ERROR_MESSAGE, vm.ui.value.loadError)
    }
}
