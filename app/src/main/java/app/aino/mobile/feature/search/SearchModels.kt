package app.aino.mobile.feature.search

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

// ── Response (`GET /api/search?q=` → bare `{tasks, notes, users, events, leaves, sprints, logs}`) ──

@Serializable
data class SearchResults(
    val tasks: List<SearchTask> = emptyList(),
    val notes: List<SearchNote> = emptyList(),
    val users: List<SearchUser> = emptyList(),
    val events: List<SearchEvent> = emptyList(),
    val leaves: List<SearchLeave> = emptyList(),
    val sprints: List<SearchSprint> = emptyList(),
    val logs: List<SearchLog> = emptyList(),
) {
    fun isEmpty(): Boolean = tasks.isEmpty() && notes.isEmpty() && users.isEmpty() && events.isEmpty() &&
        leaves.isEmpty() && sprints.isEmpty() && logs.isEmpty()
}

@Serializable
data class SearchTask(
    @Serializable(with = AnyAsString::class) val id: String = "",
    val title: String? = null,
    /** `ts_headline` output: plain text with `<b>` around the matched words. */
    val snippet: String? = null,
    val status: String? = null,
)

/** Notebook page ids are strings. */
@Serializable
data class SearchNote(
    @Serializable(with = AnyAsString::class) val id: String = "",
    val title: String? = null,
    val snippet: String? = null,
)

@Serializable
data class SearchUser(
    @Serializable(with = AnyAsString::class) val id: String = "",
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String? = null,
)

@Serializable
data class SearchEvent(
    @Serializable(with = AnyAsString::class) val id: String = "",
    val title: String? = null,
    val description: String? = null,
    @SerialName("start_time") val startTime: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
)

/** `date` is a TEXT `YYYY-MM-DD`; `duration` is `full` | `half` | `quarter`. */
@Serializable
data class SearchLeave(
    @Serializable(with = AnyAsString::class) val id: String = "",
    @Serializable(with = AnyAsString::class) val date: String = "",
    @SerialName("leave_type") val leaveType: String = "",
    @Serializable(with = AnyAsString::class) val duration: String = "",
    val status: String? = null,
    val reason: String? = null,
)

