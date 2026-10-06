package app.aino.mobile.feature.tasks

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Targeted realtime sync for Tasks. The server pushes
 * `task_assigned { taskId, title }` and `task_updated { taskId, action }`
 * (`action` = updated | status | deleted | comment) to the assignee, creator
 * and actor. Instead of refetching the whole tab, the ViewModel coalesces a
 * burst of events per task, fetches only the affected task and patches it
 * into whichever list it belongs to.
 */
enum class TaskAction { Assigned, Updated, Status, Deleted, Comment }

data class TaskEvent(val taskId: Long, val action: TaskAction)

/** `null` when the payload has no usable task id (the caller falls back to a reload). */
fun parseTaskEvent(type: String, data: JsonElement?): TaskEvent? {
    val obj = data as? JsonObject ?: return null
    val id = (obj["taskId"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        ?: (obj["task_id"] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
        ?: return null
    if (id <= 0) return null
    val action = when (type) {
        "task_assigned" -> TaskAction.Assigned
        "task_updated" -> when ((obj["action"] as? JsonPrimitive)?.contentOrNull) {
            "deleted" -> TaskAction.Deleted
            "status" -> TaskAction.Status
            "comment" -> TaskAction.Comment
            else -> TaskAction.Updated
        }
        else -> return null
    }
    return TaskEvent(id, action)
}

/** Per-task net effect of a burst: a delete wins; otherwise a single refetch covers every action. */
fun coalesceTaskEvents(events: List<TaskEvent>): Map<Long, Set<TaskAction>> {
    val out = LinkedHashMap<Long, MutableSet<TaskAction>>()
    events.forEach { out.getOrPut(it.taskId) { mutableSetOf() } += it.action }
    return out.mapValues { (_, actions) -> if (TaskAction.Deleted in actions) setOf(TaskAction.Deleted) else actions }
}

/** More distinct tasks than this in one burst → a full reload is cheaper than N detail fetches. */
const val MAX_TARGETED_SYNC = 8

/**
 * Client mirror of the server's list filters (`GET /tasks`, `GET /tasks/backlog`)
 * so a patched task only stays on screen when the server would have returned it.
 */
fun matchesFilters(task: Task, filters: TaskFilters, userId: Long?, includeStatus: Boolean): Boolean {
    if (filters.assignee.isNotEmpty()) {
        val who = if (filters.assignee == "me") userId else filters.assignee.toLongOrNull()
        val mine = who != null && (task.assignedTo == who || (task.userId == who && task.assignedTo == null))
        if (!mine) return false
    }
    if (filters.label.isNotEmpty() && task.labels.none { it.id.toString() == filters.label }) return false
    if (filters.priority in setOf("low", "medium", "high") && task.priority != filters.priority) return false
    if (includeStatus && filters.status in setOf("pending", "in_progress", "in_review", "done") && task.status != filters.status) return false
    val q = filters.search.trim()
    if (q.isNotEmpty()) {
        val hay = task.title + " " + stripHtml(task.description)
        if (!hay.contains(q, ignoreCase = true)) return false
    }
    return true
}

/** Sprint board membership: the selected sprint and the active filters. */
fun belongsToSprint(task: Task, sprintId: Long?, filters: TaskFilters, userId: Long?): Boolean =
    sprintId != null && task.sprintId == sprintId && matchesFilters(task, filters, userId, includeStatus = true)

/** Backlog membership (`date IS NULL AND sprint_id IS NULL`) and the active filters. */
fun belongsToBacklog(task: Task, filters: TaskFilters, userId: Long?): Boolean =
    task.isBacklogItem && matchesFilters(task, filters, userId, includeStatus = false)

/** Scheduled view membership: a planner date inside the loaded window, plus the active filters. */
fun belongsToScheduled(task: Task, from: java.time.LocalDate, to: java.time.LocalDate, filters: TaskFilters, userId: Long?): Boolean {
    val day = localDateOf(task.date) ?: return false
    return !day.isBefore(from) && !day.isAfter(to) && matchesFilters(task, filters, userId, includeStatus = true)
}

/** Replace in place, append when new, or drop when it no longer belongs. */
fun upsertTask(list: List<Task>, task: Task, belongs: Boolean): List<Task> {
    val index = list.indexOfFirst { it.id == task.id }
    return when {
        !belongs && index < 0 -> list
        !belongs -> list.filterNot { it.id == task.id }
        index < 0 -> list + task
        else -> list.toMutableList().also { it[index] = task }
    }
}

/** Keeps the backlog summary chips honest after a local patch (`old`/`new` = before/after membership). */
fun adjustSummary(summary: BacklogSummary, old: Task?, new: Task?): BacklogSummary {
    if (old == null && new == null) return summary
    val byPriority = summary.byPriority.toMutableMap()
    val byStatus = summary.byStatus.toMutableMap()
    fun bump(task: Task, delta: Int) {
        byPriority[task.priority] = maxOf(0, (byPriority[task.priority] ?: 0) + delta)
        byStatus[task.status] = maxOf(0, (byStatus[task.status] ?: 0) + delta)
    }
    old?.let { bump(it, -1) }
    new?.let { bump(it, 1) }
    val totalDelta = (if (new != null) 1 else 0) - (if (old != null) 1 else 0)
    return summary.copy(total = maxOf(0, summary.total + totalDelta), byPriority = byPriority, byStatus = byStatus)
}

/** The page window a silent reload should fetch so infinite scroll keeps its position. */
fun backlogWindow(loaded: Int, pageSize: Int): Int = maxOf(pageSize, loaded).coerceAtMost(500)
