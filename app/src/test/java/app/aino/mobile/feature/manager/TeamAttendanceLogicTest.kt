package app.aino.mobile.feature.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Team Attendance roster filters, counts, date stepping and hours labels. */
class TeamAttendanceLogicTest {

    private val members = listOf(
        TeamAttendanceMember(1, "Ann", status = "working", hoursToday = 3.5),
        TeamAttendanceMember(2, "Bob", status = "away", floorMinutes = 120),
        TeamAttendanceMember(3, "Cy", status = "not_started"),
        TeamAttendanceMember(4, "Di", status = "on_leave", leaveType = "sick"),
        TeamAttendanceMember(5, "Ed", status = "working", hoursToday = 8.0),
    )

    @Test
    fun countsEveryFilterIncludingAll() {
        val counts = teamCounts(members)
        assertEquals(5, counts[TeamFilter.All])
        assertEquals(2, counts[TeamFilter.Working])
        assertEquals(1, counts[TeamFilter.Away])
        assertEquals(1, counts[TeamFilter.NotStarted])
        assertEquals(1, counts[TeamFilter.OnLeave])
    }

    @Test
    fun filtersByStatus() {
        assertEquals(listOf(1L, 5L), filterTeam(members, TeamFilter.Working).map { it.id })
        assertEquals(members, filterTeam(members, TeamFilter.All))
    }

    @Test
    fun dateStepperNeverGoesPastToday() {
        val today = LocalDate.of(2026, 10, 2)
        assertEquals("2026-10-01", shiftTeamDate("2026-10-02", -1, today))
        assertEquals("2026-10-02", shiftTeamDate("2026-10-02", 1, today))
        assertEquals("2026-09-30", shiftTeamDate("2026-09-29", 1, today))
        assertEquals("2026-10-01", shiftTeamDate("garbage", -1, today))
    }

    @Test
    fun hoursLabelPrefersServerHours() {
        assertEquals("3.5h", teamHoursLabel(members[0]))
        assertEquals("2h", teamHoursLabel(members[1]))
        assertNull(teamHoursLabel(members[2]))
        assertEquals("8h", teamHoursLabel(members[4]))
    }
}
