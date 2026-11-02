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
)

/** Accepts enriched [Task] rows, never picker/insight rows or network clients. */
internal class TicketStore(private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(TicketSnapshot())
    val state = mutable.asStateFlow()
    private val highlights = mutableMapOf<Long, Job>()

    fun upsert(ticket: Task) = upsert(listOf(ticket))

    fun upsert(tickets: List<Task>) {
        mutable.update { snapshot ->
            snapshot.copy(tickets = snapshot.tickets + tickets.associate { ticket ->
                ticket.id to if (ticket.comments.isEmpty()) ticket else ticket.copy(comments = emptyList())
            })
        }
    }

    fun remove(id: Long) {
        mutable.update { it.copy(tickets = it.tickets - id, recentlyChanged = it.recentlyChanged - id) }
    }

    fun highlight(id: Long) {
        mutable.update { it.copy(recentlyChanged = it.recentlyChanged + id) }
        scope.launch {
            highlights.remove(id)?.cancel()
            val job = launch {
                delay(2_200)
                mutable.update { it.copy(recentlyChanged = it.recentlyChanged - id) }
            }
            highlights[id] = job
            job.invokeOnCompletion { if (highlights[id] === job) highlights.remove(id) }
        }
    }

    fun clear() {
        mutable.value = TicketSnapshot()
    }
}
