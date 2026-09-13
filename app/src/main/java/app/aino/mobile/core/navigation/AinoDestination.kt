package app.aino.mobile.core.navigation

enum class AinoDestination(
    val route: String,
    val label: String,
    val inBottomBar: Boolean = false,
) {
    Dashboard("dashboard", "Home", true),
    Attendance("attendance", "Attendance", true),
    Tasks("tasks", "Tasks", true),
    Chat("chat", "Chat", true),
    More("more", "More", true),
    Calendar("calendar", "Calendar"),
    Notes("notes", "Notes"),
    Organization("organization", "Organization"),
    Manager("manager", "Manager"),
    Admin("admin", "Admin"),
    Tenants("tenants", "Tenants"),
    Profile("profile", "Profile"),
}

val bottomDestinations: List<AinoDestination> = AinoDestination.entries.filter { it.inBottomBar }

fun destinationFor(value: String): AinoDestination? =
    AinoDestination.entries.firstOrNull {
        it.label.equals(value, ignoreCase = true) || it.route.equals(value, ignoreCase = true)
    }

fun availableMoreDestinations(role: String, hasReports: Boolean): List<AinoDestination> = buildList {
    add(AinoDestination.Calendar)
    add(AinoDestination.Notes)
    add(AinoDestination.Organization)
    add(AinoDestination.Profile)
    if (hasReports || role in setOf("manager", "admin", "super_admin")) add(AinoDestination.Manager)
    if (role in setOf("admin", "super_admin")) add(AinoDestination.Admin)
    if (role == "platform_admin") add(AinoDestination.Tenants)
}
