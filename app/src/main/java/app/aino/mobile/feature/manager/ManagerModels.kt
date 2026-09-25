package app.aino.mobile.feature.manager

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Wire models (bare JSON, see server/routes/manager.ts) ───────────────────

/**
 * `manager` `request_metadata` — the free-form `metadata` JSON blob attached
 * to an `approval_requests` row. Which fields are populated depends on
 * [ApprovalRow.type] (`leave`, `leave_withdraw`, `manual_entry`, `overtime`);
 * see `RequestDetails.tsx`.
 */
@Serializable
data class RequestMetadata(
    @SerialName("leave_type") val leaveType: String? = null,
    val date: String? = null,
    val duration: String? = null,
    @SerialName("previous_status") val previousStatus: String? = null,
    @SerialName("clock_in") val clockIn: String? = null,
    @SerialName("clock_out") val clockOut: String? = null,
    @SerialName("work_mode") val workMode: String? = null,
    val hours: String? = null,
    val edit: Boolean? = null,
    val breaks: List<BreakWindow>? = null,
)

@Serializable
data class BreakWindow(val start: String? = null, val end: String? = null)

/**
 * A row from `GET /manager/approvals`, `/manager/my-requests` and
 * `/manager/member/:userId/requests` — all three project the same
 * `approval_requests` shape with different joined name columns.
 */
@Serializable
data class ApprovalRow(
    val id: Long,
    val type: String? = null,
    val status: String? = null,
    @SerialName("requester_id") val requesterId: Long? = null,
    @SerialName("approver_id") val approverId: Long? = null,
    @SerialName("reference_id") val referenceId: Long? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("requester_name") val requesterName: String? = null,
    @SerialName("requester_avatar") val requesterAvatar: String? = null,
    @SerialName("approver_name") val approverName: String? = null,
    val metadata: RequestMetadata? = null,
)

/** `POST /manager/approvals/:id/approve|reject` — `{ message }`. */
@Serializable
data class ManagerMessageResponse(val message: String = "")

/** `POST /manager/approvals/bulk` — `{ message, processed, skipped }`. */
@Serializable
data class BulkApprovalResponse(val message: String = "", val processed: Int = 0, val skipped: Int = 0)

/** A row of `GET /manager/team-attendance`. */
@Serializable
data class TeamAttendanceMember(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    val status: String = "not_started",
    val state: String? = null,
    @SerialName("hours_today") val hoursToday: Double? = null,
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val workMode: String? = null,
    val clockInTime: String? = null,
    @SerialName("current_task") val currentTask: String? = null,
    @SerialName("leave_type") val leaveType: String? = null,
)

/** A member row inside `GET /manager/team-analytics`'s `members` array. */
@Serializable
data class AnalyticsMember(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    val hours: Double = 0.0,
    val totalFloorMinutes: Int = 0,
    val avgFloorMinutes: Int = 0,
    val avgBreakMinutes: Int = 0,
    val daysWorked: Int = 0,
    val targetMetDays: Int = 0,
    val targetMetPercent: Int = 0,
    val punctualityPercent: Int = 0,
    val officeDays: Int = 0,
    val remoteDays: Int = 0,
    val tasksDone: Int = 0,
    val tasksTotal: Int = 0,
    val taskCompletionRate: Int = 0,
    val leaves: Int = 0,
    val leavesByType: Map<String, Int> = emptyMap(),
    val todayStatus: String? = null,
    val todayHoursMin: Int = 0,
    val trend: List<Int> = emptyList(),
    val streak: Int = 0,
)

/** `GET /manager/team-analytics`. */
@Serializable
data class TeamAnalyticsResponse(
    val totalMembers: Int = 0,
    val avgHours: Double = 0.0,
    val avgBreakMinutes: Int = 0,
    val totalTasksDone: Int = 0,
    val totalLeaves: Int = 0,
    val pendingApprovals: Int = 0,
    val expectedHours: Int = 0,
    val expectedWeekdays: Int = 0,
    val targetMinutes: Int = 480,
    val avgPunctuality: Int = 0,
    val avgTargetMet: Int = 0,
    val trendDates: List<String> = emptyList(),
    val members: List<AnalyticsMember> = emptyList(),
)

/** A row of `GET /manager/member/:userId/hours`. */
@Serializable
data class MemberHourRow(
    val date: String = "",
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val workMode: String? = null,
)

/** A row of `GET /manager/member/:userId/leaves` (bare `leaves` table + `approved_by_name`). */
@Serializable
data class MemberLeaveRow(
    val id: Long,
    @SerialName("user_id") val userId: Long? = null,
    val date: String = "",
    @SerialName("leave_type") val leaveType: String? = null,
    val duration: String? = null,
    val status: String? = null,
    val reason: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("approved_by_name") val approvedByName: String? = null,
)

/** A row of `GET /manager/member/:userId/tasks` (bare `tasks` table). */
@Serializable
data class MemberTaskRow(
    val id: Long,
    val title: String? = null,
    val status: String? = null,
    val priority: String? = null,
    val date: String? = null,
)

@Serializable
data class MemberUser(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
)

@Serializable
data class WeeklyTrendDay(
    val date: String = "",
    val dayLabel: String = "",
    val floorMinutes: Int = 0,
    val breakMinutes: Int = 0,
    val workMode: String? = null,
)

@Serializable
data class Stats30d(
    val daysWorked: Int = 0,
    val totalFloorMinutes: Int = 0,
    val avgFloorMinutes: Int = 0,
    val avgBreakMinutes: Int = 0,
    val targetMetDays: Int = 0,
    val targetMetPercent: Int = 0,
    val punctualityPercent: Int = 0,
)

@Serializable
data class MonthTaskStats(
    val total: Int = 0,
    val done: Int = 0,
    val inProgress: Int = 0,
    val completionRate: Int = 0,
)

@Serializable
data class LeaveBalance(
    @SerialName("leave_type") val leaveType: String? = null,
    @SerialName("policy_name") val policyName: String? = null,
    val color: String? = null,
    val quota: Double = 0.0,
    @SerialName("carried_forward") val carriedForward: Double = 0.0,
    val used: Double = 0.0,
    @SerialName("total_days") val totalDays: Double = 0.0,
    val remaining: Double = 0.0,
)

/** `GET /manager/member/:userId/overview`. */
@Serializable
data class MemberOverviewResponse(
    val user: MemberUser,
    val todayHours: Double = 0.0,
    val todayBreakMin: Int = 0,
    val todayTasks: List<MemberTaskRow> = emptyList(),
    val pendingRequests: Int = 0,
    val monthLeaves: Int = 0,
    val recentLeaves: List<MemberLeaveRow> = emptyList(),
    val recentRequests: List<ApprovalRow> = emptyList(),
    val weeklyTrend: List<WeeklyTrendDay> = emptyList(),
    val stats30d: Stats30d = Stats30d(),
    val monthTaskStats: MonthTaskStats = MonthTaskStats(),
    val leaveBalances: List<LeaveBalance> = emptyList(),
)
