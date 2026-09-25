package app.aino.mobile.feature.tasks

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The three `Tasks.tsx` tabs; `key` is the web `?tab=` value. */
enum class TaskTab(val key: String) {
    Sprint("sprint"), Backlog("backlog"), ServiceDesk("service-desk");

    companion object {
        fun fromKey(key: String?): TaskTab? = entries.firstOrNull { it.key == key }
    }
}

/** `tasks/constants.ts` PRIORITIES — colour is a web token name resolved by the UI. */
data class PriorityOption(val value: String, val label: String, val icon: String, val tone: Tone)

/** `tasks/constants.ts` COLUMNS. */
data class ColumnDef(val id: String, val label: String, val icon: String, val tone: Tone)

/** CSS variables the constants reference (`var(--danger)` …). */
enum class Tone { Danger, Warning, Success, Muted, PrimaryLight }

val PRIORITIES = listOf(
    PriorityOption("high", "High", "●", Tone.Danger),
    PriorityOption("medium", "Medium", "●", Tone.Warning),
    PriorityOption("low", "Low", "●", Tone.Success),
)

val COLUMNS = listOf(
    ColumnDef("pending", "To Do", "○", Tone.Muted),
    ColumnDef("in_progress", "In Progress", "◐", Tone.Warning),
    ColumnDef("in_review", "In Review", "◑", Tone.PrimaryLight),
    ColumnDef("done", "Done", "●", Tone.Success),
)

fun priorityOf(value: String?): PriorityOption = PRIORITIES.firstOrNull { it.value == value } ?: PRIORITIES[1]

fun columnOf(status: String?): ColumnDef = COLUMNS.firstOrNull { it.id == status } ?: COLUMNS[0]

/** The server's allow-list on `GET /tasks` and `GET /tasks/backlog`. */
private val SERVER_PRIORITIES = setOf("low", "medium", "high")
private val SERVER_STATUSES = setOf("pending", "in_progress", "in_review", "done")

/** `useFilters` state; empty string = "All". */
data class TaskFilters(
    val assignee: String = "",
    val label: String = "",
    val priority: String = "",
    val status: String = "",
    val search: String = "",
)

/** `useFilters.filterCount` — status only counts on the Sprint tab. */
fun filterCount(filters: TaskFilters, tab: TaskTab): Int {
    val base = mutableListOf(filters.assignee, filters.label, filters.priority, filters.search.trim())
    if (tab == TaskTab.Sprint) base += filters.status
    return base.count(String::isNotEmpty)
}

private fun encode(params: List<Pair<String, String>>): String =
    params.joinToString("&") { (key, value) -> "$key=" + URLEncoder.encode(value, "UTF-8") }

private fun withQuery(path: String, params: List<Pair<String, String>>): String =
    if (params.isEmpty()) path else "$path?" + encode(params)

/** `plannerFilters` / `backlogFilters` (the backlog never sends a status). */
private fun filterParams(filters: TaskFilters, includeStatus: Boolean): List<Pair<String, String>> = buildList {
    if (filters.assignee.isNotEmpty()) add("assignee" to filters.assignee)
    if (filters.label.isNotEmpty()) add("label" to filters.label)
    filters.priority.takeIf { it in SERVER_PRIORITIES }?.let { add("priority" to it) }
    if (includeStatus) filters.status.takeIf { it in SERVER_STATUSES }?.let { add("status" to it) }
    filters.search.trim().takeIf(String::isNotEmpty)?.let { add("search" to it) }
}

/** Sprint board: `GET /tasks?sprint_id=…&<plannerFilters>`. */
fun sprintTasksQuery(sprintId: Long, filters: TaskFilters): String =
    withQuery("tasks", listOf("sprint_id" to sprintId.toString()) + filterParams(filters, includeStatus = true))

/** Backlog page: `GET /tasks/backlog?<backlogFilters>&limit&offset`. */
fun backlogQuery(filters: TaskFilters, limit: Int, offset: Int): String =
    withQuery("tasks/backlog", filterParams(filters, includeStatus = false) + listOf("limit" to "$limit", "offset" to "$offset"))

fun searchQuery(q: String): String = withQuery("tasks/search", listOf("q" to q.trim()))

/** `useBacklog` sort options, in `<select>` order. */
enum class BacklogSort(val key: String, val label: String) {
    Priority("priority", "Priority"),
    Newest("newest", "Newest first"),
    Oldest("oldest", "Oldest first"),
    DueDate("due_date", "Due date"),
    Title("title", "Title A-Z"),
}

