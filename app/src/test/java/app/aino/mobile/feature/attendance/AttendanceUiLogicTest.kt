package app.aino.mobile.feature.attendance

import app.aino.mobile.core.common.TrackerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Mobile-first Attendance page logic: hero, pager tabs, calendar summary, requests, insights. */
class AttendanceUiLogicTest {

    // ---- Pager tabs <-> deep-link hashes ----

    @Test
    fun pagerIndexAndHashRoundTripForEveryTab() {
        AttendanceTab.entries.forEach { tab ->
            assertEquals(tab, AttendanceTab.fromPage(tab.page))
            assertEquals(tab, AttendanceTab.fromHash(tab.hash))
        }
        assertEquals(listOf(0, 1, 2, 3), AttendanceTab.entries.map { it.page })
        assertEquals(AttendanceTab.Overview, AttendanceTab.fromPage(9))
        assertEquals(AttendanceTab.Overview, AttendanceTab.fromPage(-1))
    }

    @Test
    fun renamedTabsKeepTheirWebHashes() {
        assertEquals(listOf("Overview", "Leaves", "Requests", "Insights"), AttendanceTab.entries.map { it.label })
        assertEquals(2, AttendanceTab.fromHash("#manual-entry").page)
        assertEquals(3, AttendanceTab.fromHash("analytics").page)
        assertEquals(1, AttendanceTab.fromHash("leaves").page)
    }

    // ---- Today hero ----

    @Test
    fun heroAddsElapsedSecondsToTheRunningBucket() {
        val working = todaySnapshot(TrackerStatus(state = "on_floor", floorMinutes = 120, breakMinutes = 10, targetMinutes = 480), 90)
        assertEquals(TodayPhase.Working, working.phase)
        assertEquals(120 * 60L + 90, working.workedSeconds)
        assertEquals(600L, working.breakSeconds)
        assertEquals(359, working.remainingMinutes)
        assertEquals(0.25f, working.progress, 0.01f)
        assertFalse(working.canClockIn)

        val onBreak = todaySnapshot(TrackerStatus(state = "on_break", floorMinutes = 120, breakMinutes = 10), 60)
        assertEquals(TodayPhase.OnBreak, onBreak.phase)
        assertEquals(7_200L, onBreak.workedSeconds)
        assertEquals(660L, onBreak.breakSeconds)
    }

    @Test
    fun heroCountsFromExactServerSecondsAndTheViewModelAnchor() {
        // Refreshed at 2h 00m 42s: the hero must not snap back to 2h 00m 00s.
        val status = TrackerStatus(state = "on_floor", floorMinutes = 120, floorSeconds = 7_242, breakMinutes = 10, breakSeconds = 615, receivedAtEpochMs = 50_000)
        assertEquals(7_242L + 3, todaySnapshot(status, 3).workedSeconds)

        val fromStatus = todaySnapshot(status, timer = null, nowEpochMs = 53_000)
        assertEquals(7_245L, fromStatus.workedSeconds)
        assertEquals(615L, fromStatus.breakSeconds)

        // A smoothed (older) anchor keeps the on-screen count.
        val kept = app.aino.mobile.core.common.TimerAnchor(7_240, 615, "on_floor", atEpochMs = 47_000)
        assertEquals(7_246L, todaySnapshot(status, kept, 53_000).workedSeconds)

        // An anchor that has not caught up with a new state is ignored.
        val onBreak = status.copy(state = "on_break")
        val hero = todaySnapshot(onBreak, kept, 53_000)
        assertEquals(TodayPhase.OnBreak, hero.phase)
        assertEquals(7_242L, hero.workedSeconds)
        assertEquals(618L, hero.breakSeconds)
    }

    @Test
    fun heroPhasesWhenLoggedOut() {
        val fresh = todaySnapshot(null, 500)
        assertEquals(TodayPhase.NotStarted, fresh.phase)
        assertEquals(0L, fresh.workedSeconds)
        assertTrue(fresh.canClockIn)

        val done = todaySnapshot(TrackerStatus(state = "logged_out", floorMinutes = 300), 500)
        assertEquals(TodayPhase.Done, done.phase)
        assertEquals(18_000L, done.workedSeconds)
        assertTrue("target not met: may clock in again", done.canClockIn)

        val met = todaySnapshot(TrackerStatus(state = "logged_out", floorMinutes = 540, dailyTargetMet = true, targetMinutes = 480), 0)
        assertFalse(met.canClockIn)
        assertTrue(met.targetMet)
        assertEquals(60, met.overtimeMinutes)
        assertEquals(1f, met.progress)
    }

    @Test
    fun hoursLabelIsCompact() {
        assertEquals("45m", hoursLabel(45))
        assertEquals("7h 05m", hoursLabel(425))
        assertEquals("8h 00m", hoursLabel(480))
    }

    // ---- Overview month summary ----

