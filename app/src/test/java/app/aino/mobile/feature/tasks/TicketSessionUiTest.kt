package app.aino.mobile.feature.tasks

import androidx.lifecycle.ViewModelStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val vmPlan = harness.viewModelPlan
        harness.loadBoardPlanAndDetail()
        assertTrue(vmPlan.ui.value.backlog.isEmpty())

        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        val detail = harness.detailViewModel
        detail.updateFields(detail.ui.value.detail!!, buildJsonObject { put("sprint_id", null as String?) })
        harness.await { detail.ui.value.detail?.container == "backlog" && vmPlan.ui.value.backlog.size == 1 && harness.boardViewModel.ui.value.tasks.isEmpty() }

        assertTrue("Board must drop a ticket moved to backlog", harness.boardViewModel.ui.value.tasks.isEmpty())
        assertTrue("Board response must resolve current membership", harness.boardViewModel.ui.value.sprintBoard!!.tickets.isEmpty())
        assertEquals(listOf(201L), vmPlan.ui.value.backlog.map { it.id })
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
        harness.boardViewModel.moveTask(harness.boardViewModel.ui.value.tasks.single(), WorkflowState(102, "in_progress", "In Progress"))
        harness.await { harness.detailViewModel.ui.value.detail?.workflowStateKey == "in_progress" && harness.writes == 1 }
        assertEquals(102L, harness.detailViewModel.ui.value.detail?.workflowStateId)
        assertEquals("in_progress", harness.boardViewModel.ui.value.sprintBoard?.tickets?.single()?.workflowStateKey)
    }

    @Test
    fun aRealtimeUpdateRefetchesIntoEveryLoadedViewInTheSession() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.detailResponse = harness.original.replace("Ship release", "Realtime release")
        harness.boardTicket = harness.detailResponse

        harness.session.onRealtimeEvent(
            "task_updated",
            buildJsonObject {
                put("taskId", 201)
                put("action", "updated")
            },
        )
        harness.await {
            harness.boardViewModel.ui.value.tasks.singleOrNull()?.title == "Realtime release" &&
                harness.viewModel.searchResults.value.singleOrNull()?.title == "Realtime release" &&
                harness.detailViewModel.ui.value.detail?.title == "Realtime release"
        }

        assertEquals("Realtime release", harness.session.store.state.value.tickets[201]?.title)
    }

    @Test
    fun aGoneRealtimeTicketLeavesEveryViewAndEmitsOnlyOneNotice() {
        for (status in listOf(403, 404)) {
            val harness = harness()
            harness.loadBoardPlanAndDetail()
            harness.detailErrorStatus = status
            harness.session.onRealtimeEvent(
                "task_updated",
                buildJsonObject {
                    put("taskId", 201)
                    put("action", "updated")
                },
            )
            harness.await {
                201L !in harness.session.store.state.value.tickets &&
                    harness.boardViewModel.ui.value.tasks.none { it.id == 201L } &&
                    harness.detailViewModel.ui.value.detail == null
            }

            val notice = runBlocking { withTimeout(1_000) { harness.session.notices.first() } }
            assertEquals("Some tickets are no longer available to you", notice.text)
            assertNull(runBlocking { withTimeoutOrNull(100) { harness.session.notices.first() } })
        }
    }

    @Test
    fun unusableRealtimePayloadRequestsOneFullReloadFromTheSession() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        val boardReads = harness.boardReads

        harness.session.onRealtimeEvent("task_updated", null)
        harness.await { harness.boardReads > boardReads }

        assertEquals(boardReads + 1, harness.boardReads)
    }

    @Test
    fun tooManyRealtimeTicketsRequestOneFullReloadInsteadOfTargetedFetches() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        val boardReads = harness.boardReads
        val detailReads = harness.detailReads

        repeat(MAX_TARGETED_SYNC + 1) { index ->
            harness.session.onRealtimeEvent(
                "task_updated",
                buildJsonObject {
                    put("taskId", 300 + index)
                    put("action", "updated")
                },
            )
        }
        harness.await { harness.boardReads > boardReads && harness.detailReads > detailReads }

        assertEquals(boardReads + 1, harness.boardReads)
        assertEquals(detailReads + 1, harness.detailReads)
    }

    @Test
    fun visibleTicketPollingCanBeForwardedThroughTheSession() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        val boardReads = harness.boardReads

        harness.session.pollVisible()
        harness.await { harness.boardReads > boardReads }

        assertEquals(boardReads + 1, harness.boardReads)
    }

    @Test
    fun reconnectResyncsEveryLoadedView() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.scheduledViewModel.loadScheduled()
        harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded }
        val boardReads = harness.boardReads
        val planReads = harness.planReads
        val scheduledReads = harness.scheduledReads
        val detailReads = harness.detailReads
        harness.boardTicket = harness.original.replace("Ship release", "Refreshed release")
        harness.detailResponse = harness.boardTicket
        harness.planRows = """{"id":203,"title":"Refreshed backlog","container":"backlog"}"""
        harness.scheduledRows = """{"id":202,"title":"Refreshed schedule","container":"scheduled","planned_date":"${harness.scheduledViewModel.ui.value.window.first}"}"""

        harness.session.onReconnect()
        harness.await {
            harness.boardReads > boardReads &&
                harness.planReads > planReads &&
                harness.scheduledReads > scheduledReads &&
                harness.detailReads > detailReads &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.title == "Refreshed release" &&
                harness.detailViewModel.ui.value.detail?.title == "Refreshed release" &&
                harness.viewModelPlan.ui.value.backlog.any { it.id == 203L } &&
                harness.scheduledViewModel.ui.value.scheduled.any { it.id == 202L }
        }

        assertEquals(boardReads + 1, harness.boardReads)
        // A concurrent Detail refresh invalidates Plan's aggregate baseline and retries its read.
        assertTrue(harness.planReads >= planReads + 1)
        assertEquals(scheduledReads + 1, harness.scheduledReads)
        assertEquals(detailReads + 1, harness.detailReads)
    }

    @Test
    fun closingTheSessionStopsQueuedRealtimeSync() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        val detailReads = harness.detailReads
        harness.session.onRealtimeEvent(
            "task_updated",
            buildJsonObject {
                put("taskId", 201)
                put("action", "updated")
            },
        )

        harness.session.close()
        Thread.sleep(500)

        assertEquals(detailReads, harness.detailReads)
    }

    @Test
    fun anOpenDetailIsMarkedStaleWhenItsRealtimeTicketChanges() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.detailViewModel.startEdit()
        harness.detailResponse = harness.original.replace("Ship release", "Changed elsewhere")
        harness.boardTicket = harness.detailResponse

        harness.session.onRealtimeEvent(
            "task_updated",
            buildJsonObject {
                put("taskId", 201)
                put("action", "updated")
            },
        )
        harness.await { harness.detailViewModel.ui.value.detailStale }

        assertTrue(harness.detailViewModel.ui.value.detailEditing)
        assertEquals("Changed elsewhere", harness.detailViewModel.ui.value.detail?.title)
    }

    @Test
    fun aFailedBoardMoveDoesNotReplaceANewerRealtimeTicket() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.holdAndRejectNextStatusWrite()

        try {
            harness.boardViewModel.moveTask(
                harness.boardViewModel.ui.value.tasks.single(),
                WorkflowState(102, "in_progress", "In Progress"),
            )
            assertTrue("Move request should be in flight", harness.awaitStatusWrite())
            harness.await { harness.boardViewModel.ui.value.tasks.single().workflowStateKey == "in_progress" }

            harness.detailResponse = harness.original.replace("Ship release", "Realtime release")
            harness.boardTicket = harness.detailResponse
            harness.session.onRealtimeEvent(
                "task_updated",
                buildJsonObject {
                    put("taskId", 201)
                    put("action", "status")
                },
            )
            harness.await {
                harness.detailReads > 1 &&
                    harness.boardViewModel.ui.value.tasks.single().title == "Realtime release" &&
                    harness.detailViewModel.ui.value.detail?.title == "Realtime release"
            }

            harness.finishStatusWrite()
            harness.await { harness.boardViewModel.ui.value.error != null }

            assertEquals("Realtime release", harness.boardViewModel.ui.value.tasks.single().title)
            assertEquals("Realtime release", harness.detailViewModel.ui.value.detail?.title)
            assertEquals("todo", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
        } finally {
            harness.finishStatusWrite()
        }
    }

    @Test
    fun aFailedBoardMoveDoesNotReplaceAStructurallyEqualNewerTicket() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.holdAndRejectNextStatusWrite()

        try {
            harness.boardViewModel.moveTask(
                harness.boardViewModel.ui.value.tasks.single(),
                WorkflowState(102, "in_progress", "In Progress"),
            )
            assertTrue("Move request should be in flight", harness.awaitStatusWrite())
            harness.detailResponse = harness.original.replace("\"workflow_state_id\":101", "\"workflow_state_id\":102")
                .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"in_progress\"")
            harness.detailViewModel.refreshDetail()
            harness.await { harness.detailReads > 1 && !harness.detailViewModel.ui.value.detailLoading }

            harness.finishStatusWrite()
            harness.await { harness.boardViewModel.ui.value.error != null }

            assertEquals("in_progress", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
            assertEquals("in_progress", harness.detailViewModel.ui.value.detail?.workflowStateKey)
        } finally {
            harness.finishStatusWrite()
        }
    }

    @Test
    fun aFailedBoardMoveRestoresThePreviousTicketInBoardAndDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.holdAndRejectNextStatusWrite()

        try {
            harness.boardViewModel.moveTask(
                harness.boardViewModel.ui.value.tasks.single(),
                WorkflowState(102, "in_progress", "In Progress"),
            )
            assertTrue("Move request should be in flight", harness.awaitStatusWrite())
            harness.await {
                harness.boardViewModel.ui.value.tasks.single().workflowStateKey == "in_progress" &&
                    harness.detailViewModel.ui.value.detail?.workflowStateKey == "in_progress"
            }

            harness.finishStatusWrite()
            harness.await {
                harness.boardViewModel.ui.value.error != null &&
                    harness.boardViewModel.ui.value.tasks.single().workflowStateKey == "todo" &&
                    harness.detailViewModel.ui.value.detail?.workflowStateKey == "todo"
            }

            assertEquals("todo", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
            assertEquals("todo", harness.detailViewModel.ui.value.detail?.workflowStateKey)
        } finally {
            harness.finishStatusWrite()
        }
    }

    @Test
    fun undoAfterASuccessfulBoardMoveRestoresThePreviousTicketInBoardAndDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"workflow_state_id\":101", "\"workflow_state_id\":102")
            .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"in_progress\"")

        harness.boardViewModel.moveTask(
            harness.boardViewModel.ui.value.tasks.single(),
            WorkflowState(102, "in_progress", "In Progress"),
        )
        harness.await {
            harness.writes == 1 &&
                harness.boardViewModel.ui.value.tasks.single().workflowStateKey == "in_progress" &&
                harness.detailViewModel.ui.value.detail?.workflowStateKey == "in_progress"
        }
        val notice = runBlocking { withTimeout(5_000) { harness.viewModel.notices.first() } }

        assertEquals("Undo", notice.actionLabel)
        assertEquals(102L, harness.detailViewModel.ui.value.detail?.workflowStateId)
        harness.saved = harness.original
        notice.action?.invoke()
        harness.await { harness.writes == 2 && harness.boardViewModel.ui.value.tasks.single().workflowStateKey == "todo" }

        assertEquals("todo", harness.boardViewModel.ui.value.tasks.single().workflowStateKey)
        assertEquals(101L, harness.boardViewModel.ui.value.tasks.single().workflowStateId)
        assertEquals("todo", harness.detailViewModel.ui.value.detail?.workflowStateKey)
        assertEquals(101L, harness.detailViewModel.ui.value.detail?.workflowStateId)
    }

    @Test
    fun aCancelledTicketLeavesEveryLoadedViewIncludingDetailAndInlineComments() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.scheduledViewModel.loadScheduled()
        harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded }
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.openInlineComments()
        harness.await { harness.inlineComments?.loading == false }

        harness.saved = harness.original.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
        harness.detailViewModel.updateFields(
            harness.detailViewModel.ui.value.detail!!,
            buildJsonObject { put("workflow_state_key", "cancelled") },
        )
        harness.await {
            harness.detailViewModel.ui.value.detail == null &&
                harness.boardViewModel.ui.value.tasks.isEmpty() &&
                harness.viewModel.searchResults.value.isEmpty() &&
                harness.inlineComments == null
        }

        assertTrue(harness.boardViewModel.ui.value.sprintBoard!!.tickets.isEmpty())
        assertTrue(harness.viewModelPlan.ui.value.backlog.none { it.id == 201L })
        assertTrue(harness.scheduledViewModel.ui.value.scheduled.none { it.id == 201L })
    }

    @Test
    fun aCancelledFullTicketLeavesBothBoardProjectionsAndDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
        harness.detailViewModel.updateFields(
            harness.detailViewModel.ui.value.detail!!,
            buildJsonObject { put("workflow_state_key", "cancelled") },
        )
        harness.await {
            harness.detailViewModel.ui.value.detail == null &&
                harness.boardViewModel.ui.value.tasks.isEmpty()
        }

        assertTrue(harness.boardViewModel.ui.value.sprintBoard!!.tickets.isEmpty())
        assertTrue(harness.viewModelPlan.ui.value.backlog.none { it.id == 201L })
    }

    @Test
    fun aTicketCancelledFromTheBoardMovesToTheSprintsCancelledSection() {
        val harness = harness()
        val cancellable = harness.original.replace("\"can\":{", "\"can\":{\"cancel\":{\"allowed\":true},")
        harness.boardTicket = cancellable
        harness.detailResponse = cancellable
        harness.loadBoardPlanAndDetail()
        val cancelled = cancellable.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
            .replace("\"workflow_state_id\":101", "\"workflow_state_id\":103,\"completed_at\":\"2026-11-03T05:00:00Z\"")
        harness.cancelResponse = cancelled
        harness.cancelledRows = cancelled
        harness.boardTicket = ""

        harness.boardViewModel.requestDelete(harness.boardViewModel.ui.value.tasks.single())
        harness.boardViewModel.acceptConfirm()

        harness.await {
            harness.boardViewModel.ui.value.tasks.isEmpty() &&
                harness.boardViewModel.ui.value.cancelled.singleOrNull()?.id == 201L &&
                harness.detailViewModel.ui.value.closed
        }
        assertTrue(harness.boardViewModel.ui.value.cancelled.single().isCancelled)
        assertNull(harness.detailViewModel.ui.value.detail)
    }

    @Test
    fun aCancelledBacklogTicketIsListedUnderCompletedNotOpenWork() {
        val harness = harness()
        val cancelled = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
            .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
            .replace("\"workflow_state_id\":101", "\"workflow_state_id\":103,\"completed_at\":\"2026-11-03T05:00:00Z\"")
        harness.planRows = cancelled
        harness.detailResponse = cancelled

        harness.viewModelPlan.loadBacklog()
        harness.await { harness.viewModelPlan.ui.value.planLoaded }

        assertTrue(harness.viewModelPlan.ui.value.backlog.isEmpty())
        assertTrue(harness.viewModelPlan.ui.value.importable.isEmpty())
        assertEquals(listOf(201L), harness.viewModelPlan.ui.value.completed.map { it.id })
        assertEquals(0, harness.viewModelPlan.ui.value.backlogTotal)
    }

    @Test
    fun aCancelledTicketOpenedFromCompletedStaysReadable() {
        val harness = harness()
        harness.detailResponse = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
            .replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")

        harness.await { harness.detailViewModel.ui.value.detail?.isCancelled == true }

        assertFalse(harness.detailViewModel.ui.value.closed)
    }

    @Test
    fun searchResultsResolveTheLatestTicketFromAnotherViewModel() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }

        val updatedTitle = "Ship release v2"
        harness.saved = harness.original.replace("Ship release", updatedTitle)
        harness.detailViewModel.updateFields(
            harness.detailViewModel.ui.value.detail!!,
            buildJsonObject { put("title", updatedTitle) },
        )
        harness.await {
            harness.writes == 1 &&
                harness.detailViewModel.ui.value.detail?.title == updatedTitle &&
                harness.viewModel.searchResults.value.singleOrNull()?.title == updatedTitle
        }

        assertEquals(updatedTitle, harness.viewModel.searchResults.value.single().title)
    }

    @Test
    fun aCancelledScheduledTicketLeavesScheduledAndSearchResults() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.scheduledViewModel.loadScheduled()
        harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded }
        val plannedDay = LocalDate.now().plusDays(1).toString()
        val scheduled = harness.original
            .replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"$plannedDay\"")

        harness.saved = scheduled
        harness.detailViewModel.schedule(201, plannedDay)
        harness.await { harness.writes == 1 && harness.scheduledViewModel.ui.value.scheduled.size == 1 }
        harness.searchRows = scheduled
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }

        harness.saved = scheduled.replace("\"workflow_state_key\":\"todo\"", "\"workflow_state_key\":\"cancelled\"")
        harness.detailViewModel.updateFields(
            harness.detailViewModel.ui.value.detail!!,
            buildJsonObject { put("workflow_state_key", "cancelled") },
        )
        harness.await {
            harness.writes == 2 &&
                harness.scheduledViewModel.ui.value.scheduled.isEmpty() &&
                harness.viewModel.searchResults.value.isEmpty() &&
                harness.detailViewModel.ui.value.detail == null
        }

        assertTrue(harness.scheduledViewModel.ui.value.scheduled.none { it.id == 201L })
        assertTrue(harness.viewModel.searchResults.value.none { it.id == 201L })
    }

    @Test
    fun removingATicketFromTheSharedStoreRemovesItFromEveryLoadedView() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.scheduledViewModel.loadScheduled()
        harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded }
        val plannedDay = LocalDate.now().plusDays(1).toString()
        val scheduled = harness.original
            .replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"$plannedDay\"")
        harness.saved = scheduled
        harness.detailViewModel.schedule(201, plannedDay)
        harness.await { harness.writes == 1 && harness.scheduledViewModel.ui.value.scheduled.size == 1 }
        harness.searchRows = scheduled
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.openInlineComments()
        harness.await { harness.inlineComments?.loading == false }

        harness.session.onRealtimeEvent("task_updated", buildJsonObject {
            put("taskId", 201)
            put("action", "deleted")
        })
        harness.await {
            harness.boardViewModel.ui.value.tasks.isEmpty() &&
                harness.viewModelPlan.ui.value.backlog.none { it.id == 201L } &&
                harness.scheduledViewModel.ui.value.scheduled.isEmpty() &&
                harness.viewModel.searchResults.value.isEmpty() &&
                harness.detailViewModel.ui.value.detail == null &&
                harness.inlineComments == null &&
                harness.session.store.state.value.tickets[201L] == null
        }

        harness.viewModel.loadSprint()
        harness.await { !harness.boardViewModel.ui.value.sprintLoading }

        assertTrue(harness.session.store.state.value.tickets[201L] == null)
        assertTrue(harness.boardViewModel.ui.value.tasks.isEmpty())
    }

    @Test
    fun placementUsesTheFullMutationResponseRatherThanASecondDetailRead() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.viewModelPlan.assignSprint(harness.boardViewModel.ui.value.tasks.single(), null)
        harness.await {
            harness.writes == 1 && harness.boardViewModel.ui.value.tasks.isEmpty() &&
                201L in harness.viewModelPlan.ui.value.recentlyUpdated &&
                201L in harness.detailViewModel.ui.value.recentlyUpdated
        }
        assertEquals("backlog", harness.detailViewModel.ui.value.detail?.container)
        assertEquals(listOf(201L), harness.viewModelPlan.ui.value.backlog.map { it.id })
        assertTrue(201L in harness.viewModelPlan.ui.value.recentlyUpdated)
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
        harness.viewModelPlan.loadBacklog()
        harness.await {
            harness.viewModelPlan.ui.value.planLoaded &&
                harness.viewModelPlan.ui.value.backlog.map { it.id } == listOf(201L)
        }
        harness.await {
            !harness.detailViewModel.ui.value.detailLoading &&
                harness.detailViewModel.ui.value.detail?.title == "Ship release"
        }

        harness.planRows = ""
        harness.viewModelPlan.loadBacklog()
        harness.await {
            harness.planReads == 2 && !harness.viewModelPlan.ui.value.backlogLoading &&
                harness.viewModelPlan.ui.value.backlog.isEmpty()
        }

        assertTrue(harness.viewModelPlan.ui.value.backlog.isEmpty())
        assertEquals(0, harness.viewModelPlan.ui.value.backlogTotal)
        assertEquals("Ship release", harness.detailViewModel.ui.value.detail?.title)
    }

    @Test
    fun aCreatedFullTicketIsVisibleEvenWhenTheFollowUpPlanLoadFails() {
        val harness = harness()
        harness.viewModelPlan.loadBacklog()
        harness.await { harness.viewModelPlan.ui.value.planLoaded }
        harness.rejectPlanLoads = true
        harness.saved = """{"id":202,"title":"New ticket","container":"backlog"}"""
        harness.viewModelPlan.updateDraft { it.copy(title = "New ticket") }
        harness.viewModelPlan.submitBacklog()
        harness.await { harness.writes == 1 && !harness.viewModelPlan.ui.value.creating }

        assertEquals(listOf("New ticket"), harness.viewModelPlan.ui.value.backlog.map { it.title })
        assertEquals(1, harness.viewModelPlan.ui.value.backlogTotal)
    }

    @Test
    fun schedulingUsesTheFullResponseToRemovePlanWorkAndUpdateDetail() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"2026-11-03\"")

        harness.scheduledViewModel.schedule(201, "2026-11-03")
        harness.await { harness.writes == 1 && harness.detailViewModel.ui.value.detail?.container == "scheduled" }
        assertTrue(harness.boardViewModel.ui.value.tasks.isEmpty())
        assertTrue(harness.viewModelPlan.ui.value.backlog.isEmpty())
        assertEquals(1, harness.detailReads)

        harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.scheduledViewModel.unschedule(201, "Ship release", ask = false)
        harness.await { harness.writes == 2 && harness.detailViewModel.ui.value.detail?.container == "backlog" }
        assertEquals(listOf(201L), harness.viewModelPlan.ui.value.backlog.map { it.id })
        assertEquals(1, harness.detailReads)
    }

    @Test
    fun scheduledResultsFollowTheSharedPlannedDayIntoAndOutOfTheWindow() {
        val harness = harness()
        val plannedDay = LocalDate.now().plusDays(1).toString()
        val scheduled = harness.original
            .replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"$plannedDay\"")
        val outsideWindow = LocalDate.now().plusDays(90).toString()
        harness.loadBoardPlanAndDetail()
        harness.scheduledViewModel.loadScheduled()
        harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded && harness.scheduledViewModel.ui.value.scheduled.isEmpty() }

        harness.saved = scheduled
        harness.detailViewModel.schedule(201, plannedDay)
        harness.await {
            harness.writes == 1 && harness.scheduledViewModel.ui.value.scheduled.singleOrNull()?.plannedDate == plannedDay
        }
        assertEquals(plannedDay, harness.scheduledViewModel.ui.value.scheduled.single().plannedDate)

        harness.saved = scheduled.replace(plannedDay, outsideWindow)
        harness.detailViewModel.schedule(201, outsideWindow)
        harness.await { harness.writes == 2 && harness.scheduledViewModel.ui.value.scheduled.isEmpty() }
        assertTrue(harness.scheduledViewModel.ui.value.scheduled.none { it.id == 201L })
    }

    @Test
    fun openingAFullTicketKeepsItsDataWhenTheDetailReadFails() {
        val harness = harness()
        harness.rejectDetailReads = true
        val ticket = Json { ignoreUnknownKeys = true }.decodeFromString<Task>(harness.original)
        harness.session.store.upsert(ticket)
        harness.await { !harness.detailViewModel.ui.value.detailLoading }

        assertEquals("Ship release", harness.detailViewModel.ui.value.detail?.title)
        assertEquals("sprint", harness.detailViewModel.ui.value.detail?.container)
    }

    @Test
    fun aPlanReadReplacesTheWholeTicketAndProjectsCompletionWithoutLosingTheDetailThread() {
        val harness = harness()
        harness.detailResponse = harness.original.dropLast(1) + ""","comments":[{"id":7,"content":"Keep this thread"}]}"""
        harness.loadBoardPlanAndDetail()
        harness.planRows = """{"id":201,"title":"Finished from Plan","container":"backlog","completed_at":"2026-11-02"}"""
        harness.viewModelPlan.loadBacklog()
        harness.await { harness.detailViewModel.ui.value.detail?.title == "Finished from Plan" && harness.viewModelPlan.ui.value.completedCount == 1 }

        assertTrue(harness.boardViewModel.ui.value.tasks.isEmpty())
        assertTrue(harness.viewModelPlan.ui.value.backlog.isEmpty())
        assertEquals(listOf("Finished from Plan"), harness.viewModelPlan.ui.value.completed.map { it.title })
        assertEquals("medium", harness.detailViewModel.ui.value.detail?.priority)
        assertEquals(null, harness.detailViewModel.ui.value.detail?.capabilities)
        assertTrue(harness.detailViewModel.ui.value.detail!!.comments.isEmpty())
        assertEquals(listOf("Keep this thread"), harness.detailViewModel.ui.value.detailComments.map { it.content })
    }

    @Test
    fun commentChangesAreSharedBetweenInlineAndDetailAcrossViewModels() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()

        harness.openInlineComments()
        harness.await { harness.inlineComments?.loading == false }
        harness.inlineViewModel.addComment(201, "From inline")
        harness.await { harness.inlineComments?.items?.any { it.content == "From inline" } == true }
        harness.await { harness.detailViewModel.ui.value.detailComments.any { it.content == "From inline" } }
        harness.await { harness.boardViewModel.ui.value.tasks.singleOrNull()?.commentCount == 1 }

        harness.detailViewModel.addComment(201, "From detail")
        harness.await { harness.detailViewModel.ui.value.detailComments.any { it.content == "From detail" } }
        harness.await { harness.inlineComments?.items?.any { it.content == "From detail" } == true }
        harness.await {
            harness.boardViewModel.ui.value.tasks.singleOrNull()?.commentCount == 2 &&
                harness.detailViewModel.ui.value.detail?.commentCount == 2
        }
        assertEquals(2, harness.boardViewModel.ui.value.tasks.single().commentCount)
        assertEquals(2, harness.detailViewModel.ui.value.detail?.commentCount)

        harness.detailViewModel.editComment(201, 1, "Edited from detail")
        harness.await { harness.inlineComments?.items?.any { it.content == "Edited from detail" } == true }
        harness.await { harness.detailViewModel.ui.value.detailComments.any { it.content == "Edited from detail" } }

        harness.inlineViewModel.deleteComment(201, 2)
        harness.inlineViewModel.acceptConfirm()
        harness.await { harness.inlineComments?.items?.none { it.id == 2L } == true }
        harness.await { harness.detailViewModel.ui.value.detailComments.none { it.id == 2L } }
        harness.await {
            harness.boardViewModel.ui.value.tasks.singleOrNull()?.commentCount == 1 &&
                harness.detailViewModel.ui.value.detail?.commentCount == 1
        }
        assertEquals(1, harness.boardViewModel.ui.value.tasks.single().commentCount)
        assertEquals(1, harness.detailViewModel.ui.value.detail?.commentCount)

        harness.commentsResponse = """
            [{"id":99,"content":"Realtime comment","user_id":2},
             {"id":100,"content":"Another remote comment","user_id":3}]
        """.trimIndent()
        harness.session.onRealtimeEvent("task_updated", buildJsonObject {
            put("taskId", 201)
            put("action", "comment")
        })
        harness.await {
            harness.inlineComments?.items?.size == 2 &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.commentCount == 2
        }
        harness.await { harness.detailViewModel.ui.value.detailComments.size == 2 }
        assertEquals(listOf("Realtime comment", "Another remote comment"), harness.detailViewModel.ui.value.detailComments.map { it.content })

        harness.planRows = harness.original
            .replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
            .replace("\"capabilities\":", "\"comment_count\":2,\"capabilities\":")
        harness.viewModelPlan.loadBacklog()
        harness.await { harness.viewModelPlan.ui.value.backlog.any { it.id == 201L } }
        assertEquals(2, harness.viewModelPlan.ui.value.backlog.single().commentCount)
        assertEquals(2, harness.detailViewModel.ui.value.detail?.commentCount)
    }

    @Test
    fun aKeyLookupWritesItsFullTicketEvenWhenTheFollowUpDetailReadFails() {
        val harness = harness()
        harness.loadBoardPlanAndDetail()
        harness.rejectDetailReads = true
        harness.saved = harness.original.replace("Ship release", "Fresh key lookup")
        runBlocking { TicketDetailViewModel.resolveKey(harness.repository, harness.session, "APP-201") }
        harness.detailViewModel.refreshDetail()
        harness.await { harness.detailReads == 2 && !harness.detailViewModel.ui.value.detailLoading }

        assertEquals("Fresh key lookup", harness.detailViewModel.ui.value.detail?.title)
        assertEquals("Fresh key lookup", harness.boardViewModel.ui.value.tasks.single().title)
        assertEquals("Fresh key lookup", harness.detailViewModel.ui.value.detail?.title)
    }

    @Test
    fun importKeepsTheSuccessfulPlacementResponseWhenItsFollowingFieldWriteFails() {
        val harness = harness()
        val backlog = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
        harness.planRows = backlog
        harness.detailResponse = backlog
        harness.viewModelPlan.loadBacklog()
        harness.await { harness.viewModelPlan.ui.value.planLoaded }
        val ticket = harness.viewModelPlan.ui.value.backlog.single()
        harness.await { !harness.detailViewModel.ui.value.detailLoading }
        harness.rejectBoardReads = true
        harness.viewModel.bind(userId = 1, role = "employee", teamName = null, agileEnabled = true)
        harness.await { harness.viewModel.ui.value.sprints.isNotEmpty() && !harness.boardViewModel.ui.value.sprintLoading }
        harness.saved = harness.original.replace("Ship release", "Assigned copy")
        harness.rejectFieldWrites = true
        harness.viewModelPlan.configureImport(ticket)
        harness.viewModelPlan.setImportAssignee(9)

        harness.viewModelPlan.confirmImport()
        harness.await { harness.viewModelPlan.ui.value.error == "Failed to import task to sprint" }

        assertEquals("Assigned copy", harness.detailViewModel.ui.value.detail?.title)
        assertEquals(10L, harness.detailViewModel.ui.value.detail?.sprintId)
        assertTrue(harness.viewModelPlan.ui.value.backlog.isEmpty())
    }
}

