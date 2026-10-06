package app.aino.mobile.core.call

enum class CallPhase {
    Idle,
    Ringing,
    Accepting,
    Connecting,
    Connected,
    Reconnecting,
    Ended,
    Rejected,
    Busy,
    Expired,
    Failed,
}

enum class CallEvent {
    Incoming,
    Outgoing,
    Accept,
    PeerAccepted,
    PeerReconnect,
    MediaConnected,
    MediaDisconnected,
    RemoteEnded,
    RemoteRejected,
    RemoteBusy,
    RingExpired,
    Failure,
    LocalEnd,
    Reset,
}

private val TERMINAL_PHASES = setOf(
    CallPhase.Ended,
    CallPhase.Rejected,
    CallPhase.Busy,
    CallPhase.Expired,
    CallPhase.Failed,
)

fun CallPhase.isTerminal(): Boolean = this in TERMINAL_PHASES

/**
 * Pure call lifecycle transition policy. Terminal phases absorb every event except
 * an explicit [CallEvent.Reset], preventing late RTC/socket callbacks from reviving
 * a call whose resources are already being released.
 */
fun reduceCallPhase(current: CallPhase, event: CallEvent): CallPhase {
    if (event == CallEvent.Reset) return CallPhase.Idle
    if (current.isTerminal()) return current

    return when (event) {
        CallEvent.Incoming -> if (current == CallPhase.Idle) CallPhase.Ringing else current
        CallEvent.Outgoing -> if (current == CallPhase.Idle) CallPhase.Connecting else current
        CallEvent.Accept -> if (current == CallPhase.Ringing) CallPhase.Accepting else current
        CallEvent.PeerAccepted -> if (current in setOf(CallPhase.Ringing, CallPhase.Connecting)) CallPhase.Connecting else current
        CallEvent.PeerReconnect,
        CallEvent.MediaDisconnected,
        -> if (current in setOf(CallPhase.Accepting, CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)) {
            CallPhase.Reconnecting
        } else {
            current
        }
        CallEvent.MediaConnected -> if (current in setOf(CallPhase.Accepting, CallPhase.Connecting, CallPhase.Reconnecting)) {
            CallPhase.Connected
        } else {
            current
        }
        CallEvent.RemoteEnded,
        CallEvent.LocalEnd,
        -> CallPhase.Ended
        CallEvent.RemoteRejected -> CallPhase.Rejected
        CallEvent.RemoteBusy -> CallPhase.Busy
        CallEvent.RingExpired -> CallPhase.Expired
        CallEvent.Failure -> CallPhase.Failed
        CallEvent.Reset -> CallPhase.Idle
    }
}

/** Server ring timeout (authoritative: it sends `call_ended` reason `no_answer`); push `expiresAt` = now + this. */
const val SERVER_RING_TIMEOUT_MS = 60_000L

/** Caller fallback "No answer" when the server's `call_ended` never arrives. */
const val OUTGOING_NO_ANSWER_MS = 63_000L

data class CallReconcileAction(val event: CallEvent, val reason: String)

/**
 * Maps `GET chat/calls/:id` `status` onto a local terminal event for a call
 * this device still thinks is live (after a reconnect / app foreground).
 * `ringing` / `answered` keep the call, except that a call answered while this
 * device was still ringing was picked up elsewhere.
 */
fun reconcileServerCallStatus(serverStatus: String?, localPhase: CallPhase, incoming: Boolean): CallReconcileAction? {
    if (localPhase == CallPhase.Idle || localPhase.isTerminal()) return null
    val ringingHere = incoming && localPhase == CallPhase.Ringing
    return when (serverStatus) {
        "declined" -> CallReconcileAction(CallEvent.RemoteRejected, "declined")
        "missed" -> CallReconcileAction(CallEvent.RemoteEnded, "missed")
        "ended" -> CallReconcileAction(CallEvent.RemoteEnded, if (ringingHere) "answered_elsewhere" else "ended")
        "answered" -> if (ringingHere) CallReconcileAction(CallEvent.RemoteEnded, "answered_elsewhere") else null
        else -> null
    }
}

/** Reasons for a ring ending that mean "this device's user never picked up and nobody else of theirs did". */
fun isMissedCallReason(reason: String?): Boolean = when (reason) {
    "accepted", "rejected", "declined", "handled_accepted", "handled_rejected", "handled_elsewhere",
    "answered_elsewhere", "local_end", "local_reject", "huddle",
    -> false
    else -> true
}

/**
 * Callee missed-call decision: the call rang on this device, was neither
 * answered nor declined here, and ended for a reason that is not "handled on
 * another of my devices".
 */
fun shouldNotifyMissedCall(ringingHere: Boolean, handledHere: Boolean, reason: String?): Boolean =
    ringingHere && !handledHere && isMissedCallReason(reason)