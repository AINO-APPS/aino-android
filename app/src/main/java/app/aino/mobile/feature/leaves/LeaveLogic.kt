package app.aino.mobile.feature.leaves

import java.time.LocalDate

enum class LeaveTab { Apply, History, Calendar }

/** The three durations the server accepts; anything else falls back to `full`. */
val LEAVE_DURATIONS = listOf("full", "half", "quarter")

fun durationDays(duration: String): Double = when (duration) {
    "half" -> 0.5
    "quarter" -> 0.25
    else -> 1.0
}

fun durationLabel(duration: String): String = when (duration) {
    "half" -> "Half day"
    "quarter" -> "Quarter day"
    else -> "Full day"
}

fun leaveStatusLabel(status: String): String = when (status) {
    "withdraw_pending" -> "Withdrawal pending"
    else -> status.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

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

/** Expands an inclusive start..end range into the date list the server expects. */
fun expandDateRange(start: String, end: String): List<String> {
    val from = runCatching { LocalDate.parse(start) }.getOrNull() ?: return emptyList()
    val to = runCatching { LocalDate.parse(end) }.getOrNull() ?: return emptyList()
    if (to < from) return emptyList()
    return generateSequence(from) { day -> day.plusDays(1).takeIf { it <= to } }
        .map(LocalDate::toString)
        .toList()
}

/**
 * Which self-service action the server will accept for a leave, so the UI never
 * offers one that is guaranteed to 400:
 * - pending: withdraw (deletes the row) or cancel via DELETE.
 * - approved: withdraw only, which raises a manager approval request.
 * - auto-booked public holidays: nothing; they are org-wide.
 */
enum class LeaveAction { None, Cancel, RequestWithdrawal }

fun availableLeaveAction(leave: Leave): LeaveAction {
    if (leave.leaveType == "holiday" && leave.reason?.startsWith("Public holiday:") == true) return LeaveAction.None
    return when (leave.status) {
        "pending" -> LeaveAction.Cancel
        "approved" -> LeaveAction.RequestWithdrawal
        else -> LeaveAction.None
    }
}

/** Total days a set of leaves consumes, using the server's duration weights. */
fun totalLeaveDays(leaves: List<Leave>): Double = leaves.sumOf { durationDays(it.duration) }

fun formatDays(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else "%.2f".format(value).trimEnd('0').trimEnd('.')
