package app.aino.mobile.core.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import app.aino.mobile.core.designsystem.icons.HeroIcons

enum class AinoDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val inBottomBar: Boolean = false,
) {
    // Bottom bar (§2): Home · Calendar · Tasks · Chat · More.
    Dashboard("dashboard", "Home", HeroIcons.Home, true),
    Calendar("calendar", "Calendar", HeroIcons.CalendarDays, true),
    Tasks("tasks", "Tasks", HeroIcons.ClipboardDocumentCheck, true),
    Chat("chat", "Chat", HeroIcons.ChatBubbleOvalLeft, true),
    ChatThread("chat/{conversationId}", "Chat", HeroIcons.ChatBubbleOvalLeft),
    More("more", "More", HeroIcons.EllipsisHorizontal, true),

    // Demoted out of the bottom bar (§2): reachable from the More sheet.
    Attendance("attendance", "Attendance", HeroIcons.Clock),
    Leaves("leaves", "Leaves", HeroIcons.Sun),
    Notes("notes", "Notes", HeroIcons.ClipboardDocument),
    Organization("organization", "Organization", HeroIcons.BuildingOffice2),
    Manager("manager", "My Team", HeroIcons.UserGroup),
    Admin("admin", "Admin", HeroIcons.ShieldCheck),
    Tenants("tenants", "Tenants", HeroIcons.CircleStack),
    Profile("profile", "Profile", HeroIcons.User),
    Notifications("notifications", "Notifications", HeroIcons.Bell),
    ApiProbe("api-probe", "API Probe", HeroIcons.BugAnt),
}

val bottomDestinations: List<AinoDestination> = AinoDestination.entries.filter { it.inBottomBar }

fun destinationFor(value: String): AinoDestination? =
    AinoDestination.entries.firstOrNull {
        it.label.equals(value, ignoreCase = true) || it.route.equals(value, ignoreCase = true)
    }

const val CHAT_CONVERSATION_ARGUMENT = "conversationId"
const val CHAT_DEEP_LINK_PATTERN = "aino://chat/{$CHAT_CONVERSATION_ARGUMENT}"

/** Profile sub-pages (the web's Edit Profile / Notification Sounds modals and `/profile/face`). */
const val PROFILE_EDIT_ROUTE = "profile/edit"
const val PROFILE_SOUNDS_ROUTE = "profile/sounds"
const val PROFILE_FACE_ROUTE = "profile/face"

/** Web `/meeting/:code` — the MeetingJoin lobby. */
const val MEETING_ROUTE = "meeting/{code}"
fun meetingRoute(code: String): String = "meeting/" + java.net.URLEncoder.encode(code, "UTF-8")

/** Web `/meeting/:code/room` — the live room (auto-joins when opened by link). */
const val MEETING_ROOM_ROUTE = "meeting/{code}/room"
fun meetingRoomRoute(code: String): String = meetingRoute(code) + "/room"

/** Web `/huddle/:code` — instant group call, no lobby. */
const val HUDDLE_ROUTE = "huddle/{code}"
fun huddleRoute(code: String): String = "huddle/" + java.net.URLEncoder.encode(code, "UTF-8")

/** The calendar's New / Edit Event form (shares the Calendar route's ViewModel). */
const val CALENDAR_EVENT_ROUTE = "calendar/event"

/** P6.2: `TaskDetailModal` is full screen on phones (shares the Tasks ViewModel). */
const val TASK_DETAIL_ROUTE = "tasks/detail"

/** P6.8: web `/sprint-insights` (inside the shell, Tasks tab stays selected). */
const val SPRINT_INSIGHTS_ROUTE = "sprint-insights"

/** Web `/tasks?task=&tab=&sprint_id=` links land here, apply the params, then show Tasks. */
const val TASK_LINK_ROUTE = "tasks/link?task={task}&tab={tab}&sprint_id={sprint_id}"
fun taskLinkRoute(task: String?, tab: String?, sprintId: String?): String =
    "tasks/link?task=${task.orEmpty()}&tab=${tab.orEmpty()}&sprint_id=${sprintId.orEmpty()}"

/** P6.5 / P6.6: Admin → Projects and Admin → Agile Config (web `/admin?tab=projects|agile`). */
const val ADMIN_AGILE_ROUTE = "admin/agile"
const val ADMIN_PROJECTS_ROUTE = "admin/projects"

/** P10.1: one Admin section as a full-screen page (web `/admin?tab=<key>`). */
const val ADMIN_SECTION_ROUTE = "admin/s/{key}"
fun adminSectionRoute(key: String): String = "admin/s/" + java.net.URLEncoder.encode(key, "UTF-8")

/** P10.1: the web `UserDrawer` as a full-screen page. */
const val ADMIN_USER_ROUTE = "admin/users/{userId}"
fun adminUserRoute(userId: Long): String = "admin/users/$userId"

