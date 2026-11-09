package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Landing on Tasks opens the sprint named current by the server.
 *
 * Once a team's sprint is started, the sprint is where the work is; the backlog
 * is only the pool of unplanned tickets. The rule is narrow: it only applies
 * while the user is still on the default Backlog tab, so a tab they chose is
 * never overridden.
 */
class SprintLandingTest {
    private val active = AvailableSprint(id = 1, name = "Sprint #1", status = "active")
    private val planned = AvailableSprint(id = 2, name = "Sprint #2", status = "planned")

    @Test
    fun aCurrentSprintMakesTheSprintTabTheLanding() {
        assertEquals(TaskTab.Sprint, landingTab(TaskTab.Backlog, listOf(active, planned), active.id, agileEnabled = true))
    }

    @Test
    fun aPausedCurrentSprintStillMakesTheSprintTabTheLanding() {
        val paused = active.copy(status = "paused")
        assertEquals(TaskTab.Sprint, landingTab(TaskTab.Backlog, listOf(paused), paused.id, agileEnabled = true))
    }

    @Test
    fun noCurrentSprintKeepsTheBacklog() {
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, listOf(planned), null, agileEnabled = true))
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, emptyList(), null, agileEnabled = true))
    }

    @Test
    fun agileDisabledNeverLandsOnTheSprint() {
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, listOf(active), active.id, agileEnabled = false))
    }

    @Test
    fun anExplicitTabIsNeverOverridden() {
        assertEquals(TaskTab.Reports, landingTab(TaskTab.Reports, listOf(active), active.id, agileEnabled = true))
        assertEquals(TaskTab.ServiceDesk, landingTab(TaskTab.ServiceDesk, listOf(active), active.id, agileEnabled = true))
    }
}
