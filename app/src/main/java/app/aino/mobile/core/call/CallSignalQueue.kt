package app.aino.mobile.core.call

import app.aino.mobile.core.call.webrtc.CallSignal

/**
 * Call signals that could not be written because the realtime socket was down.
 * They are flushed in order once it reconnects, so a brief socket drop during
 * negotiation or an ICE restart no longer loses the offer/answer or candidates
 * (which used to leave the call stuck on "Connecting..." until the 30 s timeout).
 *
 * Only the newest description of each type matters, and media state is
 * re-sent on connect anyway, so stale duplicates are dropped.
 */
class CallSignalQueue(private val capacity: Int = 256) {
    private val pending = ArrayDeque<CallSignal>()

    @Synchronized
    fun add(signal: CallSignal) {
        if (signal.type != "ice-candidate") pending.removeAll { it.type == signal.type }
        pending.addLast(signal)
        while (pending.size > capacity) pending.removeFirst()
    }

    /**
     * Sends queued signals in order through [send], stopping at the first failure
     * (that signal and the rest stay queued). Returns how many were sent.
     */
    @Synchronized
    fun flush(send: (CallSignal) -> Boolean): Int {
        var sent = 0
        while (pending.isNotEmpty()) {
            if (!send(pending.first())) break
            pending.removeFirst()
            sent++
        }
        return sent
    }

    @Synchronized
    fun clear() = pending.clear()

    @Synchronized
    fun size() = pending.size
}

/** Backoff between ICE restarts while the media path stays down: 2 s, 4 s, 8 s, then every 8 s. */
internal fun iceRestartDelayMs(attempt: Int): Long = 2_000L shl attempt.coerceIn(0, 2)

/** Restarts allowed per outage before giving up; the counter resets once media reconnects. */
internal const val MAX_ICE_RESTARTS = 4
