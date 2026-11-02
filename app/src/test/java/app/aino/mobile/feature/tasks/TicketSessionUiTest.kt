package app.aino.mobile.feature.tasks

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketSessionUiTest {
    private val harnesses = mutableListOf<TicketSessionHarness>()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() {
        harnesses.forEach { it.close() }
        Dispatchers.resetMain()
    }

    private fun harness() = TicketSessionHarness().also(harnesses::add)

    @Test
    fun onePlacementWriteMovesAnOpenBoardTicketToBacklogEverywhere() {
        val harness = harness()
        val vm = harness.viewModel
        harness.loadBoardPlanAndDetail()
        assertTrue(vm.ui.value.backlog.isEmpty())

        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        val detail = harness.detailViewModel
        detail.updateFields(detail.ui.value.detail!!, buildJsonObject { put("sprint_id", null as String?) })
        harness.await { detail.ui.value.detail?.container == "backlog" && vm.ui.value.backlog.size == 1 && vm.ui.value.tasks.isEmpty() }

        assertTrue("Board must drop a ticket moved to backlog", vm.ui.value.tasks.isEmpty())
        assertTrue("Board response must resolve current membership", vm.ui.value.sprintBoard!!.tickets.isEmpty())
        assertEquals(listOf(201L), vm.ui.value.backlog.map { it.id })
        assertEquals("backlog", detail.ui.value.detail?.container)
        assertEquals(null, detail.ui.value.detail?.sprintId)
        assertEquals(1, harness.writes)
    }

    @Test
    fun aBoardWorkflowChangeUpdatesOpenDetailWithoutReopening() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"workflow_state_id\":101", "\"workflow_state_id\":102")
            .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"in_progress\"")
        harness.viewModel.moveTask(harness.viewModel.ui.value.tasks.single(), WorkflowState(102, "in_progress", "In Progress"))
        harness.await { harness.detailViewModel.ui.value.detail?.workflowStateKey == "in_progress" && harness.writes == 1 }
        assertEquals(102L, harness.detailViewModel.ui.value.detail?.workflowStateId)
        assertEquals("in_progress", harness.viewModel.ui.value.sprintBoard?.tickets?.single()?.workflowStateKey)
    }

    @Test
    fun aCancelledFullTicketLeavesBothBoardProjectionsButRemainsOpenInDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
        harness.detailViewModel.updateFields(
            harness.detailViewModel.ui.value.detail!!,
            buildJsonObject { put("workflow_state_key", "cancelled") },
        )
        harness.await {
            harness.detailViewModel.ui.value.detail?.workflowStateKey == "cancelled" &&
                harness.viewModel.ui.value.tasks.isEmpty()
        }

        assertTrue(harness.viewModel.ui.value.sprintBoard!!.tickets.isEmpty())
        assertEquals(201L, harness.detailViewModel.ui.value.detail?.id)
    }

    @Test
    fun placementUsesTheFullMutationResponseRatherThanASecondDetailRead() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.viewModel.assignSprint(harness.viewModel.ui.value.tasks.single(), null)
        harness.await {
            harness.writes == 1 && harness.viewModel.ui.value.tasks.isEmpty() &&
                201L in harness.viewModel.ui.value.recentlyUpdated &&
                201L in harness.detailViewModel.ui.value.recentlyUpdated
        }
        assertEquals("backlog", harness.detailViewModel.ui.value.detail?.container)
        assertEquals(listOf(201L), harness.viewModel.ui.value.backlog.map { it.id })
        assertTrue(201L in harness.viewModel.ui.value.recentlyUpdated)
        assertTrue(201L in harness.detailViewModel.ui.value.recentlyUpdated)
        assertEquals(1, harness.detailReads)
    }

    @Test
    fun aPlanReloadReplacesMembershipWithoutEvictingOpenDetail() {
        val harness = harness()
        val backlog = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.planRows = backlog
        harness.detailResponse = backlog
        harness.viewModel.loadBacklog()
        harness.await { harness.viewModel.ui.value.planLoaded }
        harness.detailViewModel.openDetail(harness.viewModel.ui.value.backlog.single())
        harness.await { !harness.detailViewModel.ui.value.detailLoading }

        harness.planRows = ""
        harness.viewModel.loadBacklog()
        harness.await { harness.planReads == 2 && harness.viewModel.ui.value.backlog.isEmpty() }

        assertTrue(harness.viewModel.ui.value.backlog.isEmpty())
        assertEquals(0, harness.viewModel.ui.value.backlogTotal)
        assertEquals("Ship release", harness.detailViewModel.ui.value.detail?.title)
    }

    @Test
    fun aCreatedFullTicketIsVisibleEvenWhenTheFollowUpPlanLoadFails() {
        val harness = harness()
        harness.viewModel.loadBacklog()
        harness.await { harness.viewModel.ui.value.planLoaded }
        harness.rejectPlanLoads = true
        harness.saved = """{"id":202,"title":"New ticket","container":"backlog"}"""
        harness.viewModel.updateDraft { it.copy(title = "New ticket") }
        harness.viewModel.submitBacklog()
        harness.await { harness.writes == 1 && !harness.viewModel.ui.value.creating }

        assertEquals(listOf("New ticket"), harness.viewModel.ui.value.backlog.map { it.title })
        assertEquals(1, harness.viewModel.ui.value.backlogTotal)
    }

    @Test
    fun schedulingUsesTheFullResponseToRemovePlanWorkAndUpdateDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"2026-11-03\"")

        harness.viewModel.schedule(201, "2026-11-03")
        harness.await { harness.writes == 1 && harness.detailViewModel.ui.value.detail?.container == "scheduled" }
        assertTrue(harness.viewModel.ui.value.tasks.isEmpty())
        assertTrue(harness.viewModel.ui.value.backlog.isEmpty())
        assertEquals(1, harness.detailReads)

        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.viewModel.unschedule(201, "Ship release", ask = false)
        harness.await { harness.writes == 2 && harness.detailViewModel.ui.value.detail?.container == "backlog" }
        assertEquals(listOf(201L), harness.viewModel.ui.value.backlog.map { it.id })
        assertEquals(1, harness.detailReads)
    }

    @Test
    fun openingAFullTicketKeepsItsDataWhenTheDetailReadFails() {
        val harness = harness()
        harness.rejectDetailReads = true
        val ticket = Json { ignoreUnknownKeys = true }.decodeFromString<Task>(harness.original)
        harness.viewModel.openDetail(ticket)
        harness.await { !harness.viewModel.ui.value.detailLoading }

        assertEquals("Ship release", harness.viewModel.ui.value.detail?.title)
        assertEquals("sprint", harness.viewModel.ui.value.detail?.container)
    }

    @Test
    fun aPlanReadReplacesTheWholeTicketAndProjectsCompletionWithoutLosingTheDetailThread() {
        val harness = harness()
        harness.detailResponse = harness.original.dropLast(1) + ""","comments":[{"id":7,"content":"Keep this thread"}]}"""
        harness.loadBoardPlanAndDetail()
        harness.planRows = """{"id":201,"title":"Finished from Plan","container":"backlog","completed_at":"2026-11-02"}"""
        harness.viewModel.loadBacklog()
        harness.await { harness.detailViewModel.ui.value.detail?.title == "Finished from Plan" && harness.viewModel.ui.value.completedCount == 1 }

        assertTrue(harness.viewModel.ui.value.tasks.isEmpty())
        assertTrue(harness.viewModel.ui.value.backlog.isEmpty())
        assertEquals(listOf("Finished from Plan"), harness.viewModel.ui.value.completed.map { it.title })
        assertEquals("medium", harness.detailViewModel.ui.value.detail?.priority)
        assertEquals(null, harness.detailViewModel.ui.value.detail?.capabilities)
        assertTrue(harness.detailViewModel.ui.value.detail!!.comments.isEmpty())
        assertEquals(listOf("Keep this thread"), harness.detailViewModel.ui.value.detailComments.map { it.content })
    }

    @Test
    fun aKeyLookupWritesItsFullTicketEvenWhenTheFollowUpDetailReadFails() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.rejectDetailReads = true
        harness.saved = harness.original.replace("Ship release", "Fresh key lookup")
        harness.viewModel.openByKey("APP-201")
        harness.await { harness.detailReads == 2 && !harness.viewModel.ui.value.detailLoading }

        assertEquals("Fresh key lookup", harness.viewModel.ui.value.detail?.title)
        assertEquals("Fresh key lookup", harness.viewModel.ui.value.tasks.single().title)
        assertEquals("Fresh key lookup", harness.detailViewModel.ui.value.detail?.title)
    }

    @Test
    fun stoppingTheTimerPreservesTheLoggedMinutesInOpenDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.detailViewModel.stopTimer(201)
        harness.await { !harness.detailViewModel.ui.value.timerBusy }

        assertEquals(5, harness.detailViewModel.ui.value.detail?.loggedMinutes)
    }

    @Test
    fun importKeepsTheSuccessfulPlacementResponseWhenItsFollowingFieldWriteFails() {
        val harness = harness()
        val backlog = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.planRows = backlog
        harness.detailResponse = backlog
        harness.viewModel.loadBacklog()
        harness.await { harness.viewModel.ui.value.planLoaded }
        val ticket = harness.viewModel.ui.value.backlog.single()
        harness.detailViewModel.openDetail(ticket)
        harness.await { !harness.detailViewModel.ui.value.detailLoading }
        harness.rejectBoardReads = true
        harness.viewModel.bind(userId = 1, role = "employee", teamName = null, agileEnabled = true)
        harness.await { harness.viewModel.ui.value.sprints.isNotEmpty() && !harness.viewModel.ui.value.sprintLoading }
        harness.saved = harness.original.replace("Ship release", "Assigned copy")
        harness.rejectFieldWrites = true
        harness.viewModel.configureImport(ticket)
        harness.viewModel.setImportAssignee(9)

        harness.viewModel.confirmImport()
        harness.await { harness.viewModel.ui.value.error == "Failed to import task to sprint" }

        assertEquals("Assigned copy", harness.detailViewModel.ui.value.detail?.title)
        assertEquals(10L, harness.detailViewModel.ui.value.detail?.sprintId)
        assertTrue(harness.viewModel.ui.value.backlog.isEmpty())
    }
}

