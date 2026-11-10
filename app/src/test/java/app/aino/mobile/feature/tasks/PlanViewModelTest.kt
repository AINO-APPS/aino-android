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

    @Test fun cancellingFromBacklogConfirmsBeforeWritingAndClosesDetailAndSearch() {
        val deletes = java.util.concurrent.atomic.AtomicInteger()
        val ticket = """{"id":7,"title":"Ship","container":"backlog","capabilities":{"can":{"cancel":{"allowed":true}}}}"""
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/7" && request.method == "DELETE" -> {
                    deletes.incrementAndGet()
                    "{}"
                }
                request.path.startsWith("tasks/backlog") -> """{"tasks":[$ticket]}"""
                request.path.startsWith("tasks/search") -> "[$ticket]"
                request.path == "tasks/7/detail" -> ticket
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val shell = TasksShellViewModel(repository, ticketSession = session)
        val plan = PlanViewModel(repository, session)
        val detail = TicketDetailViewModel(7, repository, session)
        plan.loadBacklog()
        shell.setSearch("Ship")
        await { plan.ui.value.backlog.size == 1 && shell.searchResults.value.size == 1 && !detail.ui.value.detailLoading }

        plan.requestDelete(plan.ui.value.backlog.single())
        assertEquals(0, deletes.get())
        assertEquals("Cancel ticket", plan.ui.value.confirm?.title)
        plan.acceptConfirm()

        await { plan.ui.value.backlog.isEmpty() && shell.searchResults.value.isEmpty() && detail.ui.value.closed }
        assertEquals(1, deletes.get())
        assertEquals(null, plan.ui.value.confirm)
    }

    @Test fun createdBacklogTicketAppearsWithoutARefresh() {
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.method == "POST" && request.path == "tasks/backlog" ->
                    """{"id":201,"project_id":3,"title":"Capture work","container":"backlog","priority":"high"}"""
                request.path.startsWith("projects") -> """[{"id":3,"name":"Project","capabilities":{"canCreateTickets":true}}]"""
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
        shell.openSheet(TaskSheet.CreateTicket)
        await { plan.ui.value.draft.projectId == 3L }
        plan.updateDraft { it.copy(title = "Capture work") }
        plan.submitBacklog()
        await { plan.ui.value.backlog.any { it.id == 201L } && !plan.ui.value.creating }
        assertEquals("Capture work", plan.ui.value.backlog.single().title)
        assertEquals(1, plan.ui.value.backlogTotal)
    }

    @Test fun changingSprintDoesNotChangeTheTicketDueDate() {
        val plan = PlanViewModel(TaskRepository(ApiClient { ApiResponse(200, emptyMap(), "[]".toByteArray()) }), session)
        plan.updateDraft { it.copy(dueDate = "2026-10-10") }

        plan.setDraftSprint(10)
        assertEquals("2026-10-10", plan.ui.value.draft.dueDate)

        plan.setDraftSprint(null)
        assertEquals("2026-10-10", plan.ui.value.draft.dueDate)

        val ticket = Task(11, "Keep its due date", dueDate = "2026-10-10")
        plan.configureImport(ticket)
        assertEquals("2026-10-10", plan.ui.value.importDueDate)
    }

    @Test fun bulkPlacementUpdatesPlanBoardSearchAndDetailFromOneRequest() {
        var placed = false
        var bulkRequests = 0
        val rows: () -> String = {
            (201..202).joinToString(",") { id ->
                """{"id":$id,"project_id":3,"title":"Ticket $id","container":"${if (placed) "sprint" else "backlog"}","sprint_id":${if (placed) "10" else "null"},"capabilities":{"can":{"placeInSprint":{"allowed":true}}}}"""
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
                    """{"id":$id,"project_id":3,"title":"Ticket $id","container":"${if (placed) "sprint" else "backlog"}","sprint_id":${if (placed) "10" else "null"},"capabilities":{"can":{"placeInSprint":{"allowed":true}}}}"""
                }
                request.path.startsWith("projects") -> """[{"id":3,"name":"Project","capabilities":{"canCreateTickets":true}}]"""
                request.path == "sprints?project_id=3" -> """{"sprints":[{"id":10,"project_id":3,"name":"Sprint 10","status":"active"}]}"""
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
        await { shell.ui.value.planScope.selectedProjectId == 3L }
        shell.loadBacklog()
        shell.selectSprint(10)
        await { plan.ui.value.backlog.size == 2 }
        detail.refreshDetail()
        shell.setSearch("Ticket")
        await { shell.searchResults.value.size == 2 && detail.ui.value.detail != null }
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
                    shell.searchResults.value.all { it.container == "sprint" }
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
            harness.viewModel.selectTab(TaskTab.Reports)
            assertEquals(false, harness.viewModelPlan.ui.value.planOpen)
        } finally {
            harness.close()
        }
    }

    @Test fun bulkWriteReconcilesSuccessfulReadsAndReloadsEvenWhenAnotherReadFails() {
        val placed = java.util.concurrent.atomic.AtomicBoolean()
        val planReads = java.util.concurrent.atomic.AtomicInteger()
        val bulkRequests = java.util.concurrent.atomic.AtomicInteger()
        fun ticket(id: Int) = """{"id":$id,"title":"Ticket $id","container":"${if (placed.get()) "sprint" else "backlog"}","sprint_id":${if (placed.get()) "10" else "null"},"capabilities":{"can":{"placeInSprint":{"allowed":true}}}}"""
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/bulk" -> {
                    bulkRequests.incrementAndGet()
                    placed.set(true)
                    """{"changed":2,"results":[{"id":201,"ok":true},{"id":202,"ok":true}]}"""
                }
                request.path.startsWith("tasks/backlog") -> {
                    planReads.incrementAndGet()
                    """{"tasks":[${if (placed.get()) "" else "${ticket(201)},${ticket(202)}"}]}"""
                }
                request.path == "tasks/201/detail" -> ticket(201)
                request.path == "tasks/202/detail" -> throw java.io.IOException("offline")
                else -> "[]"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val plan = PlanViewModel(repository, session)
        val detail = TicketDetailViewModel(201, repository, session)
        plan.loadBacklog()
        await { plan.ui.value.backlog.size == 2 && detail.ui.value.detail != null }
        val readsBefore = planReads.get()
        plan.toggleSelection(201)
        plan.toggleSelection(202)
        plan.bulkAction("move_to_sprint", "10")

        await {
            detail.ui.value.detail?.container == "sprint" &&
                plan.ui.value.error?.contains("2 updated; could not refresh") == true && planReads.get() > readsBefore
        }
        assertEquals(1, bulkRequests.get())
        assertTrue(plan.ui.value.selectedIds.isEmpty())
        assertEquals(10L, detail.ui.value.detail?.sprintId)
    }
}
