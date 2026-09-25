package app.aino.mobile.feature.attendance

import app.aino.mobile.core.common.LenientDoubleSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Leave shapes follow `server/routes/leaves.ts` and `server/routes/leavePolicy.ts`.
 * Moved from the retired `feature/leaves` package (P3.3): on the web, /leaves
 * redirects into the Attendance > Leaves tab, so the domain lives here now.
 *
 * `leave-policy/balances` does not coerce its NUMERIC columns the way
 * `/leaves/balance` did, so quota/used/carried_forward use the tolerant
 * decoder (node-pg serializes NUMERIC as a quoted string).
 */
@Serializable
data class LeaveBalance(
    @SerialName("leave_type") val leaveType: String,
    @Serializable(with = LenientDoubleSerializer::class) val year: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val quota: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val used: Double = 0.0,
    @SerialName("carried_forward")
    @Serializable(with = LenientDoubleSerializer::class)
    val carriedForward: Double = 0.0,
    @SerialName("policy_name") val policyName: String? = null,
    val color: String? = null,
) {
    /** Matches the server's effective quota: accrued quota plus carry-forward. */
    val total: Double get() = quota + carriedForward
    val available: Double get() = (total - used).coerceAtLeast(0.0)
    val usedPercent: Int get() = if (total > 0) ((used / total) * 100).toInt().coerceAtMost(100) else 0

    fun label(): String = policyName?.takeIf(String::isNotBlank)
        ?: leaveType.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/**
 * HR "All Balances" row (`GET /leave-policy/balances?year=all`): one row per
 * user × leave type.
 */
@Serializable
data class UserLeaveBalance(
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    @SerialName("leave_type") val leaveType: String,
    @Serializable(with = LenientDoubleSerializer::class) val quota: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val used: Double = 0.0,
    @SerialName("carried_forward")
    @Serializable(with = LenientDoubleSerializer::class)
    val carriedForward: Double = 0.0,
)

/**
 * `leave_policies.annual_quota` is `NUMERIC` and the GET route does **not**
 * coerce it — node-pg delivers it quoted, so it needs the tolerant decoder.
 */
@Serializable
data class LeavePolicy(
    val id: Long? = null,
    @SerialName("leave_type") val leaveType: String,
    val name: String? = null,
    @SerialName("annual_quota")
    @Serializable(with = LenientDoubleSerializer::class)
    val annualQuota: Double = 0.0,
    @SerialName("half_day_allowed") val halfDayAllowed: Boolean = false,
    @SerialName("quarter_day_allowed") val quarterDayAllowed: Boolean = false,
    val color: String? = null,
)

/**
 * Leave application body (`POST /leaves`). The server accepts a single `date`
 * or a `dates` batch; the client always sends the batch shape, mirroring
 * `addLeavesBatch` (a single-day request is a one-element list).
 */
@Serializable
data class ApplyLeavePayload(
    @SerialName("leave_type") val leaveType: String,
    val dates: List<String>,
    val duration: String = "full",
    val reason: String? = null,
)

@Serializable
data class LeaveMessageResponse(val message: String = "", val ids: List<Long> = emptyList())
