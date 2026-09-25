package app.aino.mobile.feature.attendance

import java.time.LocalDate

/** The three durations the server accepts; anything else falls back to `full`. */
val LEAVE_DURATIONS = listOf("full", "half", "quarter")

fun durationDays(duration: String): Double = when (duration) {
    "half" -> 0.5
    "quarter" -> 0.25
    else -> 1.0
}

fun durationLabel(duration: String): String = when (duration) {
    "half" -> "Half Day"
    "quarter" -> "Quarter Day"
    else -> "Full Day"
}

/**
 * A leave row is treated as an auto-booked public holiday when it is a
 * Holiday-type leave whose reason starts with the marker written by the server
 * (`Public holiday: <name>`). These are company-wide closures and do not
 * appear in the personal leave history (`LeaveHistory.tsx`).
 */
fun isPublicHolidayLeave(leave: LeaveOverlay): Boolean =
    leave.leaveType == "holiday" && leave.reason?.startsWith("Public holiday:") == true

/**
 * The server's own rules, mirrored so an invalid application never leaves the
 * device: a leave type is required, at least one valid YYYY-MM-DD date, at most
 * 60 dates in one request, and a reason of at most 500 characters. Half and
 * quarter days are additionally gated by the org policy.
 */
fun validateLeaveApplication(
    leaveType: String,
    dates: List<String>,
    duration: String,
    reason: String,
    policy: LeavePolicy?,
    hasAnyPolicy: Boolean,
): String? {
    if (leaveType.isBlank()) return "Leave type required"
    if (dates.isEmpty()) return "Date(s) required"
    if (dates.size > 60) return "Cannot apply for more than 60 days at once"
    if (dates.any { !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(it) }) return "No valid dates provided"
    if (reason.length > 500) return "Reason must be 500 characters or less"
    // The server rejects an uncovered type only when the org configured policies
    // at all; an org with none still accepts any type.
    if (policy == null && hasAnyPolicy) return "'$leaveType' leave is not allowed by your organization's policy"
    if (duration == "half" && policy != null && !policy.halfDayAllowed) {
        return "Half-day leave is not allowed for this leave type"
    }
    if (duration == "quarter" && policy != null && !policy.quarterDayAllowed) {
        return "Quarter-day leave is not allowed for this leave type"
    }
    return null
}

/**
 * Expands an inclusive start..end range into the date list the server expects
 * (`getDateRange` port), optionally skipping Saturday/Sunday.
 */
fun expandDateRange(start: String, end: String, skipWeekends: Boolean = false): List<String> {
    val from = runCatching { LocalDate.parse(start) }.getOrNull() ?: return emptyList()
    val to = runCatching { LocalDate.parse(end) }.getOrNull() ?: return emptyList()
    if (to < from) return emptyList()
    return generateSequence(from) { day -> day.plusDays(1).takeIf { it <= to } }
        .filter { !skipWeekends || (it.dayOfWeek.value != 6 && it.dayOfWeek.value != 7) }
        .map(LocalDate::toString)
        .toList()
}

/** Total days a set of leaves consumes, using the server's duration weights. */
fun totalLeaveDays(leaves: List<LeaveOverlay>): Double = leaves.sumOf { durationDays(it.duration) }

fun formatDays(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')

/** Readable status label ("withdraw_pending" → "Withdrawal pending"). */
fun leaveStatusLabel(status: String): String =
    LEAVE_STATUS_CONFIG[status]?.label
        ?: status.replace('_', ' ').replaceFirstChar(Char::uppercase)

/**
 * The web offers Withdraw for pending and approved leaves only; auto-booked
 * public holidays are org-wide and locked (`LeaveHistory.tsx` canWithdraw).
 */
fun canWithdrawLeave(leave: LeaveOverlay): Boolean =
    !isPublicHolidayLeave(leave) && (leave.status == "pending" || leave.status == "approved")
