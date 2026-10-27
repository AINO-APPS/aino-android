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
    fun groupsTheBacklogByPriorityDroppingEmptyBands() {
        val list = listOf(
            Task(1, "a", priority = "low"),
            Task(2, "b", priority = "high"),
            Task(3, "c", priority = "urgent"), // unknown → Medium, like sortBacklog
            Task(4, "d", priority = "high"),
        )
        val groups = groupBacklog(sortBacklog(list, BacklogSort.Priority), BacklogSort.Priority, today)!!
        assertEquals(listOf("high", "medium", "low"), groups.map { it.key })
        assertEquals(listOf(2L, 4L), groups[0].tasks.map { it.id })
        assertEquals(listOf(3L), groups[1].tasks.map { it.id })
        assertEquals("High priority", groups[0].label)
        assertEquals(Tone.Danger, groups[0].tone)

        val onlyLow = groupBacklog(listOf(Task(9, "z", priority = "low")), BacklogSort.Priority, today)!!
        assertEquals(listOf("low"), onlyLow.map { it.key })
    }

    @Test
    fun groupsTheBacklogIntoDueDateBuckets() {
        val list = listOf(
            Task(1, "overdue", dueDate = "2026-09-20"),
            Task(2, "done late", dueDate = "2026-09-20", completedAt = "2026-09-21T00:00:00Z"),
            Task(3, "today", dueDate = "2026-09-25"),
            Task(4, "week", dueDate = "2026-10-02"),
            Task(5, "later", dueDate = "2026-10-03T00:00:00Z"),
            Task(6, "none"),
        )
        val groups = groupBacklog(sortBacklog(list, BacklogSort.DueDate), BacklogSort.DueDate, today)!!
        assertEquals(listOf("overdue", "today", "week", "later", "none", "past"), groups.map { it.key })
        assertEquals(listOf(1L), groups.first { it.key == "overdue" }.tasks.map { it.id })
        assertEquals(listOf(2L), groups.first { it.key == "past" }.tasks.map { it.id })
        assertEquals(listOf(6L), groups.first { it.key == "none" }.tasks.map { it.id })
        assertEquals(list.size, groups.sumOf { it.tasks.size })
    }

    @Test
    fun otherSortsStayFlat() {
        val list = listOf(Task(1, "a"), Task(2, "b", priority = "high"))
        assertNull(groupBacklog(list, BacklogSort.Newest, today))
        assertNull(groupBacklog(list, BacklogSort.Oldest, today))
        assertNull(groupBacklog(list, BacklogSort.Title, today))
        assertTrue(groupBacklog(emptyList(), BacklogSort.Priority, today)!!.isEmpty())
    }

    @Test
    fun sprintSelectionAndRoles() {
        val active = AvailableSprint(id = 2, name = "S2", startDate = "2026-09-20", endDate = "2026-10-01", status = "active")
        val planned = AvailableSprint(id = 3, name = "S3", startDate = "2026-10-02", endDate = "2026-10-15")
        assertEquals(2L, pickSprint(null, listOf(planned, active)))
        assertEquals(3L, pickSprint(3, listOf(planned, active)))
        assertEquals(3L, pickSprint(99, listOf(planned)))
        assertTrue(canManageSprint("team_lead"))
        assertFalse(canManageSprint("employee"))
    }

    @Test
    fun kanbanMatchesByWorkflowStateIdOrStateKey() {
        val review = WorkflowState(id = 7, key = "in_review", name = "In Review")
        val tasks = listOf(
            Task(id = 1, title = "a", workflowStateKey = "pending", workflowStateId = 7),
            Task(id = 2, title = "b", workflowStateKey = "in_review"),
            Task(id = 3, title = "c", workflowStateKey = "done", workflowStateId = 9),
        )
        assertEquals(listOf(1L, 2L), tasksForColumn(tasks, review).map { it.id })
        // The fallback workflow has id 0, so only state keys match.
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
        assertEquals("carried forward from 2026-09-24", h("created", "planned_date", "2026-09-24"))
        assertEquals("changed status from pending → done", h("status_change", old = "pending", new = "done"))
        assertEquals("updated assignee: — → Vishnu", h("updated", "assigned_to", null, "Vishnu"))
        assertEquals("updated description", h("updated", "description", "a", "b"))
        assertEquals("moved to backlog", h("unscheduled"))
        assertEquals("⇔", historyIcon("status_change"))
    }

    @Test
    fun commentValidation() {
        assertNull(validateComment("Looks good"))
        assertEquals("Comment cannot be empty", validateComment("  "))
        assertEquals("Comment must be 2000 characters or less", validateComment("z".repeat(2001)))
    }

    @Test
    fun recomputesStatsAfterAnOptimisticMove() {
        val tasks = listOf(
            Task(1, "a", completedAt = "2026-09-21T00:00:00Z"),
            Task(2, "b", workflowStateCategory = "in_progress"),
            Task(3, "c"),
            Task(4, "d", completedAt = "2026-09-22T00:00:00Z"),
        )
        assertEquals(TaskStats(total = 4, done = 2, inProgress = 1, percent = 50), recomputeStats(tasks))
    }

    @Test
        fun onlyAssigneeReporterOrgAdminOrOwningTeamCanChangeStatus() {
            val task = Task(1, "a", userId = 2, assignedTo = 3, teamId = 7)
        assertTrue(canChangeStatus(task, 2, "employee"))
        assertTrue(canChangeStatus(task, 3, "employee"))
        assertTrue(canChangeStatus(task, 9, "hr_admin"))
        assertFalse(canChangeStatus(task, 9, "manager"))
        assertFalse(canChangeStatus(task, null, "employee"))
            // Any member of the owning team may move it (server
            // `canChangeTaskStatus` with team ownership on).
            assertTrue(canChangeStatus(task, 9, "employee", teamId = 7))
            assertFalse(canChangeStatus(task, 9, "employee", teamId = 8))
            assertFalse(canChangeStatus(task, 9, "employee", teamId = null))
        }

        @Test
        fun aTicketsTeamDoesNotLeakThroughANullTeamId() {
            // A ticket with no owning team must not match a caller whose team is
            // also unknown — otherwise every unteamed user could move every row.
            val orphan = Task(1, "a", userId = 2, assignedTo = 3, teamId = null)
            assertFalse(canChangeStatus(orphan, 9, "employee", teamId = null))
        }

        @Test
        fun noTransitionRowsMeansTheWorkflowIsUnrestricted() {
            val config = AgileConfig()
            assertTrue(config.transitions.isEmpty())
            assertTrue(config.canTransition(1, 2))
            assertEquals(config.workflowStates.size, config.legalTargets(1).size)
        }

        @Test
        fun transitionsRestrictTheOfferedTargets() {
            val config = AgileConfig(
                workflowStates = listOf(
                    WorkflowState(1, "pending", "New", "open"),
                    WorkflowState(2, "in_progress", "In Progress", "in_progress"),
                    WorkflowState(3, "done", "Done", "done", isTerminal = true),
                ),
                transitions = listOf(WorkflowTransition(fromStateId = 1, toStateId = 2)),
            )
            assertTrue(config.canTransition(1, 2))
            assertFalse(config.canTransition(2, 1))
            // Staying put is always allowed, so the current state stays visible.
            assertTrue(config.canTransition(1, 1))
            // A missing endpoint cannot be evaluated, so it is not blocked.
            assertTrue(config.canTransition(null, 2))
            // The current state plus the one legal edge; state 3 is not offered.
            assertEquals(listOf(1L, 2L), config.legalTargets(1).map { it.id })
        }

        @Test
        fun fallbackStatesAreOfferedWhenTheyCarryNoId() {
            // Before `/agile/config` resolves every fallback state has `id: 0` and
            // so cannot be matched against the graph; none may disappear.
            val config = AgileConfig(
                transitions = listOf(WorkflowTransition(fromStateId = 1, toStateId = 2)),
            )
            assertEquals(config.workflowStates.size, config.legalTargets(1).size)
        }

    @Test
    fun newTicketsStartInNew() {
        assertEquals("New", columnOf("pending").label)
        assertEquals("New", FALLBACK_WORKFLOW_STATES.first { it.isInitial }.name)
    }

        @Test
        fun reorderNeighboursBracketTheMovedRow() {
            // Ranks 10,20,30,40 in order; ids are positions 1..4.
            val ids = listOf(1L, 2L, 3L, 4L)

            // Moving 3 up puts it between 1 and 2: before=1's rank, after=2's rank.
            // The old code sent (2, 1) — both above — which the server rejects
            // because before >= after.
            assertEquals(1L to 2L, rankNeighbours(ids, 3L, up = true))
            // Moving 2 down puts it between 3 and 4.
            assertEquals(3L to 4L, rankNeighbours(ids, 2L, up = false))
        }

        @Test
        fun reorderNeighboursHandleTheEndsOfTheList() {
            val ids = listOf(1L, 2L, 3L)

            // The first row cannot move up, the last cannot move down.
            assertNull(rankNeighbours(ids, 1L, up = true))
            assertNull(rankNeighbours(ids, 3L, up = false))

            // Moving to an end leaves one neighbour null, never two.
            assertEquals(null to 1L, rankNeighbours(ids, 2L, up = true))
            assertEquals(3L to null, rankNeighbours(ids, 2L, up = false))
        }

        @Test
        fun reorderNeighboursAlwaysEmitBeforeBeforeAfter() {
            // The server rejects `before >= after`; pin that the pair is ordered so
            // an inversion can never ship again. Ids ascend with rank here.
            val ids = listOf(10L, 20L, 30L, 40L)
            for (id in ids) {
                for (up in listOf(true, false)) {
                    val pair = rankNeighbours(ids, id, up) ?: continue
                    val (before, after) = pair
                    if (before != null && after != null) {
                        assertTrue("move $id ${if (up) "up" else "down"} emitted $before >= $after", before < after)
                    }
                }
            }
        }
}
