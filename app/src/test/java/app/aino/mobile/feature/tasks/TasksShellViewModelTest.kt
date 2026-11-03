package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksShellViewModelTest {
    private lateinit var session: TicketSession
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }
    @After fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    @Test
    fun searchShowsTheLatestWriteAndDropsCancelledTicketsWithoutSearchingAgain() {
        val original = """{"id":7,"title":"Ship","container":"backlog","workflow_state_key":"todo","capabilities":{"can":{"edit":{"allowed":true}}}}"""
        var saved = original
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path.startsWith("tasks/search") -> "[$original]"
                request.path == "tasks/7/detail" -> saved
                request.path == "tasks/7" && request.method == "PUT" -> saved
                request.path == "tasks/7/history" -> "[]"
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        shell.setSearch("Ship")
        await { shell.searchResults.value.size == 1 }
        val detail = TicketDetailViewModel(7, repository, session)
        await { detail.ui.value.detail?.title == "Ship" }
        saved = original.replace("Ship", "Ship today")
        detail.updateFields(detail.ui.value.detail!!, buildJsonObject { put("title", "Ship today") })
        await { shell.searchResults.value.singleOrNull()?.title == "Ship today" }
        assertEquals("Ship today", shell.searchResults.value.single().title)
        saved = saved.replace("\"todo\"", "\"cancelled\"")
        detail.updateFields(detail.ui.value.detail!!, buildJsonObject { put("workflow_state_id", 9) })
        await { shell.searchResults.value.isEmpty() }
        assertTrue(shell.searchResults.value.isEmpty())
    }

    @Test
    fun endingTheSessionClearsSearchRowsOnAnExistingShell() {
        val repository = TaskRepository(ApiClient {
            ApiResponse(200, emptyMap(), """[{"id":7,"title":"Previous user","container":"backlog"}]""".toByteArray())
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        shell.setSearch("Previous")
        await { shell.searchResults.value.size == 1 }

        session.close()

        assertTrue(shell.searchResults.value.isEmpty())
        assertEquals("", shell.ui.value.searchQuery)
    }

    @Test
    fun refreshingServiceDeskKeepsItsTabAndSignalsItsIndependentSurface() {
        val repository = TaskRepository(ApiClient { request ->
            val body = if (request.path == "tasks/preferences") """{"card_density":"compact"}""" else "{}"
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        shell.bind(1, "employee", null, agileEnabled = false)
        await { shell.ui.value.teamResolved }
        shell.selectTab(TaskTab.ServiceDesk)
        val before = shell.ui.value.serviceDeskVersion

        shell.refresh(pull = true)

        await { shell.ui.value.serviceDeskVersion > before && !shell.ui.value.refreshing }
        assertEquals(TaskTab.ServiceDesk, shell.ui.value.tab)
        assertTrue(shell.ui.value.visibleTabs.contains(TaskTab.ServiceDesk))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for UI state", condition())
    }
}
