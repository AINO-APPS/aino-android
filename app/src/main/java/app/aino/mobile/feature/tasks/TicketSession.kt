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
import java.util.UUID

/** Shared Ticket ownership beneath the existing Tasks ViewModel. */
class TicketSession {
    val key: String = UUID.randomUUID().toString()
    internal val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val lock = Any()
    private var closed = false
    private val resets = mutableListOf<() -> Unit>()
    internal val store = TicketStore(scope, state(TicketSnapshot()))
    private val mutablePlanScope = state(PlanScope())
    val planScope = mutablePlanScope.asStateFlow()
    internal val planScopeLock = Any()

    internal fun writePlanScope(value: PlanScope) {
        mutablePlanScope.value = value
    }

    private val mutableCommentThreads = state(emptyMap<Long, CommentThread>())
    internal val commentThreads = mutableCommentThreads.asStateFlow()

    internal fun setCommentThread(thread: CommentThread) {
        mutableCommentThreads.update { it + (thread.taskId to thread) }
    }

    internal fun setCommentThreadIfAbsent(thread: CommentThread) {
        mutableCommentThreads.update { if (thread.taskId in it) it else it + (thread.taskId to thread) }
    }

    internal fun updateCommentThread(taskId: Long, transform: (CommentThread) -> CommentThread) {
        mutableCommentThreads.update { threads ->
            val current = threads[taskId] ?: CommentThread(taskId, loading = false)
            threads + (taskId to transform(current).copy(taskId = taskId))
        }
    }

    internal fun clearCommentThreads() {
        mutableCommentThreads.value = emptyMap()
    }

    /**
     * Closing also clears retained Activity ViewModels. Ignore late responses
     * from blocking HTTP calls that cannot observe coroutine cancellation yet.
     */
    @OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)
    internal fun <T> state(initial: T): MutableStateFlow<T> = synchronized(lock) {
        val flow = MutableStateFlow(initial)
        if (!closed) resets += { flow.value = initial }
        object : MutableStateFlow<T> by flow {
            override var value: T
                get() = flow.value
                set(value) = synchronized(lock) { if (!closed) flow.value = value }

            override fun compareAndSet(expect: T, update: T): Boolean = synchronized(lock) {
                closed || flow.compareAndSet(expect, update)
            }

            override suspend fun emit(value: T) { this.value = value }

            override fun tryEmit(value: T): Boolean {
                this.value = value
                return true
            }
        }
    }

    internal fun onClose(action: () -> Unit) {
        val runNow = synchronized(lock) {
            if (closed) true else {
                resets.add(action)
                false
            }
        }
        if (runNow) action()
    }

    fun close() {
        val actions = synchronized(lock) {
            if (closed) return
            closed = true
            resets.toList().also { resets.clear() }
        }
        scope.cancel()
        actions.forEach { it() }
    }
}

internal data class TicketSnapshot(
    val tickets: Map<Long, Task> = emptyMap(),
    val recentlyChanged: Set<Long> = emptySet(),
    // Equal-valued server copies must still replace an optimistic object's identity.
    val ticketRevision: Long = 0,
)

/** Accepts enriched [Task] rows, never picker/insight rows or network clients. */
internal class TicketStore(
    private val scope: CoroutineScope,
    private val mutable: MutableStateFlow<TicketSnapshot>,
) {
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
