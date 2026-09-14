package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskLogicTest {
    @Test
    fun buildsQueryWithOnlyServerAllowedFilters() {
        val filters = TaskFilters(assigneeMine = true, priority = "high", status = "in_progress", search = "release plan")
        assertEquals(
            "tasks?date=2026-09-14&assignee=me&priority=high&status=in_progress&search=release+plan",
            taskQuery("tasks", filters, "2026-09-14"),
        )
    }

    @Test
    fun dropsUnsupportedFilterValuesAndEmptySearch() {
        val filters = TaskFilters(assigneeMine = false, priority = "urgent", status = "archived", search = "   ")
        assertEquals("tasks", taskQuery("tasks", filters))
        assertEquals(
            "tasks/backlog?limit=100&offset=0",
            taskQuery("tasks/backlog", filters, extra = listOf("limit" to "100", "offset" to "0")),
        )
    }

    @Test
    fun advancesThroughTheDefaultWorkflowAndWrapsFromDone() {
        assertEquals("in_progress", nextTaskStatus("pending"))
        assertEquals("in_review", nextTaskStatus("in_progress"))
        assertEquals("done", nextTaskStatus("in_review"))
        assertEquals("pending", nextTaskStatus("done"))
        // A tenant-custom workflow key is unknown to the default ladder; start work.
        assertEquals("in_progress", nextTaskStatus("triage"))
    }

    @Test
    fun sortsByPriorityThenStatusLikeTheServer() {
        fun task(id: Long, priority: String, status: String) = Task(id = id, title = "t$id", priority = priority, status = status)
        val ordered = listOf(
            task(1, "low", "in_progress"),
            task(2, "high", "done"),
            task(3, "high", "in_progress"),
            task(4, "medium", "pending"),
        ).sortedBy(::taskSortKey).map { it.id }
        assertEquals(listOf(3L, 2L, 4L, 1L), ordered)
    }

    @Test
    fun mirrorsServerCreateValidation() {
        assertNull(validateTask("Ship release", "", "2026-09-14", "2026-09-20"))
        assertEquals("Task title is required", validateTask("   ", "", null, null))
        assertEquals("Task title must be 200 characters or less", validateTask("x".repeat(201), "", null, null))
        assertEquals("Task description must be 5000 characters or less", validateTask("ok", "y".repeat(5001), null, null))
        assertEquals("Due date must use YYYY-MM-DD", validateTask("ok", "", null, "20-09-2026"))
        assertEquals(
            "Due date cannot be earlier than the task date",
            validateTask("ok", "", "2026-09-14", "2026-09-01"),
        )
        // A backlog item has no date, so an early due date is allowed there.
        assertNull(validateTask("ok", "", null, "2026-09-01"))
    }

    @Test
    fun mirrorsServerCommentValidation() {
        assertNull(validateComment("Looks good"))
        assertEquals("Comment cannot be empty", validateComment("  "))
        assertEquals("Comment must be 2000 characters or less", validateComment("z".repeat(2001)))
    }

    @Test
    fun recomputesStatsAfterAnOptimisticStatusChange() {
        val tasks = listOf(
            Task(id = 1, title = "a", status = "done"),
            Task(id = 2, title = "b", status = "in_progress"),
            Task(id = 3, title = "c", status = "pending"),
            Task(id = 4, title = "d", status = "done"),
        )
        assertEquals(TaskStats(total = 4, done = 2, inProgress = 1, percent = 50), recomputeStats(tasks))
        assertEquals(TaskStats(), recomputeStats(emptyList()))
    }

    @Test
    fun labelsDefaultWorkflowKeysAndFallsBackForCustomStates() {
        assertEquals("To do", taskStatusLabel("pending"))
        assertEquals("In progress", taskStatusLabel("in_progress"))
        assertEquals("In review", taskStatusLabel("in_review"))
        assertEquals("Done", taskStatusLabel("done"))
        assertEquals("Ready for qa", taskStatusLabel("ready_for_qa"))
    }
}
