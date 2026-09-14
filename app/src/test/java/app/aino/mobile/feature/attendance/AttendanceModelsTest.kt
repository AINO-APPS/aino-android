package app.aino.mobile.feature.attendance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.time.YearMonth

class AttendanceModelsTest {
    private val policy = AttendancePolicy(
        verificationEnabled = true,
        officeLatitude = 10.0,
        officeLongitude = 20.0,
        officeRadiusMeters = 150.0,
    )

    @Test
    fun officeFingerprintRequiresAccurateInsideGeofenceLocation() {
        assertTrue(canUseFingerprintFallback(policy, WorkMode.Office, LocationProof(10.0, 20.0, 25f)))
        assertFalse(canUseFingerprintFallback(policy, WorkMode.Office, LocationProof(11.0, 20.0, 25f)))
        assertFalse(canUseFingerprintFallback(policy, WorkMode.Office, LocationProof(10.0, 20.0, 500f)))
        assertFalse(canUseFingerprintFallback(policy, WorkMode.Remote, LocationProof(10.0, 20.0, 25f)))
    }

    @Test
    fun remoteClockOutDoesNotRequireOfficeVerification() {
        assertFalse(requiresAttendanceVerification(policy, AttendanceAction.ClockOut, WorkMode.Remote, "remote"))
        assertTrue(requiresAttendanceVerification(policy, AttendanceAction.ClockIn, WorkMode.Remote, null))
        assertTrue(requiresAttendanceVerification(policy, AttendanceAction.ClockOut, WorkMode.Office, "office"))
    }

    @Test
    fun disabledPolicyNeedsNoLocationOrIdentityGate() {
        val disabled = AttendancePolicy(verificationEnabled = false)
        assertFalse(requiresLocation(disabled, WorkMode.Office))
        assertFalse(requiresAttendanceVerification(disabled, AttendanceAction.ClockIn, WorkMode.Office, null))
    }

    @Test
    fun monthGridStartsSundayAndAlwaysContainsSixWeeks() {
        val grid = monthGrid(YearMonth.of(2026, 9))
        assertEquals(42, grid.size)
        assertEquals(LocalDate.of(2026, 8, 30), grid.first())
        assertEquals(LocalDate.of(2026, 10, 10), grid.last())
    }

    @Test
    fun classifiesPresentWeekendTodayFutureAndAbsent() {
        val today = LocalDate.of(2026, 9, 14)
        val workDays = setOf(1, 2, 3, 4, 5)
        assertEquals(AttendanceDayKind.Present, attendanceKind(today.minusDays(3), today, AttendanceDay("2026-09-11", 240), workDays, 240))
        assertEquals(AttendanceDayKind.Weekend, attendanceKind(LocalDate.of(2026, 9, 13), today, null, workDays, 240))
        assertEquals(AttendanceDayKind.InProgress, attendanceKind(today, today, null, workDays, 240))
        assertEquals(AttendanceDayKind.Future, attendanceKind(today.plusDays(1), today, null, workDays, 240))
        assertEquals(AttendanceDayKind.Absent, attendanceKind(today.minusDays(4), today, null, workDays, 240))
    }
}