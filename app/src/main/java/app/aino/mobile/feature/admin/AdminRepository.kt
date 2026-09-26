package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `client/src/api/admin.ts` + the `/org` admin calls (`client/src/api/organization.ts`).
 * Responses are bare JSON except `admin/announcements`, which wraps in `{ data }`.
 */
class AdminRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true },
) {
    // ── Overview / audit ────────────────────────────────────────────────────

    fun stats(): AdminStats = load {
        // @api GET admin/stats
        decode(api.execute(ApiRequest(path = "admin/stats")))
    }

    fun auditLogs(filters: AuditFilters, limit: Int, offset: Int): AuditLogPage = load {
        val q = query(
            "entity_type" to filters.entityType.ifBlank { null },
            "action" to filters.action.ifBlank { null },
            "actor_id" to filters.actorId.ifBlank { null },
            "from" to filters.from.ifBlank { null },
            "to" to filters.to.ifBlank { null },
            "limit" to "$limit",
            "offset" to "$offset",
        )
        // @api GET admin/audit-logs
        decode(api.execute(ApiRequest(path = "admin/audit-logs" + q)))
    }

    /** `AdminHome.tsx` `homeData` + `setupData`: each call is independent and failures read as empty. */
    fun homeSummary(hasOrg: Boolean): AdminHomeSummary {
        fun count(path: String): Int = runCatching {
            json.parseToJsonElement(api.execute(ApiRequest(path = path)).bodyAsString().ifBlank { "[]" }).let { el ->
                (el as? JsonArray)?.size ?: ((el as? JsonObject)?.get("data") as? JsonArray)?.size ?: 0
            }
        }.getOrDefault(0)
        // @api GET admin/role-requests
        val pending = count("admin/role-requests?status=pending")
        if (!hasOrg) return AdminHomeSummary(pendingRoleRequests = pending)
        val tz = runCatching { orgSettings()?.timezone }.getOrNull()
        return AdminHomeSummary(
            pendingRoleRequests = pending,
            tzSet = !tz.isNullOrBlank() && tz != "UTC",
            // @api GET org/departments
            hasDept = count("org/departments") > 0,
            // @api GET org/teams
            hasTeam = count("org/teams") > 0,
            // @api GET leave-policy/policies
            hasPolicy = count("leave-policy/policies") > 0,
        )
    }

    // ── Registration + invite codes (Android-only screens) ──────────────────

    fun registrationSettings(): RegistrationSettings = load {
        // @api GET admin/registration-settings
        decode(api.execute(ApiRequest(path = "admin/registration-settings")))
    }

    fun updateRegistrationMode(mode: String): RegistrationSettings {
        // @api PUT admin/registration-settings
        return mutate("admin/registration-settings", buildJsonObject { put("mode", mode) }, "PUT")
    }

    fun inviteCodes(): List<InviteCode> = load {
        // @api GET admin/invite-codes
        decode(api.execute(ApiRequest(path = "admin/invite-codes")))
    }

    fun createInviteCode(role: String, maxUses: Int, expiresDays: Int?): CreatedInvite {
        val body = buildJsonObject {
            put("role", role)
            put("max_uses", maxUses)
            put("expires_days", expiresDays?.let(::JsonPrimitive) ?: JsonNull)
        }
        // @api POST admin/invite-codes
        return mutate("admin/invite-codes", body, "POST")
    }

    fun deactivateInviteCode(id: Long): MessageResponse {
        // @api DELETE admin/invite-codes/:id
        return mutate("admin/invite-codes/$id", Unit, "DELETE")
    }

    // ── Organizations (platform_admin) ──────────────────────────────────────

    fun organizations(): List<AdminOrganization> = load {
        val all = mutableListOf<AdminOrganization>()
        var page = 1
        while (page <= MAX_PICKER_PAGES) {
            // @api GET admin/organizations
            val result: OrganizationPage = decode(api.execute(ApiRequest(path = "admin/organizations?page=$page&per_page=100")))
            all += result.data
            if (result.data.isEmpty() || all.size >= result.total) break
            page++
        }
        all
    }

    fun organization(id: Long): AdminOrganization = load {
        // @api GET admin/organizations/:id
        decode(api.execute(ApiRequest(path = "admin/organizations/$id")))
    }

    fun createOrganization(draft: OrganizationDraft): CreatedOrganization {
        val body = buildJsonObject {
            put("name", draft.name.trim())
            put("work_hours_per_day", draft.workHoursPerDay.trim().toIntOrNull() ?: 8)
            put("work_days", draft.workDays.trim())
            put("timezone", draft.timezone.trim().ifBlank { "UTC" })
        }
        // @api POST admin/organizations
        return mutate("admin/organizations", body, "POST")
    }

    fun updateOrganization(draft: OrganizationDraft) {
        val id = requireNotNull(draft.id)
        val body = buildJsonObject {
            put("name", draft.name.trim())
            put("work_hours_per_day", draft.workHoursPerDay.trim().toIntOrNull() ?: 8)
            put("work_days", draft.workDays.trim())
            put("timezone", draft.timezone.trim().ifBlank { "UTC" })
            put("fiscal_year_start", draft.fiscalYearStart.trim().toIntOrNull() ?: 1)
        }
        // @api PUT admin/organizations/:id
        mutate<JsonObject, JsonObject>("admin/organizations/$id", body, "PUT")
    }

    fun deleteOrganization(id: Long): MessageResponse {
        // @api DELETE admin/organizations/:id
        return mutate("admin/organizations/$id", Unit, "DELETE")
    }

    // ── Users ───────────────────────────────────────────────────────────────

    fun users(filters: UserFilters, page: Int, perPage: Int = 50): UserPage = load {
        val q = query(
            "search" to filters.search.trim().ifBlank { null },
            "role" to filters.role.ifBlank { null },
            "is_active" to filters.status.ifBlank { null },
            "page" to "$page",
            "per_page" to "$perPage",
        )
        // @api GET admin/users
        decode(api.execute(ApiRequest(path = "admin/users" + q)))
    }

    fun user(id: Long): AdminUser = load {
        // @api GET admin/users/:id
        decode(api.execute(ApiRequest(path = "admin/users/$id")))
    }

    fun createUser(draft: NewUserDraft): CreatedUser {
        val body = buildJsonObject {
            put("full_name", draft.fullName.trim())
            put("username", draft.username.trim())
            put("email", draft.email.trim())
            if (draft.password.isNotBlank()) put("password", draft.password)
            put("role", draft.role)
            put("org_id", draft.orgId.toJson())
            put("department_id", draft.departmentId.toJson())
            put("team_id", draft.teamId.toJson())
            put("manager_id", draft.managerId.toJson())
        }
        // @api POST admin/users
        return mutate("admin/users", body, "POST")
    }

    fun changeRole(userId: Long, role: String, reason: String?): RoleChangeResult {
        val body = buildJsonObject {
            put("role", role)
            put("reason", reason.toJsonOrNull())
        }
        // @api PUT admin/users/:id/role
        return mutate("admin/users/$userId/role", body, "PUT")
    }

    fun updateAssignment(userId: Long, orgId: Long?, departmentId: Long?, teamId: Long?, managerId: Long?): MessageResponse {
        val body = buildJsonObject {
            put("org_id", orgId.toJson())
            put("department_id", departmentId.toJson())
            put("team_id", teamId.toJson())
            put("manager_id", managerId.toJson())
        }
        // @api PUT admin/users/:id/assignment
        return mutate("admin/users/$userId/assignment", body, "PUT")
    }

    /** The route toggles `is_active`; the response carries the new state. */
    fun toggleActive(userId: Long): ToggleActiveResult {
        // @api PUT admin/users/:id/deactivate
        return mutate("admin/users/$userId/deactivate", Unit, "PUT")
    }

    fun resetPassword(userId: Long, newPassword: String): MessageResponse {
        // @api POST admin/users/:id/reset-password
        return mutate("admin/users/$userId/reset-password", buildJsonObject { put("new_password", newPassword) }, "POST")
    }

    fun resetFaceEnrollment(userId: Long): MessageResponse {
        // @api DELETE admin/users/:id/face-enroll
        return mutate("admin/users/$userId/face-enroll", Unit, "DELETE")
    }

    fun deleteUser(userId: Long): MessageResponse {
        // @api DELETE admin/users/:id
        return mutate("admin/users/$userId", Unit, "DELETE")
    }

    /** JSON form of the import route (the multipart CSV upload is web-only). */
    fun importUsers(rows: List<ImportRow>, orgId: Long?): ImportResult {
        val body = buildJsonObject {
            put("users", buildJsonArray {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("username", r.username)
                        put("full_name", r.fullName)
                        put("email", r.email)
                        put("role", r.role.ifBlank { "employee" })
                        if (!r.password.isNullOrBlank()) put("password", r.password)
                        if (!r.departmentName.isNullOrBlank()) put("department_name", r.departmentName)
                        if (!r.teamName.isNullOrBlank()) put("team_name", r.teamName)
                        if (!r.managerUsername.isNullOrBlank()) put("manager_username", r.managerUsername)
                    })
                }
            })
            if (orgId != null) put("org_id", orgId)
        }
        // @api POST admin/users/import
        return mutate("admin/users/import", body, "POST")
    }

    // ── Role-change requests ────────────────────────────────────────────────

    fun roleRequests(status: String): List<RoleRequest> = load {
        // @api GET admin/role-requests
        decode(api.execute(ApiRequest(path = "admin/role-requests" + query("status" to status.ifBlank { null }))))
    }

    fun approveRoleRequest(id: Long): ApproveResult {
        // @api POST admin/role-requests/:id/approve
        return mutate("admin/role-requests/$id/approve", Unit, "POST")
    }

    fun rejectRoleRequest(id: Long, reason: String?): MessageResponse {
        // @api POST admin/role-requests/:id/reject
        return mutate("admin/role-requests/$id/reject", buildJsonObject { put("reject_reason", reason.toJsonOrNull()) }, "POST")
    }

    fun cancelRoleRequest(id: Long): MessageResponse {
        // @api POST admin/role-requests/:id/cancel
        return mutate("admin/role-requests/$id/cancel", Unit, "POST")
    }

    // ── Pay periods ─────────────────────────────────────────────────────────

    fun payPeriods(): List<PayPeriod> = load {
        // @api GET admin/pay-periods
        decode(api.execute(ApiRequest(path = "admin/pay-periods")))
    }

    fun lockPayPeriod(label: String, startDate: String, endDate: String): PayPeriod {
        val body = buildJsonObject {
            put("label", label.trim())
            put("start_date", startDate)
            put("end_date", endDate)
        }
        // @api POST admin/pay-periods
        return mutate("admin/pay-periods", body, "POST")
    }

    fun unlockPayPeriod(id: Long): MessageResponse {
        // @api DELETE admin/pay-periods/:id
        return mutate("admin/pay-periods/$id", Unit, "DELETE")
    }

    // ── Task labels (admin catalogue, Android-only screen) ──────────────────

    fun taskLabels(): List<AdminTaskLabel> = load {
        // @api GET admin/task-labels
        decode(api.execute(ApiRequest(path = "admin/task-labels")))
    }

    fun createTaskLabel(name: String, color: String): AdminTaskLabel {
        // @api POST admin/task-labels
        return mutate("admin/task-labels", buildJsonObject { put("name", name.trim()); put("color", color) }, "POST")
    }

    fun updateTaskLabel(id: Long, name: String, color: String): AdminTaskLabel {
        // @api PUT admin/task-labels/:id
        return mutate("admin/task-labels/$id", buildJsonObject { put("name", name.trim()); put("color", color) }, "PUT")
    }

    fun deleteTaskLabel(id: Long): MessageResponse {
        // @api DELETE admin/task-labels/:id
        return mutate("admin/task-labels/$id", Unit, "DELETE")
    }

    // ── Announcements (super_admin, `{ data }` envelope) ────────────────────

    fun announcements(): List<AdminAnnouncement> = load {
        // @api GET admin/announcements
        decode<DataEnvelope<List<AdminAnnouncement>>>(api.execute(ApiRequest(path = "admin/announcements"))).data
    }

    fun createAnnouncement(message: String, type: String, durationHours: String): AdminAnnouncement {
        val body = buildJsonObject {
            put("message", message.trim())
            put("type", type)
            if (durationHours.isNotBlank()) put("duration", durationHours)
        }
        // @api POST admin/announcements
        return mutate<JsonObject, DataEnvelope<AdminAnnouncement>>("admin/announcements", body, "POST").data
    }

    /** Only the provided fields are sent; a blank [durationHours] keeps the current expiry. */
    fun updateAnnouncement(
        id: Long,
        message: String? = null,
        type: String? = null,
        isActive: Boolean? = null,
        durationHours: String? = null,
    ): AdminAnnouncement {
        val body = buildJsonObject {
            message?.let { put("message", it.trim()) }
            type?.let { put("type", it) }
            isActive?.let { put("is_active", it) }
            durationHours?.takeIf { it.isNotBlank() }?.let { put("duration", it) }
        }
        // @api PUT admin/announcements/:id
        return mutate<JsonObject, DataEnvelope<AdminAnnouncement>>("admin/announcements/$id", body, "PUT").data
    }

    fun deleteAnnouncement(id: Long) {
        // @api DELETE admin/announcements/:id
        mutate<Unit, JsonObject>("admin/announcements/$id", Unit, "DELETE")
    }

    // ── Org settings (`/org`) ───────────────────────────────────────────────

    /** `null` when the caller is not in an org. */
    fun orgSettings(): OrgSettings? = load {
        // @api GET org/current
        json.decodeFromString<OrgSettings?>(api.execute(ApiRequest(path = "org/current")).bodyAsString().ifBlank { "null" })
    }

    /** `OrgSettings.tsx` save. Name / hours / days are only sent for super_admin+ (the route ignores them otherwise). */
    fun saveGeneralSettings(draft: GeneralSettingsDraft, canEditAll: Boolean): OrgSettings {
        val body = buildJsonObject {
            if (canEditAll) {
                put("name", draft.name.trim())
                put("work_hours_per_day", draft.workHoursPerDay.trim().toIntOrNull() ?: 8)
                put("work_days", draft.workDays.sorted().joinToString(","))
            }
            put("timezone", draft.timezone)
            put("fiscal_year_start", draft.fiscalYearStart.trim().toIntOrNull() ?: 1)
            put("min_hours_present", draft.minHoursPresent.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("office_start_time", draft.officeStartTime.toJsonOrNull())
            put("biometric_login_enabled", draft.biometricLoginEnabled)
        }
        // @api PUT org/settings
        return mutate("org/settings", body, "PUT")
    }

    /** `OfficeLocationSettings.tsx` save. */
    fun saveAttendanceSettings(draft: AttendanceSettingsDraft): OrgSettings {
        val body = buildJsonObject {
            put("office_latitude", draft.latitude.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("office_longitude", draft.longitude.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("office_radius_m", draft.radius.trim().toIntOrNull()?.takeIf { it != 0 } ?: 150)
            put("office_address", draft.address.toJsonOrNull())
            put("attendance_verification_enabled", draft.verifyOn)
            put("office_wifi_bssids", JsonArray(draft.wifi.map { ap ->
                buildJsonObject { put("bssid", ap.bssid); put("label", ap.label.toJsonOrNull()) }
            }))
            put("office_wifi_verification_enabled", draft.wifiOn)
        }
        return mutate("org/settings", body, "PUT")
    }

    // ── Org roles (`/org/roles`) ────────────────────────────────────────────

    fun orgRoles(): OrgRoles = load {
        // @api GET org/roles
        decode(api.execute(ApiRequest(path = "org/roles")))
    }

    fun createOrgRole(draft: RoleDraft): OrgRoles {
        val body = buildJsonObject {
            put("role_key", draft.roleKey.trim())
            put("label", draft.label.trim())
            put("description", draft.description.trim())
            put("color", draft.color)
            put("permission_level", draft.permissionLevel)
        }
        // @api POST org/roles
        return mutate("org/roles", body, "POST")
    }

    fun updateOrgRole(draft: RoleDraft): OrgRoles {
        val body = buildJsonObject {
            put("label", draft.label.trim())
            put("description", draft.description.trim())
            put("color", draft.color)
            put("permission_level", draft.permissionLevel)
        }
        // @api PATCH org/roles/:role_key
        return mutate("org/roles/${encodePath(draft.roleKey)}", body, "PATCH")
    }

    fun deleteOrgRole(roleKey: String): OrgRoles {
        // @api DELETE org/roles/:role_key
        return mutate("org/roles/${encodePath(roleKey)}", Unit, "DELETE")
    }

    // ── Org membership (Android-only actions) ───────────────────────────────

    fun inviteToOrg(userId: Long, role: String, departmentId: Long?, teamId: Long?): MessageResponse {
        val body = buildJsonObject {
            put("user_id", userId)
            put("role", role)
            put("department_id", departmentId.toJson())
            put("team_id", teamId.toJson())
        }
        // @api POST org/invite
        return mutate("org/invite", body, "POST")
    }

    fun removeFromOrg(userId: Long): MessageResponse {
        // @api POST org/remove-member
        return mutate("org/remove-member", buildJsonObject { put("user_id", userId) }, "POST")
    }

    // ── Pickers ─────────────────────────────────────────────────────────────

    fun departments(orgId: Long?): List<PickerDepartment> = load {
        // @api GET org/departments
        decode(api.execute(ApiRequest(path = "org/departments" + query("org_id" to orgId?.toString()))))
    }

    fun teams(orgId: Long?): List<PickerTeam> = load {
        // @api GET org/teams
        decode(api.execute(ApiRequest(path = "org/teams" + query("org_id" to orgId?.toString()))))
    }

    fun members(orgId: Long?): List<PickerMember> = load {
        val all = mutableListOf<PickerMember>()
        var page = 1
        while (page <= MAX_PICKER_PAGES) {
            val q = query("is_active" to "true", "org_id" to orgId?.toString(), "per_page" to "100", "page" to "$page")
            // @api GET org/members
            val result: PickerMemberPage = decode(api.execute(ApiRequest(path = "org/members" + q)))
            all += result.data
            if (result.data.isEmpty() || all.size >= result.total) break
            page++
        }
        all
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun Long?.toJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull

    private fun String?.toJsonOrNull(): JsonElement = this?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull

    private fun query(vararg params: Pair<String, String?>): String {
        val present = params.filter { it.second != null }
        if (present.isEmpty()) return ""
        return present.joinToString("&", prefix = "?") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }
    }

    private fun encodePath(segment: String): String = java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private inline fun <T> load(block: () -> T): T {
        try {
            return block()
        } catch (error: ApiError.Http) {
            throw AdminFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            // OkHttp requires a body for POST/PUT/PATCH; DELETE may go without one.
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw AdminFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        val obj = json.parseToJsonElement(error.responseBody).jsonObject
        (obj["error"] ?: obj["message"])?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })

    private companion object {
        const val MAX_PICKER_PAGES = 50
    }
}

/** [serverMessage] is the route's `{ error }`; callers fall back to the web's copy when it is absent. */
class AdminFailure(val serverMessage: String?, val statusCode: Int, cause: Throwable) :
    Exception(serverMessage ?: "HTTP $statusCode", cause)

/** `e.response?.data?.error || fallback` */
fun Throwable.adminMessage(fallback: String): String = (this as? AdminFailure)?.serverMessage ?: fallback