    @Test
    fun monthSummaryCountsKindsAndTotalsHoursForTheMonthOnly() {
        val month = YearMonth.of(2026, 9) // Sep 1 2026 is a Tuesday
        val today = LocalDate.of(2026, 9, 10)
        val history = mapOf(
            LocalDate.of(2026, 9, 1) to AttendanceDay("2026-09-01", floorMinutes = 500),
            LocalDate.of(2026, 9, 2) to AttendanceDay("2026-09-02", floorMinutes = 200),
            LocalDate.of(2026, 8, 31) to AttendanceDay("2026-08-31", floorMinutes = 480), // previous month, visible in grid
        )
        val leaves = mapOf(
            LocalDate.of(2026, 9, 3) to LeaveOverlay(1, "2026-09-03", "casual", status = "approved"),
            LocalDate.of(2026, 9, 4) to LeaveOverlay(2, "2026-09-04", "sick", status = "pending"),
        )
        val holidays = mapOf(LocalDate.of(2026, 9, 7) to HolidayOverlay(1, "2026-09-07", "Festival"))
        val summary = monthSummary(month, today, history, leaves, holidays, setOf(1, 2, 3, 4, 5), 480)

        assertEquals(1, summary.present)
        assertEquals(1, summary.leave)
        assertEquals(1, summary.leavePending)
        assertEquals(1, summary.holiday)
        assertEquals(8, summary.weekend) // Sep 2026: 4 Saturdays + 4 Sundays
        // Workdays Sep 1..9 minus present(1), leave(3,4), holiday(7): 2, 8, 9 absent; 10 is today (in progress).
        assertEquals(3, summary.absent)
        assertEquals(700, summary.workedMinutes)
    }

    @Test
    fun presentThresholdPrefersMinHoursPresent() {
        assertEquals(240, presentThresholdMinutes(AttendancePolicy(minHoursPresent = 4.0)))
        assertEquals(450, presentThresholdMinutes(AttendancePolicy(workHoursPerDay = 7.5)))
        assertEquals(480, presentThresholdMinutes(null))
    }

    // ---- Day detail timeline ----

    @Test
    fun dayTimelinePairsBreaksAndFindsFirstInLastOut() {
        val entries = listOf(
            RawTimeEntry(entryType = "clock_out", timestamp = "2026-09-01T17:30:00Z"),
            RawTimeEntry(entryType = "clock_in", timestamp = "2026-09-01T09:00:00Z", workMode = "remote"),
            RawTimeEntry(entryType = "break_start", timestamp = "2026-09-01T12:00:00Z"),
            RawTimeEntry(entryType = "break_end", timestamp = "2026-09-01T12:30:00Z", isManual = true, approvalStatus = "pending"),
            RawTimeEntry(entryType = "break_start", timestamp = "2026-09-01T15:00:00Z"),
        )
        val timeline = dayTimeline(entries)
        assertEquals(local("2026-09-01T09:00:00Z"), timeline.clockIn)
        assertEquals(local("2026-09-01T17:30:00Z"), timeline.clockOut)
        assertEquals(2, timeline.breaks.size)
        assertEquals(local("2026-09-01T12:00:00Z") to local("2026-09-01T12:30:00Z"), timeline.breaks[0])
        assertNull(timeline.breaks[1].second)
        assertEquals("remote", timeline.workMode)
        assertEquals(1, timeline.sessions)
        assertTrue(timeline.hasManual)
        assertTrue(timeline.pendingApproval)
        assertFalse(timeline.stillOpen)
    }

    @Test
    fun dayTimelineFlagsAnOpenSessionAndEmptyDays() {
        val open = dayTimeline(
            listOf(
                RawTimeEntry(entryType = "clock_in", timestamp = "2026-09-01T09:00:00Z"),
                RawTimeEntry(entryType = "clock_out", timestamp = "2026-09-01T12:00:00Z"),
                RawTimeEntry(entryType = "clock_in", timestamp = "2026-09-01T13:00:00Z"),
            ),
        )
        assertTrue(open.stillOpen)
        assertEquals(2, open.sessions)
        assertTrue(dayTimeline(emptyList()).isEmpty)
    }

    // ---- Leaves ----

    @Test
    fun leaveFiltersExcludeHolidaysAndSortNewestFirst() {
        val leaves = listOf(
            LeaveOverlay(1, "2026-09-02", "casual", status = "approved"),
            LeaveOverlay(2, "2026-09-09", "sick", status = "pending"),
            LeaveOverlay(3, "2026-09-05", "holiday", status = "approved", reason = "Public holiday: Festival"),
            LeaveOverlay(4, "2026-09-12", "casual", status = "pending"),
        )
        assertEquals(listOf(4L, 2L, 1L), filterLeaves(leaves, "all", "all").map { it.id })
        assertEquals(listOf(4L, 2L), filterLeaves(leaves, "pending", "all").map { it.id })
        assertEquals(listOf(4L), filterLeaves(leaves, "pending", "casual").map { it.id })
    }

    // ---- Requests ----