/** Reusable public UI seam: real ViewModel and repository, fake only at HTTP. */
internal class TicketSessionHarness {
    val original = """
        {"id":201,"title":"Ship release","priority":"high","sprint_id":10,"container":"sprint",
         "workflow_state_id":101,"workflow_state_key":"todo",
         "capabilities":{"transitions":[{"to":102,"allowed":true}],"can":{"edit":{"allowed":true},"changeState":{"allowed":true},"placeInSprint":{"allowed":true},"schedule":{"allowed":true},"comment":{"allowed":true},"logWork":{"allowed":true}}}}
    """.trimIndent()
    @Volatile var saved = original
    @Volatile var writes = 0
    @Volatile var detailReads = 0
    @Volatile var historyReads = 0
    @Volatile var planReads = 0
    @Volatile var boardReads = 0
    @Volatile var sprintStatsReads = 0
    @Volatile var scheduledReads = 0
    @Volatile var commentReads = 0
    @Volatile var commentWrites = 0
    @Volatile var commentsResponse = "[]"
    @Volatile var worklogsResponse = """{"worklogs":[],"time_spent_minutes":0}"""
    @Volatile var worklogMutationResponse = "{}"
    @Volatile var worklogError: String? = null
    @Volatile var worklogsReadGate: HeldTicketResponse? = null
    @Volatile var worklogPayload: kotlinx.serialization.json.JsonObject? = null
    @Volatile var estimatePayload: kotlinx.serialization.json.JsonObject? = null
    @Volatile var estimateResponse = "{}"
    @Volatile var estimateWriteGate: HeldTicketResponse? = null
    @Volatile var worklogWriteGate: HeldTicketResponse? = null
    @Volatile var effortWrites = 0
    @Volatile var estimateError: String? = null
    @Volatile var planRows = ""
    /** Rows for `GET tasks?sprint_id=…&status=cancelled`; the board omits cancelled. */
    @Volatile var cancelledRows = ""
    /** The Ticket `DELETE tasks/201` answers with, or blank for a bare `{}`. */
    @Volatile var cancelResponse = ""
    @Volatile var scheduledRows = ""
    @Volatile var searchRows = ""
    @Volatile var detailResponse = original
    @Volatile var detailResponseGate: HeldTicketResponse? = null
    @Volatile var boardResponseGate: HeldTicketResponse? = null
    @Volatile var commentResponseGate: HeldTicketResponse? = null
    @Volatile var commentsReadGate: HeldTicketResponse? = null
    @Volatile var cancelledReadGate: HeldTicketResponse? = null
    @Volatile var rejectCommentReads = false
    @Volatile var rejectCancelledReads = false
    @Volatile var rejectPlanLoads = false
    @Volatile var rejectDetailReads = false
    @Volatile var detailErrorStatus: Int? = null
    @Volatile var rejectBoardReads = false
    @Volatile var rejectFieldWrites = false
    @Volatile private var holdStatusWrite = false
    @Volatile private var rejectStatusWrite = false
    private val statusWriteStarted = CountDownLatch(1)
    private val statusWriteRelease = CountDownLatch(1)
    @Volatile var boardTicket = original
    @Volatile var boardColumnCount = 0
    @Volatile var completedRollover: String? = null
    val repository = TaskRepository(ApiClient { request ->
        val body = when {
            request.path == "sprints/11/board" ->
                """{"sprint":{"id":11,"name":"Next sprint"},"columns":[{"stateId":102,"count":1,"accepting":true}],"tickets":[$saved]}"""
            request.path == "sprints/10/complete" -> {
                completedRollover = Json.parseToJsonElement(requireNotNull(request.body).toString(Charsets.UTF_8))
                    .jsonObject["rolloverTo"]?.jsonPrimitive?.content
                boardTicket = ""
                "{}"
            }
            request.path == "sprints/10/board" -> {
                boardReads++
                if (rejectBoardReads) throw java.io.IOException("offline")
                val response = """{"sprint":{"id":10,"name":"Sprint"},"columns":[{"stateId":102,"count":$boardColumnCount,"accepting":true}],"tickets":[$boardTicket]}"""
                boardResponseGate?.hold(response) ?: response
            }
            request.path == "sprints/10/stats" -> { sprintStatsReads++; "{}" }
            request.path.startsWith("tasks/backlog?") -> {
                planReads++
                if (rejectPlanLoads) throw java.io.IOException("offline")
                """{"tasks":[$planRows],"pagination":{"total":0,"hasMore":false}}"""
            }
            request.path.startsWith("tasks/scheduled") -> {
                scheduledReads++
                """{"tasks":[$scheduledRows]}"""
            }
            request.path.startsWith("tasks/search?") -> "[$searchRows]"
            request.path.startsWith("tasks?") && "status=cancelled" in request.path -> {
                if (rejectCancelledReads) throw java.io.IOException("History offline")
                val response = """{"tasks":[$cancelledRows]}"""
                cancelledReadGate?.hold(response) ?: response
            }
            request.path == "tasks/201" && request.method == "DELETE" ->
                if (cancelResponse.isBlank()) "{}" else """{"cancelled":true,"id":201,"task":$cancelResponse}"""
            request.path == "tasks/201/detail" -> {
                detailReads++
                detailErrorStatus?.let { status ->
                    throw ApiError.Http(status, """{"error":"Not visible"}""", request.method, request.path)
                }
                if (rejectDetailReads) throw java.io.IOException("offline")
                val response = detailResponse
                detailResponseGate?.hold(response) ?: response
            }
            request.path == "tasks/201/comments" && request.method == "GET" -> {
                commentReads++
                if (rejectCommentReads) throw java.io.IOException("Comments offline")
                commentsReadGate?.hold(commentsResponse) ?: commentsResponse
            }
            request.path == "tasks/201/comments" && request.method == "POST" -> {
                commentWrites++
                val response = """{"id":$commentWrites,"content":"${if (commentWrites == 1) "From inline" else "From detail"}","user_id":1}"""
                commentResponseGate?.hold(response) ?: response
            }
            request.path == "tasks/201/comments/1" && request.method == "PUT" ->
                """{"id":1,"content":"Edited from detail"}"""
            request.path == "tasks/201/comments/2" && request.method == "DELETE" -> "{}"
            request.path == "tasks/201/estimates" && request.method == "PATCH" -> {
                estimatePayload = request.body?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
                estimateError?.let { throw ApiError.Http(400, it, request.method, request.path) }
                effortWrites++
                estimateWriteGate?.hold(estimateResponse) ?: estimateResponse
            }
            request.path == "tasks/201/worklogs" && request.method == "GET" -> worklogsReadGate?.hold(worklogsResponse) ?: worklogsResponse
            request.path.startsWith("tasks/201/worklogs") -> {
                worklogPayload = request.body?.let { Json.parseToJsonElement(it.toString(Charsets.UTF_8)).jsonObject }
                worklogError?.let { throw ApiError.Http(400, it, request.method, request.path) }
                effortWrites++
                worklogWriteGate?.hold(worklogMutationResponse) ?: worklogMutationResponse
            }
            request.path == "tasks/201/block" || request.path == "tasks/201/parent" && request.method == "PATCH" -> {
                writes++
                saved
            }
            request.path.startsWith("tasks/by-key/") -> saved
            request.path == "tasks/available-sprints" -> """[{"id":10,"name":"Sprint","status":"active"},{"id":11,"name":"Next sprint","status":"planned"}]"""
            request.path == "sprints" -> """{"sprints":[{"id":10,"name":"Sprint","status":"active"}],"currentSprintId":10}"""
            request.path == "tasks/201/history" -> { historyReads++; "[]" }
            request.path in setOf("tasks/labels", "tasks/assignable-users", "projects") -> "[]"
            (request.path == "tasks/201" && request.method == "PUT") ||
                request.path == "tasks/201/status" || request.path == "tasks/201/assign-sprint" ||
                request.path == "tasks/201/schedule" || request.path == "tasks/201/unschedule" ||
                (request.path == "tasks/backlog" && request.method == "POST") -> {
                if (rejectFieldWrites && request.method == "PUT") throw java.io.IOException("offline")
                if (request.path == "tasks/201/status" && holdStatusWrite) {
                    statusWriteStarted.countDown()
                    if (!statusWriteRelease.await(5, TimeUnit.SECONDS)) {
                        throw java.io.IOException("Timed out waiting to finish status write")
                    }
                    if (rejectStatusWrite) throw java.io.IOException("offline")
                }
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
    val viewModelPlan = PlanViewModel(repository, viewModel.ticketSession)
    val scheduledViewModel = ScheduledViewModel(repository, session)
    val boardViewModel = SprintBoardViewModel(repository, session)
    private val detailModel = lazy {
        TicketDetailViewModel(201, repository, session).also { viewModels.put("detail", it) }
    }
    val detailViewModel get() = detailModel.value
    private val inlineModel = lazy {
        TicketDetailViewModel(201, repository, session).also { viewModels.put("inline", it) }
    }
    val inlineViewModel get() = inlineModel.value
    fun openInlineComments() = inlineViewModel.loadComments()
    val inlineComments get() = if (!inlineModel.isInitialized() || inlineViewModel.ui.value.closed) null
        else inlineViewModel.ui.value.let { CommentThread(201, it.detailComments, it.commentsLoading) }
    private val viewModels = ViewModelStore().apply {
        put("tasks", viewModel)
        put("plan", viewModelPlan)
        put("scheduled", scheduledViewModel)
        put("board", boardViewModel)
    }

    fun close() {
        worklogsReadGate?.release()
        detailResponseGate?.release()
        boardResponseGate?.release()
        commentResponseGate?.release()
        commentsReadGate?.release()
        cancelledReadGate?.release()
        viewModels.clear()
        session.close()
    }

    /** Holds one HTTP response after its payload has been captured. */
    internal class HeldTicketResponse {
        private val first = java.util.concurrent.atomic.AtomicBoolean(true)
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)

        fun hold(response: String): String {
            if (first.getAndSet(false)) {
                started.countDown()
                check(released.await(10, TimeUnit.SECONDS)) { "HTTP response was not released" }
            }
            return response
        }

        fun awaitStarted() = assertTrue("HTTP request did not start", started.await(5, TimeUnit.SECONDS))
        fun release() = released.countDown()
    }

    fun holdAndRejectNextStatusWrite() {
        holdStatusWrite = true
        rejectStatusWrite = true
    }

    fun awaitStatusWrite() = statusWriteStarted.await(5, TimeUnit.SECONDS)

    fun finishStatusWrite() {
        statusWriteRelease.countDown()
    }

    fun loadBoardPlanAndDetail() {
        viewModel.bind(userId = 1, role = "employee", teamName = null, agileEnabled = true)
        await { !boardViewModel.ui.value.sprintLoading && boardViewModel.ui.value.tasks.size == 1 }
        viewModelPlan.loadBacklog()
        await { viewModelPlan.ui.value.planLoaded }
        await { !detailViewModel.ui.value.detailLoading && !detailViewModel.ui.value.worklogsLoading }
    }

    fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val state = viewModel.ui.value
        assertTrue(
            "Timed out waiting for UI state: user=${state.userId}, " +
                "board=${boardViewModel.ui.value.boardTicketIds}, plan=${viewModelPlan.ui.value.planTicketIds}, scheduled=${scheduledViewModel.ui.value.scheduledTicketIds}, " +
                "search=${state.searchResultIds}, inline=${inlineComments?.items?.size}, " +
                "detail=${if (detailModel.isInitialized()) detailViewModel.ui.value.detail?.id else null}, commentReads=$commentReads, writes=$writes",
            condition(),
        )
    }
}
