package app.aino.mobile.feature.admin

/** One query: last good data survives a failed refetch (stale-while-revalidate). */
data class Load<T>(val data: T? = null, val loading: Boolean = false, val error: String? = null)

/** `useAutoDismiss` banner. */
data class AdminNotice(val ok: Boolean, val text: String)

/** Org Settings rail (`OrgSettingsPage.tsx`); Branding / Email templates use [BrandingViewModel] (P10.4). */
enum class OrgSettingsTab(val label: String) {
    General("General"), Attendance("Attendance"), Roles("Roles & Labels"), Branding("Branding"), EmailTemplates("Email templates");

    /** Tabs whose data [AdminViewModel] owns. */
    val usesOrgSettings: Boolean get() = this == General || this == Attendance
}

data class AdminUiState(
    val role: String = "",
    val userId: Long = 0,
    val orgId: Long? = null,
    val stats: Load<AdminStats> = Load(),
    val home: Load<AdminHomeSummary> = Load(),
    val users: Load<UserPage> = Load(),
    val userFilters: UserFilters = UserFilters(),
    val userPage: Int = 1,
    val user: Load<AdminUser> = Load(),
    val departments: Load<List<PickerDepartment>> = Load(),
    val teams: Load<List<PickerTeam>> = Load(),
    val members: Load<List<PickerMember>> = Load(),
    val organizations: Load<List<AdminOrganization>> = Load(),
    val roleRequests: Load<List<RoleRequest>> = Load(),
    val roleRequestStatus: String = "pending",
    val audit: Load<AuditLogPage> = Load(),
    val auditFilters: AuditFilters = AuditFilters(),
    val auditOffset: Int = 0,
    val payPeriods: Load<List<PayPeriod>> = Load(),
    val taskLabels: Load<List<AdminTaskLabel>> = Load(),
    val announcements: Load<List<AdminAnnouncement>> = Load(),
    val orgSettings: Load<OrgSettings> = Load(),
    val orgRoles: Load<OrgRoles> = Load(),
    val registration: Load<RegistrationSettings> = Load(),
    val inviteCodes: Load<List<InviteCode>> = Load(),
    val settingsTab: OrgSettingsTab = OrgSettingsTab.General,
    /** Bumped by a forced Org Settings reload so the Branding / Email templates tabs refetch too. */
    val settingsRefresh: Int = 0,
    val notice: AdminNotice? = null,
    val busy: Boolean = false,
    val createdUser: CreatedUser? = null,
    val importResult: ImportResult? = null,
    val createdInvite: CreatedInvite? = null,
) {
    val isPlatformAdmin: Boolean get() = role == "platform_admin"
    val isSuperOrAbove: Boolean get() = role == "super_admin" || role == "platform_admin"
}

/**
 * `AdminHome.tsx` attention + setup signals. Each flag is best-effort: a failed
 * call reads as "not done" / zero, exactly like the web's `Promise.allSettled`.
 */
data class AdminHomeSummary(
    val pendingRoleRequests: Int = 0,
    val tzSet: Boolean = false,
    val hasDept: Boolean = false,
    val hasTeam: Boolean = false,
    val hasPolicy: Boolean = false,
) {
    /** `(label, targetSectionKey, done)` in the web's checklist order. */
    val checklist: List<Triple<String, String, Boolean>>
        get() = listOf(
            Triple("Set organization timezone & work hours", "org-settings", tzSet),
            Triple("Create at least one department", "departments", hasDept),
            Triple("Create at least one team", "teams", hasTeam),
            Triple("Define a leave policy", "org-settings", hasPolicy),
        )
}

/**
 * Section keys rendered by [AdminSectionScreen]. Web `SECTIONS` keys are kept
 * verbatim; the Android-only pages (endpoints the web UI never calls) are
 * `organizations`, `task-labels`, `registration` and `announcements`.
 */
object AdminSectionKeys {
    const val HOME = "home"
    const val USERS = "users"
    const val ADD = "add"
    const val ROLE_REQUESTS = "role-requests"
    const val PAYROLL = "payroll"
    const val AUDIT = "audit"
    const val ORG_SETTINGS = "org-settings"
    const val ORGANIZATIONS = "organizations"
    const val TASK_LABELS = "task-labels"
    const val REGISTRATION = "registration"
    const val ANNOUNCEMENTS = "announcements"

    val ALL = setOf(HOME, USERS, ADD, ROLE_REQUESTS, PAYROLL, AUDIT, ORG_SETTINGS, ORGANIZATIONS, TASK_LABELS, REGISTRATION, ANNOUNCEMENTS)
}

/** `RegistrationSettings.mode` values accepted by `PUT admin/registration-settings`. */
val REGISTRATION_MODES = listOf("open" to "Open", "invite_only" to "Invite only", "closed" to "Closed")

