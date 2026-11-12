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
class SubtaskPlanningTest {
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
        assertTrue("Expected hierarchy state did not arrive", condition())
    }

    @Test fun refreshingLoadedSubtasksPropagatesCancellation() {
        val parent = Task(7, "Parent")
        val child = Task(8, "Child", parentTaskId = 7, isSubtask = true)
        session.store.upsert(child)
        val repository = TaskRepository(ApiClient {
            throw kotlinx.coroutines.CancellationException("cancelled")
        })

        try {
            refreshLoadedSubtasks(parent, repository, session)
            throw AssertionError("Cancellation should propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Expected: cancellation must not be reported as a refresh failure.
        }
    }

    @Test fun freshSubtaskSummaryDoesNotRetainAnOmittedStaleChildList() {
        val child = Task(8, "Deleted child", parentTaskId = 7, isSubtask = true)
        session.store.upsert(Task(
            id = 7,
            title = "Parent",
            subtasks = listOf(child),
            subtaskSummary = SubtaskSummary(total = 1),
        ))

        session.store.upsert(Task(
            id = 7,
            title = "Parent",
            subtaskSummary = SubtaskSummary(total = 0),
        ))

        val refreshed = session.store.state.value.tickets.getValue(7)
        assertEquals(0, refreshed.subtaskSummary?.total)
        assertEquals(null, refreshed.subtasks)
    }

    @Test fun placingParentRefetchesLoadedChildWithoutWritingChildPlacement() {
        var moved = false
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun child() = """{"id":8,"title":"Child","parent_task_id":7,"is_subtask":true,
            "container":"${if (moved) "sprint" else "backlog"}","sprint_id":${if (moved) "10" else "null"},
            "capabilities":{"can":{"placeInSprint":{"allowed":false,"reason":"SUBTASK_FOLLOWS_PARENT"}}}}"""
        fun parent() = """{"id":7,"title":"Parent","container":"${if (moved) "sprint" else "backlog"}",
            "sprint_id":${if (moved) "10" else "null"},"subtasks":[${child()}],
            "capabilities":{"can":{"placeInSprint":{"allowed":true}}}}"""
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/bulk" -> {
                    moved = true
                    writes += request.path
                    """{"changed":1,"results":[{"id":7,"ok":true}]}"""
                }
                request.method == "PATCH" -> {
                    writes += request.path
                    moved = request.body?.decodeToString()?.contains("null") == false
                    parent()
                }
                request.path.startsWith("tasks/backlog") -> """{"tasks":[${if (moved) "" else parent()}]}"""
                request.path == "tasks/8/detail" -> child()
                request.path == "tasks/7/detail" -> parent()
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        session.writePlanScope(PlanScope(sprints = listOf(AvailableSprint(10, "Sprint 10", "active"))))
        val plan = PlanViewModel(repository, session)
        val detail = TicketDetailViewModel(8, repository, session)
        plan.loadBacklog()
        await { plan.ui.value.planLoaded && detail.ui.value.detail != null }
        assertEquals(listOf(7L), plan.ui.value.backlog.map(Task::id))
        assertEquals(1, plan.ui.value.backlogTotal)
        plan.assignSprint(plan.ui.value.backlog.single(), 10)
        await { detail.ui.value.detail?.sprintId == 10L && plan.ui.value.backlog.isEmpty() }
        assertEquals(listOf("tasks/7/assign-sprint"), writes)
        plan.assignSprint(detail.ui.value.detail!!, null)
        Thread.sleep(50)
        assertEquals(listOf("tasks/7/assign-sprint"), writes)
        val parentDetail = TicketDetailViewModel(7, repository, session)
        await { !parentDetail.ui.value.detailLoading }
        parentDetail.assignSprint(parentDetail.ui.value.detail!!, null)
        await { detail.ui.value.detail?.sprintId == null }
        assertEquals(listOf("tasks/7/assign-sprint", "tasks/7/assign-sprint"), writes)
        await { plan.ui.value.backlog.any { it.id == 7L } }
        plan.toggleSelection(7)
        plan.bulkAction("move_to_sprint", "10")
        await { detail.ui.value.detail?.sprintId == 10L }
        assertEquals(listOf("tasks/7/assign-sprint", "tasks/7/assign-sprint", "tasks/bulk"), writes)
    }

    @Test fun remoteSubtaskChangesRefreshParentSummaryAndNestedBacklog() {
        var changed = false
        var deleted = false
        fun child() = """{"id":8,"title":"${if (changed) "Finished child" else "Open child"}",
            "is_subtask":true,"parent_task_id":7,"container":"backlog"}"""
        fun parent() = """{"id":7,"title":"Parent","container":"backlog","subtasks":[${if (deleted) "" else child()}],
            "subtask_summary":{"total":${if (deleted) 0 else 1},"done":${if (changed && !deleted) 1 else 0}},
            "effort":{"total_time_spent_minutes":${if (changed) 90 else 30}}}"""
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/7/detail" -> parent()
                request.path == "tasks/8/detail" -> child()
                request.path.startsWith("tasks/backlog") -> """{"tasks":[${parent()}]}"""
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        session.writePlanScope(PlanScope(userId = 1))
        val plan = PlanViewModel(repository, session)
        val detail = TicketDetailViewModel(7, repository, session)
        plan.loadBacklog()
        await { plan.ui.value.planLoaded && !detail.ui.value.detailLoading }
        changed = true
        session.onRealtimeEvent("task_updated", buildJsonObject { put("taskId", 8); put("action", "updated") })
        await { detail.ui.value.detail?.subtaskSummary?.done == 1 &&
            plan.ui.value.backlog.single().subtasks.orEmpty().single().title == "Finished child" }
        assertEquals(90, detail.ui.value.detail?.effort?.totalTimeSpentMinutes)
        deleted = true
        session.onRealtimeEvent("task_deleted", buildJsonObject { put("taskId", 8) })
        await { detail.ui.value.detail?.subtaskSummary?.total == 0 &&
            plan.ui.value.backlog.single().subtasks.orEmpty().isEmpty() }
    }

    @Test fun projectMoveUsesServerKeysAliasesAndStateForEachLoadedFamilyMember() {
        var moved = false
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        fun parent() = """{"id":7,"title":"Parent","project_id":${if (moved) 4 else 3},
            "ticket_key":"${if (moved) "OPS-21" else "SRC-7"}","workflow_state_key":"ready",
            "key_aliases":${if (moved) """["SRC-7"]""" else "[]"},
            "capabilities":{"can":{"canMoveProject":{"allowed":true}}}}"""
        fun child() = """{"id":8,"title":"Child","is_subtask":true,"parent_task_id":7,
            "project_id":${if (moved) 4 else 3},"ticket_key":"${if (moved) "OPS-22" else "SRC-8"}",
            "parent_key":"${if (moved) "OPS-21" else "SRC-7"}","workflow_state_key":"${if (moved) "review" else "todo"}",
            "key_aliases":${if (moved) """["SRC-8"]""" else "[]"},
            "capabilities":{"can":{"canMoveProject":{"allowed":false,"reason":"SUBTASK_FOLLOWS_PARENT"}}}}"""
        val repository = TaskRepository(ApiClient { request ->
            val body = when (request.path) {
                "tasks/7/detail" -> parent()
                "tasks/8/detail" -> child()
                "projects/mine" -> """[{"id":4,"name":"Operations","capabilities":{"canCreateTickets":true}}]"""
                "tasks/7/move-project/preview" -> """{"target_project_id":4,"key_format":"OPS-<number>",
                    "workflow_state":{"id":22,"key":"ready","name":"Ready","category":"open"},"leaves_sprint":false}"""
                "tasks/7/move-project" -> {
                    moved = true
                    writes += request.path
                    """{"task":${parent()},"moved":true}"""
                }
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val parentDetail = TicketDetailViewModel(7, repository, session)
        val childDetail = TicketDetailViewModel(8, repository, session)
        await { parentDetail.ui.value.detail != null && childDetail.ui.value.detail != null }
        parentDetail.startProjectMove()
        await { parentDetail.ui.value.moveProjects.isNotEmpty() }
        parentDetail.previewProjectMove(4)
        await { parentDetail.ui.value.confirm != null }
        parentDetail.acceptConfirm()
        await { childDetail.ui.value.detail?.displayKey == "OPS-22" }
        assertEquals("OPS-21", parentDetail.ui.value.detail?.displayKey)
        assertEquals("OPS-21", childDetail.ui.value.detail?.parentKey)
        assertEquals(listOf("SRC-8"), childDetail.ui.value.detail?.keyAliases)
        assertEquals("review", childDetail.ui.value.detail?.workflowStateKey)
        childDetail.startProjectMove()
        assertEquals(false, childDetail.ui.value.movePickerOpen)
        assertEquals(listOf("tasks/7/move-project"), writes)
    }

    @Test fun editableSubtaskCannotBeRankedIndependently() {
        val writes = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repository = TaskRepository(ApiClient { request ->
            if (request.method != "GET") writes += request.path
            ApiResponse(200, emptyMap(), """{"id":8,"title":"Child","is_subtask":true,"parent_task_id":7,
                "capabilities":{"can":{"edit":{"allowed":true}}}}""".toByteArray())
        })
        val detail = TicketDetailViewModel(8, repository, session)
        val plan = PlanViewModel(repository, session)
        await { detail.ui.value.detail != null }
        plan.reorder(8, 7, null)
        Thread.sleep(100)
        assertTrue(writes.isEmpty())
        assertEquals("Subtasks follow their parent's project and sprint.", capabilityReasonCopy("SUBTASK_FOLLOWS_PARENT"))
    }

    @Test fun childWorkEstimateAndDeletionRefreshLoadedParentEffortFromServer() {
        var stage = 0
        val totals = listOf(50, 80, 80, 50)
        val remaining = listOf(0, 0, 20, 20)
        fun child() = """{"id":8,"title":"Child","is_subtask":true,"parent_task_id":7,
            "time_spent_minutes":${if (stage in 1..2) 30 else 0},"remaining_estimate_minutes":${remaining[stage]},
            "capabilities":{"can":{"logWork":{"allowed":true},"canEditEstimate":{"allowed":true}}}}"""
        fun summary() = """{"id":8,"time_spent_minutes":${if (stage in 1..2) 30 else 0},
            "remaining_estimate_minutes":${remaining[stage]}}"""
        val repository = TaskRepository(ApiClient { request ->
            val body = when {
                request.path == "tasks/7/detail" -> """{"id":7,"title":"Parent","time_spent_minutes":50,
                    "subtask_summary":{"total":1,"done":0},"subtasks":[${child()}],
                    "effort":{"own_time_spent_minutes":50,"total_time_spent_minutes":${totals[stage]},
                    "total_remaining_estimate_minutes":${100 + remaining[stage]}}}"""
                request.path == "tasks/8/detail" -> child()
                request.path == "tasks/8/estimates" -> { stage = 2; """{"ticket":${summary()}}""" }
                request.path == "tasks/8/worklogs/9" -> { stage = 3; """{"ticket":${summary()}}""" }
                request.path == "tasks/8/worklogs" && request.method == "POST" -> {
                    stage = 1
                    """{"worklog":{"id":9,"day_worked":"2026-11-09","minutes":30,"canDelete":true},"ticket":${summary()}}"""
                }
                request.path == "tasks/8/worklogs" -> """{"worklogs":[],"time_spent_minutes":0}"""
                request.path == "tasks/7/worklogs" -> """{"worklogs":[],"time_spent_minutes":50}"""
                else -> "{}"
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val parent = TicketDetailViewModel(7, repository, session)
        val child = TicketDetailViewModel(8, repository, session)
        await { !parent.ui.value.detailLoading && !child.ui.value.detailLoading && !child.ui.value.worklogsLoading }
        child.saveWorklog(WorklogDraft(timeSpent = "30m"))
        await { !child.ui.value.worklogBusy && parent.ui.value.detail?.effort?.totalTimeSpentMinutes == 80 }
        assertEquals(50, parent.ui.value.detail?.timeSpentMinutes)
        child.startEstimateEdit(EstimateKind.Remaining)
        child.saveEstimate("20m")
        await { !child.ui.value.estimateBusy && parent.ui.value.detail?.effort?.totalRemainingEstimateMinutes == 120 }
        child.deleteWorklog(9)
        await { !child.ui.value.worklogBusy && parent.ui.value.detail?.effort?.totalTimeSpentMinutes == 50 }
        assertEquals(0, child.ui.value.detail?.timeSpentMinutes)
        assertEquals(20, child.ui.value.detail?.remainingEstimateMinutes)
    }
}