@Serializable
data class SearchSprint(
    @Serializable(with = AnyAsString::class) val id: String = "",
    val name: String? = null,
    val goal: String? = null,
    val status: String? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

@Serializable
data class SearchLog(
    @Serializable(with = AnyAsString::class) val id: String = "",
    val action: String? = null,
    @SerialName("entity_type") val entityType: String? = null,
    @SerialName("actor_name") val actorName: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Any JSON primitive as its string content (ids may be numbers or strings); null → "". */
internal object AnyAsString : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.aino.search.AnyAsString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: return decoder.decodeString()
        if (element is JsonNull) return ""
        return (element as? JsonPrimitive)?.content ?: element.toString()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

// ── Rules (useGlobalSearch) ──────────────────────────────────────────────────

/** `q.trim().length < 2` short-circuits both the server call and the nav filter. */
const val SEARCH_MIN_CHARS = 2
/** `setTimeout(() => doSearch(val), 350)`. */
const val SEARCH_DEBOUNCE_MS = 350L
/** Server `term = query.trim().slice(0, 100)`. */
const val SEARCH_MAX_CHARS = 100

fun isSearchable(query: String): Boolean = query.trim().length >= SEARCH_MIN_CHARS

/** The term actually sent: trimmed and capped like the server. */
fun searchTerm(query: String): String = query.trim().take(SEARCH_MAX_CHARS)

/** `constants/index.ts` ROLE_LEVEL. */
val ROLE_LEVEL: Map<String, Int> = mapOf(
    "employee" to 1, "team_lead" to 2, "manager" to 3, "hr_admin" to 4, "super_admin" to 5, "platform_admin" to 6,
)

val ROLE_LABELS: Map<String, String> = mapOf(
    "employee" to "Employee", "team_lead" to "Team Lead", "manager" to "Manager",
    "hr_admin" to "HR Admin", "super_admin" to "Super Admin", "platform_admin" to "Platform Admin",
)

/** Lucide icon keys of `NAV_INDEX`, mapped to Material icons by the screen. */
enum class NavIcon {
    Home, Calendar, CheckSquare, FileText, MessageSquare, CalendarCheck, Palmtree, BarChart3, FileEdit,
    Building2, ClipboardList, Wallet, Users, Settings, User, UserPlus, Download, ScrollText, RefreshCw, Building,
}

data class NavItem(
    val icon: NavIcon,
    val title: String,
    val sub: String,
    val path: String,
    val keywords: String,
    val minRole: String? = null,
    val excludeRole: String? = null,
)

/** useGlobalSearch `NAV_INDEX`, verbatim. */
val NAV_INDEX: List<NavItem> = listOf(
    NavItem(NavIcon.Home, "Dashboard", "Home overview & time tracker", "/", "home overview clock tracker"),
    NavItem(NavIcon.Calendar, "Calendar", "Events, reminders & schedules", "/calendar", "events reminders schedule"),
    NavItem(NavIcon.CheckSquare, "Tasks", "My tasks & assignments", "/tasks", "todo assignments work tickets"),
    NavItem(NavIcon.FileText, "Notes", "Personal notebook", "/notes", "notebook journal writing pages"),
    NavItem(NavIcon.MessageSquare, "Chat", "Team messaging", "/chat", "messages messaging team direct"),
    NavItem(NavIcon.CalendarCheck, "Attendance", "Attendance calendar, leaves, manual entry & analytics", "/attendance", "attendance present absent calendar overview"),
    NavItem(NavIcon.Palmtree, "Leaves", "Leave requests & history", "/attendance#leaves", "vacation time off absence sick holiday request"),
    NavItem(NavIcon.BarChart3, "Analytics", "Work hours & productivity stats", "/attendance#analytics", "reports hours productivity stats charts"),
    NavItem(NavIcon.FileEdit, "Manual Entry", "Log work hours manually", "/attendance#manual-entry", "clock time log entry hours manual"),
    NavItem(NavIcon.Building2, "Organization", "Org profile & settings", "/organization", "company settings profile org details", excludeRole = "platform_admin"),
    NavItem(NavIcon.ClipboardList, "Leave Policy", "Leave balances & public holidays", "/attendance#leaves", "balance quota leave entitlement policy"),
    NavItem(NavIcon.Wallet, "Leave Balances", "My leave balances & quotas", "/attendance#leaves", "quota remaining sick planned balance"),
    NavItem(NavIcon.Palmtree, "Holidays", "Company public holidays", "/attendance#leaves", "public holiday national bank calendar"),
    NavItem(NavIcon.Users, "Manager Dashboard", "Team approvals & reports", "/manager", "approve team overtime manual reports pending", minRole = "team_lead"),
    NavItem(NavIcon.Settings, "Admin Panel", "User & org management", "/admin", "admin manage settings panel", minRole = "hr_admin"),
    NavItem(NavIcon.User, "User Management", "View & edit user accounts", "/admin?tab=users", "users employees accounts manage", minRole = "hr_admin"),
    NavItem(NavIcon.UserPlus, "Create User", "Add a new user account", "/admin?tab=create", "new user create add register", minRole = "hr_admin"),
    NavItem(NavIcon.Download, "Import Users", "Bulk import from CSV / JSON", "/admin?tab=import", "bulk import csv json users batch", minRole = "hr_admin"),
    NavItem(NavIcon.ScrollText, "Audit Logs", "System activity history", "/admin?tab=audit", "logs history activity events actions audit", minRole = "hr_admin"),
    NavItem(NavIcon.RefreshCw, "Role Requests", "Pending role change requests", "/admin?tab=role-requests", "role promotion request pending", minRole = "hr_admin"),
    NavItem(NavIcon.Wallet, "Payroll", "Pay periods & payroll export", "/admin?tab=payroll", "pay salary export hours period payroll", minRole = "hr_admin"),
    NavItem(NavIcon.Building, "Org Structure", "Departments, teams & org chart", "/admin?tab=structure", "departments teams structure chart", minRole = "super_admin"),
    NavItem(NavIcon.Building, "Tenant Management", "Manage tenants, organizations & databases", "/tenants", "org tenant company organizations database platform console", minRole = "platform_admin"),
    NavItem(NavIcon.ClipboardList, "Leave Policies", "Configure leave quotas & accrual", "/attendance#leaves", "policy accrual quota configure sick", minRole = "hr_admin"),
    NavItem(NavIcon.Users, "All Leave Balances", "View all employees' leave balances", "/attendance#leaves", "all balances employees leave", minRole = "hr_admin"),
)

/** `visibleNav`: role-gated by `minRole` level and `excludeRole`. */
fun visibleNav(role: String?): List<NavItem> {
    val level = ROLE_LEVEL[role] ?: 1
    return NAV_INDEX.filter { n ->
        (n.minRole == null || level >= (ROLE_LEVEL[n.minRole] ?: 1)) && (n.excludeRole == null || n.excludeRole != role)
    }
}

/** `navResults`: client-side substring match on title/sub/keywords, top 6. */
fun navResults(query: String, role: String?): List<NavItem> {
    if (!isSearchable(query)) return emptyList()
    val lower = query.trim().lowercase()
    return visibleNav(role).filter {
        it.title.lowercase().contains(lower) || it.sub.lowercase().contains(lower) || it.keywords.lowercase().contains(lower)
    }.take(6)
}

// ── Presentation rows (GlobalSearch.tsx) ─────────────────────────────────────

enum class SearchKind { Nav, Task, Note, Event, Leave, Sprint, User, Log }

/** Badge colours as ARGB; null means the theme default (`--surface` / `--text-muted`). */
data class SearchBadge(val text: String, val fg: Long? = null, val bg: Long? = null, val go: Boolean = false)

data class SearchRow(
    val key: String,
    val kind: SearchKind,
    val title: String,
    val snippet: String?,
    /** Snippet contains `<b>` highlight markup (task `ts_headline`). */
    val snippetHtml: Boolean = false,
    val badge: SearchBadge? = null,
    val link: String,
    val navIcon: NavIcon? = null,
    /** People rows: a present avatar replaces the icon. */
    val avatarUrl: String? = null,
)

data class SearchSection(val title: String, val rows: List<SearchRow>)

private val LEAVE_STATUS_COLOR = mapOf(
    "approved" to 0xFF16A34A, "pending" to 0xFFD97706, "rejected" to 0xFFDC2626, "withdraw_pending" to 0xFF0284C7,
)
private val SPRINT_STATUS_COLOR = mapOf("active" to 0xFF16A34A, "planned" to 0xFF2563EB, "completed" to 0xFF6B7280)

/** `.status-*` classes in GlobalSearch.module.css as (fg, bg). */
private val TASK_STATUS_COLORS = mapOf(
    "done" to (0xFF86EFAC to 0xFF14532D),
    "in_progress" to (0xFF93C5FD to 0xFF1E3A5F),
    "in_review" to (0xFFBAE6FD to 0xFF0F3B59),
    "todo" to (0xFF94A3B8 to 0xFF292929),
)

/** useGlobalSearch `navigateToItem` destinations. */
fun searchLink(kind: SearchKind, id: String, navPath: String? = null): String = when (kind) {
    SearchKind.Nav -> navPath ?: "/"
    SearchKind.Task -> "/tasks?taskId=$id"
    SearchKind.Note -> "/notes?pageId=$id"
    SearchKind.Event -> "/calendar"
    SearchKind.Leave -> "/attendance#leaves"
    SearchKind.Sprint -> "/manager"
    SearchKind.User -> "/admin?tab=users&userId=$id"
    SearchKind.Log -> "/admin?tab=audit"
}

/** People avatar src: absolute paths as-is, bare file names under `/uploads/avatars/`. */
fun searchAvatarPath(avatar: String?): String? = avatar?.takeIf(String::isNotEmpty)?.let {
    if (it.startsWith("/")) it else "/uploads/avatars/$it"
}

/**
 * GlobalSearch sections in render order: Pages & Features, Tasks, Notes,
 * Calendar Events, Leave Requests, Sprints, People, Audit Logs. Empty
 * buckets are omitted.
 */
fun searchSections(
    nav: List<NavItem>,
    results: SearchResults?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): List<SearchSection> {
    val sections = mutableListOf<SearchSection>()
    fun add(title: String, rows: List<SearchRow>) { if (rows.isNotEmpty()) sections += SearchSection(title, rows) }

    add("Pages & Features", nav.mapIndexed { i, n ->
        SearchRow(
            key = "nav-$i-${n.path}", kind = SearchKind.Nav, title = n.title, snippet = n.sub,
            badge = SearchBadge("Go", go = true), link = searchLink(SearchKind.Nav, "", n.path), navIcon = n.icon,
        )
    })
    if (results != null) {
        add("Tasks", results.tasks.map { t ->
            val colors = TASK_STATUS_COLORS[t.status]
            SearchRow(
                key = "task-${t.id}", kind = SearchKind.Task, title = t.title.orEmpty(),
                snippet = t.snippet?.takeIf(String::isNotEmpty), snippetHtml = true,
                badge = t.status?.let { SearchBadge(it.replace("_", " "), colors?.first, colors?.second) },
                link = searchLink(SearchKind.Task, t.id),
            )
        })
        add("Notes", results.notes.map { n ->
            SearchRow("note-${n.id}", SearchKind.Note, n.title.orEmpty(), n.snippet?.takeIf(String::isNotEmpty), link = searchLink(SearchKind.Note, n.id))
        })
        add("Calendar Events", results.events.map { e ->
            val start = parseServerInstant(e.startTime)
            val dateStr = when {
                start == null -> e.startTime.orEmpty()
                e.allDay -> webLocaleDate(start, zone, locale)
                else -> "${webLocaleDate(start, zone, locale)} ${webLocaleTime(start, zone, locale)}"
            }
            val description = e.description?.takeIf(String::isNotEmpty)?.let { " · ${it.take(60)}" }.orEmpty()
            SearchRow("event-${e.id}", SearchKind.Event, e.title.orEmpty(), dateStr + description, link = searchLink(SearchKind.Event, e.id))
        })
        add("Leave Requests", results.leaves.map { l ->
            val reason = l.reason?.takeIf(String::isNotEmpty)?.let { " · ${it.take(60)}" }.orEmpty()
            SearchRow(
                key = "leave-${l.id}", kind = SearchKind.Leave,
                title = "${l.leaveType.replaceFirstChar { it.uppercaseChar() }} leave — ${l.date}",
                snippet = "${l.duration} day$reason",
                badge = SearchBadge(l.status.orEmpty(), LEAVE_STATUS_COLOR[l.status]),
                link = searchLink(SearchKind.Leave, l.id),
            )
        })
        add("Sprints", results.sprints.map { sp ->
            val goal = sp.goal?.takeIf(String::isNotEmpty)?.let { " · ${it.take(60)}" }.orEmpty()
            SearchRow(
                key = "sprint-${sp.id}", kind = SearchKind.Sprint, title = sp.name.orEmpty(),
                snippet = "${sp.startDate.orEmpty()} → ${sp.endDate.orEmpty()}$goal",
                badge = SearchBadge(sp.status.orEmpty(), SPRINT_STATUS_COLOR[sp.status]),
                link = searchLink(SearchKind.Sprint, sp.id),
            )
        })
        add("People", results.users.map { u ->
            SearchRow(
                key = "user-${u.id}", kind = SearchKind.User, title = u.fullName ?: u.username.orEmpty(),
                snippet = u.email, badge = SearchBadge(ROLE_LABELS[u.role] ?: u.role.orEmpty()),
                link = searchLink(SearchKind.User, u.id), avatarUrl = searchAvatarPath(u.avatar),
            )
        })
        add("Audit Logs", results.logs.map { l ->
            val actor = l.actorName?.takeIf(String::isNotEmpty)?.let { "by $it · " }.orEmpty()
            val date = parseServerInstant(l.createdAt)?.let { webLocaleDate(it, zone, locale) } ?: l.createdAt.orEmpty()
            SearchRow(
                "log-${l.id}", SearchKind.Log, "${l.action.orEmpty()} — ${l.entityType.orEmpty()}", actor + date,
                link = searchLink(SearchKind.Log, l.id),
            )
        })
    }
    return sections
}

/** One run of a `ts_headline` snippet; [bold] runs were wrapped in `<b>`. */
data class SnippetRun(val text: String, val bold: Boolean)

/** Parses `<b>`-highlighted snippet markup; other tags are stripped and basic entities decoded. */
fun parseSnippetHighlights(html: String): List<SnippetRun> {
    val runs = mutableListOf<SnippetRun>()
    var bold = false
    var index = 0
    val tag = Regex("<\\s*(/?)\\s*([a-zA-Z0-9]+)[^>]*>")
    fun emit(raw: String) {
        if (raw.isEmpty()) return
        val text = decodeEntities(raw)
        val last = runs.lastOrNull()
        if (last != null && last.bold == bold) runs[runs.lastIndex] = last.copy(text = last.text + text) else runs += SnippetRun(text, bold)
    }
    for (match in tag.findAll(html)) {
        emit(html.substring(index, match.range.first))
        if (match.groupValues[2].equals("b", ignoreCase = true) || match.groupValues[2].equals("strong", ignoreCase = true)) {
            bold = match.groupValues[1].isEmpty()
        }
        index = match.range.last + 1
    }
    emit(html.substring(index))
    return runs
}

private fun decodeEntities(text: String): String = text
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
    .replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

// ── Dates (`toLocaleDateString()` / `toLocaleTimeString([], {hour: "2-digit", minute: "2-digit"})`) ──

internal fun parseServerInstant(value: String?): Instant? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
    runCatching { return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }
    return null
}

/** Numeric short date with a four-digit year, like `toLocaleDateString()` ("9/25/2026" in en-US). */
fun webLocaleDate(instant: Instant, zone: ZoneId, locale: Locale): String {
    var pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
    if (!pattern.contains("yyyy")) pattern = pattern.replace(Regex("y+"), "yyyy")
    return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone))
}

/** Two-digit hour and minute in the locale's clock ("09:30 AM" in en-US). */
fun webLocaleTime(instant: Instant, zone: ZoneId, locale: Locale): String {
    val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)
        .replace(Regex("(?<![hH])([hH])(?![hH])"), "$1$1")
    return DateTimeFormatter.ofPattern(pattern, locale).format(instant.atZone(zone))
}
