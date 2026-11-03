package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlanViewModelTest {
    private lateinit var session: TicketSession

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        session = TicketSession()
    }
    @After fun tearDown() {
        session.close()
        Dispatchers.resetMain()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Expected UI state did not arrive", condition())
    }

    @Test fun createdBacklogTicketAppearsWithoutARefresh() {
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.method == "POST" && request.path == "tasks/backlog" ->
                    """{"id":201,"title":"Capture work","container":"backlog","priority":"high"}"""
                request.path.startsWith("tasks/backlog") -> """{"tasks":[]}"""
                request.path == "tasks/carry-forward" -> """{"carried":0}"""
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TaskViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        shell.bind(1, "employee", null, agileEnabled = false)
        await { plan.ui.value.planLoaded }
        plan.updateDraft { it.copy(title = "Capture work") }
        plan.submitBacklog()
        await { plan.ui.value.backlog.any { it.id == 201L } && !plan.ui.value.creating }
        assertEquals("Capture work", plan.ui.value.backlog.single().title)
        assertEquals(1, plan.ui.value.backlogTotal)
    }

    @Test fun bulkPlacementUpdatesPlanBoardSearchAndDetailFromOneRequest() {
        var placed = false
        var bulkRequests = 0
        val rows: () -> String = {
            (201..202).joinToString(",") { id ->
                """{"id":$id,"title":"Ticket $id","container":"${if (placed) "sprint" else "backlog"}","sprint_id":${if (placed) "10" else "null"},"capabilities":{"can":{"placeInSprint":{"allowed":true}}}}"""
            }
        }
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/bulk" -> {
                    bulkRequests++
                    placed = true
                    """{"changed":2,"results":[{"id":201,"ok":true},{"id":202,"ok":true}]}"""
                }
                request.path.startsWith("tasks/backlog") -> """{"tasks":[${if (placed) "" else rows()}]}"""
                request.path.startsWith("tasks/search") -> """[${rows()}]"""
                request.path.endsWith("/detail") -> {
                    val id = request.path.split('/')[1]
                    """{"id":$id,"title":"Ticket $id","container":"${if (placed) "sprint" else "backlog"}","sprint_id":${if (placed) "10" else "null"},"capabilities":{"can":{"placeInSprint":{"allowed":true}}}}"""
                }
                request.path == "tasks/available-sprints" -> """[{"id":10,"name":"Sprint 10","status":"active"}]"""
                request.path == "sprints/10/board" -> """{"sprint":{"id":10,"name":"Sprint 10"},"tickets":[]}"""
                request.path == "tasks/carry-forward" -> """{"carried":0}"""
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TaskViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        val board = SprintBoardViewModel(repository, session)
        val detail = TicketDetailViewModel(201, repository, session)
        shell.bind(1, "employee", null, agileEnabled = true)
        shell.selectSprint(10)
        await { plan.ui.value.backlog.size == 2 }
        detail.refreshDetail()
        shell.setSearch("Ticket")
        await { shell.ui.value.searchResults.size == 2 && detail.ui.value.detail != null }
        val boardSizes = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val planSizes = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val boardChanges = CoroutineScope(Dispatchers.Main).launch {
            board.ui.map { it.tasks.size }.distinctUntilChanged().collect { boardSizes += it }
        }
        val planChanges = CoroutineScope(Dispatchers.Main).launch {
            plan.ui.map { it.backlog.size }.distinctUntilChanged().collect { planSizes += it }
        }
        try {
            plan.toggleSelection(201)
            plan.toggleSelection(202)
            plan.bulkAction("move_to_sprint", "10")
            await {
                plan.ui.value.backlog.isEmpty() && board.ui.value.tasks.size == 2 &&
                    detail.ui.value.detail?.container == "sprint" &&
                    shell.ui.value.searchResults.all { it.container == "sprint" }
            }
            assertEquals(1, bulkRequests)
            assertEquals(listOf(201L, 202L), board.ui.value.tasks.map { it.id }.sorted())
            assertEquals(10L, detail.ui.value.detail?.sprintId)
            assertTrue(plan.ui.value.selectedIds.isEmpty())
            assertEquals(listOf(0, 2), boardSizes.toList())
            assertEquals(listOf(2, 0), planSizes.toList())
        } finally {
            boardChanges.cancel()
            planChanges.cancel()
        }
    }

    @Test fun leavingTheBacklogClosesSprintPlanning() {
        val harness = TicketSessionHarness()
        try {
            harness.loadBoardPlanAndDetail()
            harness.viewModel.selectTab(TaskTab.Backlog)
            harness.viewModelPlan.openSprintPlan()
            assertTrue(harness.viewModelPlan.ui.value.planOpen)
            harness.viewModel.selectTab(TaskTab.ServiceDesk)
            assertEquals(false, harness.viewModelPlan.ui.value.planOpen)
        } finally {
            harness.close()
        }
    }
}
