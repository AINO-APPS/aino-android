package app.aino.mobile.feature.tasks

import java.net.URLEncoder

enum class TaskTab { Today, Backlog }

/** The four default workflow keys the server accepts on `PATCH /tasks/:id/status`. */
val TASK_STATUSES = listOf("pending", "in_progress", "in_review", "done")
val TASK_PRIORITIES = listOf("high", "medium", "low")

fun taskStatusLabel(status: String): String = when (status) {
    "pending" -> "To do"
    "in_progress" -> "In progress"
    "in_review" -> "In review"
    "done" -> "Done"
    else -> status.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/**
 * The server orders by priority then status (`routes/tasks/crud.ts`); mirror it
 * so a locally re-sorted list never disagrees with the next server response.
 */
fun taskSortKey(task: Task): Int {
    val priority = when (task.priority) {
        "high" -> 1
        "medium" -> 2
        else -> 3
    }
    val status = when (task.status) {
        "in_progress" -> 1
        "in_review" -> 2
        "pending" -> 3
        else -> 4
    }
    return priority * 10 + status
}

/** Advances through the default workflow, matching the planner's tap-to-advance. */
fun nextTaskStatus(status: String): String {
    val index = TASK_STATUSES.indexOf(status)
    if (index < 0) return "in_progress"
    return TASK_STATUSES[(index + 1) % TASK_STATUSES.size]
}

data class TaskFilters(
    val assigneeMine: Boolean = true,
    val priority: String? = null,
    val status: String? = null,
    val search: String = "",
)

/**
 * Builds the query string for `GET /tasks` and `GET /tasks/backlog`. Only values
 * the server allow-lists are emitted, so a stale client cannot send a filter the
 * server would silently drop and then render a list the user did not ask for.
 */
fun taskQuery(
    basePath: String,
    filters: TaskFilters,
    date: String? = null,
    extra: List<Pair<String, String>> = emptyList(),
): String {
    val params = buildList {
        date?.let { add("date" to it) }
        if (filters.assigneeMine) add("assignee" to "me")
        filters.priority?.takeIf { it in TASK_PRIORITIES }?.let { add("priority" to it) }
        filters.status?.takeIf { it in TASK_STATUSES }?.let { add("status" to it) }
        filters.search.trim().takeIf(String::isNotEmpty)?.let { add("search" to it) }
        addAll(extra)
    }
    if (params.isEmpty()) return basePath
    return basePath + "?" + params.joinToString("&") { (key, value) ->
        "$key=" + URLEncoder.encode(value, "UTF-8")
    }
}

/** Mirrors the server's create validation so an invalid task never leaves the device. */
fun validateTask(title: String, description: String, date: String?, dueDate: String?): String? {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "Task title is required"
    if (trimmed.length > 200) return "Task title must be 200 characters or less"
    if (description.length > 5000) return "Task description must be 5000 characters or less"
    val normalizedDue = dueDate?.trim().orEmpty()
    if (normalizedDue.isNotEmpty()) {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(normalizedDue)) return "Due date must use YYYY-MM-DD"
        if (date != null && normalizedDue < date) return "Due date cannot be earlier than the task date"
    }
    return null
}

/** `POST /tasks/:id/comments` requires text or a file; Android only sends text. */
fun validateComment(content: String): String? {
    val trimmed = content.trim()
    if (trimmed.isEmpty()) return "Comment cannot be empty"
    if (trimmed.length > 2000) return "Comment must be 2000 characters or less"
    return null
}

/** Local stats recomputation used after an optimistic status change. */
fun recomputeStats(tasks: List<Task>): TaskStats {
    val total = tasks.size
    val done = tasks.count { it.status == "done" }
    val inProgress = tasks.count { it.status == "in_progress" }
    return TaskStats(total, done, inProgress, if (total > 0) Math.round(done * 100f / total) else 0)
}
