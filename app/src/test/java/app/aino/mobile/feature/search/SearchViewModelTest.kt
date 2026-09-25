package app.aino.mobile.feature.search

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val captured = mutableListOf<ApiRequest>()
    private var fail = false

    private fun viewModel(role: String? = "employee") = SearchViewModel(
        SearchRepository(
            ApiClient { request ->
                captured += request
                if (fail) throw ApiError.Http(500, """{"error":"Search failed"}""", request.method, request.path)
                ApiResponse(200, emptyMap(), """{"tasks":[{"id":1,"title":"Leave form","status":"todo"}]}""".toByteArray())
            },
        ),
        role,
        io = dispatcher,
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun keystrokesAreDebouncedIntoOneRequest() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("le")
        vm.onQueryChange("lea")
        vm.onQueryChange("leave")
        // Nav results are immediate and client-side.
        assertTrue(vm.ui.value.navResults.isNotEmpty())
        assertTrue(vm.ui.value.pending)
        assertFalse(vm.ui.value.showNoResults)

        advanceTimeBy(SEARCH_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue(captured.isEmpty())
        advanceTimeBy(2)
        runCurrent()

        assertEquals(listOf("search?q=leave"), captured.map { it.path })
        assertEquals("Leave form", vm.ui.value.results?.tasks?.single()?.title)
        assertFalse(vm.ui.value.loading)
        assertTrue(vm.ui.value.hasResults)
    }

    @Test
    fun shortQueriesClearResultsWithoutARequest() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onQueryChange("leave")
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        vm.onQueryChange("l")
        advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(1, captured.size)
        assertNull(vm.ui.value.results)
        assertTrue(vm.ui.value.navResults.isEmpty())
        assertFalse(vm.ui.value.showNoResults)
    }

    @Test
    fun failuresShowTheWebErrorAndNoResultsHint() = runTest(dispatcher) {
        fail = true
        val vm = viewModel()
        vm.onQueryChange("zzqx")
        vm.submit()
        runCurrent()
        assertEquals(1, captured.size)
        assertEquals("Search failed. Please try again.", vm.ui.value.error)
        assertNull(vm.ui.value.results)
        assertTrue(vm.ui.value.showNoResults)
    }

    @Test
    fun roleChangesRefilterPages() = runTest(dispatcher) {
        val vm = viewModel("employee")
        vm.onQueryChange("admin")
        assertTrue(vm.ui.value.navResults.none { it.title == "Admin Panel" })
        vm.setRole("hr_admin")
        assertTrue(vm.ui.value.navResults.any { it.title == "Admin Panel" })
    }
}