private fun instantOf(value: String?): Instant? = value?.let {
    runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(it) }.getOrNull()
        ?: runCatching { LocalDate.parse(it.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
}

/** `sortedBacklogTasks`; stable like `Array.prototype.sort`. */
fun sortBacklog(tasks: List<Task>, sort: BacklogSort): List<Task> = when (sort) {
    BacklogSort.Priority -> tasks.sortedBy { mapOf("high" to 0, "medium" to 1, "low" to 2)[it.priority] ?: 1 }
    BacklogSort.Newest -> tasks.sortedByDescending { instantOf(it.createdAt)?.toEpochMilli() ?: 0L }
    BacklogSort.Oldest -> tasks.sortedBy { instantOf(it.createdAt)?.toEpochMilli() ?: 0L }
    BacklogSort.DueDate -> tasks.sortedWith(compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate.orEmpty() })
    BacklogSort.Title -> tasks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

/** Accepts `YYYY-MM-DD` or an ISO timestamp; the server's DATE columns are plain dates. */
fun localDateOf(value: String?): LocalDate? = value?.takeIf { it.length >= 10 }?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

/** `utils.formatDueDate`. */
fun formatDueDate(value: String?, today: LocalDate = LocalDate.now()): String? {
    val date = localDateOf(value) ?: return null
    val diff = ChronoUnit.DAYS.between(today, date)
    return when {
        diff == 0L -> "Today"
        diff == 1L -> "Tomorrow"
        diff == -1L -> "Yesterday"
        diff < 0 -> "${-diff}d overdue"
        diff <= 7 -> "${diff}d left"
        else -> date.format(MONTH_DAY)
    }
}

/** `utils.formatDate`. */
fun formatDate(value: String?): String = localDateOf(value)?.format(MONTH_DAY_YEAR).orEmpty()

/** `utils.isDueOverdue`. */
fun isDueOverdue(value: String?, today: LocalDate = LocalDate.now()): Boolean =
    localDateOf(value)?.isBefore(today) == true

/** `utils.formatRelativeTime`. */
fun formatRelativeTime(value: String?, now: Instant = Instant.now()): String {
    val at = instantOf(value) ?: return ""
    val minutes = ChronoUnit.MINUTES.between(at, now)
    if (minutes < 1) return "just now"
    if (minutes < 60) return "${minutes}m ago"
    val hours = minutes / 60
    if (hours < 24) return "${hours}h ago"
    val days = hours / 24
    if (days < 7) return "${days}d ago"
    if (days < 30) return "${days / 7}w ago"
    return at.atZone(ZoneId.systemDefault()).toLocalDate().format(MONTH_DAY)
}

private val LOCALE_STRING = DateTimeFormatter.ofPattern("M/d/yyyy, h:mm:ss a", Locale.US)

/** `new Date(x).toLocaleString()` (en-US). */
fun formatLocaleString(value: String?): String =
    instantOf(value)?.atZone(ZoneId.systemDefault())?.format(LOCALE_STRING).orEmpty()

/** TasksHeader `daysLeft` (never negative). */
fun sprintDaysLeft(endDate: String, today: LocalDate = LocalDate.now()): Long {
    val end = localDateOf(endDate) ?: return 0
    return maxOf(0, ChronoUnit.DAYS.between(today, end))
}

/** The Sprint tab header subtitle. */
fun sprintSubtitle(sprint: AvailableSprint?, today: LocalDate = LocalDate.now()): String {
    if (sprint == null) return "Loading sprint…"
    val range = "${sprint.startDate.take(10)} → ${sprint.endDate.take(10)}"
    return if (sprint.status == "paused") "$range • Paused" else "$range • ${sprintDaysLeft(sprint.endDate, today)}d remaining"
}

/** Keep the current pick, else the active sprint, else the first (Tasks.tsx effect). */
fun pickSprint(current: Long?, sprints: List<AvailableSprint>): Long? {
    if (sprints.isEmpty()) return current
    if (current != null && sprints.any { it.id == current }) return current
    return sprints.firstOrNull { it.status == "active" }?.id ?: sprints.first().id
}

/** Roles allowed to drive the sprint lifecycle from the board. */
fun canManageSprint(role: String?): Boolean =
    role in setOf("team_lead", "manager", "super_admin", "hr_admin", "platform_admin")

/** KanbanBoard `getColTasks`: by workflow_state_id, else by the legacy status key. */
fun tasksForColumn(tasks: List<Task>, state: WorkflowState): List<Task> = tasks.filter { t ->
    (t.workflowStateId != null && state.id != 0L && t.workflowStateId == state.id) || t.status == state.key
}

/** AgilePickers `formatPoints`: 1.00 → "1", 0.50 → "0.5". */
fun formatPoints(value: Double?): String {
    if (value == null) return ""
    if (value == Math.floor(value) && !value.isInfinite()) return value.toLong().toString()
    return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
}

/** Story-point chip equality (`String(value ?? "") === String(v ?? "")`), tolerant of "5" vs 5.0. */
fun samePoints(a: String?, b: String?): Boolean {
    if (a.isNullOrEmpty() || b.isNullOrEmpty()) return a.isNullOrEmpty() && b.isNullOrEmpty()
    val x = a.toDoubleOrNull()
    val y = b.toDoubleOrNull()
    return if (x != null && y != null) x == y else a == b
}

private val ENTITIES = mapOf("&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&amp;" to "&")

/** `utils.stripHtml` — the element's `textContent`. */
fun stripHtml(html: String?): String {
    if (html.isNullOrEmpty()) return ""
    var text = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
    ENTITIES.forEach { (entity, char) -> text = text.replace(entity, char) }
    return text
}

/**
 * Plain text typed on Android → the HTML the web stores. `mentions` maps an
 * inserted `@Display Name` to its user id and becomes MentionInput's
 * `<span class="mention-chip" data-user-id>` chip.
 */
fun plainTextToHtml(text: String, mentions: Map<String, Long> = emptyMap()): String {
    if (text.isBlank()) return ""
    val tokens = mentions.keys.sortedByDescending { it.length }
    return text.trimEnd().split('\n').joinToString("") { line ->
        var escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        tokens.forEach { name ->
            val chip = "@" + name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            escaped = escaped.replace(chip, "<span class=\"mention-chip\" data-user-id=\"${mentions.getValue(name)}\" contenteditable=\"false\">$chip</span>")
        }
        "<p>${escaped.ifEmpty { "<br>" }}</p>"
    }
}

/** Server comment validation (`POST /tasks/:id/comments`). */
fun validateComment(content: String): String? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return "Comment cannot be empty"
    if (trimmed.length > 2000) return "Comment must be 2000 characters or less"
    return null
}

