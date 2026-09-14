package app.aino.mobile.feature.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/profile` returns the tenant user row plus derived fields
 * (`has_reports`, `tenant_id`, impersonation markers, tenant plan/features).
 * Every non-identity field is optional because the same route serves platform
 * users, impersonated sessions and virtual (id 0) admins.
 */
@Serializable
data class ProfileUser(
    val id: Long,
    val username: String,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String = "employee",
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("tenant_id") val tenantId: Long? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    @SerialName("has_reports") val hasReports: Boolean = false,
    @SerialName("tenant_plan") val tenantPlan: String? = null,
    @SerialName("tenant_features") val tenantFeatures: Map<String, Boolean> = emptyMap(),
    val impersonated: Boolean = false,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username
}

@Serializable
data class UpdateProfilePayload(
    @SerialName("full_name") val fullName: String,
    val username: String,
)

@Serializable
data class UpdateEmailPayload(val email: String)

@Serializable
data class FaceStatus(
    val enrolled: Boolean = false,
    @SerialName("enrolled_at") val enrolledAt: String? = null,
)

// ── Global search (`GET /api/search?q=`) ──────────────────────────────────
// The service returns seven fixed buckets; audit logs are only populated for
// hr_admin and above, and notes come from the user's own notebook JSON.

@Serializable
data class SearchTaskHit(
    val id: Long,
    val title: String,
    val status: String? = null,
    val priority: String? = null,
    val date: String? = null,
    val snippet: String? = null,
)

@Serializable
data class SearchNoteHit(
    val id: String,
    val title: String = "Untitled",
    val snippet: String? = null,
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
)

@Serializable
data class SearchUserHit(
    val id: Long,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val role: String? = null,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username.orEmpty()
}

@Serializable
data class SearchEventHit(
    val id: Long,
    val title: String,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
)

@Serializable
data class SearchLeaveHit(
    val id: Long,
    val date: String,
    @SerialName("leave_type") val leaveType: String,
    val status: String? = null,
    val reason: String? = null,
)

@Serializable
data class SearchSprintHit(
    val id: Long,
    val name: String,
    val status: String? = null,
    val goal: String? = null,
)

@Serializable
data class SearchLogHit(
    val id: Long,
    val action: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class SearchResults(
    val tasks: List<SearchTaskHit> = emptyList(),
    val notes: List<SearchNoteHit> = emptyList(),
    val users: List<SearchUserHit> = emptyList(),
    val events: List<SearchEventHit> = emptyList(),
    val leaves: List<SearchLeaveHit> = emptyList(),
    val sprints: List<SearchSprintHit> = emptyList(),
    val logs: List<SearchLogHit> = emptyList(),
) {
    val total: Int
        get() = tasks.size + notes.size + users.size + events.size + leaves.size + sprints.size + logs.size

    val isEmpty: Boolean get() = total == 0
}
