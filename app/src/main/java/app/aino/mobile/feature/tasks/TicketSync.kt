package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement

internal sealed interface TicketSyncSignal {
    data object FullReload : TicketSyncSignal
    data object Reconnect : TicketSyncSignal
    data object VisiblePoll : TicketSyncSignal
}

internal sealed interface TicketSyncChange {
    val ticketId: Long

    data class Updated(
        override val ticketId: Long,
        val refreshBoard: Boolean,
        val staleDetail: Boolean,
        val comments: List<TaskComment>,
    ) : TicketSyncChange
    data class Removed(override val ticketId: Long, val reason: RemovalReason) : TicketSyncChange
}

internal enum class RemovalReason { Deleted, NoLongerVisible }

/** Realtime ownership and network reconciliation for one signed-in Ticket session. */
internal class TicketSync(
    private val session: TicketSession,
    private val repository: TaskRepository,
) {
    private val scope = CoroutineScope(SupervisorJob(session.job) + Dispatchers.Main.immediate)
    private val mutableSignals = MutableSharedFlow<TicketSyncSignal>(extraBufferCapacity = 8)
    val signals = mutableSignals.asSharedFlow()
    private val mutableChanges = MutableSharedFlow<TicketSyncChange>(extraBufferCapacity = 32)
    val changes = mutableChanges.asSharedFlow()
    private val pendingEvents = mutableListOf<TaskEvent>()
    private var pendingReload = false
    private var debounceJob: Job? = null
    private val syncMutex = Mutex()
    @Volatile private var invisibleNoticed = false

    init {
        session.onClose {
            synchronized(pendingEvents) {
                pendingEvents.clear()
                pendingReload = false
            }
            scope.cancel()
        }
    }

    fun onRealtimeEvent(type: String, data: JsonElement?) {
        if (!session.job.isActive || session.planScope.value.userId == null) return
        val event = parseTaskEvent(type, data)
        synchronized(pendingEvents) {
            if (event == null) pendingReload = true else pendingEvents += event
        }
        scheduleSync()
    }

    fun onReconnect() {
        if (session.job.isActive && session.planScope.value.userId != null) {
            mutableSignals.tryEmit(TicketSyncSignal.Reconnect)
        }
    }

    fun pollVisible() {
        if (session.job.isActive && session.planScope.value.userId != null) {
            mutableSignals.tryEmit(TicketSyncSignal.VisiblePoll)
        }
    }

    fun resetRefreshCycle() {
        invisibleNoticed = false
    }

    private fun scheduleSync() {
        debounceJob?.cancel()
        debounceJob = scope.launch(Dispatchers.IO) {
            delay(SYNC_DEBOUNCE_MS)
            syncMutex.withLock {
                val (events, reload) = synchronized(pendingEvents) {
                    val copy = pendingEvents.toList()
                    pendingEvents.clear()
                    val shouldReload = pendingReload
                    pendingReload = false
                    copy to shouldReload
                }
                if (events.isNotEmpty() || reload) applySync(events, reload)
            }
        }
    }

    private suspend fun applySync(events: List<TaskEvent>, forceReload: Boolean) {
        val byTicket = coalesceTaskEvents(events)
        if (forceReload || byTicket.size > MAX_TARGETED_SYNC) {
            mutableSignals.emit(TicketSyncSignal.FullReload)
            return
        }

        var reload = false
        byTicket.forEach { (id, actions) ->
            when {
                TaskAction.Deleted in actions -> remove(id, RemovalReason.Deleted)
                actions == setOf(TaskAction.Comment) -> syncComments(id)
                else -> {
                    if (!syncTicket(id, refreshBoard = TaskAction.Comment !in actions)) reload = true
                    if (TaskAction.Comment in actions) syncComments(id)
                }
            }
        }
        if (reload) mutableSignals.emit(TicketSyncSignal.FullReload)
    }

    private suspend fun syncTicket(id: Long, refreshBoard: Boolean): Boolean {
        val removalToken = session.store.removalToken(id)
        val fresh = runCatching { repository.loadDetail(id) }.getOrElse { error ->
            if (error.isNoLongerVisible()) {
                remove(id, RemovalReason.NoLongerVisible)
                if (!invisibleNoticed) {
                    invisibleNoticed = true
                    session.publishNotice(TaskNotice("Some tickets are no longer available to you"))
                }
                return true
            }
            return false
        }
        session.planRevision.incrementAndGet()
        session.store.upsert(fresh, removalToken)
        session.store.highlight(id)
        mutableChanges.emit(TicketSyncChange.Updated(id, refreshBoard, staleDetail = true, comments = fresh.comments))
        return true
    }

    private suspend fun syncComments(id: Long) {
        val watchingThread = id in session.commentThreads.value
        val before = session.commentThreads.value[id]?.items.orEmpty()
        val fetched = runCatching { repository.loadComments(id) }.getOrElse {
            session.publishNotice(TaskNotice("Failed to refresh ticket comments"))
            return
        }
        val items = if (watchingThread) session.reconcileCommentThread(id, before, fetched) else fetched
        session.planRevision.incrementAndGet()
        session.store.update(id) { it.copy(commentCount = items.size) }
        session.store.highlight(id)
        mutableChanges.emit(TicketSyncChange.Updated(id, refreshBoard = false, staleDetail = false, comments = items))
    }

    private fun remove(id: Long, reason: RemovalReason) {
        session.planRevision.incrementAndGet()
        session.store.remove(id, preventReinsert = true)
        session.removeCommentThread(id)
        mutableChanges.tryEmit(TicketSyncChange.Removed(id, reason))
    }

    private fun Throwable.isNoLongerVisible(): Boolean {
        val status = when (this) {
            is TaskFailure -> statusCode
            is ApiError.Http -> statusCode
            else -> 0
        }
        return status == 403 || status == 404
    }

    companion object {
        private const val SYNC_DEBOUNCE_MS = 400L
    }
}
