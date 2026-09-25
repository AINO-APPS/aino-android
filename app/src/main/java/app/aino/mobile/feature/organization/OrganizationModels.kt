package app.aino.mobile.feature.organization

import app.aino.mobile.core.common.roleLabel
import java.text.Collator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Wire models (bare JSON, see server/routes/organization.ts) ──────────────

/** `GET /org/current` — the row, or JSON `null` when the user has no org. */
@Serializable
data class OrgInfo(
    val id: Long,
    val name: String? = null,
    val slug: String? = null,
    val memberCount: Int? = null,
    val deptCount: Int? = null,
    val teamCount: Int? = null,
)

@Serializable
data class CreateOrgResponse(
    val id: Long,
    val name: String? = null,
    val slug: String? = null,
    val message: String? = null,
)

/** `GET /org/departments`: hr_admin+ get `d.*` + counts; others get their own row only. */
@Serializable
data class Department(
    val id: Long,
    val name: String = "",
    @SerialName("head_id") val headId: Long? = null,
    @SerialName("head_name") val headName: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
)

@Serializable
data class Team(
    val id: Long,
    val name: String = "",
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("lead_id") val leadId: Long? = null,
    @SerialName("lead_name") val leadName: String? = null,
    @SerialName("member_count") val memberCount: Int? = null,
    @SerialName("sprint_duration_weeks") val sprintDurationWeeks: Int? = null,
    @SerialName("sprint_start_date") val sprintStartDate: String? = null,
)

/** A row of `GET /org/members` (`{ data, total, page, perPage }`), used by the pickers. */
@Serializable
data class OrgMember(
    val id: Long,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
) {
    /** `m.full_name || m.username` */
    val label: String get() = fullName?.takeIf(String::isNotEmpty) ?: username.orEmpty()
}

@Serializable
data class OrgMembersPage(
    val data: List<OrgMember> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val perPage: Int = 50,
)

/** `GET /org/teams/:id/sprint-config` (camelCase on the wire). */
@Serializable
data class SprintConfig(
    val teamId: Long? = null,
    val teamName: String? = null,
    val sprintDurationWeeks: Int? = null,
    val sprintStartDate: String? = null,
    val sprintMode: String? = null,
    val sprintPaused: Boolean = false,
)

@Serializable
data class Sprint(
    val id: Long,
    val name: String? = null,
    val status: String? = null,
)

/** `GET /sprints/active`, `POST /sprints/:id/pause|resume` → `{ sprint }`. */
@Serializable
data class SprintEnvelope(val sprint: Sprint? = null)

@Serializable
data class ChartDepartment(
    val id: Long,
    val name: String = "",
    @SerialName("head_id") val headId: Long? = null,
    @SerialName("head_name") val headName: String? = null,
    @SerialName("head_avatar") val headAvatar: String? = null,
)

@Serializable
data class ChartTeam(
    val id: Long,
    val name: String = "",
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("lead_id") val leadId: Long? = null,
    @SerialName("lead_name") val leadName: String? = null,
    @SerialName("lead_avatar") val leadAvatar: String? = null,
)

@Serializable
data class ChartMember(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String = "",
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("manager_id") val managerId: Long? = null,
    @SerialName("manager_name") val managerName: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
) {
    val name: String get() = fullName.orEmpty()
}

/** `GET /org/chart` */
@Serializable
data class OrgChart(
    val departments: List<ChartDepartment> = emptyList(),
    val teams: List<ChartTeam> = emptyList(),
    val members: List<ChartMember> = emptyList(),
)

/** `GET /tasks/labels/manage` row. */
@Serializable
data class TaskLabel(
    val id: Long,
    val name: String = "",
    val color: String = DEFAULT_LABEL_COLOR,
    @SerialName("created_by_username") val createdByUsername: String? = null,
)

// ── Pure logic (web parity) ─────────────────────────────────────────────────

const val DEFAULT_LABEL_COLOR = "#0ea5e9"

/** TaskLabelsTab `PRESET_COLORS`. */
val PRESET_LABEL_COLORS = listOf(
    "#0ea5e9", "#ef4444", "#f59e0b", "#10b981", "#3b82f6",
    "#8b5cf6", "#ec4899", "#14b8a6", "#f97316", "#64748b",
)

private val ADMIN_ROLES = setOf("hr_admin", "super_admin", "platform_admin")

/** Organization.tsx / Departments / Teams: `canManage` = `isAdmin`. */
fun isOrgAdmin(role: String?): Boolean = role in ADMIN_ROLES

/** `canManageLabels = !isAdmin && ["manager"].includes(role)` */
fun canManageLabels(role: String?): Boolean = !isOrgAdmin(role) && role == "manager"

