package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The Android Tasks surfaces follow the same rules as the web (ADR-015).
 *
 * Tabs are decided by the *selected team's* sprint state — a running sprint
 * leads with the board, and Backlog and Scheduled sit beside it. With no active
 * sprint there is no board, so Sprint is hidden. Service Desk always renders.
 */
class PlanningSurfaceTest {
    /** Only the dates matter for the Scheduled rules. */
    private fun task(
        id: Long,
        plannedDate: String? = null,
        dueDate: String? = null,
        completedAt: String? = null,
    ): Task = Task(id, "Ticket $id", plannedDate = plannedDate, dueDate = dueDate, completedAt = completedAt)

    private val active = AvailableSprint(id = 12, name = "Sprint #1", status = "active", teamId = 3)
    private val planned = AvailableSprint(id = 13, name = "Sprint #2", status = "planned", teamId = 3)

    @Test
    fun anActiveSprintOffersSprintBacklogAndScheduled() {
        assertEquals(
            listOf(TaskTab.Sprint, TaskTab.Backlog, TaskTab.Scheduled, TaskTab.ServiceDesk),
            planningTabs(agileEnabled = true, sprints = listOf(active, planned)),
        )
    }

    @Test
    fun onlyAPlannedSprintHidesTheSprintTab() {
        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.Scheduled, TaskTab.ServiceDesk),
            planningTabs(agileEnabled = true, sprints = listOf(planned)),
        )
        assertEquals(
            listOf(TaskTab.Backlog, TaskTab.Scheduled, TaskTab.ServiceDesk),
            planningTabs(agileEnabled = true, sprints = emptyList()),
        )
    }

    @Test
    fun agileOffNeverOffersSprintEvenWhenItIsRunning() {
        assertFalse(planningTabs(agileEnabled = false, sprints = listOf(active)).contains(TaskTab.Sprint))
    }

    @Test
    fun theActiveSprintIdIsTheBoardsDefault() {
        assertEquals(12L, activeSprintId(listOf(planned, active)))
        assertNull(activeSprintId(listOf(planned)))
    }

    @Test
    fun landingIsTheBoardWhenTheTeamHasAnActiveSprint() {
        assertEquals(TaskTab.Sprint, landingTab(TaskTab.Backlog, listOf(active), agileEnabled = true))
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, listOf(planned), agileEnabled = true))
        // A tab the user chose is never overridden.
        assertEquals(TaskTab.Scheduled, landingTab(TaskTab.Scheduled, listOf(active), agileEnabled = true))
    }

    @Test
    fun aScheduledTicketSitsOnItsPlannedDateThenItsDueDate() {
        assertEquals(LocalDate.of(2026, 10, 9), scheduleDateOf(task(1, plannedDate = "2026-10-09T00:00:00Z", dueDate = "2026-10-20")))
        assertEquals(LocalDate.of(2026, 10, 20), scheduleDateOf(task(2, dueDate = "2026-10-20")))
        assertNull(scheduleDateOf(task(3)))
    }

    @Test
    fun scheduledGroupsByDueDateWhenThereIsNoPlannedDate() {
        val today = LocalDate.of(2026, 10, 6)
        val groups = groupScheduled(
            listOf(
                task(1, dueDate = "2026-10-07"),
                task(2, dueDate = "2026-10-01"),
                task(3, plannedDate = "2026-10-07", dueDate = "2026-10-20"),
            ),
            today,
        )
        assertEquals(listOf("Overdue", "Tomorrow"), groups.map { it.label })
        assertEquals(listOf(2L), groups[0].tasks.map { it.id })
        assertEquals(listOf(1L, 3L), groups[1].tasks.map { it.id }.sorted())
    }

    @Test
    fun ticketsBeyondTheHorizonAreKeptUnderLater() {
        val today = LocalDate.of(2026, 10, 6)
        val groups = groupScheduled(
            listOf(task(1, plannedDate = "2026-11-04"), task(2, plannedDate = "2026-11-06"), task(3, dueDate = "2027-01-15")),
            today,
        )
        assertEquals(listOf("2026-11-04", "later"), groups.map { it.key })
        assertEquals("Later", groups[1].label)
        assertNull(groups[1].date)
        assertEquals(listOf(2L, 3L), groups[1].tasks.map { it.id })
    }

    @Test
    fun aTicketFinishedWithinTheWindowKeepsItsPastDay() {
        val today = LocalDate.of(2026, 10, 6)
        val groups = groupScheduled(
            listOf(
                task(1, plannedDate = "2026-10-02", completedAt = "2026-10-02T09:00:00Z"),
                task(2, plannedDate = "2026-10-02"),
                task(3, plannedDate = "2026-09-01", completedAt = "2026-09-01T09:00:00Z"),
            ),
            today,
        )
        assertEquals(listOf("overdue", "2026-10-02"), groups.map { it.key })
        assertEquals(listOf(2L), groups[0].tasks.map { it.id })
        assertEquals(listOf(1L), groups[1].tasks.map { it.id })
    }

    @Test
    fun theScheduledQueryTargetsTheScheduledEndpointWithTeamScope() {
        assertEquals(
            "tasks/scheduled?priority=high&team_id=3",
            scheduledQuery(TaskFilters(priority = "high"), teamScope = TeamScope(teamId = 3)),
        )
        assertEquals("tasks/scheduled", scheduledQuery(TaskFilters(), teamScope = TeamScope()))
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
        assertEquals(active.id, planTargetId(listOf(planned, active)))
        assertEquals(planned.id, planTargetId(listOf(planned)))
        assertNull(planTargetId(emptyList()))
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
