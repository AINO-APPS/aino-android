package app.aino.mobile.feature.attendance

import app.aino.mobile.core.common.TimerAnchor
import app.aino.mobile.core.common.TrackerStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/**
 * Pure presentation logic for the mobile-first Attendance page (approved
 * deviation from web parity, Attendance only). Kept free of Compose so it is
 * unit-tested directly.
 */

// ---------------------------------------------------------------------------
// Today hero card
// ---------------------------------------------------------------------------

enum class TodayPhase(val label: String) {
    NotStarted("Not started"),
    Working("Working"),
    OnBreak("On break"),
    Done("Done"),
}

data class TodaySnapshot(
    val phase: TodayPhase,
    val workedSeconds: Long,
    val breakSeconds: Long,
    val targetMinutes: Int,
    val workMode: String,
    /** Logged out and the daily target is not met yet (same rule as the dashboard timer). */
    val canClockIn: Boolean,
    val targetMet: Boolean,
) {
    val workedMinutes: Int get() = (workedSeconds / 60).toInt()
    val progress: Float get() = (workedSeconds / (targetMinutes * 60f)).coerceIn(0f, 1f)
    val remainingMinutes: Int get() = (targetMinutes - workedMinutes).coerceAtLeast(0)
    val overtimeMinutes: Int get() = (workedMinutes - targetMinutes).coerceAtLeast(0)
}

/**
 * Live view of today's status: server seconds (whole minutes on older
 * servers) plus the seconds elapsed since the status was received, added to
 * whichever bucket is running.
 */
fun todaySnapshot(status: TrackerStatus?, elapsedSeconds: Long): TodaySnapshot {
    val state = status?.state ?: "logged_out"
    val elapsed = elapsedSeconds.coerceAtLeast(0)
    val floor = (status?.exactFloorSeconds ?: 0L) + if (state == "on_floor") elapsed else 0L
    val breaks = (status?.exactBreakSeconds ?: 0L) + if (state == "on_break") elapsed else 0L
    return buildTodaySnapshot(status, floor, breaks)
}

/**
 * Same, counted from the ViewModel's [timer] anchor (which smooths refreshes).
 * Falls back to the status itself until the anchor has caught up with its state.
 */
fun todaySnapshot(status: TrackerStatus?, timer: TimerAnchor?, nowEpochMs: Long): TodaySnapshot {
    val anchor = timer?.takeIf { status != null && it.state == status.state }
        ?: status?.let { TimerAnchor.of(it, nowEpochMs = nowEpochMs) }
        ?: return buildTodaySnapshot(null, 0L, 0L)
    val (floor, breaks) = anchor.at(nowEpochMs)
    return buildTodaySnapshot(status, floor, breaks)
}

private fun buildTodaySnapshot(status: TrackerStatus?, floor: Long, breaks: Long): TodaySnapshot {
    val state = status?.state ?: "logged_out"
    val targetMet = status?.dailyTargetMet == true
    val phase = when (state) {
        "on_floor" -> TodayPhase.Working
        "on_break" -> TodayPhase.OnBreak
        else -> if (targetMet || (status?.exactFloorSeconds ?: 0L) > 0 || status?.entries?.isNotEmpty() == true) {
            TodayPhase.Done
        } else {
            TodayPhase.NotStarted
        }
    }
    return TodaySnapshot(
        phase = phase,
        workedSeconds = floor,
        breakSeconds = breaks,
        targetMinutes = (status?.targetMinutes ?: 480).coerceAtLeast(1),
        workMode = status?.workMode ?: "office",
        canClockIn = state == "logged_out" && !targetMet,
        targetMet = targetMet,
    )
}

/** "7h 05m" — compact hours for chips and tiles. */
fun hoursLabel(minutes: Int): String {
    val abs = kotlin.math.abs(minutes)
    return if (abs < 60) "${abs}m" else "%dh %02dm".format(abs / 60, abs % 60)
}

// ---------------------------------------------------------------------------
// Overview calendar
// ---------------------------------------------------------------------------

