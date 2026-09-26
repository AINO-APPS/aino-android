package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.LenientIntNullableSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/*
 * `server/routes/admin.ts` + the `/org` admin routes (`server/routes/organization.ts`).
 * Every response is bare JSON except `admin/announcements` (`{ data }`).
 * NUMERIC / COUNT columns may arrive quoted, so counts use the lenient serializers.
 */

/** `GET /admin/stats`. */
@Serializable
data class AdminStats(
    @Serializable(LenientIntSerializer::class) val totalUsers: Int = 0,
    @Serializable(LenientIntSerializer::class) val activeUsers: Int = 0,
    @Serializable(LenientIntSerializer::class) val departments: Int = 0,
    @Serializable(LenientIntSerializer::class) val teams: Int = 0,
    @Serializable(LenientIntSerializer::class) val pendingApprovals: Int = 0,
    @Serializable(LenientIntSerializer::class) val clockedInToday: Int = 0,
)

/** `GET /admin/audit-logs` row (`al.*` + actor columns). */
@Serializable
data class AuditLog(
    val id: Long,
    val action: String = "",
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("entity_id") val entityId: JsonElement? = null,
    val details: String? = null,
    @SerialName("ip_address") val ipAddress: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("actor_id") val actorId: Long? = null,
    @SerialName("actor_username") val actorUsername: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("actor_is_inspector") val actorIsInspector: Boolean? = null,
    @SerialName("actor_inspector_real_name") val actorInspectorRealName: String? = null,
) {
    val entityIdText: String? get() = (entityId as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}

@Serializable
data class AuditLogPage(
    @Serializable(LenientIntSerializer::class) val total: Int = 0,
    val logs: List<AuditLog> = emptyList(),
)

/** Audit-log query (`AuditLogs.tsx` filters). */
data class AuditFilters(
    val entityType: String = "",
    val action: String = "",
    val actorId: String = "",
    val from: String = "",
    val to: String = "",
) {
    val activeCount: Int get() = listOf(entityType, action, actorId, from, to).count { it.isNotBlank() }
}

/** `GET/PUT /admin/registration-settings`. */
@Serializable
data class RegistrationSettings(val mode: String = "open", val message: String? = null)

/** `GET /admin/invite-codes` row. `max_uses` 0 = unlimited. */
@Serializable
data class InviteCode(
    val id: Long,
    val code: String = "",
    val role: String = "employee",
    @SerialName("max_uses") @Serializable(LenientIntNullableSerializer::class) val maxUses: Int? = null,
    @SerialName("used_count") @Serializable(LenientIntNullableSerializer::class) val usedCount: Int? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
)

@Serializable
data class CreatedInvite(val code: String = "", val message: String? = null)

/** `GET /admin/task-labels` row. */
@Serializable
data class AdminTaskLabel(
    val id: Long,
    val name: String = "",
    val color: String? = null,
    @SerialName("created_by_username") val createdByUsername: String? = null,
)

/** `GET /admin/pay-periods` row. DATE columns may arrive as ISO timestamps. */
@Serializable
data class PayPeriod(
    val id: Long,
    val label: String = "",
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    @SerialName("locked_by") val lockedBy: Long? = null,
    @SerialName("locked_at") val lockedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("locked_by_name") val lockedByName: String? = null,
)

/** `admin/announcements` row (super_admin). */
@Serializable
data class AdminAnnouncement(
    val id: Long,
    val message: String = "",
    val type: String = "info",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("created_by_name") val createdByName: String? = null,
    @SerialName("org_name") val orgName: String? = null,
)

@Serializable
data class DataEnvelope<T>(val data: T)

@Serializable
data class MessageResponse(val message: String? = null)

/** Picker rows (`/org/departments`, `/org/teams`, `/org/members`). */
@Serializable
data class PickerDepartment(val id: Long, val name: String = "")

@Serializable
data class PickerTeam(val id: Long, val name: String = "", @SerialName("department_id") val departmentId: Long? = null)

@Serializable
data class PickerMember(
    val id: Long,
    @SerialName("full_name") val fullName: String = "",
    val role: String = "employee",
    @SerialName("team_id") val teamId: Long? = null,
)

@Serializable
data class PickerMemberPage(
    val data: List<PickerMember> = emptyList(),
    @Serializable(LenientIntSerializer::class) val total: Int = 0,
)
