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