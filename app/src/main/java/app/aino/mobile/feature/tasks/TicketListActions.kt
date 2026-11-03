package app.aino.mobile.feature.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Shared list commands; each surface owns its own errors and confirmation. */
internal class TicketListActions(
    private val repository: TaskRepository,
    private val session: TicketSession,
    private val scope: CoroutineScope,
    private val confirm: (ConfirmRequest) -> Unit,
    private val fail: (Throwable, String) -> Unit,
) {
    private fun allowed(ticket: Task, capability: TaskCapability): Boolean {
        val decision = (session.store.state.value.tickets[ticket.id] ?: ticket).capability(capability)
        if (!decision.allowed) session.publishNotice(TaskNotice(decision.reasonCopy()))
        return decision.allowed
    }

    fun assignSprint(ticket: Task, sprintId: Long?) {
        if (!allowed(ticket, TaskCapability.PlaceInSprint)) return
        scope.launch(Dispatchers.IO) {
            runCatching { repository.assignSprint(ticket.id, sprintId) }.fold(
                onSuccess = { saved ->
                    session.planRevision.incrementAndGet()
                    session.store.upsert(saved)
                    session.store.highlight(saved.id)
                    session.requestBoard(BoardRequest.ReloadLoaded)
                    val name = session.planScope.value.sprints.firstOrNull { it.id == sprintId }?.name
                    session.publishNotice(TaskNotice(if (name != null) "Added to $name" else "Moved to backlog"))
                },
                onFailure = { fail(it, "Failed to assign sprint") },
            )
        }
    }

    fun requestDelete(ticket: Task) {
        if (!allowed(ticket, TaskCapability.Cancel)) return
        confirm(ConfirmRequest(
            "Cancel ticket",
            "Cancel \"${ticket.title}\"? The ticket and its history are kept, and it leaves the open work.",
            "Cancel ticket",
            danger = true,
        ) {
            scope.launch(Dispatchers.IO) {
                runCatching { repository.deleteTask(ticket.id) }.fold(
                    onSuccess = { response ->
                        session.planRevision.incrementAndGet()
                        session.store.markCancelled(ticket.id, response.task)
                        session.requestBoard(BoardRequest.ReloadLoaded)
                        session.publishNotice(TaskNotice("Cancelled ${ticket.displayKey}"))
                    },
                    onFailure = { fail(it, "Failed to cancel ticket") },
                )
            }
        })
    }
}
