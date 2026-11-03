package app.aino.mobile.feature.tasks

import app.aino.mobile.feature.tasks.TicketSessionHarness.HeldTicketResponse
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TicketDetailViewModelTest {
    private lateinit var harness: TicketSessionHarness

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        harness = TicketSessionHarness()
    }

    @After fun tearDown() {
        harness.close()
        Dispatchers.resetMain()
    }

    @Test fun refreshingDetailReplacesTheCachedCommentThread() {
        harness.detailResponse = withComments("""{"id":1,"content":"Old"}""")
        harness.loadBoardPlanAndDetail()
        harness.detailResponse = withComments("""{"id":2,"content":"Fresh"}""")

        harness.detailViewModel.refreshDetail()

        harness.await { harness.detailReads == 2 && !harness.detailViewModel.ui.value.detailLoading }
        assertEquals(listOf("Fresh"), harness.detailViewModel.ui.value.detailComments.map { it.content })
    }

    private fun withComments(items: String) = harness.original.dropLast(1) + ""","comments":[$items]}"""

    @Test fun loadingCommentsKeepsConcurrentAddEditAndDelete() = lateCommentsKeepMutations(realtime = false)

    @Test fun realtimeCommentsKeepConcurrentAddEditAndDelete() = lateCommentsKeepMutations(realtime = true)

    private fun lateCommentsKeepMutations(realtime: Boolean) {
        val baseline = """{"id":1,"content":"Old edit"},{"id":2,"content":"Delete me"},{"id":3,"content":"Old remote"}"""
        harness.detailResponse = withComments(baseline)
        harness.loadBoardPlanAndDetail()
        harness.commentsResponse = """[$baseline,{"id":4,"content":"Remote addition"}]"""
        val gate = HeldTicketResponse().also { harness.commentsReadGate = it }
        if (realtime) {
            harness.session.onRealtimeEvent("task_updated", buildJsonObject {
                put("taskId", 201)
                put("action", "comment")
            })
        } else harness.detailViewModel.loadComments()
        gate.awaitStarted()

        harness.commentWrites = 4
        harness.detailViewModel.addComment(201, "New local", null, emptyMap()) {}
        harness.detailViewModel.editComment(201, 1, "Edited from detail") {}
        harness.detailViewModel.deleteComment(201, 2)
        harness.detailViewModel.acceptConfirm()
        harness.await {
            val comments = harness.detailViewModel.ui.value.detailComments
            comments.any { it.id == 5L } && comments.none { it.id == 2L } &&
                comments.firstOrNull { it.id == 1L }?.content == "Edited from detail"
        }
        gate.release()
        harness.await { harness.detailViewModel.ui.value.detailComments.any { it.id == 4L } }
        assertEquals(
            listOf("Edited from detail", "Old remote", "Remote addition", "From detail"),
            harness.detailViewModel.ui.value.detailComments.map { it.content },
        )
        harness.await { harness.boardViewModel.ui.value.tasks.single().commentCount == 4 }
    }

    @Test fun failedCommentLoadKeepsTheSharedThreadAndReportsTheFailure() {
        harness.detailResponse = withComments("""{"id":1,"content":"Keep me"}""")
        harness.loadBoardPlanAndDetail()
        val countBefore = harness.detailViewModel.ui.value.detail?.commentCount
        harness.rejectCommentReads = true
        harness.detailViewModel.loadComments()
        harness.await { harness.detailViewModel.ui.value.error != null && !harness.detailViewModel.ui.value.commentsLoading }
        assertEquals(listOf("Keep me"), harness.detailViewModel.ui.value.detailComments.map { it.content })
        assertEquals(countBefore, harness.detailViewModel.ui.value.detail?.commentCount)
    }

    @Test fun refreshingCommentsReconcilesServerChangesWithoutLosingConcurrentMutations() {
        harness.detailResponse = withComments(
            """{"id":1,"content":"Old edit"},{"id":2,"content":"Delete me"},{"id":3,"content":"Old remote"}""",
        )
        harness.loadBoardPlanAndDetail()
        harness.detailResponse = withComments(
            """{"id":1,"content":"Old edit"},{"id":2,"content":"Delete me"},{"id":3,"content":"Fresh remote"},{"id":4,"content":"Remote addition"}""",
        )
        val gate = HeldTicketResponse().also { harness.detailResponseGate = it }
        harness.detailViewModel.refreshDetail()
        gate.awaitStarted()

        harness.commentWrites = 4
        harness.detailViewModel.addComment(201, "New local", null, emptyMap()) {}
        harness.detailViewModel.editComment(201, 1, "Edited from detail") {}
        harness.detailViewModel.deleteComment(201, 2)
        harness.detailViewModel.acceptConfirm()
        harness.await {
            val comments = harness.detailViewModel.ui.value.detailComments
            comments.any { it.id == 5L } && comments.none { it.id == 2L } &&
                comments.firstOrNull { it.id == 1L }?.content == "Edited from detail"
        }
        gate.release()

        harness.await { !harness.detailViewModel.ui.value.detailLoading }
        assertEquals(
            listOf("Edited from detail", "Fresh remote", "Remote addition", "From detail"),
            harness.detailViewModel.ui.value.detailComments.map { it.content },
        )
        assertEquals(4, harness.detailViewModel.ui.value.detail?.commentCount)
    }

    @Test fun addingACommentAlreadyReceivedByRealtimeDoesNotCountItTwice() {
        harness.loadBoardPlanAndDetail()
        val gate = HeldTicketResponse().also { harness.commentResponseGate = it }
        val done = java.util.concurrent.atomic.AtomicBoolean()
        harness.detailViewModel.addComment(201, "From inline", null, emptyMap()) { done.set(true) }
        gate.awaitStarted()
        harness.commentsResponse = """[{"id":1,"content":"From inline","user_id":1}]"""
        harness.session.onRealtimeEvent("task_updated", buildJsonObject {
            put("taskId", 201)
            put("action", "comment")
        })
        harness.await {
            harness.detailViewModel.ui.value.detailComments.size == 1 &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.commentCount == 1
        }
        gate.release()
        harness.await { done.get() }

        assertEquals(listOf(1L), harness.detailViewModel.ui.value.detailComments.map { it.id })
        assertEquals(1, harness.detailViewModel.ui.value.detail?.commentCount)
        assertEquals(1, harness.boardViewModel.ui.value.tasks.single().commentCount)
    }

    @Test fun refreshingDetailKeepsTheCountOfACommentAddedDuringTheRead() {
        harness.loadBoardPlanAndDetail()
        val gate = HeldTicketResponse().also { harness.detailResponseGate = it }
        harness.detailViewModel.refreshDetail()
        gate.awaitStarted()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        harness.detailViewModel.addComment(201, "New comment", null, emptyMap()) { done.set(true) }
        harness.await { done.get() }
        gate.release()
        harness.await { !harness.detailViewModel.ui.value.detailLoading }

        assertEquals(1, harness.detailViewModel.ui.value.detailComments.size)
        assertEquals(1, harness.detailViewModel.ui.value.detail?.commentCount)
        assertEquals(1, harness.boardViewModel.ui.value.tasks.single().commentCount)
    }

    @Test fun cancellingFromDetailCannotBeUndoneByInFlightLoads() = cancellationRejectsStaleLoads(fromDetail = true)

    @Test fun cancellingFromAListCannotBeUndoneByInFlightLoads() = cancellationRejectsStaleLoads(fromDetail = false)

    @Test fun aCancelledTicketCanLegitimatelyReopenLaterInTheSameSession() {
        cancellationRejectsStaleLoads(fromDetail = true)
        harness.detailResponse = harness.original.replace("Ship release", "Reopened release")
        harness.boardTicket = harness.detailResponse

        harness.session.onRealtimeEvent("task_updated", buildJsonObject {
            put("taskId", 201)
            put("action", "updated")
        })

        harness.await { harness.boardViewModel.ui.value.tasks.singleOrNull()?.title == "Reopened release" }
        assertEquals("Reopened release", harness.boardViewModel.ui.value.tasks.single().title)
    }

    private fun cancellationRejectsStaleLoads(fromDetail: Boolean) {
        harness.detailResponse = harness.original.replace("\"can\":{", "\"can\":{\"cancel\":{\"allowed\":true},")
        harness.boardTicket = harness.detailResponse
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.detailResponse
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        val historyReads = harness.historyReads
        val detailGate = HeldTicketResponse().also { harness.detailResponseGate = it }
        val boardGate = HeldTicketResponse().also { harness.boardResponseGate = it }
        harness.detailViewModel.refreshDetail()
        harness.boardViewModel.loadSprint()
        detailGate.awaitStarted()
        boardGate.awaitStarted()
        val ticket = harness.detailViewModel.ui.value.detail!!
        harness.boardTicket = ""

        if (fromDetail) {
            harness.detailViewModel.requestDelete(ticket)
            harness.detailViewModel.acceptConfirm()
        } else {
            harness.boardViewModel.requestDelete(ticket)
            harness.boardViewModel.acceptConfirm()
        }
        harness.await { harness.detailViewModel.ui.value.closed }
        detailGate.release()
        boardGate.release()
        harness.await {
            harness.historyReads > historyReads &&
                !harness.detailViewModel.ui.value.detailLoading && !harness.boardViewModel.ui.value.sprintLoading
        }

        assertNull(harness.detailViewModel.ui.value.detail)
        assertTrue(harness.boardViewModel.ui.value.tasks.isEmpty())
        assertTrue(harness.viewModel.searchResults.value.isEmpty())
    }

    @Test fun blockerWriteUpdatesDetailBoardAndSearchFromTheAuthoritativeTicket() {
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.saved = harness.original.dropLast(1) + ""","is_blocked":true,"blocked_reason":"Waiting"}"""
        harness.detailResponse = harness.saved

        harness.detailViewModel.setBlocker(201, true, "Waiting")

        harness.await {
            harness.detailViewModel.ui.value.detail?.isBlocked == true &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.isBlocked == true &&
                harness.viewModel.searchResults.value.singleOrNull()?.isBlocked == true
        }
        assertEquals(2, harness.detailReads)
        assertEquals("Waiting", harness.detailViewModel.ui.value.detail?.blockedReason)
    }

    @Test fun parentWriteUpdatesEveryLoadedProjection() {
        harness.loadBoardPlanAndDetail()
        harness.searchRows = harness.original
        harness.viewModel.setSearch("Ship")
        harness.await { harness.viewModel.searchResults.value.size == 1 }
        harness.saved = harness.original.dropLast(1) + ""","parent_task_id":77}"""
        harness.detailResponse = harness.saved

        harness.detailViewModel.setParent(201, 77)

        harness.await {
            harness.detailViewModel.ui.value.detail?.parentTaskId == 77L &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.parentTaskId == 77L &&
                harness.viewModel.searchResults.value.singleOrNull()?.parentTaskId == 77L
        }
        assertEquals(2, harness.detailReads)
    }

    @Test fun timerStopUpdatesDetailAndListRowsTogether() {
        harness.loadBoardPlanAndDetail()
        harness.detailViewModel.stopTimer(201)
        harness.await {
            !harness.detailViewModel.ui.value.timerBusy &&
                harness.detailViewModel.ui.value.detail?.loggedMinutes == 5 &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.loggedMinutes == 5
        }
        assertEquals(5, harness.boardViewModel.ui.value.sprintBoard?.tickets?.single()?.loggedMinutes)
    }

    @Test fun separateTicketRoutesKeepEditingTabsHistoryAndTimersIsolated() {
        harness.loadBoardPlanAndDetail()
        val other = TicketDetailViewModel(202, harness.repository, harness.session)
        val models = androidx.lifecycle.ViewModelStore().apply { put("other", other) }
        try {
            harness.detailViewModel.startEdit()
            harness.detailViewModel.setDetailTab(DetailTab.History)
            harness.await { !other.ui.value.detailLoading }

            assertEquals(201L, harness.detailViewModel.ui.value.ticketId)
            assertEquals(202L, other.ui.value.ticketId)
            assertTrue(harness.detailViewModel.ui.value.detailEditing)
            assertFalse(other.ui.value.detailEditing)
            assertEquals(DetailTab.Comments, other.ui.value.detailTab)
            assertNull(other.ui.value.detail)
            assertEquals("Ship release", harness.detailViewModel.ui.value.detail?.title)
        } finally {
            models.clear()
        }
    }

    @Test fun endingTheSessionClearsAnAlreadyOpenDetail() {
        harness.loadBoardPlanAndDetail()
        harness.detailViewModel.startEdit()
        harness.session.close()

        assertNull(harness.detailViewModel.ui.value.detail)
        assertTrue(harness.detailViewModel.ui.value.history.isEmpty())
        assertTrue(harness.detailViewModel.ui.value.detailComments.isEmpty())
        assertFalse(harness.detailViewModel.ui.value.detailEditing)
        assertFalse(harness.detailViewModel.ui.value.timerRunning)
    }

    @Test fun schedulingFromDetailKeepsTheViewActionAfterTheRouteCloses() {
        harness.loadBoardPlanAndDetail()
        harness.saved = harness.original.replace("\"container\":\"sprint\"", "\"container\":\"scheduled\"")
            .replace("\"sprint_id\":10", "\"sprint_id\":null")
        harness.detailViewModel.schedule(201, "2026-11-03", closeAfter = true)
        harness.await { harness.detailViewModel.ui.value.closed }

        val notice = runBlocking { withTimeout(1_000) { harness.session.notices.first() } }
        assertEquals("View", notice.actionLabel)
        assertEquals("2026-11-03", notice.scheduledDate)
    }

    @Test fun savingChecklistPublishesTheFullTicketToTheLoadedRows() {
        harness.loadBoardPlanAndDetail()
        harness.detailResponse = harness.original.dropLast(1) +
            ""","acceptance_criteria":[{"id":"build","text":"Builds","done":true}]}"""

        harness.detailViewModel.saveCriteria(201, listOf(CriterionPayload("build", "Builds", true)))

        harness.await {
            harness.detailViewModel.ui.value.detail?.acceptanceCriteria?.singleOrNull()?.done == true &&
                harness.boardViewModel.ui.value.tasks.singleOrNull()?.acceptanceCriteria?.singleOrNull()?.done == true
        }
    }

    @Test fun unscheduleUndoStillWorksAfterTheDetailViewModelIsCleared() {
        harness.loadBoardPlanAndDetail()
        val day = java.time.LocalDate.now().plusDays(1).toString()
        val scheduled = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
            .replace("\"container\":\"sprint\"", "\"container\":\"scheduled\",\"planned_date\":\"$day\"")
        harness.detailResponse = scheduled
        val route = TicketDetailViewModel(201, harness.repository, harness.session)
        val models = androidx.lifecycle.ViewModelStore().apply { put("route", route) }
        try {
            harness.await { !route.ui.value.detailLoading }
            harness.scheduledViewModel.loadScheduled()
            harness.await { harness.scheduledViewModel.ui.value.scheduledLoaded }
            harness.saved = harness.original.replace("\"sprint_id\":10", "\"sprint_id\":null")
                .replace("\"container\":\"sprint\"", "\"container\":\"backlog\"")
            route.unschedule(201, "Ship release", closeAfter = true, ask = false)
            harness.await { route.ui.value.closed && harness.scheduledViewModel.ui.value.scheduled.isEmpty() }
            val notice = runBlocking { withTimeout(1_000) { harness.session.notices.first() } }
            assertEquals("Undo", notice.actionLabel)
            models.clear()

            harness.saved = scheduled
            notice.action!!.invoke()

            harness.await { harness.scheduledViewModel.ui.value.scheduled.singleOrNull()?.plannedDate == day }
        } finally {
            models.clear()
        }
    }
}
