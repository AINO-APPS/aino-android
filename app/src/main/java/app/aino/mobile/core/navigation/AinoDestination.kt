package app.aino.mobile.core.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.BeachAccess
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.ui.graphics.vector.ImageVector

enum class AinoDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = false,
) {
    Dashboard("dashboard", "Home", Icons.Outlined.Home, true),
    Attendance("attendance", "Attendance", Icons.Outlined.Schedule, true),
    Tasks("tasks", "Tasks", Icons.Outlined.Checklist, true),
    Chat("chat", "Chat", Icons.Outlined.ChatBubbleOutline, true),
    More("more", "More", Icons.Outlined.MoreHoriz, true),
    Leaves("leaves", "Leaves", Icons.Outlined.BeachAccess),
    Calendar("calendar", "Calendar", Icons.Outlined.CalendarMonth),
    Notes("notes", "Notes", Icons.Outlined.NoteAlt),
    Organization("organization", "Organization", Icons.Outlined.Apartment),
    Manager("manager", "Manager", Icons.Outlined.Groups),
    Admin("admin", "Admin", Icons.Outlined.AdminPanelSettings),
    Tenants("tenants", "Tenants", Icons.Outlined.Storage),
    Profile("profile", "Profile", Icons.Outlined.PersonOutline),
}

val bottomDestinations: List<AinoDestination> = AinoDestination.entries.filter { it.inBottomBar }

fun destinationFor(value: String): AinoDestination? =
    AinoDestination.entries.firstOrNull {
        it.label.equals(value, ignoreCase = true) || it.route.equals(value, ignoreCase = true)
    }

fun availableMoreDestinations(role: String, hasReports: Boolean): List<AinoDestination> = buildList {
    add(AinoDestination.Leaves)
    add(AinoDestination.Calendar)
    add(AinoDestination.Notes)
    add(AinoDestination.Organization)
    add(AinoDestination.Profile)
    if (hasReports || role in setOf("manager", "admin", "super_admin")) add(AinoDestination.Manager)
    if (role in setOf("admin", "super_admin")) add(AinoDestination.Admin)
    if (role == "platform_admin") add(AinoDestination.Tenants)
}
