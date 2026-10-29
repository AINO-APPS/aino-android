package app.aino.mobile.feature.home

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A closed day range, both ends inclusive. */
data class DayRange(val start: LocalDate, val end: LocalDate)

/**
 * The current week for the home card: Monday 00:00 to Sunday 23:59 in the
 * device's timezone (spec D2). Not a rolling seven days and not ISO-week
 * numbering — the card says "this week", so a Sunday afternoon must still show
 * that morning's work under the same dates.
 */
fun weekRange(today: LocalDate): DayRange {
    val monday = today.minusDays(((today.dayOfWeek.value - DayOfWeek.MONDAY.value) + 7L) % 7L)
    return DayRange(monday, monday.plusDays(6))
}

/**
 * The last day before this week, i.e. the Sunday that closes the previous one.
 * The card asks for open tickets planned on or before it so slipped work is
 * pinned as Overdue instead of disappearing (spec D4).
 */
fun previousWeekEnd(monday: LocalDate): LocalDate = monday.minusDays(1)

/** How many rows the card shows before it collapses the rest into "＋N more". */
const val MY_WORK_ROW_LIMIT = 6

/** How far back the Overdue lookup reaches. Older slipped work is history. */
const val OVERDUE_DAYS = 30L

/** The workflow state a ticket reached by cancelling; never shown in "my work". */
const val CANCELLED_STATE_KEY = "cancelled"

/** One headed block of rows: Overdue, or a single day of the week. */
data class MyWorkGroup(val key: String, val label: String, val tasks: List<DashboardTask>)

/** What the card renders, already shaped by the rules below. */
data class MyWork(
    val mode: MyWorkMode,
    val title: String,
    /** Sprint mode only: the board the header navigates to. */
    val sprintId: Long? = null,
    val groups: List<MyWorkGroup> = emptyList(),
    /** Completed tickets this week — counted, never listed (spec D6). */
    val done: Int = 0,
    val total: Int = 0,
    /** Rows that fit nowhere because of [MY_WORK_ROW_LIMIT]. */
    val hidden: Int = 0,
    /** The lookup failed; the card must not claim the week is empty. */
    val failed: Boolean = false,
) {
    val isEmpty: Boolean get() = groups.all { it.tasks.isEmpty() }
}

enum class MyWorkMode { Sprint, Week }

/**
 * The Tasks tab the card's header opens.
 *
 * Its own enum rather than `feature.tasks.TaskTab`, which `feature/home` may not
 * import; [key] is what `TaskTab.fromKey` and the `?tab=` deep links expect.
 */
enum class MyWorkTarget(val key: String) {
    Sprint("sprint"),
    Scheduled("scheduled"),
}

/** Where the card's header goes: the sprint board, or the Scheduled tab. */
fun MyWork.target(): MyWorkTarget =
    if (mode == MyWorkMode.Sprint) MyWorkTarget.Sprint else MyWorkTarget.Scheduled

/**
 * The tickets that count as the viewer's own work: assigned to them, not
 * finished, not cancelled.
 *
 * Assignee is checked here rather than trusted from the request: the server's
 * `assignee=me` also returns tickets the viewer created and nobody picked up
 * (spec D5), and the date-range query does not carry the board's cancelled
 * exclusion (spec D14). Both rules have to be applied by the caller.
 */
fun myOpenTasks(tasks: List<DashboardTask>, userId: Long): List<DashboardTask> =
    tasks.filter { it.assignedTo == userId && it.completedAt == null && it.workflowStateKey != CANCELLED_STATE_KEY }

/** Completed tickets of the same set, for the header's "N of M done". */
fun myDoneCount(tasks: List<DashboardTask>, userId: Long): Int =
    tasks.count { it.assignedTo == userId && it.completedAt != null && it.workflowStateKey != CANCELLED_STATE_KEY }

/** Everything of the viewer's in [tasks], done and open, cancelled excluded. */
fun myWorkCount(tasks: List<DashboardTask>, userId: Long): Int =
    tasks.count { it.assignedTo == userId && it.workflowStateKey != CANCELLED_STATE_KEY }

private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE", Locale.US)

/**
 * Sprint mode: the viewer's open tickets in the running sprint, flat.
 *
 * A sprint is a container, so its rows are not grouped by day — a ticket in a
 * sprint may have no planned day at all (ADR-015: a sprint wins over a planned
 * day). The sprint endpoint orders by `created_at`, so the card sorts.
 */
fun sprintGroups(tasks: List<DashboardTask>): List<MyWorkGroup> =
    if (tasks.isEmpty()) emptyList()
    else listOf(MyWorkGroup("sprint", "", tasks.sortedBy { priorityRank(it.priority) }))

/**
 * Week mode: **Overdue** first (each row keeps its day in the label), then one
 * group per day of the week that has work, Monday through Sunday.
 *
 * Rows come back priority-ordered from the server for this path, which is the
 * order within a day; only the buckets are reordered here.
 */
fun weekGroups(
    overdue: List<DashboardTask>,
    week: List<DashboardTask>,
    weekStart: LocalDate,
): List<MyWorkGroup> = buildList {
    if (overdue.isNotEmpty()) add(MyWorkGroup("overdue", "Overdue", overdue.sortedByDay()))
    val byDay = week.filter { it.plannedDate != null }
        .groupBy { LocalDate.parse(it.plannedDate) }
    (0L..6L).forEach { offset ->
        val day = weekStart.plusDays(offset)
        val dayTasks = byDay[day] ?: return@forEach
        add(MyWorkGroup(day.toString(), day.format(DAY_HEADER), dayTasks))
    }
}

/**
 * The day a row shows: only Overdue rows carry one, because the group header
 * already names the day everywhere else (spec D4).
 */
fun rowDayLabel(task: DashboardTask, groupKey: String): String? =
    if (groupKey != "overdue" || task.plannedDate == null) null
    else LocalDate.parse(task.plannedDate).format(ROW_DAY)

private val ROW_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)

/** Overdue rows read left-to-right by the day they slipped from. */
private fun List<DashboardTask>.sortedByDay(): List<DashboardTask> =
    sortedWith(compareBy({ it.plannedDate ?: "" }, { priorityRank(it.priority) }))

private fun priorityRank(priority: String?): Int = when (priority) {
    "high" -> 0
    "medium" -> 1
    "low" -> 2
    else -> 3
}

/**
 * Trim to [limit] rows across all groups, keeping group order and dropping a
 * group whose rows were all trimmed away. Returns the surviving groups and how
 * many rows were dropped.
 */
fun capGroups(groups: List<MyWorkGroup>, limit: Int = MY_WORK_ROW_LIMIT): Pair<List<MyWorkGroup>, Int> {
    var remaining = limit
    var hidden = 0
    val kept = buildList {
        groups.forEach { group ->
            if (remaining <= 0) {
                hidden += group.tasks.size
                return@forEach
            }
            val take = minOf(remaining, group.tasks.size)
            remaining -= take
            if (take > 0) add(group.copy(tasks = group.tasks.take(take)))
            hidden += group.tasks.size - take
        }
    }
    return kept to hidden
}
