package app.aino.mobile.core.common

/** `pages/admin/constants.ts` `ROLE_LABELS` (system defaults), shared by Organization and My Team. */
val ROLE_LABELS = mapOf(
    "employee" to "Employee",
    "team_lead" to "Team Lead",
    "manager" to "Manager",
    "hr_admin" to "HR Admin",
    "super_admin" to "Super Admin",
    "platform_admin" to "Platform Admin",
)

fun roleLabel(role: String): String = ROLE_LABELS[role] ?: role
