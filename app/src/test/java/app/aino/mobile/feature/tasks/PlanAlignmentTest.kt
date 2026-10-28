package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PlanAlignmentTest {
    private val sprint = AvailableSprint(id = 12, name = "Release", status = "active")
    private val rows = listOf(
        Task(1, "Assigned", assignedTo = 9, priority = "high", sprintId = 12, storyPoints = 3.0),
        Task(2, "Created", userId = 9, priority = "high", plannedDate = "2026-11-01", storyPoints = 5.0),
        Task(3, "Someone else's", userId = 9, assignedTo = 8, priority = "low"),
        Task(4, "Done", assignedTo = 9, priority = "high", completedAt = "2026-10-25"),
    )

    @Test fun planDefaultsToBacklogCompactAndExpandedCompleted() {
        val ui = TaskUiState(agileEnabled = true)
        assertEquals(TaskTab.Backlog, ui.tab)
        // No running sprint: no board, so no Sprint tab.
        assertEquals(listOf(TaskTab.Backlog, TaskTab.Scheduled, TaskTab.ServiceDesk), ui.visibleTabs)
        assertTrue(ui.quickFilters.isAll)
        assertTrue(ui.compact)
        assertTrue(ui.completedExpanded)
        assertFalse(ui.copy(selectedIds = setOf(1)).completedExpanded)
        assertFalse(ui.copy(agileEnabled = false).visibleTabs.contains(TaskTab.Sprint))
    }

    @Test fun tabSetFollowsTheSelectedTeamsSprintState() {
        // A started sprint is the working surface: the board leads and Backlog
        // and Scheduled sit beside it (ADR-015).
        assertEquals(
            listOf(TaskTab.Sprint, TaskTab.Backlog, TaskTab.Scheduled, TaskTab.ServiceDesk),
            TaskUiState(agileEnabled = true, sprints = listOf(sprint)).visibleTabs,
        )
        // Only a planned sprint is not a board, so Sprint is hidden.
        val planned = sprint.copy(id = 13, status = "planned")
        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.Scheduled, TaskTab.ServiceDesk),
            TaskUiState(agileEnabled = true, sprints = listOf(planned)).visibleTabs,
        )
        // Agile off loses the Sprint tab whatever is running.
        assertFalse(TaskUiState(agileEnabled = false, sprints = listOf(sprint)).visibleTabs.contains(TaskTab.Sprint))
        // The sprint config alone is not enough: there must be a running sprint.
        val configured = TaskUiState(agileEnabled = true, sprintEnabled = true, sprints = emptyList())
        assertFalse(configured.visibleTabs.contains(TaskTab.Sprint))
    }

    @Test fun aHiddenTabIsNeverSelectable() {
        val running = TaskUiState(agileEnabled = true, sprints = listOf(sprint))
        assertEquals(TaskTab.Sprint, normalizeTab(TaskTab.Sprint, running.visibleTabs))
        assertEquals(TaskTab.Scheduled, normalizeTab(TaskTab.Scheduled, running.visibleTabs))
        val idle = TaskUiState(agileEnabled = true, sprints = emptyList())
        assertEquals(TaskTab.Backlog, normalizeTab(TaskTab.Sprint, idle.visibleTabs))
        assertEquals(TaskTab.Scheduled, normalizeTab(TaskTab.Scheduled, idle.visibleTabs))
        // Service Desk is a support queue and outlives both rules.
        assertEquals(TaskTab.ServiceDesk, normalizeTab(TaskTab.ServiceDesk, idle.visibleTabs))
    }
    @Test fun everyQuickFilterCombinationUsesAndSemantics() {
        for (mine in listOf(false, true)) for (high in listOf(false, true)) for (unscheduled in listOf(false, true)) {
            val filters = BacklogQuickFilters(mine, high, unscheduled)
            val actual = rows.filter { matchesPlanFilters(it, TaskFilters(), filters, 9) }.map { it.id }
            val expected = rows.filter {
                (!mine || it.id in listOf(1L, 2L, 4L)) &&
                    (!high || it.priority == "high") &&
                    (!unscheduled || it.plannedDate == null)
            }.map { it.id }
            assertEquals("$filters", expected, actual)
        }
        assertFalse(matchesPlanFilters(rows[0], TaskFilters(), BacklogQuickFilters(mine = true), null))
    }

    @Test fun projectionExcludesSprintWorkFromTheBacklog() {
        val ui = TaskUiState(userId = 9, sprints = listOf(sprint), planTasks = rows, planLoaded = true).projectPlan()
        // Task 1 carries sprint_id 12, which is open: it belongs on the board only.
        assertEquals(2, ui.backlogTotal)
        assertEquals(listOf(2L, 3L), ui.backlog.map { it.id })
        assertEquals(1, ui.completedCount)
        assertNull(ui.backlogGroups)
        // Task 2 carries a planned date, so the unscheduled chip drops it too.
        val unscheduled = ui.copy(quickFilters = BacklogQuickFilters(unscheduled = true)).projectPlan()
        assertEquals(listOf(3L), unscheduled.backlog.map { it.id })
        assertEquals(1, unscheduled.completedCount)
    }

    @Test fun aStrandedSprintTicketStaysVisibleInItsOwnGroup() {
        // The task's sprint is not in the open list (deleted, or completed and
        // dropped from available-sprints), so the ticket must not vanish.
        val stranded = listOf(Task(7, "Orphan", sprintId = 99))
        val ui = TaskUiState(
            userId = 9, sprints = listOf(sprint), planTasks = stranded, planLoaded = true,
        ).projectPlan()
        assertEquals(listOf(7L), ui.backlog.map { it.id })
        val groups = ui.backlogGroups!!
        assertEquals(1, groups.size)
        assertEquals(listOf(7L), groups.single().tasks.map { it.id })
        assertTrue(groups.single().label.startsWith("Sprint 99"))
    }

    @Test fun strandedGroupDoesNotHideTheOrdinaryBacklogRows() {
        // Regression: the list used to render `backlogGroups` *instead of* the
        // backlog, so one stranded ticket removed every ordinary row.
        val mixed = listOf(
            Task(1, "Normal", plannedDate = "2026-11-01"),
            Task(2, "Also normal"),
            Task(7, "Orphan", sprintId = 99),
        )
        val ui = TaskUiState(
            userId = 9, sprints = listOf(sprint), planTasks = mixed, planLoaded = true,
        ).projectPlan()
        assertEquals(listOf(1L, 2L, 7L), ui.sortedBacklog.map { it.id })
        val stranded = ui.backlogGroups.orEmpty()
        val grouped = stranded.flatMap { group -> group.tasks.map { it.id } }.toSet()
        assertEquals(setOf(7L), grouped)
        // What the list renders: ordinary rows first, then the stranded group.
        assertEquals(
            listOf(1L, 2L, 7L),
            ui.sortedBacklog.filterNot { it.id in grouped }.map { it.id } + stranded.flatMap { g -> g.tasks.map { it.id } },
        )
    }

    @Test fun allQuickFiltersPreservesAdvancedPredicatesAndEpic() {
        val label = TaskLabel(7, "Mobile")
        // Backlog work: rows[0] otherwise carries sprint 12, which is open.
        val task = rows[0].copy(labels = listOf(label), parentTaskId = 22, sprintId = null)
        val advanced = TaskFilters(assignee = "9", label = "7", priority = "high", search = "Assigned")
        val before = TaskUiState(
            userId = 9, sprints = listOf(sprint), planTasks = listOf(task), planLoaded = true,
            filters = advanced, selectedEpicId = 22, quickFilters = BacklogQuickFilters(true, true, true),
        ).projectPlan()
        val reset = before.copy(quickFilters = BacklogQuickFilters()).projectPlan()
        assertEquals(advanced, reset.filters)
        assertEquals(22L, reset.selectedEpicId)
        assertEquals(listOf(task), reset.backlog)
        assertTrue(reset.copy(filters = advanced.copy(priority = "low")).projectPlan().backlog.isEmpty())
    }

    @Test fun realtimeProjectionMovesHiddenRowsBetweenGroupsAndCompletion() {
        val base = TaskUiState(
            sprints = listOf(sprint), planTasks = rows, planLoaded = true,
            quickFilters = BacklogQuickFilters(unscheduled = true),
        ).projectPlan()
        val delta = parseTaskDelta(Json.parseToJsonElement(
            """{"taskId":2,"fields":{"planned_date":null,"sprint_id":12}}""",
        ))!!
        val moved = base.copy(planTasks = base.planTasks.map { if (it.id == 2L) applyTaskDelta(it, delta) else it }).projectPlan()
        // Task 2 moved into an open sprint, so it leaves the backlog entirely.
        assertEquals(listOf(3L), moved.backlog.map { it.id })
        assertNull(moved.backlogGroups)
        // Completion is canonical server data, hydrated after workflow_state_id deltas.
        val patched = moved.copy(planTasks = moved.planTasks.map { if (it.id == 2L) it.copy(completedAt = "2026-10-26") else it }).projectPlan()
        // Task 2 is completed but still inside open sprint 12, so it is not backlog work either way.
        assertEquals(1, patched.completedCount)
        assertEquals(1, patched.backlogTotal)
        val cleared = patched.copy(quickFilters = BacklogQuickFilters()).projectPlan()
        assertFalse(cleared.backlog.any { it.id == 2L })
        assertTrue(cleared.completed.any { it.id == 4L })
    }

    @Test fun repositoryDrainsAllServerPagesBeforePlanFiltering() {
        val requests = mutableListOf<String>()
        val repository = TaskRepository(ApiClient { request ->
            requests += request.path
            val body = when {
                request.path.startsWith("tasks/backlog") && request.path.contains("offset=0") ->
                    """{"tasks":[{"id":1,"title":"Dated","planned_date":"2026-11-01"}],"pagination":{"total":2,"hasMore":true}}"""
                request.path.startsWith("tasks/backlog") ->
                    """{"tasks":[{"id":2,"title":"Unscheduled","priority":"high"}],"pagination":{"total":2,"hasMore":false}}"""
                else -> """{"tasks":[]}"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val all = repository.loadPlanTasks()
        assertEquals(listOf(1L, 2L), all.map { it.id })
        // The backlog is the only source: no sprint request is made.
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.startsWith("tasks/backlog") })
        assertTrue(requests[0].contains("include_completed=1"))
        assertTrue(requests[1].contains("offset=1"))
        assertEquals(listOf(2L), all.filter {
            matchesPlanFilters(it, TaskFilters(), BacklogQuickFilters(unscheduled = true), null)
        }.map { it.id })
    }

    @Test fun quickChipsDoNotNarrowSprintPlanningOrImport() {
        val ui = TaskUiState(
            planLoaded = true, planTasks = rows, sprints = listOf(sprint),
            quickFilters = BacklogQuickFilters(unscheduled = true),
        ).projectPlan()
        // Sprint work is never offered for import, and the unscheduled chip does
        // not narrow the pool further.
        assertEquals(listOf(2L, 3L), ui.importable.map { it.id })
    }
}