/** P10.3: a payroll page (web `/admin?tab=compensation|salary-slips|payment-config`). */
const val ADMIN_PAYROLL_ROUTE = "admin/payroll/{key}"
fun adminPayrollRoute(key: String): String = "admin/payroll/" + java.net.URLEncoder.encode(key, "UTF-8")

/** `/admin?tab=` keys of the payroll pages. */
val ADMIN_PAYROLL_PAGE_KEYS = setOf("compensation", "salary-slips", "payment-config")

/** P10.3 Android-only pages: one employee's payroll record, one salary slip. */
const val ADMIN_PAYROLL_EMPLOYEE_ROUTE = "admin/payroll-employee/{userId}?name={name}"
fun adminPayrollEmployeeRoute(userId: Long, name: String): String =
    "admin/payroll-employee/$userId?name=" + java.net.URLEncoder.encode(name, "UTF-8")
const val ADMIN_SALARY_SLIP_ROUTE = "admin/salary-slip/{slipId}"
fun adminSalarySlipRoute(slipId: Long): String = "admin/salary-slip/$slipId"

/** `/admin?tab=` keys rendered by the shared Admin section page (web `SECTIONS` + Android-only pages). */
val ADMIN_SECTION_PAGE_KEYS = setOf(
    "home", "users", "add", "role-requests", "payroll", "audit", "org-settings",
    "organizations", "task-labels", "registration", "announcements",
)

/** P8: `EmployeeDashboard.tsx` — a manager's view of one team member (full screen, own back button). */
const val MANAGER_MEMBER_ROUTE = "manager/member/{userId}"
fun managerMemberRoute(userId: Long): String = "manager/member/$userId"

private val FULL_SCREEN_ROUTES = setOf(
    AinoDestination.ChatThread.route,
    AinoDestination.Profile.route,
    AinoDestination.Notifications.route,
    PROFILE_EDIT_ROUTE, PROFILE_SOUNDS_ROUTE, PROFILE_FACE_ROUTE,
    MEETING_ROUTE,
    MEETING_ROOM_ROUTE,
    HUDDLE_ROUTE,
    CALENDAR_EVENT_ROUTE,
    TASK_DETAIL_ROUTE,
    SEARCH_ROUTE,
    NOTE_EDITOR_ROUTE,
    NOTE_HISTORY_ROUTE,
    ADMIN_AGILE_ROUTE,
    ADMIN_PROJECTS_ROUTE,
    ADMIN_SECTION_ROUTE,
    ADMIN_USER_ROUTE,
    ADMIN_PAYROLL_ROUTE,
    ADMIN_PAYROLL_EMPLOYEE_ROUTE,
    ADMIN_SALARY_SLIP_ROUTE,
    MANAGER_MEMBER_ROUTE,
)

/** Full-screen routes draw their own title bar, so the shell hides its top and bottom bars. */
fun isFullScreenRoute(route: String?): Boolean = route != null && route in FULL_SCREEN_ROUTES

const val SEARCH_ROUTE = "search"

/** P7.2 notes editor / version history (full screen; the Notes home lives inside the shell). */
const val NOTE_EDITOR_ROUTE = "notes/{pageId}"
const val NOTE_HISTORY_ROUTE = "notes/history/{pageId}"
fun noteEditorRoute(pageId: String): String = "notes/" + java.net.URLEncoder.encode(pageId, "UTF-8")
fun noteHistoryRoute(pageId: String): String = "notes/history/" + java.net.URLEncoder.encode(pageId, "UTF-8")

/**
 * Maps a web client route (notification targets, search results, note links)
 * onto an Android route, or null when Android has no counterpart yet.
 * Tasks `?task=` / `?taskId=` / `?tab=` / `?sprint_id=` go through the task
 * link route; `#hash` tabs map to the Attendance `?tab=` argument.
 */
