package app.aino.mobile.feature.admin

/** The web's option catalogs, copied verbatim from the admin pages. */
object AdminCatalog {
    /** `AnnouncementsTab.tsx` `TYPES` (value, label, color). */
    val ANNOUNCEMENT_TYPES = listOf(
        Triple("info", "Info", 0xFF3B82F6),
        Triple("success", "Success", 0xFF10B981),
        Triple("warning", "Warning", 0xFFF59E0B),
        Triple("urgent", "Urgent", 0xFFEF4444),
        Triple("quote", "Quote", 0xFF38BDF8),
    )

    /** `AnnouncementsTab.tsx` `DURATIONS` (hours). */
    val DURATIONS = listOf(
        "" to "No expiry", "1" to "1 hour", "6" to "6 hours", "12" to "12 hours", "24" to "1 day",
        "72" to "3 days", "168" to "1 week", "336" to "2 weeks", "720" to "1 month",
    )

    /** `AuditLogs.tsx` `ENTITY_TYPES`. */
    val ENTITY_TYPES = listOf(
        "user", "leave", "time_entry", "task", "team", "department", "organization",
        "leave_policy", "holiday", "approval_request", "role_change_request",
    )

    /** `AuditLogs.tsx` `ACTIONS`. */
    val ACTIONS = listOf(
        "create", "update", "delete", "approve", "reject", "login", "update_role", "request_role_change",
        "approve_role_change", "reject_role_change", "deactivate", "reactivate", "admin_create", "admin_update",
        "admin_delete", "admin_reset_password", "invite", "remove_member",
    )

    /** Assignable tenant roles. */
    val ROLES = listOf("employee", "team_lead", "manager", "hr_admin", "super_admin")

    /** `invite-codes` POST accepts these roles (below the caller's own level). */
    val INVITE_ROLES = listOf("employee", "team_lead", "manager", "hr_admin")

    /** `OrgRoleLabels.tsx` `PERMISSION_LEVELS`. */
    val PERMISSION_LEVELS = listOf(
        Triple(1, "Standard member", "Basic access only — equivalent to employee."),
        Triple(2, "Team lead", "Can review and manage their own team."),
        Triple(3, "Manager", "Approves leaves and tasks; manages a department."),
        Triple(4, "HR admin", "Full people-ops: invite/remove/manage org members."),
    )

    /** `OrgSettings.tsx` working days (JS day-of-week, Mon-first). */
    val WEEK_DAYS = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 0 to "Sun")

    fun roleLabel(role: String): String = role.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
