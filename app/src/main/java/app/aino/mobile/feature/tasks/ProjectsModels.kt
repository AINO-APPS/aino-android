package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `projects` row + `lead_name` / `lead_username` / `task_count` (`server/routes/projects.ts`). */
@Serializable
data class Project(
    val id: Long,
    val key: String = "",
    val name: String = "",
    val description: String? = null,
    val color: String = PROJECT_COLORS.first(),
    @SerialName("lead_user_id") val leadUserId: Long? = null,
    @SerialName("lead_name") val leadName: String? = null,
    @SerialName("lead_username") val leadUsername: String? = null,
    @SerialName("task_count") @Serializable(with = LenientIntSerializer::class) val taskCount: Int = 0,
    @SerialName("next_task_number") @Serializable(with = LenientIntSerializer::class) val nextTaskNumber: Int = 1,
    @SerialName("is_archived") val isArchived: Boolean = false,
) {
    /** `p.lead_name || p.lead_username || "—"`. */
    fun leadDisplay(): String = leadName?.takeIf(String::isNotEmpty) ?: leadUsername?.takeIf(String::isNotEmpty) ?: "—"
}

/** The shared server pagination shape `{ limit, offset, total, hasMore }`. */
@Serializable
data class PageInfo(
    @Serializable(with = LenientIntSerializer::class) val limit: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val offset: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val total: Int = 0,
    val hasMore: Boolean = false,
)

@Serializable
internal data class ProjectsEnvelope(val projects: List<Project> = emptyList(), val pagination: PageInfo? = null)

/** One page of the projects grid. */
data class ProjectPage(val projects: List<Project>, val total: Int)

@Serializable
data class ProjectTaskPerson(val username: String? = null, @SerialName("full_name") val fullName: String? = null)

/** `GET /projects/:id/tasks` row (enriched task; only the panel's columns are read). */
@Serializable
data class ProjectTask(
    val id: Long,
    val title: String = "",
    val status: String? = null,
    @SerialName("issue_key") val issueKey: String? = null,
    val assignee: ProjectTaskPerson? = null,
) {
    fun keyDisplay(): String = issueKey?.takeIf(String::isNotEmpty) ?: "#$id"
    fun assigneeDisplay(): String =
        assignee?.fullName?.takeIf(String::isNotEmpty) ?: assignee?.username?.takeIf(String::isNotEmpty) ?: "—"
}

@Serializable
internal data class ProjectTasksEnvelope(val tasks: List<ProjectTask> = emptyList(), val pagination: PageInfo? = null)

data class ProjectTaskPage(val tasks: List<ProjectTask>, val total: Int)

/** `POST /projects` / `PUT /projects/:id` body; `key` only on create. */
@Serializable
data class ProjectPayload(
    val name: String,
    val description: String?,
    val color: String,
    @SerialName("lead_user_id") val leadUserId: Long?,
    val key: String? = null,
)

@Serializable
internal data class ArchivePayload(@SerialName("is_archived") val isArchived: Boolean)

@Serializable
data class DeleteProjectResponse(
    val ok: Boolean = false,
    @SerialName("detached_tasks") @Serializable(with = LenientIntSerializer::class) val detachedTasks: Int = 0,
)

/** 409 `{ code: "PROJECT_NOT_EMPTY", task_count }` from `DELETE /projects/:id`. */
class ProjectNotEmptyException(val taskCount: Int?, message: String) : Exception(message)

// ── Web constants (Projects.tsx) ──────────────────────────────────────────

val PROJECT_ROLE_LEVELS = mapOf(
    "employee" to 1, "team_lead" to 2, "manager" to 3,
    "hr_admin" to 4, "super_admin" to 5, "platform_admin" to 6,
)

private val PROJECT_KEY_RE = Regex("^[A-Z][A-Z0-9_]{1,9}$")

val PROJECT_COLORS = listOf(
    "#6366f1", "#8b5cf6", "#10b981", "#f59e0b",
    "#ef4444", "#3b82f6", "#ec4899", "#14b8a6",
)

val PROJECT_PAGE_SIZES = listOf(6, 12, 24, 48)
val PROJECT_TASK_PAGE_SIZES = listOf(10, 25, 50, 100)

fun projectRoleLevel(role: String?): Int = PROJECT_ROLE_LEVELS[role] ?: 1

/** `canEdit` — manager+. */
fun canEditProjects(role: String?): Boolean = projectRoleLevel(role) >= 3

/** `canDelete` — super_admin and platform_admin. */
fun canDeleteProjects(role: String?): Boolean = projectRoleLevel(role) >= 5

fun isValidProjectKey(key: String): Boolean = PROJECT_KEY_RE.matches(key)

/** Key input `onChange`: upper-case, strip everything but A–Z/0–9/_, `maxLength={10}`. */
fun sanitizeProjectKey(input: String): String = input.uppercase().replace(Regex("[^A-Z0-9_]"), "").take(10)

internal fun plural(n: Int?): String = if (n == 1) "" else "s"

/** The second `confirm()` after a 409 `PROJECT_NOT_EMPTY`. */
fun forceDeleteMessage(project: Project, taskCount: Int?): String {
    val n = taskCount?.takeIf { it != 0 }?.toString() ?: "?"
    return "\"${project.name}\" still contains $n task${plural(taskCount?.takeIf { it != 0 })}.\n\n" +
        "Force-delete will detach every task from this project — the tickets stay, " +
        "but they lose their ${project.key}-N issue key, and any GitHub branches/PRs that " +
        "mention those keys will no longer link back.\n\n" +
        "Continue with force-delete?"
}

/** Pagination summary: `1–12 of 30 projects`, or `N projects` for a single fixed page. */
fun paginationSummary(total: Int, limit: Int, offset: Int, itemLabel: String): String {
    val safeLimit = maxOf(1, limit)
    val safeOffset = maxOf(0, offset)
    val start = if (total == 0) 0 else safeOffset + 1
    val end = minOf(total, safeOffset + safeLimit)
    return "$start–$end of $total $itemLabel${plural(total)}"
}

fun pageCount(total: Int, limit: Int): Int = maxOf(1, (total + maxOf(1, limit) - 1) / maxOf(1, limit))