/** Local stats recomputation after an optimistic status change. */
fun recomputeStats(tasks: List<Task>): TaskStats {
    val total = tasks.size
    val done = tasks.count { it.status == "done" }
    val inProgress = tasks.count { it.status == "in_progress" }
    return TaskStats(total, done, inProgress, if (total > 0) Math.round(done * 100f / total) else 0)
}

/** `Pagination` range label: "1–25 of 40 tickets". */
fun paginationLabel(total: Int, limit: Int, offset: Int, itemLabel: String): String {
    if (total == 0) return "0 ${itemLabel}s"
    val from = offset + 1
    val to = minOf(offset + limit, total)
    return "$from–$to of $total ${itemLabel}${if (total != 1) "s" else ""}"
}

enum class HistoryPart { Plain, Old, New }

private val HISTORY_FIELDS = mapOf(
    "status" to "status", "title" to "title", "description" to "description", "priority" to "priority",
    "assigned_to" to "assignee", "due_date" to "due date", "date" to "schedule", "labels" to "labels",
    "story_points" to "story points", "work_item_type" to "work item type", "sprint" to "sprint",
    "parent" to "parent task", "is_blocked" to "blocked status", "project" to "project",
)

/** TaskDetailModal history `actionText()` as styled runs. */
fun historyText(h: TaskHistoryEntry): List<Pair<HistoryPart, String>> = when {
    h.action == "created" && h.field == "date" && !h.oldValue.isNullOrEmpty() ->
        listOf(HistoryPart.Plain to "carried forward from ", HistoryPart.Old to h.oldValue)
    h.action == "created" -> listOf(HistoryPart.Plain to "created this task")
    h.action == "comment_added" -> listOf(HistoryPart.Plain to "added a comment")
    h.action == "comment_edited" -> listOf(HistoryPart.Plain to "edited a comment")
    h.action == "comment_deleted" -> listOf(HistoryPart.Plain to "deleted a comment")
    h.action == "status_change" -> listOf(
        HistoryPart.Plain to "changed status from ", HistoryPart.Old to h.oldValue.orEmpty(),
        HistoryPart.Plain to " → ", HistoryPart.New to h.newValue.orEmpty(),
    )
    h.action == "scheduled" -> listOf(HistoryPart.Plain to "scheduled to ", HistoryPart.New to h.newValue.orEmpty())
    h.action == "unscheduled" -> listOf(HistoryPart.Plain to "moved to backlog")
    h.action == "updated" && !h.field.isNullOrEmpty() -> {
        val label = HISTORY_FIELDS[h.field] ?: h.field
        if (h.field == "description") listOf(HistoryPart.Plain to "updated $label")
        else listOf(
            HistoryPart.Plain to "updated $label: ", HistoryPart.Old to (h.oldValue?.ifEmpty { null } ?: "—"),
            HistoryPart.Plain to " → ", HistoryPart.New to (h.newValue?.ifEmpty { null } ?: "—"),
        )
    }
    else -> listOf(HistoryPart.Plain to h.action)
}

private val HISTORY_ICONS = mapOf(
    "created" to "+", "status_change" to "⇔", "updated" to "✎", "scheduled" to "▸", "unscheduled" to "□",
    "comment_added" to "•", "comment_edited" to "✎", "comment_deleted" to "×", "deleted" to "×",
)

fun historyIcon(action: String): String = HISTORY_ICONS[action] ?: "✎"