data class MonthSummary(
    val present: Int = 0,
    val absent: Int = 0,
    val leave: Int = 0,
    val leavePending: Int = 0,
    val holiday: Int = 0,
    val weekend: Int = 0,
    val workedMinutes: Int = 0,
)

/** Counts each day of [month] by its calendar kind and totals the hours worked in that month. */
fun monthSummary(
    month: YearMonth,
    today: LocalDate,
    history: Map<LocalDate, AttendanceDay>,
    leaves: Map<LocalDate, LeaveOverlay>,
    holidays: Map<LocalDate, HolidayOverlay>,
    workDays: Set<Int>,
    minimumMinutes: Int,
): MonthSummary {
    val kinds = (1..month.lengthOfMonth()).map(month::atDay)
        .groupingBy { attendanceKind(it, today, history[it], workDays, minimumMinutes, leaves[it], holidays[it]) }
        .eachCount()
    return MonthSummary(
        present = kinds[AttendanceDayKind.Present] ?: 0,
        absent = kinds[AttendanceDayKind.Absent] ?: 0,
        leave = kinds[AttendanceDayKind.Leave] ?: 0,
        leavePending = kinds[AttendanceDayKind.LeavePending] ?: 0,
        holiday = kinds[AttendanceDayKind.Holiday] ?: 0,
        weekend = kinds[AttendanceDayKind.Weekend] ?: 0,
        workedMinutes = history.filterKeys { YearMonth.from(it) == month }.values.sumOf { it.floorMinutes.coerceAtLeast(0) },
    )
}

/** Minutes worked that count a day as present (`min_hours_present`, else the workday length). */
fun presentThresholdMinutes(policy: AttendancePolicy?): Int =
    ((policy?.minHoursPresent ?: policy?.workHoursPerDay ?: 8.0) * 60).toInt()

data class DayTimeline(
    val clockIn: LocalTime? = null,
    val clockOut: LocalTime? = null,
    val breaks: List<Pair<LocalTime?, LocalTime?>> = emptyList(),
    val sessions: Int = 0,
    val workMode: String? = null,
    val hasManual: Boolean = false,
    val pendingApproval: Boolean = false,
    /** The last clock entry is a clock-in: the session is still running. */
    val stillOpen: Boolean = false,
) {
    val isEmpty: Boolean get() = clockIn == null && clockOut == null && breaks.isEmpty()
}

/** Day sheet summary of `tracker/entries/{date}`: first in, last out, paired breaks. */
fun dayTimeline(entries: List<RawTimeEntry>): DayTimeline {
    if (entries.isEmpty()) return DayTimeline()
    val sorted = entries.sortedBy { it.timestamp }
    val breaks = mutableListOf<Pair<LocalTime?, LocalTime?>>()
    var open: LocalTime? = null
    var hasOpen = false
    sorted.forEach { entry ->
        when (entry.entryType) {
            "break_start" -> {
                if (hasOpen) breaks += open to null
                open = entryLocalTime(entry.timestamp)
                hasOpen = true
            }
            "break_end" -> if (hasOpen) {
                breaks += open to entryLocalTime(entry.timestamp)
                hasOpen = false
            }
        }
    }
    if (hasOpen) breaks += open to null
    val firstIn = sorted.firstOrNull { it.entryType == "clock_in" }
    return DayTimeline(
        clockIn = firstIn?.let { entryLocalTime(it.timestamp) },
        clockOut = sorted.lastOrNull { it.entryType == "clock_out" }?.let { entryLocalTime(it.timestamp) },
        breaks = breaks,
        sessions = sorted.count { it.entryType == "clock_in" },
        workMode = firstIn?.workMode,
        hasManual = sorted.any { it.isManual },
        pendingApproval = sorted.any { it.approvalStatus == "pending" },
        stillOpen = sorted.lastOrNull { it.entryType == "clock_in" || it.entryType == "clock_out" }?.entryType == "clock_in",
    )
}

// ---------------------------------------------------------------------------
// Leaves
// ---------------------------------------------------------------------------

/** Personal leave history (public holidays excluded) narrowed by the status/type chips ("all" = any). */
fun filterLeaves(leaves: List<LeaveOverlay>, status: String, type: String): List<LeaveOverlay> =
    leaves.filterNot(::isPublicHolidayLeave)
        .filter { (status == "all" || it.status == status) && (type == "all" || it.leaveType == type) }
        .sortedByDescending { it.date }

