package app.aino.mobile.core.call

import android.content.Context
import app.aino.mobile.core.AppContainer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An outgoing call the server never assigned an id to, older than the ring timeout, can only be stale. */
fun isAbandonedOutgoing(state: CallSessionUiState, nowMs: Long): Boolean {
    if (state.phase == CallPhase.Idle || state.phase.isTerminal()) return false
    if (state.route != null || state.outgoingConversationId == null) return false
    val started = state.startedAtMs ?: return false
    return nowMs - started > OUTGOING_NO_ANSWER_MS
}

/**
 * Socket reconnect / app foreground catch-up for 1:1 calls: the server keeps
 * no replay log, so a `call_ended` lost while offline would leave a ring or a
 * call screen up forever. Asks `GET chat/calls/:id` and ends the local session
 * when the server says the call is over.
 */
object CallReconciler {
    private val running = AtomicBoolean(false)

    suspend fun reconcile(context: Context, nowMs: Long = System.currentTimeMillis()) {
        if (!running.compareAndSet(false, true)) return
        try {
            val appContext = context.applicationContext
            reconcileSavedRing(appContext, nowMs)
            val session = CallSessionRuntime.get(appContext)
            val state = session.state.value
            if (state.phase == CallPhase.Idle || state.phase.isTerminal()) return
            if (isAbandonedOutgoing(state, nowMs)) {
                withContext(Dispatchers.Main) { ActiveCallRuntime.get(appContext).hangUp() }
                return
            }
            val route = state.route ?: return
            // Group-call rings carry a meeting id, not a 1:1 call row.
            if (route.meetingCode != null) return
            val status = withContext(Dispatchers.IO) {
                runCatching { CallApi(AppContainer.get(appContext).api).status(route.callId).status }.getOrNull()
            } ?: return
            withContext(Dispatchers.Main) { session.reconcile(route.callId, status) }
        } finally {
            running.set(false)
        }
    }

    /** Cold start: a ring persisted by a process that died is gone once its `expiresAt` passed. */
    fun clearStaleRing(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        // Reading drops a pending Answer/Decline older than its TTL.
        PendingCallActionStore.read(appContext)
        val record = RingingCallStore.read(appContext) ?: return
        if (record.expiresAtMs > nowMs) return
        IncomingCallNotifications.cancel(appContext)
    }

    private suspend fun reconcileSavedRing(context: Context, nowMs: Long) {
        val record = RingingCallStore.read(context) ?: return
        if (record.expiresAtMs > nowMs || record.info.meetingCode != null) return
        val status = withContext(Dispatchers.IO) {
            runCatching { CallApi(AppContainer.get(context).api).status(record.info.callId) }.getOrNull()
        } ?: return // Keep the record for retry when the server is reachable.
        when (status.status) {
            "missed" -> MissedCallNotifier.post(context, record.info)
            "answered", "declined" -> MissedCallNotifier.markHandled(context, record.info.callId)
            "ended" -> if (status.startedAt != null || (status.duration ?: 0) > 0) {
                MissedCallNotifier.markHandled(context, record.info.callId)
            }
        }
    }
}
