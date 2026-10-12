package app.aino.mobile.feature.organization

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
class OrganizationViewModelTest {
    @Volatile private var offline = false
    private val requests = java.util.concurrent.atomic.AtomicInteger()

    private val api = ApiClient { request ->
        requests.incrementAndGet()
        if (offline) throw ApiError.Network(request.method, "https://x/api/${request.path}", java.io.IOException("offline"))
        val body = if (request.path == "org/current") """{"id":1,"name":"Acme"}""" else "[]"
        ApiResponse(200, emptyMap(), body.toByteArray())
    }

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
    }

    @Test
    fun offlineRefreshKeepsTheOrgAndTabWithoutErrors() {
        val vm = OrganizationViewModel(OrganizationRepository(api))
        vm.bind("employee", 1)
        waitFor { vm.ui.value.org != null && !vm.ui.value.orgLoading && !vm.ui.value.departments.loading }
        assertEquals("Acme", vm.ui.value.org?.name)

        offline = true
        val before = requests.get()
        vm.refresh()
        waitFor { requests.get() > before + 1 && !vm.ui.value.orgLoading && !vm.ui.value.departments.loading }
        assertEquals("Acme", vm.ui.value.org?.name)
        assertNull(vm.ui.value.orgError)
        assertEquals(emptyList<Department>(), vm.ui.value.departments.data)
        assertNull(vm.ui.value.departments.error)
    }
}
