package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class TaskLogicTest {
    private val today = LocalDate.of(2026, 9, 25)

    @Test
    fun sprintQueryCarriesSprintIdThenPlannerFilters() {
        val filters = TaskFilters(assignee = "me", label = "3", priority = "high", status = "in_review")
        assertEquals(
            "tasks?sprint_id=12&assignee=me&label=3&priority=high&status=in_review",
            sprintTasksQuery(12, filters),
        )
    }

    @Test
    fun backlogQueryNeverSendsStatusAndDropsUnknownValues() {
        val filters = TaskFilters(assignee = "", priority = "urgent", status = "done", search = "  ")
        assertEquals("tasks/backlog?limit=25&offset=50", backlogQuery(filters, 25, 50))
        assertEquals("tasks/search?q=release+plan", searchQuery(" release plan "))
    }

    @Test
    fun filterCountOnlyIncludesStatusOnTheSprintTab() {
        val filters = TaskFilters(assignee = "me", status = "done")
        assertEquals(1, filterCount(filters, TaskTab.Backlog))
        assertEquals(2, filterCount(filters, TaskTab.Sprint))
    }

    @Test
    fun formatsDueDatesLikeTheWeb() {
        assertEquals("Today", formatDueDate("2026-09-25", today))
        assertEquals("Tomorrow", formatDueDate("2026-09-26", today))
        assertEquals("Yesterday", formatDueDate("2026-09-24", today))
        assertEquals("3d overdue", formatDueDate("2026-09-22", today))
        assertEquals("7d left", formatDueDate("2026-10-02", today))
        assertEquals("Oct 20", formatDueDate("2026-10-20T00:00:00.000Z", today))
        assertNull(formatDueDate(null, today))
        assertTrue(isDueOverdue("2026-09-24", today))
        assertFalse(isDueOverdue("2026-09-25", today))
    }

    @Test
    fun relativeTimeBuckets() {
        val now = Instant.parse("2026-09-25T12:00:00Z")
        assertEquals("just now", formatRelativeTime("2026-09-25T11:59:40Z", now))
        assertEquals("5m ago", formatRelativeTime("2026-09-25T11:55:00Z", now))
        assertEquals("3h ago", formatRelativeTime("2026-09-25T09:00:00Z", now))
        assertEquals("2d ago", formatRelativeTime("2026-09-23T12:00:00Z", now))
        assertEquals("2w ago", formatRelativeTime("2026-09-10T12:00:00Z", now))
    }

    @Test
    fun sortsTheBacklogByEachWebOption() {
        fun t(id: Long, priority: String, created: String, due: String?, title: String) =
            Task(id = id, title = title, priority = priority, createdAt = created, dueDate = due)
        val list = listOf(
            t(1, "low", "2026-09-01T00:00:00Z", null, "banana"),
            t(2, "high", "2026-09-03T00:00:00Z", "2026-10-01", "Apple"),
            t(3, "medium", "2026-09-02T00:00:00Z", "2026-09-20", "cherry"),
        )
        assertEquals(listOf(2L, 3L, 1L), sortBacklog(list, BacklogSort.Priority).map { it.id })
        assertEquals(listOf(2L, 3L, 1L), sortBacklog(list, BacklogSort.Newest).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), sortBacklog(list, BacklogSort.Oldest).map { it.id })
        assertEquals(listOf(3L, 2L, 1L), sortBacklog(list, BacklogSort.DueDate).map { it.id })
        assertEquals(listOf(2L, 1L, 3L), sortBacklog(list, BacklogSort.Title).map { it.id })
    }

    @Test
    fun sprintHeaderAndSelection() {
        val active = AvailableSprint(id = 2, name = "S2", startDate = "2026-09-20", endDate = "2026-10-01", status = "active")
        val planned = AvailableSprint(id = 3, name = "S3", startDate = "2026-10-02", endDate = "2026-10-15")
        assertEquals("2026-09-20 → 2026-10-01 • 6d remaining", sprintSubtitle(active, today))
        assertEquals("2026-09-20 → 2026-10-01 • Paused", sprintSubtitle(active.copy(status = "paused"), today))
        assertEquals("Loading sprint…", sprintSubtitle(null, today))
        assertEquals(2L, pickSprint(null, listOf(planned, active)))
        assertEquals(3L, pickSprint(3, listOf(planned, active)))
        assertEquals(3L, pickSprint(99, listOf(planned)))
        assertTrue(canManageSprint("team_lead"))
        assertFalse(canManageSprint("employee"))
    }

    @Test
    fun kanbanMatchesByWorkflowStateIdOrLegacyStatusKey() {
        val review = WorkflowState(id = 7, key = "in_review", name = "In Review")
        val tasks = listOf(
            Task(id = 1, title = "a", status = "pending", workflowStateId = 7),
            Task(id = 2, title = "b", status = "in_review"),
            Task(id = 3, title = "c", status = "done", workflowStateId = 9),
        )
        assertEquals(listOf(1L, 2L), tasksForColumn(tasks, review).map { it.id })
        // The fallback workflow has id 0, so only status keys match.
        assertEquals(listOf(2L), tasksForColumn(tasks, review.copy(id = 0)).map { it.id })
    }

    @Test
    fun formatsStoryPointsWithoutTrailingZeros() {
        assertEquals("1", formatPoints(1.0))
        assertEquals("0.5", formatPoints(0.5))
        assertEquals("1.25", formatPoints(1.25))
        assertEquals("", formatPoints(null))
        assertTrue(samePoints("5", "5.0"))
        assertTrue(samePoints(null, ""))
        assertFalse(samePoints("S", "M"))
    }

    @Test
    fun htmlRoundTripsForDescriptionsAndMentions() {
        assertEquals("a<b>x", stripHtml("<p>a&lt;b&gt;x</p>"))
        assertEquals("<p>one</p><p><br></p><p>two &amp; 3</p>", plainTextToHtml("one\n\ntwo & 3\n"))
        assertEquals("", plainTextToHtml("   "))
        assertEquals(
            "<p>hi <span class=\"mention-chip\" data-user-id=\"5\" contenteditable=\"false\">@Vishnu V R</span> ok</p>",
            plainTextToHtml("hi @Vishnu V R ok", mapOf("Vishnu V R" to 5L)),
        )
    }

    @Test
    fun historyLinesMirrorTheModalCopy() {
        fun h(action: String, field: String? = null, old: String? = null, new: String? = null) =
            historyText(TaskHistoryEntry(id = 1, action = action, field = field, oldValue = old, newValue = new)).joinToString("") { it.second }
        assertEquals("created this task", h("created"))
        assertEquals("carried forward from 2026-09-24", h("created", "date", "2026-09-24"))
        assertEquals("changed status from pending → done", h("status_change", old = "pending", new = "done"))
        assertEquals("updated assignee: — → Vishnu", h("updated", "assigned_to", null, "Vishnu"))
        assertEquals("updated description", h("updated", "description", "a", "b"))
        assertEquals("moved to backlog", h("unscheduled"))
        assertEquals("⇔", historyIcon("status_change"))
    }

    @Test
    fun paginationSummaryAndComments() {
        assertEquals("26–50 of 60 tickets", paginationLabel(60, 25, 25, "ticket"))
        assertEquals("1–1 of 1 ticket", paginationLabel(1, 25, 0, "ticket"))
        assertNull(validateComment("Looks good"))
        assertEquals("Comment cannot be empty", validateComment("  "))
        assertEquals("Comment must be 2000 characters or less", validateComment("z".repeat(2001)))
    }

    @Test
    fun recomputesStatsAfterAnOptimisticMove() {
        val tasks = listOf(Task(1, "a", status = "done"), Task(2, "b", status = "in_progress"), Task(3, "c"), Task(4, "d", status = "done"))
        assertEquals(TaskStats(total = 4, done = 2, inProgress = 1, percent = 50), recomputeStats(tasks))
    }
}