/** Reusable public UI seam: real ViewModel and repository, fake only at HTTP. */
internal class TicketSessionHarness {
    val original = """
        {"id":201,"title":"Ship release","priority":"high","sprint_id":10,"container":"sprint",
         "workflow_state_id":101,"workflow_state_key":"todo",
         "capabilities":{"transitions":[{"to":102,"allowed":true}],"can":{"edit":{"allowed":true},"changeState":{"allowed":true},"placeInSprint":{"allowed":true},"schedule":{"allowed":true}}}}
    """.trimIndent()
    @Volatile var saved = original
    @Volatile var writes = 0
    @Volatile var detailReads = 0
    @Volatile var planReads = 0
    @Volatile var planRows = ""
    @Volatile var detailResponse = original
    @Volatile var rejectPlanLoads = false
    @Volatile var rejectDetailReads = false
    @Volatile var rejectBoardReads = false
    @Volatile var rejectFieldWrites = false
    @Volatile private var boardTicket = original
    private val repository = TaskRepository(ApiClient { request ->
        val body = when {
            request.path == "sprints/10/board" -> {
                if (rejectBoardReads) throw java.io.IOException("offline")
                """{"sprint":{"id":10,"name":"Sprint"},"columns":[{"stateId":102,"count":0,"accepting":true}],"tickets":[$boardTicket]}"""
            }
            request.path.startsWith("tasks/backlog?") -> {
                planReads++
                if (rejectPlanLoads) throw java.io.IOException("offline")
                """{"tasks":[$planRows],"pagination":{"total":0,"hasMore":false}}"""
            }
            request.path == "tasks/201/detail" -> {
                detailReads++
                if (rejectDetailReads) throw java.io.IOException("offline")
                detailResponse
            }
            request.path == "tasks/201/comments" -> "[]"
            request.path == "tasks/201/timer/stop" -> """{"taskId":201,"minutes":5}"""
            request.path.startsWith("tasks/by-key/") -> saved
            request.path == "tasks/available-sprints" -> """[{"id":10,"name":"Sprint","status":"active"}]"""
            request.path == "sprints" -> """{"sprints":[{"id":10,"name":"Sprint","status":"active"}],"currentSprintId":10}"""
            request.path in setOf("tasks/labels", "tasks/assignable-users", "projects", "tasks/201/history") -> "[]"
            (request.path == "tasks/201" && request.method == "PUT") ||
                request.path == "tasks/201/status" || request.path == "tasks/201/assign-sprint" ||
                request.path == "tasks/201/schedule" || request.path == "tasks/201/unschedule" ||
                (request.path == "tasks/backlog" && request.method == "POST") -> {
                if (rejectFieldWrites && request.method == "PUT") throw java.io.IOException("offline")
                boardTicket = saved
                writes++
                saved
            }
            else -> "{}"
        }
        ApiResponse(200, emptyMap(), body.toByteArray())
    })
    val session = TicketSession()
    val viewModel = TaskViewModel(repository, ticketSession = session)
    val detailViewModel = TaskViewModel(repository, ticketSession = session)
    private val viewModels = ViewModelStore().apply {
        put("board-and-plan", viewModel)
        put("detail", detailViewModel)
    }

    fun close() {
        viewModels.clear()
        session.close()
    }

    fun loadBoardPlanAndDetail() {
        viewModel.bind(userId = 1, role = "employee", teamName = null, agileEnabled = true)
        await { !viewModel.ui.value.sprintLoading && viewModel.ui.value.tasks.size == 1 }
        viewModel.loadBacklog()
        await { viewModel.ui.value.planLoaded }
        detailViewModel.openDetail(viewModel.ui.value.tasks.single())
        await { !detailViewModel.ui.value.detailLoading }
    }

    fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("Timed out waiting for UI state", condition())
    }
}
