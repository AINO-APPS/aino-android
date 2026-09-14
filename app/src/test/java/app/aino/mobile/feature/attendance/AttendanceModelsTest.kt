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

    @Test
    fun validatesManualEntryLikeTheServer() {
        val today = LocalDate.of(2026, 9, 14)
        assertEquals(null, validateManualEntry("2026-09-13", "09:00", "17:00", today))
        assertEquals("Cannot add a manual entry for a future date", validateManualEntry("2026-09-15", "09:00", "17:00", today))
        assertEquals("Logout time must be after login time", validateManualEntry("2026-09-13", "17:00", "09:00", today))
        assertEquals("Login time must use HH:MM", validateManualEntry("2026-09-13", "9am", "17:00", today))
    }

    @Test
    fun validatesOvertimeLikeTheServer() {
        assertEquals(null, validateOvertime("2026-09-14", "2.5", "Release support"))
        assertEquals("Hours must be between 0 and 24", validateOvertime("2026-09-14", "25", "Reason"))
        assertEquals("Reason is required", validateOvertime("2026-09-14", "2", ""))
    }

    @Test
    fun leaveAndHolidayPrecedenceMatchesLegacyCalendar() {
        val today = LocalDate.of(2026, 9, 14)
        val friday = LocalDate.of(2026, 9, 11)
        val approved = LeaveOverlay(1, friday.toString(), "casual", status = "approved")
        val pending = LeaveOverlay(2, friday.toString(), "sick", status = "pending")
        val holiday = HolidayOverlay(3, friday.toString(), "Foundation Day")
        val workDays = setOf(1, 2, 3, 4, 5)

        assertEquals(AttendanceDayKind.Present, attendanceKind(friday, today, AttendanceDay(friday.toString(), 240), workDays, 240, approved, holiday))
        assertEquals(AttendanceDayKind.Leave, attendanceKind(friday, today, null, workDays, 240, approved, holiday))
        assertEquals(AttendanceDayKind.LeavePending, attendanceKind(friday, today, null, workDays, 240, pending, holiday))
        assertEquals(AttendanceDayKind.Holiday, attendanceKind(friday, today, null, workDays, 240, null, holiday))
    }

    @Test
    fun buildsEditableDayAndPairsBreakRows() {
        fun atLocal(hour: Int, minute: Int = 0): String = LocalDate.of(2026, 9, 14)
            .atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toString()
        val day = editableDay(
            listOf(
                RawTimeEntry(entryType = "clock_in", timestamp = atLocal(9), workMode = "remote"),
                RawTimeEntry(entryType = "break_start", timestamp = atLocal(12)),
                RawTimeEntry(entryType = "break_end", timestamp = atLocal(12, 30)),
                RawTimeEntry(entryType = "clock_out", timestamp = atLocal(17)),
            ),
        )!!
        assertEquals("09:00", day.clockIn)
        assertEquals("17:00", day.clockOut)
        assertEquals(WorkMode.Remote, day.workMode)
        assertEquals(ManualBreakPayload("12:00", "12:30"), day.breaks.single())
    }

    @Test
    fun validatesBreakOrderingAndBounds() {
        val today = LocalDate.of(2026, 9, 14)
        assertEquals(
            null,
            validateManualEntry("2026-09-13", "09:00", "17:00", today, listOf(ManualBreakPayload("12:00", "12:30"))),
        )
        assertEquals(
            "Break times must not overlap",
            validateManualEntry(
                "2026-09-13",
                "09:00",
                "17:00",
                today,
                listOf(ManualBreakPayload("12:00", "13:00"), ManualBreakPayload("12:30", "13:30")),
            ),
        )
        assertEquals(
            "Break times must be within clock-in and clock-out times",
            validateManualEntry("2026-09-13", "09:00", "17:00", today, listOf(ManualBreakPayload("08:30", "09:30"))),
        )
    }
}