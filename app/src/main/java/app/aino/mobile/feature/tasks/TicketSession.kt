package app.aino.mobile.feature.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared Ticket ownership beneath the existing Tasks ViewModel. */
class TicketSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val store = TicketStore(scope)

    fun close() {
        scope.cancel()
        store.clear()
    }
}

internal data class TicketSnapshot(
    val tickets: Map<Long, Task> = emptyMap(),
    val recentlyChanged: Set<Long> = emptySet(),
    // Equal-valued server copies must still replace an optimistic object's identity.
    val ticketRevision: Long = 0,
)

/** Accepts enriched [Task] rows, never picker/insight rows or network clients. */
internal class TicketStore(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(TicketSnapshot())
    val state = mutable.asStateFlow()
    private val highlights = mutableMapOf<Long, Job>()
    // Serialize snapshot writes so identity-checked restores cannot race with upserts.
    private val ticketLock = Any()

    fun upsert(ticket: Task): Task {
        val stored = withoutComments(ticket)
        synchronized(ticketLock) {
            mutable.update { snapshot ->
                snapshot.copy(tickets = snapshot.tickets + (stored.id to stored), ticketRevision = snapshot.ticketRevision + 1)
            }
        }
        return stored
    }

    fun upsert(tickets: List<Task>) {
        val stored = tickets.associate { ticket -> ticket.id to withoutComments(ticket) }
        synchronized(ticketLock) {
            mutable.update { snapshot ->
                snapshot.copy(tickets = snapshot.tickets + stored, ticketRevision = snapshot.ticketRevision + 1)
            }
        }
    }

    fun remove(id: Long) {
        synchronized(ticketLock) {
            mutable.update { it.copy(tickets = it.tickets - id, recentlyChanged = it.recentlyChanged - id) }
        }
    }

    fun restoreIfCurrent(optimistic: Task, previous: Task): Boolean = synchronized(ticketLock) {
        val snapshot = mutable.value
        if (previous.id != optimistic.id || snapshot.tickets[optimistic.id] !== optimistic) return@synchronized false
        mutable.value = snapshot.copy(tickets = snapshot.tickets + (optimistic.id to previous))
        true
    }

    fun highlight(id: Long) {
        synchronized(ticketLock) {
            mutable.update { it.copy(recentlyChanged = it.recentlyChanged + id) }
        }
        scope.launch {
            highlights.remove(id)?.cancel()
            val job = launch {
                delay(2_200)
                synchronized(ticketLock) {
                    mutable.update { it.copy(recentlyChanged = it.recentlyChanged - id) }
                }
            }
            highlights[id] = job
            job.invokeOnCompletion { if (highlights[id] === job) highlights.remove(id) }
        }
    }

    fun clear() {
        synchronized(ticketLock) {
            mutable.value = TicketSnapshot()
        }
    }

    private fun withoutComments(ticket: Task): Task =
        if (ticket.comments.isEmpty()) ticket else ticket.copy(comments = emptyList())
}
