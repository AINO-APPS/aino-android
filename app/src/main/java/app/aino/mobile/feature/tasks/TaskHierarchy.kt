package app.aino.mobile.feature.tasks

import kotlinx.coroutines.CancellationException

/** Accept both nested planning reads and flat board reads without ranking children. */
internal fun planningFamilies(tasks: List<Task>, tickets: Map<Long, Task> = emptyMap()): List<Task> {
    val ids = tasks.mapTo(mutableSetOf(), Task::id)
    val children = (tasks + tickets.values).distinctBy(Task::id)
        .filter { it.isSubtask && it.parentTaskId in ids }.groupBy(Task::parentTaskId)
    return tasks.filterNot { it.isSubtask && it.parentTaskId in ids }.map { parent ->
        val nestedChildren = parent.subtasks ?: children[parent.id].orEmpty()
        parent.copy(subtasks = nestedChildren.distinctBy(Task::id).map { tickets[it.id] ?: it })
    }
}

internal fun planningTickets(tasks: List<Task>): List<Task> =
    tasks.flatMap { listOf(it) + it.subtasks.orEmpty() }.distinctBy(Task::id)

/** Family writes return the parent; refetch loaded children rather than predicting server placement or keys. */
internal fun refreshLoadedSubtasks(parent: Task, repository: TaskRepository, session: TicketSession) {
    val children = session.store.state.value.tickets.values.filter { it.isSubtask && it.parentTaskId == parent.id }
    children.forEach { child ->
        try {
            session.store.upsert(repository.loadDetail(child.id))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.publishNotice(TaskNotice("Some subtasks could not be refreshed. Refresh to retry."))
        }
    }
}
