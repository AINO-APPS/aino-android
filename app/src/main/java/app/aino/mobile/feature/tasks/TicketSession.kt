package app.aino.mobile.feature.tasks

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/** Signed-in session owner for Ticket state, scope, sync and notices. */
class TicketSession {
    val key: String = UUID.randomUUID().toString()
    internal val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)
    private val lock = Any()
    private var closed = false
    private val resets = mutableListOf<() -> Unit>()
    internal val planRevision = java.util.concurrent.atomic.AtomicInteger()
    internal val store = TicketStore(scope, state(TicketSnapshot()))
    private var ticketSync: TicketSync? = null
    private val noticeChannel = Channel<TaskNotice>(Channel.BUFFERED)
    val notices: Flow<TaskNotice> = noticeChannel.receiveAsFlow()
    private val mutablePlanScope = state(PlanScope())
    val planScope = mutablePlanScope.asStateFlow()
    internal val planScopeLock = Any()
    private val mutableBoardRequests = MutableSharedFlow<BoardRequest>(extraBufferCapacity = 32)
    internal val boardRequests = mutableBoardRequests.asSharedFlow()
    internal fun requestBoard(request: BoardRequest) { mutableBoardRequests.tryEmit(request) }
    private val mutablePlanRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    internal val planRefreshes = mutablePlanRefreshes.asSharedFlow()
    internal fun requestPlanRefresh() { mutablePlanRefreshes.tryEmit(Unit) }
    private val mutablePlanRequests = MutableSharedFlow<PlanRequest>(extraBufferCapacity = 32)
    internal val planRequests = mutablePlanRequests.asSharedFlow()
    internal fun requestPlan(request: PlanRequest) { mutablePlanRequests.tryEmit(request) }
    private val mutableScheduledRequests = MutableSharedFlow<ScheduledRequest>(extraBufferCapacity = 32)
    internal val scheduledRequests = mutableScheduledRequests.asSharedFlow()
    internal fun requestScheduled(request: ScheduledRequest) { mutableScheduledRequests.tryEmit(request) }
    private val mutableShellRequests = MutableSharedFlow<ShellRequest>(extraBufferCapacity = 32)
    internal val shellRequests = mutableShellRequests.asSharedFlow()
    internal fun requestShell(request: ShellRequest) { mutableShellRequests.tryEmit(request) }

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

    internal fun removeCommentThread(taskId: Long) {
        mutableCommentThreads.update { it - taskId }
    }

    internal fun sync(repository: TaskRepository): TicketSync = synchronized(lock) {
        ticketSync ?: TicketSync(this, repository).also { ticketSync = it }
    }

    internal fun publishNotice(notice: TaskNotice) {
        noticeChannel.trySend(notice)
    }

    internal fun launch(block: suspend () -> Unit) = scope.launch { block() }

    fun onRealtimeEvent(type: String, data: JsonElement?) {
        ticketSync?.onRealtimeEvent(type, data)
    }

    fun onReconnect() {
        ticketSync?.onReconnect()
    }

    fun pollVisible() {
        ticketSync?.pollVisible()
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
        noticeChannel.close()
        while (noticeChannel.tryReceive().isSuccess) Unit
    }
}

internal sealed interface BoardRequest {
    data object Load : BoardRequest
    data object ReloadLoaded : BoardRequest
    data object Start : BoardRequest
    data class Baseline(val sprintId: Long) : BoardRequest
}

internal sealed interface PlanRequest {
    data object Load : PlanRequest
    data object PrepareImport : PlanRequest
    data object EnsureLoaded : PlanRequest
    data object ReloadLoaded : PlanRequest
    data object Close : PlanRequest
    data object ClearFilters : PlanRequest
    data object Epics : PlanRequest
    data object CancelImport : PlanRequest
    data class CreateForColumn(val state: WorkflowState) : PlanRequest
}

internal sealed interface ScheduledRequest {
    data object CarryForward : ScheduledRequest
    data object Load : ScheduledRequest
    data object ReloadLoaded : ScheduledRequest
    data class Show(val date: String?) : ScheduledRequest
}

internal sealed interface ShellRequest {
    data object ShowScheduled : ShellRequest
    data object ReloadScheduledIfVisible : ShellRequest
    data object CloseSheet : ShellRequest
    data class OpenSheet(val sheet: TaskSheet) : ShellRequest
    data class SelectSprint(val id: Long) : ShellRequest
    data class SprintGoal(val id: Long, val goal: String) : ShellRequest
    data class ShowCompleted(val show: Boolean) : ShellRequest
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
    private class Removal(val allowReopen: Boolean)
    private val removedIds = mutableMapOf<Long, Removal>()

    // Only a detail read started after a local cancellation may observe a legitimate reopening.
    fun removalToken(id: Long): Any? = synchronized(ticketLock) { removedIds[id] }

    fun upsert(ticket: Task, removalToken: Any? = null): Task {
        val stored = withoutComments(ticket)
        synchronized(ticketLock) {
            val removal = removedIds[stored.id]
            if (removal != null) {
                if (!removal.allowReopen || removal !== removalToken || stored.workflowStateKey == "cancelled") return stored
                removedIds.remove(stored.id)
            }
            mutable.update { snapshot ->
                snapshot.copy(tickets = snapshot.tickets + (stored.id to stored), ticketRevision = snapshot.ticketRevision + 1)
            }
        }
        return stored
    }

    fun upsert(tickets: List<Task>) {
        val stored = tickets.associate { ticket -> ticket.id to withoutComments(ticket) }
        synchronized(ticketLock) {
            val accepted = stored.filterKeys { it !in removedIds }
            if (accepted.isEmpty()) return
            mutable.update { snapshot ->
                snapshot.copy(tickets = snapshot.tickets + accepted, ticketRevision = snapshot.ticketRevision + 1)
            }
        }
    }

    fun update(id: Long, transform: (Task) -> Task) {
        synchronized(ticketLock) {
            val snapshot = mutable.value
            val current = snapshot.tickets[id] ?: return
            val updated = withoutComments(transform(current))
            mutable.value = snapshot.copy(
                tickets = snapshot.tickets + (id to updated),
                ticketRevision = snapshot.ticketRevision + 1,
            )
        }
    }

    fun remove(id: Long, preventReinsert: Boolean = false, allowReopen: Boolean = false) {
        synchronized(ticketLock) {
            if (preventReinsert) removedIds[id] = Removal(allowReopen)
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
            removedIds.clear()
            mutable.value = TicketSnapshot()
        }
    }

    private fun withoutComments(ticket: Task): Task =
        if (ticket.comments.isEmpty()) ticket else ticket.copy(comments = emptyList())
}
