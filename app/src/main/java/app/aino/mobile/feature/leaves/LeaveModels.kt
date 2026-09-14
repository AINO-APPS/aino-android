package app.aino.mobile.feature.leaves

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Leave shapes follow `server/routes/leaves.ts` and `server/routes/leavePolicy.ts`.
 * `leaves.date` is stored as TEXT (YYYY-MM-DD) and the balance route coerces the
 * NUMERIC columns to numbers before responding, so quota/used/carried_forward
 * are safe to model as Double.
 */
@Serializable
data class Leave(
    val id: Long,
    val date: String,
    @SerialName("leave_type") val leaveType: String,
    val duration: String = "full",
    val status: String = "pending",
    val reason: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class LeaveBalance(
    @SerialName("leave_type") val leaveType: String,
    val year: Int = 0,
    val quota: Double = 0.0,
    val used: Double = 0.0,
    @SerialName("carried_forward") val carriedForward: Double = 0.0,
    @SerialName("policy_name") val policyName: String? = null,
    val color: String? = null,
) {
    /** Matches the server's effective quota: accrued quota plus carry-forward. */
    val available: Double get() = (quota + carriedForward - used).coerceAtLeast(0.0)

    fun label(): String = policyName?.takeIf(String::isNotBlank)
        ?: leaveType.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

@Serializable
data class LeavePolicy(
    val id: Long? = null,
    @SerialName("leave_type") val leaveType: String,
    val name: String? = null,
    @SerialName("annual_quota") val annualQuota: Double = 0.0,
    @SerialName("half_day_allowed") val halfDayAllowed: Boolean = false,
    @SerialName("quarter_day_allowed") val quarterDayAllowed: Boolean = false,
    val color: String? = null,
) {
    fun label(): String = name?.takeIf(String::isNotBlank)
        ?: leaveType.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

@Serializable
data class Holiday(
    val id: Long,
    val date: String,
    val name: String,
    @SerialName("is_optional") val isOptional: Boolean = false,
)

@Serializable
data class CalendarEvent(
    val id: Long,
    val title: String,
    val description: String? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val color: String? = null,
    @SerialName("task_title") val taskTitle: String? = null,
    @SerialName("meeting_code") val meetingCode: String? = null,
    @SerialName("meeting_status") val meetingStatus: String? = null,
)

@Serializable
data class ApplyLeavePayload(
    @SerialName("leave_type") val leaveType: String,
    val dates: List<String>,
    val duration: String = "full",
    val reason: String? = null,
)

@Serializable
data class LeaveMessageResponse(val message: String = "", val ids: List<Long> = emptyList())