fun webLinkToRoute(link: String, noteRoute: (pageId: String) -> String? = ::noteEditorRoute): String? {
    val hash = link.substringAfter('#', "").takeIf { '#' in link }
    val path = link.substringBefore('#').substringBefore('?').trimEnd('/').ifEmpty { "/" }
    val query = link.substringBefore('#').substringAfter('?', "")
        .split('&').filter { '=' in it }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
    return when {
        path == "/" -> AinoDestination.Dashboard.route
        path == "/calendar" -> AinoDestination.Calendar.route
        path == "/tasks" -> {
            val task = query["task"]?.ifEmpty { null } ?: query["taskId"]
            if (listOf(task, query["tab"], query["sprint_id"]).any { !it.isNullOrEmpty() }) taskLinkRoute(task, query["tab"], query["sprint_id"])
            else AinoDestination.Tasks.route
        }
        path == "/sprint-insights" -> SPRINT_INSIGHTS_ROUTE
        path == "/chat" -> AinoDestination.Chat.route
        path.startsWith("/chat/") -> path.removePrefix("/chat/").toLongOrNull()?.takeIf { it > 0 }?.let(::chatThreadRoute)
        path == "/notes" -> query["pageId"]?.let(noteRoute) ?: AinoDestination.Notes.route
        path == "/attendance" -> AinoDestination.Attendance.route + (hash?.let { "?tab=$it" } ?: "")
        path == "/leaves" -> AinoDestination.Attendance.route + "?tab=leaves"
        path == "/organization" -> AinoDestination.Organization.route
        path == "/manager" -> AinoDestination.Manager.route
        // Web TAB_ALIASES: `labels` → Agile Config (its Labels tab).
        path == "/admin" -> when (query["tab"]) {
            "agile", "labels" -> ADMIN_AGILE_ROUTE
            "projects" -> ADMIN_PROJECTS_ROUTE
            // Web opens the UserDrawer for `userId`; Android's equivalent is the user detail page.
            "users" -> query["userId"]?.toLongOrNull()?.let(::adminUserRoute) ?: adminSectionRoute("users")
            in ADMIN_SECTION_PAGE_KEYS -> adminSectionRoute(query.getValue("tab"))
            in ADMIN_PAYROLL_PAGE_KEYS -> adminPayrollRoute(query.getValue("tab"))
            else -> AinoDestination.Admin.route
        }
        path == "/agile-settings" -> ADMIN_AGILE_ROUTE
        path == "/projects" -> ADMIN_PROJECTS_ROUTE
        path == "/tenants" -> AinoDestination.Tenants.route
        path == "/profile/face" -> PROFILE_FACE_ROUTE
        path.startsWith("/meeting/") && path.endsWith("/room") -> meetingRoomRoute(path.removePrefix("/meeting/").removeSuffix("/room"))
        path.startsWith("/meeting/") -> meetingRoute(path.removePrefix("/meeting/"))
        path.startsWith("/huddle/") -> huddleRoute(path.removePrefix("/huddle/"))
        else -> null
    }
}

fun chatThreadRoute(conversationId: Long): String {
    require(conversationId > 0) { "conversationId must be positive" }
    return "chat/$conversationId"
}

/** Maps detail routes to their owning tab so a chat thread keeps Chat selected. */
fun bottomBarRoute(route: String?): String? = when (route) {
    AinoDestination.ChatThread.route -> AinoDestination.Chat.route
    TASK_LINK_ROUTE -> AinoDestination.Tasks.route
    SPRINT_INSIGHTS_ROUTE -> AinoDestination.Tasks.route
    else -> route
}

/**
 * Body copy for a More-sheet destination without a native screen. The Tenants
 * console is web / desktop only by product decision (P10.2): its routes need
 * the platform realm, which Android does not sign in to.
 */
fun placeholderMessage(destination: AinoDestination): String = when (destination) {
    AinoDestination.Tenants -> "The platform console is available on the web and desktop apps. Sign in there to manage tenants."
    else -> "This feature module is next in the native rollout."
}

private val roleLevels = mapOf(
    "employee" to 1, "team_lead" to 2, "manager" to 3,
    "hr_admin" to 4, "super_admin" to 5, "platform_admin" to 6,
)

/**
 * More-sheet items, in the exact order of `MobileTabBar.tsx:52-61` (§2):
 * Notes, Attendance, Organization, My Team, Admin, Tenants.
 */
fun availableMoreDestinations(
    role: String,
    hasReports: Boolean,
    features: Map<String, Boolean> = emptyMap(),
    orgId: Long? = null,
): List<AinoDestination> = buildList {
    val level = roleLevels[role] ?: 1
    if (features["notes"] == true) add(AinoDestination.Notes)
    if (features["attendance"] == true) add(AinoDestination.Attendance)
    if (orgId != null || role == "platform_admin") add(AinoDestination.Organization)
    if (level >= 2 || hasReports) add(AinoDestination.Manager)
    if (level >= 4) add(AinoDestination.Admin)
    if (role == "platform_admin") add(AinoDestination.Tenants)
}

/**
 * Bottom-bar visibility, fail-closed by feature (§2): Calendar/Tasks/Chat are
 * gated by `tenant_features`; Home and More always show. Attendance no longer
 * appears in the bar.
 */
fun visibleBottomDestinations(
    features: Map<String, Boolean>,
    ungatedPlatformAdmin: Boolean = false,
): List<AinoDestination> =
    bottomDestinations.filter { destination ->
        if (ungatedPlatformAdmin) return@filter true
        when (destination) {
            AinoDestination.Calendar -> features["calendar"] == true
            AinoDestination.Tasks -> features["tasks"] == true
            AinoDestination.Chat -> features["chat"] == true
            else -> true
        }
    }