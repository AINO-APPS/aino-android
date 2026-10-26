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
        val ui = TaskUiState(agileEnabled = true, sprints = listOf(sprint))
        assertEquals(TaskTab.Backlog, ui.tab)
        assertEquals(listOf(TaskTab.Backlog, TaskTab.Scheduled, TaskTab.Sprint, TaskTab.ServiceDesk), ui.visibleTabs)
        assertTrue(ui.quickFilters.isAll)
        assertTrue(ui.compact)
        assertTrue(ui.completedExpanded)
        assertFalse(ui.copy(selectedIds = setOf(1)).completedExpanded)
        assertFalse(ui.copy(agileEnabled = false).visibleTabs.contains(TaskTab.Sprint))
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

    @Test fun projectionGroupsSprintAndBacklogWithRealCountsAndPoints() {
        val ui = TaskUiState(userId = 9, sprints = listOf(sprint), planTasks = rows, planLoaded = true).projectPlan()
        assertEquals(3, ui.backlogTotal)
        assertEquals(1, ui.completedCount)
        val groups = ui.backlogGroups!!
        assertEquals(listOf("Release", "Backlog"), groups.map { it.label })
        assertEquals(listOf(1, 2), groups.map { it.tasks.size })
        assertEquals(listOf(3.0, 5.0), groups.map { it.tasks.sumOf { task -> task.storyPoints ?: 0.0 } })
        val unscheduled = ui.copy(quickFilters = BacklogQuickFilters(unscheduled = true)).projectPlan()
        assertEquals(listOf(1L, 3L), unscheduled.backlog.map { it.id })
        assertEquals(1, unscheduled.completedCount)
    }

    @Test fun allQuickFiltersPreservesAdvancedPredicatesAndEpic() {
        val label = TaskLabel(7, "Mobile")
        val task = rows[0].copy(labels = listOf(label), parentTaskId = 22)
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
        assertEquals(2, moved.backlogGroups!!.first().tasks.size)
        // Completion is canonical server data, hydrated after workflow_state_id deltas.
        val patched = moved.copy(planTasks = moved.planTasks.map { if (it.id == 2L) it.copy(completedAt = "2026-10-26") else it }).projectPlan()
        assertEquals(2, patched.completedCount)
        assertEquals(2, patched.backlogTotal)
        val cleared = patched.copy(quickFilters = BacklogQuickFilters()).projectPlan()
        assertEquals(2, cleared.completedCount)
        assertEquals(2, cleared.backlogTotal)
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
                else -> """{"tasks":[{"id":3,"title":"Sprint work","sprint_id":12}]}"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val all = repository.loadPlanTasks(listOf(sprint))
        assertEquals(listOf(1L, 2L, 3L), all.map { it.id })
        assertEquals(3, requests.size)
        assertTrue(requests[0].contains("include_completed=1"))
        assertTrue(requests[1].contains("offset=1"))
        assertEquals("tasks?sprint_id=12", requests.last())
        assertEquals(listOf(2L, 3L), all.filter {
            matchesPlanFilters(it, TaskFilters(), BacklogQuickFilters(unscheduled = true), null)
        }.map { it.id })
    }

    @Test fun repositoryHydratesSprintOverflowInsteadOfSilentlyCappingAt500() {
        val requests = mutableListOf<String>()
        val first500 = (1..500).joinToString(",") { """{"id":$it,"title":"Ticket $it","sprint_id":12}""" }
        val repository = TaskRepository(ApiClient { request ->
            requests += request.path
            val body = when {
                request.path.startsWith("tasks/backlog") -> """{"tasks":[]}"""
                request.path == "tasks?sprint_id=12" -> """{"tasks":[$first500]}"""
                request.path == "sprints/12/tasks" -> """{"tasks":[$first500,{"id":501,"title":"Overflow","sprint_id":12}]}"""
                else -> """{"id":501,"title":"Overflow","sprint_id":12,"assignee":{"full_name":"Ada"}}"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })
        val tasks = repository.loadPlanTasks(listOf(sprint))
        assertEquals(501, tasks.size)
        assertEquals("Ada", tasks.last().assignee?.display())
        assertEquals("tasks/501/detail", requests.last())
    }

    @Test fun quickChipsDoNotNarrowSprintPlanningOrImport() {
        val ui = TaskUiState(
            planLoaded = true, planTasks = rows, sprints = listOf(sprint),
            quickFilters = BacklogQuickFilters(unscheduled = true),
        ).projectPlan()
        assertEquals(listOf(2L, 3L), ui.importable.map { it.id })
    }
}
