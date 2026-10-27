package app.aino.mobile.feature.tasks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** `Tasks.tsx` tabs plus Android's Scheduled view; `key` is the `?tab=` value. */
enum class TaskTab(val key: String) {
    Backlog("backlog"), Scheduled("scheduled"), Sprint("sprint"), ServiceDesk("service-desk");

    companion object {
        fun fromKey(key: String?): TaskTab? = entries.firstOrNull { it.key == key }
    }
}

/** The Sprint tab has two surfaces: the board and the split planner (D21). */
enum class SprintView(val key: String) {
    Board("board"), Plan("plan");

    companion object {
        fun fromKey(key: String?): SprintView? = entries.firstOrNull { it.key == key }
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
    ColumnDef("pending", "New", "○", Tone.Muted),
    ColumnDef("in_progress", "In Progress", "◐", Tone.Warning),
    ColumnDef("in_review", "In Review", "◑", Tone.PrimaryLight),
    ColumnDef("done", "Done", "●", Tone.Success),
)

fun priorityOf(value: String?): PriorityOption = PRIORITIES.firstOrNull { it.value == value } ?: PRIORITIES[1]

fun columnOf(stateKey: String?): ColumnDef = COLUMNS.firstOrNull { it.id == stateKey } ?: COLUMNS[0]

/** The server's allow-list on `GET /tasks` and `GET /tasks/backlog`. */
private val SERVER_PRIORITIES = setOf("low", "medium", "high")

/** `useFilters` state; empty string = "All". */
data class TaskFilters(
    val assignee: String = "",
    val label: String = "",
    val priority: String = "",
    val status: String = "",
    val search: String = "",
    /** `?completed=1|0` overrides the stored preference for one view. */
    val completed: String = "",
    /** `?epic=<id>` narrows the list to one epic's children. */
    val epic: String = "",
)

/** Independent, Backlog-only predicates; resetting them never changes advanced filters. */
data class BacklogQuickFilters(
    val mine: Boolean = false,
    val high: Boolean = false,
    val unscheduled: Boolean = false,
) {
    val isAll: Boolean get() = !mine && !high && !unscheduled
    val count: Int get() = listOf(mine, high, unscheduled).count { it }
}

fun matchesPlanFilters(task: Task, filters: TaskFilters, quick: BacklogQuickFilters, userId: Long?): Boolean =
    matchesFilters(task, filters, userId, includeStatus = false) &&
        (filters.epic.isEmpty() || task.parentTaskId?.toString() == filters.epic) &&
        (!quick.mine || (userId != null && (task.assignedTo == userId || (task.userId == userId && task.assignedTo == null)))) &&
        (!quick.high || task.priority == "high") &&
        (!quick.unscheduled || task.plannedDate == null)

fun groupPlanBacklog(tasks: List<Task>, sprints: List<AvailableSprint>): List<BacklogGroup> {
    val bySprint = tasks.groupBy { it.sprintId }
    return buildList {
        bySprint.filterKeys { it != null }.forEach { (id, rows) ->
            val name = sprints.firstOrNull { it.id == id }?.name
                ?: rows.firstNotNullOfOrNull { it.sprint?.name } ?: "Sprint $id"
            add(BacklogGroup("sprint-$id", name, Tone.PrimaryLight, rows))
        }
        bySprint[null]?.let { add(BacklogGroup("backlog", "Backlog", null, it)) }
    }
}

/** `useFilters.filterCount` — status only counts on the Sprint tab. */
fun filterCount(filters: TaskFilters, tab: TaskTab): Int {
    val base = mutableListOf(filters.assignee, filters.label, filters.priority, filters.search.trim())
    if (tab == TaskTab.Sprint || tab == TaskTab.Scheduled) base += filters.status
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
    if (includeStatus) filters.status.takeIf(String::isNotEmpty)?.let { add("status" to it) }
    filters.search.trim().takeIf(String::isNotEmpty)?.let { add("search" to it) }
}

/** Sprint board: `GET /tasks?sprint_id=…&<plannerFilters>`. */
fun sprintTasksQuery(sprintId: Long, filters: TaskFilters): String =
    withQuery("tasks", listOf("sprint_id" to sprintId.toString()) + filterParams(filters, includeStatus = true))

/** Backlog page: `GET /tasks/backlog?<backlogFilters>&limit&offset`. */
fun backlogQuery(filters: TaskFilters, limit: Int, offset: Int): String =
    withQuery("tasks/backlog", filterParams(filters, includeStatus = false) + listOf("limit" to "$limit", "offset" to "$offset"))

fun searchQuery(q: String): String = withQuery("tasks/search", listOf("q" to q.trim()))

/** Days back (for overdue) and ahead covered by the Scheduled view. */
const val SCHEDULED_PAST_DAYS = 30L
const val SCHEDULED_AHEAD_DAYS = 30L

/** Scheduled view: `GET /tasks?start_date&end_date&<plannerFilters>` (planner-dated tasks in range). */
fun scheduledQuery(from: LocalDate, to: LocalDate, filters: TaskFilters): String =
    withQuery("tasks", listOf("start_date" to from.toString(), "end_date" to to.toString()) + filterParams(filters, includeStatus = true))

/** A dated section of the Scheduled view. `date == null` is the Overdue bucket. */
data class ScheduledGroup(val key: String, val label: String, val date: LocalDate?, val tasks: List<Task>)

private val DAY_HEADER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/**
 * Overdue (past planner date, not done) first, then one section per day from
 * today onward in date order. Completed past tasks are dropped — they are history.
 */
fun groupScheduled(tasks: List<Task>, today: LocalDate = LocalDate.now()): List<ScheduledGroup> {
    val dated = tasks.mapNotNull { t -> localDateOf(t.plannedDate)?.let { it to t } }
    val overdue = dated.filter { (d, t) -> d.isBefore(today) && !t.isCompleted }
        .sortedWith(compareBy<Pair<LocalDate, Task>> { it.first }.thenBy { priorityRank(it.second.priority) })
        .map { it.second }
    val upcoming = dated.filter { (d, _) -> !d.isBefore(today) }
        .groupBy({ it.first }, { it.second })
        .toSortedMap()
    return buildList {
        if (overdue.isNotEmpty()) add(ScheduledGroup("overdue", "Overdue", null, overdue))
        upcoming.forEach { (day, list) ->
            val label = when (ChronoUnit.DAYS.between(today, day)) {
                0L -> "Today"
                1L -> "Tomorrow"
                else -> day.format(DAY_HEADER)
            }
            add(ScheduledGroup(day.toString(), label, day, list.sortedWith(compareBy<Task> { it.isCompleted }.thenBy { priorityRank(it.priority) })))
        }
    }
}

private fun priorityRank(priority: String): Int = when (priority) { "high" -> 0; "medium" -> 1; else -> 2 }

/** `useBacklog` sort options, in `<select>` order. */
enum class BacklogSort(val key: String, val label: String) {
    /** Manual order: the server's LexoRank, shared by backlog and sprint (D8). */
    Rank("rank", "Manual order"),
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
    // Manual order is the server's LexoRank; the list arrives already ranked.
    BacklogSort.Rank -> tasks.sortedBy { it.rank.orEmpty() }
    BacklogSort.Priority -> tasks.sortedBy { mapOf("high" to 0, "medium" to 1, "low" to 2)[it.priority] ?: 1 }
    BacklogSort.Newest -> tasks.sortedByDescending { instantOf(it.createdAt)?.toEpochMilli() ?: 0L }
    BacklogSort.Oldest -> tasks.sortedBy { instantOf(it.createdAt)?.toEpochMilli() ?: 0L }
    BacklogSort.DueDate -> tasks.sortedWith(compareBy<Task> { it.dueDate == null }.thenBy { it.dueDate.orEmpty() })
    BacklogSort.Title -> tasks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
}

/** A display section of the backlog list (priority band or due-date bucket). */
data class BacklogGroup(val key: String, val label: String, val tone: Tone?, val tasks: List<Task>)

/**
 * Splits an already-sorted backlog into display sections. Priority and Due date
 * sorts are grouped; the other sorts return `null` (render a flat list). Order
 * inside each group is preserved and empty groups are dropped.
 */
fun groupBacklog(tasks: List<Task>, sort: BacklogSort, today: LocalDate = LocalDate.now()): List<BacklogGroup>? = when (sort) {
    BacklogSort.Priority -> {
        val byPriority = tasks.groupBy { priorityOf(it.priority).value }
        PRIORITIES.mapNotNull { p ->
            byPriority[p.value]?.takeIf { it.isNotEmpty() }?.let { BacklogGroup(p.value, "${p.label} priority", p.tone, it) }
        }
    }
    BacklogSort.DueDate -> {
        fun bucket(task: Task): String {
            val due = localDateOf(task.dueDate) ?: return "none"
            val diff = ChronoUnit.DAYS.between(today, due)
            return when {
                diff < 0 -> if (task.isCompleted) "past" else "overdue"
                diff == 0L -> "today"
                diff <= 7 -> "week"
                else -> "later"
            }
        }
        val byBucket = tasks.groupBy(::bucket)
        listOf(
            Triple("overdue", "Overdue", Tone.Danger),
            Triple("today", "Due today", Tone.Warning),
            Triple("week", "Next 7 days", Tone.PrimaryLight),
            Triple("later", "Later", Tone.Muted),
            Triple("none", "No due date", Tone.Muted),
            Triple("past", "Past due · completed", Tone.Success),
        ).mapNotNull { (key, label, tone) -> byBucket[key]?.let { BacklogGroup(key, label, tone, it) } }
    }
    else -> null
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

/** Keep the current pick, else the active sprint, else the first (Tasks.tsx effect). */
fun pickSprint(current: Long?, sprints: List<AvailableSprint>): Long? {
    if (sprints.isEmpty()) return current
    if (current != null && sprints.any { it.id == current }) return current
    return sprints.firstOrNull { it.status == "active" }?.id ?: sprints.first().id
}

/**
 * Where the Tasks page should land once the sprint list resolves.
 *
 * Once a team's sprint is running, the sprint is where the work is; the backlog
 * is only the pool of unplanned tickets. The landing only applies while the user
 * is still on the default Backlog tab and has not chosen a tab themselves, so an
 * explicit choice is never overridden.
 */
fun landingTab(current: TaskTab, sprints: List<AvailableSprint>, agileEnabled: Boolean): TaskTab {
    if (!agileEnabled) return current
    if (current != TaskTab.Backlog) return current
    return if (sprints.any { it.status == "active" }) TaskTab.Sprint else current
}

/**
 * Which empty state the sprint board shows.
 *
 * `Filtered` and `Empty` need different copy and different actions: filters hide
 * tickets that exist, so offering "import from the backlog" there is wrong.
 */
enum class BoardEmpty { None, Filtered, Empty }

/** Decide the board's empty state from what is visible and whether filters are on. */
fun boardEmptyState(hasTasks: Boolean, filterCount: Int): BoardEmpty = when {
    hasTasks -> BoardEmpty.None
    filterCount > 0 -> BoardEmpty.Filtered
    else -> BoardEmpty.Empty
}

/**
 * Which column a dragged card is over, in root coordinates.
 *
 * A column claims bounds from two places — its header and, when it is empty,
 * its dashed body — and the header must win: it sits above the body, and a
 * column whose body registered last would otherwise swallow drops aimed at its
 * own header. Testing headers first makes the header tappable regardless of
 * layout order.
 */
fun dropTargetKey(headers: Map<String, Rect>, bodies: Map<String, Rect>, pos: Offset): String? {
    headers.entries.firstOrNull { it.value.contains(pos) }?.let { return it.key }
    return bodies.entries.firstOrNull { it.value.contains(pos) }?.key
}

/** Roles allowed to drive the sprint lifecycle from the board. */
fun canManageSprint(role: String?): Boolean =
    role in setOf("team_lead", "manager", "super_admin", "hr_admin", "platform_admin")

/** Mirrors the server's `canChangeTaskStatus`.
 *
 *  Server rule (`modules/tasks/tasks.policy.ts:248-261`): the assignee, the
 *  reporter, an org admin, **or any member of the ticket's owning team**. The
 *  team clause landed with team ownership and is now live on every plan, so
 *  omitting it here would hide moves the server accepts.
 */
fun canChangeStatus(task: Task, userId: Long?, role: String?, teamId: Long? = null): Boolean {
    if (userId != null && (task.assignedTo == userId || task.userId == userId)) return true
    if (role in setOf("super_admin", "hr_admin", "platform_admin")) return true
    return teamId != null && task.teamId != null && teamId == task.teamId
}

const val STATUS_FORBIDDEN = "Only the assignee or reporter can change the status"

/** KanbanBoard `getColTasks`: by workflow_state_id, else by the workflow-state key. */
fun tasksForColumn(tasks: List<Task>, state: WorkflowState): List<Task> = tasks.filter { t ->
    (t.workflowStateId != null && state.id != 0L && t.workflowStateId == state.id) || t.workflowStateKey == state.key
}

/**
 * The neighbour ids for a one-step backlog move, as `(beforeId, afterId)`.
 *
 * The server's contract (`modules/tasks/tasks.rank.ts`): `before` is the rank
 * of the item that ends up **above** the moved ticket and `after` the one
 * **below**, and it rejects `before >= after`. The two neighbours must
 * therefore bracket the moved row. Sending two neighbours from the same side —
 * the previous behaviour in both directions — inverted the pair and 409'd
 * every attempt.
 *
 * Returns null when the move is impossible at that end of the list.
 */
fun rankNeighbours(orderedIds: List<Long>, taskId: Long, up: Boolean): Pair<Long?, Long?>? {
    val index = orderedIds.indexOf(taskId)
    if (index < 0) return null
    return if (up) {
        if (index == 0) null
        else orderedIds.getOrNull(index - 2) to orderedIds[index - 1]
    } else {
        if (index >= orderedIds.size - 1) null
        else orderedIds[index + 1] to orderedIds.getOrNull(index + 2)
    }
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

/**
 * Mentions to send with a comment: the ones picked from the suggestion list,
 * plus any `@Display Name` / `@username` typed by hand that matches exactly one
 * known person. Without a `data-user-id` chip the server cannot notify anyone,
 * so a typed mention used to be silently dropped. Longest names win, and the
 * match must end at a word boundary (`@Ann` does not match inside `@Anna`).
 */
fun resolveCommentMentions(text: String, picked: Map<String, Long>, users: List<AssignableUser>): Map<String, Long> {
    if ('@' !in text) return picked
    val result = picked.toMutableMap()
    // Text already claimed by a (longer) mention; a shorter label must not
    // match inside it (`@anna` inside `@Anna Joseph`).
    val claimed = mutableListOf<IntRange>()
    fun occurrences(label: String) =
        Regex("(?<![\\w@])@(${Regex.escape(label)})(?![\\w])", RegexOption.IGNORE_CASE).findAll(text)
            .filter { m -> claimed.none { it.first <= m.range.last && m.range.first <= it.last } }
            .toList()
    picked.keys.sortedByDescending { it.length }.forEach { label -> claimed += occurrences(label).map { it.range } }

    val candidates = users.flatMap { user ->
        listOfNotNull(user.display().takeIf(String::isNotBlank), user.username?.takeIf(String::isNotBlank)).map { it to user.id }
    }
    val byLabel = candidates.groupBy({ it.first.lowercase() }, { it })
    byLabel.values.sortedByDescending { it.first().first.length }.forEach { entries ->
        val (label, id) = entries.first()
        val matches = occurrences(label)
        if (matches.isEmpty()) return@forEach
        // Claimed even when ambiguous, so `@Vishnu V R` is not read as `@vishnu`.
        claimed += matches.map { it.range }
        // A label two different people share is ambiguous — leave it to the picker.
        if (entries.map { it.second }.distinct().size != 1) return@forEach
        if (result.keys.any { it.equals(label, ignoreCase = true) }) return@forEach
        // Use the text as typed so plainTextToHtml's exact replace finds it.
        result[matches.first().groupValues[1]] = id
    }
    return result
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
    val done = tasks.count { it.isCompleted }
    val inProgress = tasks.count { it.workflowStateCategory == "in_progress" }
    return TaskStats(total, done, inProgress, if (total > 0) Math.round(done * 100f / total) else 0)
}

enum class HistoryPart { Plain, Old, New }

private val HISTORY_FIELDS = mapOf(
    "workflow_state" to "status", "title" to "title", "description" to "description", "priority" to "priority",
    "assigned_to" to "assignee", "due_date" to "due date", "planned_date" to "schedule", "labels" to "labels",
    "story_points" to "story points", "work_item_type" to "work item type", "sprint" to "sprint",
    "parent" to "parent task", "is_blocked" to "blocked status", "project" to "project",
)

/** TaskDetailModal history `actionText()` as styled runs. */
fun historyText(h: TaskHistoryEntry): List<Pair<HistoryPart, String>> = when {
    h.action == "created" && h.field == "planned_date" && !h.oldValue.isNullOrEmpty() ->
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