/** `<option value="">{none}</option>` followed by `m.full_name || m.username`. */
fun memberOptions(none: String, members: List<OrgMember>): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to none) + members.map { it.id to it.label }

fun departmentOptions(departments: List<Department>): List<Pair<Long?, String>> =
    listOf<Pair<Long?, String>>(null to "No department") + departments.map { it.id to it.name }

enum class OrgTab(val label: String) {
    Departments("My Department"),
    Teams("My Team"),
    Chart("Org Chart"),
    Labels("Task Labels"),
}

/** Tab order from Organization.tsx (Salary Slips excluded for this phase). */
fun visibleTabs(role: String?): List<OrgTab> =
    OrgTab.entries.filter { it != OrgTab.Labels || canManageLabels(role) }

/** A DATE column may arrive as `2026-01-05` or an ISO timestamp; keep the calendar day. */
fun normalizeDate(value: String?): String? {
    val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return if (raw.length > 10 && raw[4] == '-' && raw[10] == 'T') raw.take(10) else raw
}

/** Teams "Sprint Config" cell. */
fun sprintConfigLabel(weeks: Int?, startDate: String?): String {
    val base = if (weeks != null && weeks != 0) "$weeks week${if (weeks > 1) "s" else ""}" else "Not set"
    val start = normalizeDate(startDate)
    return if (start != null) "$base (from $start)" else base
}

fun plural(count: Int, noun: String): String = "$count $noun${if (count != 1) "s" else ""}"

/** OrgChartView `filteredMembers`. */
fun filterChartMembers(members: List<ChartMember>, search: String): List<ChartMember> {
    val q = search.trim().lowercase()
    if (q.isEmpty()) return members
    return members.filter { m ->
        m.name.lowercase().contains(q) ||
            m.email?.lowercase()?.contains(q) == true ||
            roleLabel(m.role).lowercase().contains(q) ||
            m.managerName?.lowercase()?.contains(q) == true ||
            m.departmentName?.lowercase()?.contains(q) == true ||
            m.teamName?.lowercase()?.contains(q) == true
    }
}

/** `[department_name, team_name].filter(Boolean).join(" › ")` */
fun deptTeamLabel(member: ChartMember): String? =
    listOfNotNull(member.departmentName?.takeIf(String::isNotEmpty), member.teamName?.takeIf(String::isNotEmpty))
        .joinToString(" › ").ifEmpty { null }

/** Chip `title` tooltip text. */
fun memberTooltip(member: ChartMember): String = buildString {
    append(member.name)
    append("\n").append(roleLabel(member.role))
    member.departmentName?.takeIf(String::isNotEmpty)?.let { append("\nDepartment: ").append(it) }
    member.teamName?.takeIf(String::isNotEmpty)?.let { append("\nTeam: ").append(it) }
    member.managerName?.takeIf(String::isNotEmpty)?.let { append("\nReports to: ").append(it) }
}

/** Tree-row meta line. */
fun treeMeta(member: ChartMember): String = buildString {
    append(roleLabel(member.role))
    member.departmentName?.takeIf(String::isNotEmpty)?.let { append(" · ").append(it) }
    member.teamName?.takeIf(String::isNotEmpty)?.let { append(" › ").append(it) }
    member.managerName?.takeIf(String::isNotEmpty)?.let { append(" · reports to ").append(it) }
}

data class ReportingTree(val roots: List<ChartMember>, val children: Map<Long, List<ChartMember>>) {
    fun childrenOf(id: Long): List<ChartMember> = children[id].orEmpty()
}

/**
 * Manager → direct-reports map. A manager outside the active member list makes
 * the member a root, and every level is sorted by name.
 */
fun buildReportingTree(members: List<ChartMember>): ReportingTree {
    val ids = members.mapTo(HashSet()) { it.id }
    val collator = Collator.getInstance()
    val byName = Comparator<ChartMember> { a, b -> collator.compare(a.name, b.name) }
    val grouped = members.groupBy { m -> m.managerId?.takeIf { it in ids && it != m.id } }
    val roots = grouped[null].orEmpty().sortedWith(byName)
    val children = buildMap {
        grouped.forEach { (managerId, reports) -> if (managerId != null) put(managerId, reports.sortedWith(byName)) }
    }
    return ReportingTree(roots, children)
}

/** Case-insensitive match ranges for the `<mark>` highlight. */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val q = query.lowercase()
    if (q.isEmpty()) return emptyList()
    val lower = text.lowercase()
    val ranges = mutableListOf<IntRange>()
    var start = lower.indexOf(q)
    while (start >= 0) {
        ranges += start until start + q.length
        start = lower.indexOf(q, start + q.length)
    }
    return ranges
}

private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

fun isHexColor(value: String): Boolean = HEX_COLOR.matches(value)
