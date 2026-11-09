package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scrum surfaces stay available independently of sprint lifecycle.
 * Service Desk always renders.
 */
class PlanningSurfaceTest {
    private val active = AvailableSprint(id = 12, name = "Sprint #1", status = "active", teamId = 3)
    private val planned = AvailableSprint(id = 13, name = "Sprint #2", status = "planned", teamId = 3)

    @Test
    fun aStartedSprintOffersStableScrumTabs() {
        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.Sprint, TaskTab.Reports, TaskTab.ServiceDesk),
            planningTabs(agileEnabled = true, sprints = listOf(active, planned), currentSprintId = active.id),
        )
    }

    @Test
    fun aLegacyScheduledPreferenceIsNotSelectable() {
        val running = TaskUiState(planScope = PlanScope(agileEnabled = true, sprints = listOf(active), currentSprintId = active.id))
        assertNull(TaskTab.fromKey("scheduled"))
        assertEquals(TaskTab.Backlog, normalizeTab(TaskTab.fromKey("scheduled") ?: TaskTab.Backlog, running.visibleTabs))
    }

    @Test
    fun plannedAndMissingSprintsKeepTheSprintTab() {
        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.Sprint, TaskTab.Reports, TaskTab.ServiceDesk),
            planningTabs(agileEnabled = true, sprints = listOf(planned), currentSprintId = null),
        )
        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.Sprint, TaskTab.Reports, TaskTab.ServiceDesk),
            planningTabs(agileEnabled = true, sprints = emptyList(), currentSprintId = null),
        )
    }

    @Test
    fun agileOffNeverOffersSprintEvenWhenItIsRunning() {
        assertFalse(planningTabs(agileEnabled = false, sprints = listOf(active), currentSprintId = active.id).contains(TaskTab.Sprint))
    }

    @Test
    fun kanbanUsesItsOrderedServerTabsAndOnlyOffersTheEnabledBacklog() {
        val project = ProjectOption(
            id = 31,
            type = "kanban",
            planTabs = listOf("backlog", "board"),
            kanbanBacklogEnabled = true,
        )
        val scope = PlanScope(projects = listOf(project), projectScoped = true, selectedProjectId = project.id)

        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.KanbanBoard),
            planningTabs(scope, agileEnabled = false, sprints = listOf(active), currentSprintId = active.id),
        )
        assertEquals(listOf(TaskTab.Backlog, TaskTab.KanbanBoard), scope.visibleTabs)
        assertEquals(scope.visibleTabs, TaskShellUiState(planScope = scope).visibleTabs)
        assertEquals(scope.visibleTabs, TaskUiState(planScope = scope).visibleTabs)
        assertEquals(
            listOf(TaskTab.KanbanBoard),
            planningTabs(
                scope.copy(
                    projects = listOf(project.copy(planTabs = listOf("board"), kanbanBacklogEnabled = false)),
                ),
                agileEnabled = true,
                sprints = listOf(active),
                currentSprintId = active.id,
            ),
        )

        val taskList = scope.copy(
            projects = listOf(project.copy(type = "task_list", planTabs = listOf("list", "board", "calendar"))),
        )
        assertEquals(
            listOf(TaskTab.List, TaskTab.KanbanBoard, TaskTab.Upcoming),
            planningTabs(taskList, agileEnabled = false, sprints = emptyList(), currentSprintId = null),
        )
    }

    @Test
    fun switchingBetweenScrumAndKanbanKeepsEachProjectsServerTabs() {
        val scrum = ProjectOption(id = 3, planTabs = listOf("reports", "backlog", "sprint"))
        val kanban = ProjectOption(id = 4, type = "kanban", planTabs = listOf("board"))
        val scope = PlanScope(projects = listOf(scrum, kanban), projectScoped = true, selectedProjectId = scrum.id)

        assertEquals(listOf(TaskTab.Reports, TaskTab.Backlog, TaskTab.Sprint), scope.visibleTabs)
        val kanbanScope = scope.copy(selectedProjectId = kanban.id)
        assertEquals(listOf(TaskTab.KanbanBoard), kanbanScope.visibleTabs)
        assertEquals(TaskTab.KanbanBoard, normalizeTab(TaskTab.Sprint, kanbanScope.visibleTabs))
        assertFalse(TaskUiState(planScope = kanbanScope).sprintTabVisible)
        assertEquals(scope.visibleTabs, kanbanScope.copy(selectedProjectId = scrum.id).visibleTabs)
    }

    @Test
    fun theServerCurrentSprintIsTheBoardsDefaultEvenWhenPaused() {
        assertEquals(12L, pickSprint(null, listOf(planned, active), currentSprintId = 12L))
        val paused = active.copy(id = 14, status = "paused")
        assertEquals(14L, pickSprint(null, listOf(paused), currentSprintId = 14L))
        assertEquals(planned.id, pickSprint(null, listOf(planned), currentSprintId = null))
    }

    @Test
    fun landingIsTheBoardWhenTheTeamHasAnActiveSprint() {
        assertEquals(TaskTab.Sprint, landingTab(TaskTab.Backlog, listOf(active), currentSprintId = active.id, agileEnabled = true))
        val paused = active.copy(status = "paused")
        assertEquals(TaskTab.Sprint, landingTab(TaskTab.Backlog, listOf(paused), currentSprintId = paused.id, agileEnabled = true))
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, listOf(planned), currentSprintId = null, agileEnabled = true))
        // A tab the user chose is never overridden.
        assertEquals(TaskTab.Reports, landingTab(TaskTab.Reports, listOf(active), currentSprintId = active.id, agileEnabled = true))
    }

    @Test
    fun sprintInsightsPreferTheServerCurrentSprint() {
        val paused = active.copy(id = 14, status = "paused")
        val sprints = listOf(planned, paused)

        assertEquals(paused.id, initialInsightsSprint(sprints, requested = null, currentSprintId = paused.id))
        assertEquals(planned.id, initialInsightsSprint(sprints, requested = planned.id, currentSprintId = paused.id))
    }

    @Test
    fun onlySuperAdminAndAboveGetTheTeamPicker() {
        assertTrue(showsTeamPicker("super_admin"))
        assertTrue(showsTeamPicker("platform_admin"))
        assertFalse(showsTeamPicker("hr_admin"))
        assertFalse(showsTeamPicker("manager"))
        assertFalse(showsTeamPicker("employee"))
    }

    @Test
    fun theDefaultTeamIsTheRememberedOneThenTheUsersOwnThenTheFirst() {
        val teams = listOf(TeamOption(1, "Payments"), TeamOption(2, "Platform"))
        assertEquals(2L, defaultTeamId(remembered = 2, ownTeamId = 1, teams = teams))
        assertEquals(1L, defaultTeamId(remembered = null, ownTeamId = 1, teams = teams))
        assertEquals(1L, defaultTeamId(remembered = 99, ownTeamId = null, teams = teams))
        assertNull(defaultTeamId(remembered = null, ownTeamId = null, teams = emptyList()))
    }

    @Test
    fun thePlanSplitViewIsReachableFromBacklogForTheRunningSprintFirst() {
        // "Plan sprint" targets the running sprint, else the next planned one.
        assertEquals(active.id, planTargetId(listOf(planned, active), currentSprintId = active.id))
        assertEquals(planned.id, planTargetId(listOf(planned), currentSprintId = null))
        assertNull(planTargetId(emptyList(), currentSprintId = null))
    }

    @Test
    fun onlyTheSelectedTeamsSprintsDriveTheTabs() {
        val other = active.copy(id = 99, teamId = 7)
        // Without the picker the server already scoped the list, so it is used
        // as-is.
        assertEquals(listOf(active), sprintsForTeam(listOf(active), null, pickerVisible = false))
        // With it, the chosen team narrows the list.
        assertEquals(listOf(active), sprintsForTeam(listOf(active, other), 3, pickerVisible = true))
        // Unassigned has no sprints at all.
        assertTrue(sprintsForTeam(listOf(active), -1L, pickerVisible = true).isEmpty())
    }

    @Test
    fun theTeamScopeIsSentOnlyWhenAteamIsChosen() {
        assertEquals(TeamScope(teamId = 3), scopeForTeam(3))
        assertEquals(TeamScope(unassigned = true), scopeForTeam(-1))
        assertEquals(TeamScope(), scopeForTeam(null))
    }
}
