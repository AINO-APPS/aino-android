package app.aino.mobile.core.call

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.call.webrtc.callRingingEnvelope
import app.aino.mobile.core.realtime.RealtimeEnvelope
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small bounded, thread-safe memory of recent call ids (oldest evicted first). */
class RecentCallIds(private val capacity: Int = 64) {
    private val ids = LinkedHashSet<Long>()

    /** False when [callId] was already remembered. */
    @Synchronized
    fun add(callId: Long): Boolean {
        if (!ids.add(callId)) return false
        while (ids.size > capacity) ids.remove(ids.first())
        return true
    }

    @Synchronized
    fun contains(callId: Long): Boolean = callId in ids

    @Synchronized
    fun remove(callId: Long) {
        ids.remove(callId)
    }
}

/**
 * The shell's realtime socket as seen by process-wide call code (push service,
 * ring service). The shell injects [send] and keeps [connected] current.
 */
object CallRealtimeLink {
    @Volatile var send: (RealtimeEnvelope) -> Boolean = { false }
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    /** Sends only over a socket that is actually open (a connecting socket may drop the frame). */
    fun sendIfConnected(envelope: RealtimeEnvelope): Boolean =
        _connected.value && runCatching { send(envelope) }.getOrDefault(false)
}

/**
 * Callee "my phone is ringing" ack, once per call id: the `call_ringing` frame
 * when the socket is open, otherwise `POST chat/calls/:id/ringing`. A failed
 * HTTP ack is forgotten so a later ring surface (socket after push) retries.
 */
class CallRingingAcknowledger(
    private val socket: (RealtimeEnvelope) -> Boolean,
    private val http: (callId: Long, conversationId: Long) -> Unit,
    private val background: (() -> Unit) -> Unit = { task -> Thread(task, "call-ringing-ack").start() },
    capacity: Int = 64,
) {
    private val acked = RecentCallIds(capacity)

    /** True when an ack was dispatched (false: invalid ids or already acknowledged). */
    fun acknowledge(callId: Long, conversationId: Long): Boolean {
        if (callId <= 0 || conversationId <= 0 || !acked.add(callId)) return false
        val viaSocket = runCatching { socket(callRingingEnvelope(callId, conversationId, UUID.randomUUID().toString())) }.getOrDefault(false)
        if (viaSocket) return true
        background {
            runCatching { http(callId, conversationId) }.onFailure { acked.remove(callId) }
        }
        return true
    }
}

object CallRingingAck {
    @Volatile private var instance: CallRingingAcknowledger? = null

    private fun get(context: Context): CallRingingAcknowledger = instance ?: synchronized(this) {
        instance ?: CallRingingAcknowledger(
            socket = CallRealtimeLink::sendIfConnected,
            http = { callId, conversationId -> CallApi(AppContainer.get(context.applicationContext).api).ringing(callId, conversationId) },
        ).also { instance = it }
    }

    /** Group-call (huddle) invites have no 1:1 call row to acknowledge. */
    fun acknowledge(context: Context, callId: Long, conversationId: Long, meetingCode: String? = null) {
        if (!meetingCode.isNullOrBlank()) return
        get(context).acknowledge(callId, conversationId)
    }
}