// ---------------------------------------------------------------------------
// Requests (manual entry + overtime)
// ---------------------------------------------------------------------------

enum class RequestKind(val label: String) { Manual("Manual entry"), Overtime("Overtime") }

/** A manual-entry request that changes a day which already had attendance. */
fun isEditRequest(request: ManualEntryRequest): Boolean =
    request.metadata?.edit == true || request.reason?.contains("edit", ignoreCase = true) == true

data class AttendanceRequestItem(
    val key: String,
    val kind: RequestKind,
    val status: String,
    val date: String?,
    val detail: String,
    val reason: String? = null,
    val rejectReason: String? = null,
    val approverName: String? = null,
    val createdAt: String? = null,
    /** Type chip text: "Correction" for edits of a recorded day, else the kind's label. */
    val typeLabel: String = kind.label,
) {
    internal val sortKey: String get() = createdAt?.takeIf(String::isNotBlank) ?: date.orEmpty()
    val isPending: Boolean get() = status == "pending"
}

/** One list of both request types, newest first (submitted time, else the requested date). */
fun mergeRequests(manual: List<ManualEntryRequest>, overtime: List<OvertimeRequest>): List<AttendanceRequestItem> {
    val manualItems = manual.map { request ->
        val meta = request.metadata
        AttendanceRequestItem(
            key = "manual-${request.requestId}",
            kind = RequestKind.Manual,
            status = request.approvalStatus,
            date = meta?.date,
            detail = listOfNotNull(
                meta?.clockIn?.let { "In $it" },
                meta?.clockOut?.let { "Out $it" } ?: meta?.clockIn?.let { "no clock-out" },
                meta?.workMode?.replaceFirstChar(Char::uppercase),
            ).joinToString(" · "),
            rejectReason = request.rejectReason,
            approverName = request.approverName,
            createdAt = request.createdAt,
            typeLabel = if (isEditRequest(request)) "Correction" else RequestKind.Manual.label,
        )
    }
    val overtimeItems = overtime.map { request ->
        AttendanceRequestItem(
            key = "overtime-${request.id}",
            kind = RequestKind.Overtime,
            status = request.status,
            date = request.metadata?.date,
            detail = request.metadata?.hours?.let { "${formatDays(it)}h overtime" }.orEmpty(),
            reason = request.reason,
            rejectReason = request.rejectReason,
            approverName = request.approverName,
            createdAt = request.createdAt,
        )
    }
    return (manualItems + overtimeItems).sortedByDescending { it.sortKey }
}

// ---------------------------------------------------------------------------
// Insights
// ---------------------------------------------------------------------------

data class InsightsKpis(
    val workedMinutes: Int,
    val breakMinutes: Int,
    val avgPerDayMinutes: Int,
    val overtimeMinutes: Int,
    val presentDays: Int,
    val targetMetDays: Int,
    val officeDays: Int,
    val remoteDays: Int,
)

/** KPI tiles from the analytics series; a day counts as worked when any floor time was logged. */
fun insightsKpis(data: List<AttendanceDay>, targetMinutes: Int): InsightsKpis {
    val target = targetMinutes.coerceAtLeast(1)
    val worked = data.filter { it.floorMinutes > 0 }
    val total = worked.sumOf { it.floorMinutes }
    return InsightsKpis(
        workedMinutes = total,
        breakMinutes = data.sumOf { it.breakMinutes.coerceAtLeast(0) },
        avgPerDayMinutes = if (worked.isEmpty()) 0 else total / worked.size,
        overtimeMinutes = worked.sumOf { (it.floorMinutes - target).coerceAtLeast(0) },
        presentDays = worked.size,
        targetMetDays = worked.count { it.floorMinutes >= target },
        officeDays = worked.count { it.workMode != "remote" },
        remoteDays = worked.count { it.workMode == "remote" },
    )
}

/** Insights period presets (days); null is the custom range. */
val INSIGHTS_PRESETS: List<Int?> = listOf(7, 30, 90, null)