/** `UserManagement.tsx` `SAVED_VIEWS`, applied to the loaded page. */
enum class UserView(val label: String) { All("All"), Inactive("Inactive"), NoTeam("No team"), NoManager("No manager"), RolePending("Role pending"), Admins("Admins") }

fun UserView.matches(user: AdminUser, pendingUserIds: Set<Long>): Boolean = when (this) {
    UserView.All -> true
    UserView.Inactive -> !user.isActive
    UserView.NoTeam -> user.teamId == null
    UserView.NoManager -> user.managerId == null && user.role != "platform_admin"
    UserView.RolePending -> user.id in pendingUserIds
    UserView.Admins -> user.role in setOf("hr_admin", "super_admin", "platform_admin")
}

/** `AuditLogs.tsx` `DATE_PRESETS`: `(key, label, days)`; `from` = today minus days. */
val AUDIT_DATE_PRESETS = listOf(
    Triple("all", "All time", null), Triple("today", "Today", 0), Triple("7d", "Last 7 days", 7),
    Triple("30d", "Last 30 days", 30), Triple("90d", "Last 90 days", 90),
)

fun auditPresetFrom(days: Int?, today: java.time.LocalDate = java.time.LocalDate.now()): String =
    days?.let { today.minusDays(it.toLong()).toString() }.orEmpty()

/** "x–y of total" for offset paging. */
fun auditRange(offset: Int, shown: Int, total: Int): String =
    if (total == 0 || shown == 0) "0 of $total" else "${offset + 1}–${offset + shown} of $total"

const val AUDIT_PAGE_SIZE = 50
const val USERS_PAGE_SIZE = 50

/** `RoleRequests.tsx` status filter. */
val ROLE_REQUEST_STATUSES = listOf("pending" to "Pending", "approved" to "Approved", "rejected" to "Rejected", "cancelled" to "Cancelled", "" to "All")

data class PasteError(val line: Int, val errors: String)

data class PasteParse(val rows: List<ImportRow>, val errors: List<PasteError>)

private val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
private val DEFAULT_PASTE_COLS = listOf("full_name", "email", "username", "role", "department_name", "team_name")

/**
 * `AddPeopleWizard.tsx` `parsePaste`: tab/comma/semicolon delimiter detection,
 * optional header row, `full_name,email,username,role,department_name,team_name`
 * default order, username inferred from the email local part. [defaultRole]
 * fills blank roles (web `defaults.role`).
 */
fun parsePastedPeople(text: String, defaultRole: String = "employee"): PasteParse {
    val lines = text.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.isEmpty()) return PasteParse(emptyList(), emptyList())
    val first = lines.first()
    val delim = when {
        '\t' in first -> "\t"
        first.split(',').size > first.split(';').size -> ","
        else -> ";"
    }
    val headerCells = first.split(delim).map { it.trim().lowercase() }
    val hasHeader = headerCells.any { Regex("name|email|user|role").containsMatchIn(it) }
    val cols = if (hasHeader) headerCells else DEFAULT_PASTE_COLS
    val dataLines = if (hasHeader) lines.drop(1) else lines
    val rows = mutableListOf<ImportRow>()
    val errors = mutableListOf<PasteError>()
    dataLines.forEachIndexed { i, line ->
        val cells = line.split(delim).map { it.trim() }
        val row = cols.withIndex().associate { (idx, col) -> col to cells.getOrElse(idx) { "" } }
        val errs = mutableListOf<String>()
        val fullName = row["full_name"].orEmpty()
        val email = row["email"].orEmpty()
        var username = row["username"].orEmpty()
        if (fullName.isEmpty()) errs += "missing name"
        if (email.isEmpty()) errs += "missing email" else if (!EMAIL_RE.matches(email)) errs += "invalid email"
        if (username.isEmpty()) {
            username = email.substringBefore('@').lowercase().replace(Regex("[^a-z0-9_-]"), "")
            if (username.isEmpty()) errs += "cannot infer username"
        }
        if (errs.isNotEmpty()) {
            errors += PasteError(i + if (hasHeader) 2 else 1, errs.joinToString(", "))
        } else {
            rows += ImportRow(
                username = username,
                fullName = fullName,
                email = email,
                role = row["role"].orEmpty().ifEmpty { defaultRole },
                password = row["password"]?.ifEmpty { null },
                departmentName = row["department_name"]?.ifEmpty { null },
                teamName = row["team_name"]?.ifEmpty { null },
                managerUsername = row["manager_username"]?.ifEmpty { null },
            )
        }
    }
    return PasteParse(rows, errors)
}

/** `PayPeriods.tsx` client validation; null = valid. */
fun validatePayPeriod(label: String, start: String, end: String): String? = when {
    label.isBlank() -> "Label is required"
    start.isBlank() || end.isBlank() -> "Start and end dates are required"
    end < start -> "End date must be on or after start date"
    else -> null
}
