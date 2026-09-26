package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.LenientIntNullableSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /admin/organizations` row and `GET /admin/organizations/:id`. */
@Serializable
data class AdminOrganization(
    val id: Long,
    val name: String = "",
    val slug: String? = null,
    val timezone: String? = null,
    @SerialName("work_hours_per_day") @Serializable(LenientIntNullableSerializer::class) val workHoursPerDay: Int? = null,
    @SerialName("work_days") val workDays: String? = null,
    @SerialName("fiscal_year_start") @Serializable(LenientIntNullableSerializer::class) val fiscalYearStart: Int? = null,
    @SerialName("member_count") @Serializable(LenientIntNullableSerializer::class) val memberCountRow: Int? = null,
    @Serializable(LenientIntNullableSerializer::class) val memberCount: Int? = null,
    @Serializable(LenientIntNullableSerializer::class) val deptCount: Int? = null,
    @Serializable(LenientIntNullableSerializer::class) val teamCount: Int? = null,
) {
    val members: Int? get() = memberCount ?: memberCountRow
}

@Serializable
data class OrganizationPage(
    val data: List<AdminOrganization> = emptyList(),
    @Serializable(LenientIntSerializer::class) val total: Int = 0,
)

@Serializable
data class CreatedOrganization(val id: Long = 0, val name: String? = null, val message: String? = null)

/** Create/edit form for `admin/organizations` (`OrgModal.tsx`). */
data class OrganizationDraft(
    val id: Long? = null,
    val name: String = "",
    val workHoursPerDay: String = "8",
    val workDays: String = "1,2,3,4,5",
    val timezone: String = "UTC",
    val fiscalYearStart: String = "1",
)

/** `GET /admin/users` row (and `GET /admin/users/:id`). */
@Serializable
data class AdminUser(
    val id: Long,
    val username: String = "",
    @SerialName("full_name") val fullName: String = "",
    val email: String? = null,
    val avatar: String? = null,
    val role: String = "employee",
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("manager_id") val managerId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("org_name") val orgName: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("manager_name") val managerName: String? = null,
)

@Serializable
data class UserPage(
    val data: List<AdminUser> = emptyList(),
    @Serializable(LenientIntSerializer::class) val total: Int = 0,
    @Serializable(LenientIntSerializer::class) val page: Int = 1,
    @Serializable(LenientIntSerializer::class) val perPage: Int = 50,
)

/** `UserManagement.tsx` filters: search + role + status (`""`, `"true"`, `"false"`). */
data class UserFilters(val search: String = "", val role: String = "", val status: String = "")

/** `PUT /admin/users/:id/role`: applied immediately, or queued as a role-change request. */
@Serializable
data class RoleChangeResult(
    val message: String? = null,
    val immediate: Boolean? = null,
    @SerialName("request_id") val requestId: Long? = null,
    val pending: Boolean? = null,
)

/** `PUT /admin/users/:id/deactivate` toggles and returns the new state. */
@Serializable
data class ToggleActiveResult(val message: String? = null, @SerialName("is_active") val isActive: Boolean? = null)

@Serializable
data class CreatedUser(
    val id: Long = 0,
    val message: String? = null,
    @SerialName("initial_password") val initialPassword: String? = null,
)

/** `CreateUser.tsx` form. */
data class NewUserDraft(
    val fullName: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val role: String = "employee",
    val orgId: Long? = null,
    val departmentId: Long? = null,
    val teamId: Long? = null,
    val managerId: Long? = null,
)
