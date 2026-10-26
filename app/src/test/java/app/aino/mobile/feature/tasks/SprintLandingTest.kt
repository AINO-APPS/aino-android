package app.aino.mobile.feature.tasks

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Landing on Tasks opens the sprint that is actually running.
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
    fun anActiveSprintMakesTheSprintTabTheLanding() {
        assertEquals(TaskTab.Sprint, landingTab(TaskTab.Backlog, listOf(active, planned), agileEnabled = true))
    }

    @Test
    fun noActiveSprintKeepsTheBacklog() {
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, listOf(planned), agileEnabled = true))
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, emptyList(), agileEnabled = true))
    }

    @Test
    fun agileDisabledNeverLandsOnTheSprint() {
        assertEquals(TaskTab.Backlog, landingTab(TaskTab.Backlog, listOf(active), agileEnabled = false))
    }

    @Test
    fun anExplicitTabIsNeverOverridden() {
        assertEquals(TaskTab.Scheduled, landingTab(TaskTab.Scheduled, listOf(active), agileEnabled = true))
        assertEquals(TaskTab.ServiceDesk, landingTab(TaskTab.ServiceDesk, listOf(active), agileEnabled = true))
    }
}