    @Test
    fun mergedRequestsAreNewestFirstAcrossBothTypes() {
        val manual = listOf(
            ManualEntryRequest(10, "approved", ManualEntryMetadata("2026-09-01", "09:00", "18:00", "office"), createdAt = "2026-09-02T08:00:00Z"),
            ManualEntryRequest(11, "pending", ManualEntryMetadata("2026-09-05", "10:00", null), createdAt = "2026-09-06T08:00:00Z"),
        )
        val overtime = listOf(
            OvertimeRequest(20, "rejected", "Release", OvertimeMetadata("2026-09-03", 2.5), createdAt = "2026-09-04T08:00:00Z", rejectReason = "No"),
            OvertimeRequest(21, "pending", null, OvertimeMetadata("2026-09-08", 1.0), createdAt = null),
        )
        val merged = mergeRequests(manual, overtime)
        // Missing createdAt falls back to the requested date.
        assertEquals(listOf("overtime-21", "manual-11", "overtime-20", "manual-10"), merged.map { it.key })
        assertEquals(RequestKind.Overtime, merged[0].kind)
        assertEquals("In 10:00 · no clock-out", merged[1].detail)
        assertEquals("In 09:00 · Out 18:00 · Office", merged[3].detail)
        assertEquals("2.5h overtime", merged[2].detail)
        assertEquals("No", merged[2].rejectReason)
        assertEquals("Release", merged[2].reason)
    }

    @Test
    fun editRequestsAreLabelledCorrectionAndPending() {
        val manual = listOf(
            ManualEntryRequest(12, "pending", ManualEntryMetadata("2026-09-09", "09:00", "18:00", "office", edit = true)),
            ManualEntryRequest(13, "pending", ManualEntryMetadata("2026-09-10", "09:00"), reason = "Manual time entry (edit request)"),
            ManualEntryRequest(14, "approved", ManualEntryMetadata("2026-09-11", "09:00"), reason = "Manual time entry"),
        )
        val items = mergeRequests(manual, emptyList()).associateBy { it.key }
        assertEquals("Correction", items.getValue("manual-12").typeLabel)
        assertEquals("Correction", items.getValue("manual-13").typeLabel)
        assertEquals("Manual entry", items.getValue("manual-14").typeLabel)
        assertTrue(items.getValue("manual-12").isPending)
        assertEquals("Pending", leaveStatusMeta(items.getValue("manual-12").status).label)
        assertFalse(items.getValue("manual-14").isPending)
        // Corrections still filter under the manual-entry kind.
        assertEquals(RequestKind.Manual, items.getValue("manual-12").kind)
    }

    @Test
    fun existingDayShowsTheApprovalNoteAndUsesPut() {
        assertEquals(EXISTING_DAY_APPROVAL_NOTE, manualEntryNote(hasExistingEntries = true))
        assertTrue(manualEntryNote(true)!!.contains("sent for approval"))
        assertFalse(manualEntryNote(true)!!.contains("Delete", ignoreCase = true))
        assertNull(manualEntryNote(hasExistingEntries = false))
        assertEquals("PUT", manualEntryMethod(hasExistingEntries = true))
        assertEquals("POST", manualEntryMethod(hasExistingEntries = false))
        val loaded = editableDay(listOf(RawTimeEntry(1, "clock_in", "2026-09-14T09:00:00Z", "office")))
        assertEquals("PUT", manualEntryMethod(loaded!!.hasExistingEntries))
    }

    @Test
    fun manualEntrySuccessUsesTheServerMessage() {
        assertEquals(
            "Your edit was submitted for manager approval.",
            manualEntrySuccessMessage(AttendanceMutationResponse("Your edit was submitted for manager approval.", "pending", true)),
        )
        assertEquals("Submitted for approval", manualEntrySuccessMessage(AttendanceMutationResponse("")))
        assertEquals("Submitted for approval", manualEntrySuccessMessage(null))
    }

    // ---- Insights ----

    @Test
    fun insightsKpisOnlyCountWorkedDays() {
        val data = listOf(
            AttendanceDay("2026-09-01", floorMinutes = 540, breakMinutes = 30, workMode = "office"),
            AttendanceDay("2026-09-02", floorMinutes = 420, breakMinutes = 45, workMode = "remote"),
            AttendanceDay("2026-09-03", floorMinutes = 0, breakMinutes = 0),
        )
        val kpis = insightsKpis(data, 480)
        assertEquals(960, kpis.workedMinutes)
        assertEquals(75, kpis.breakMinutes)
        assertEquals(480, kpis.avgPerDayMinutes)
        assertEquals(60, kpis.overtimeMinutes)
        assertEquals(2, kpis.presentDays)
        assertEquals(1, kpis.targetMetDays)
        assertEquals(1, kpis.officeDays)
        assertEquals(1, kpis.remoteDays)
        assertEquals(0, insightsKpis(emptyList(), 480).avgPerDayMinutes)
        assertEquals(listOf(7, 30, 90, null), INSIGHTS_PRESETS)
    }

    private fun local(ts: String) = Instant.parse(ts).atZone(ZoneId.systemDefault()).toLocalTime()
}
