package app.aino.mobile.core.call

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallSessionUiState(
    val phase: CallPhase = CallPhase.Idle,
    val route: IncomingCallRoute? = null,
    val outgoingConversationId: Long? = null,
    val peerUserId: Long? = null,
    val terminalReason: String? = null,
)

fun interface CallResourceOwner {
    fun release(callId: Long?)
}

class CallSessionController(
    private val resources: CallResourceOwner = CallResourceOwner {},
    private val deduplicator: CallSignalDeduplicator = CallSignalDeduplicator(),
) {
    private val _state = MutableStateFlow(CallSessionUiState())
    val state: StateFlow<CallSessionUiState> = _state.asStateFlow()
    private var cleanupComplete = false

    @Synchronized
    fun incoming(route: IncomingCallRoute): Boolean {
        var current = _state.value
        if (current.phase.isTerminal()) {
            if (!cleanupComplete || current.route?.callId == route.callId) return false
            _state.value = CallSessionUiState()
            current = _state.value
        }
        if (current.phase != CallPhase.Idle && current.route?.callId != route.callId) return false
        cleanupComplete = false
        _state.value = current.copy(
            phase = reduceCallPhase(current.phase, CallEvent.Incoming),
            route = route,
            outgoingConversationId = null,
            peerUserId = route.callerId,
            terminalReason = null,
        )
        return true
    }

    @Synchronized
    fun outgoing(conversationId: Long): Boolean {
        if (conversationId <= 0) return false
        if (_state.value.phase.isTerminal() && cleanupComplete) {
            _state.value = CallSessionUiState()
        }
        if (_state.value.phase != CallPhase.Idle) return false
        cleanupComplete = false
        _state.value = CallSessionUiState(
            phase = CallPhase.Connecting,
            outgoingConversationId = conversationId,
        )
        return true
    }

    @Synchronized
    fun accepting() = transition(CallEvent.Accept)

    @Synchronized
    fun handle(envelope: app.aino.mobile.core.realtime.RealtimeEnvelope): Boolean {
        return handle(CallRealtimeRouter.decode(envelope) ?: return false)
    }

    @Synchronized
    fun handle(event: CallRealtimeEvent): Boolean {
        val current = _state.value
        when (event) {
            is CallRealtimeEvent.Incoming -> return incoming(event.toRoute())
            is CallRealtimeEvent.Started -> {
                if (current.route != null || current.outgoingConversationId != event.conversationId) return false
                _state.value = current.copy(
                    route = IncomingCallRoute(event.callId, event.conversationId, null, "", null, event.callType),
                )
            }
            is CallRealtimeEvent.Accepted -> {
                if (!matches(event.callId, event.conversationId)) return false
                _state.value = current.copy(
                    phase = reduceCallPhase(current.phase, CallEvent.PeerAccepted),
                    peerUserId = event.userId ?: current.peerUserId,
                )
            }
            is CallRealtimeEvent.PeerReady -> {
                if (!matches(event.callId, event.conversationId)) return false
                _state.value = current.copy(peerUserId = event.userId ?: current.peerUserId)
            }
            is CallRealtimeEvent.Reconnect -> {
                if (!matches(event.callId, event.conversationId)) return false
                _state.value = current.copy(
                    phase = reduceCallPhase(current.phase, CallEvent.PeerReconnect),
                    peerUserId = event.userId ?: current.peerUserId,
                )
            }
            is CallRealtimeEvent.Signal -> {
                // Accepted (true) only once per signalId; ActiveCallController applies it.
                if (current.route?.conversationId != event.conversationId || !deduplicator.remember(event.signalId)) return false
            }
            is CallRealtimeEvent.Rejected -> {
                if (!matches(event.callId, event.conversationId)) return false
                terminate(CallEvent.RemoteRejected, "rejected")
            }
            is CallRealtimeEvent.Ended -> {
                if (!matchesOptional(event.callId, event.conversationId)) return false
                terminate(CallEvent.RemoteEnded, event.reason ?: "ended")
            }
            is CallRealtimeEvent.HandledElsewhere -> {
                if (!matches(event.callId, event.conversationId)) return false
                // The server also sends this to the device that accepted/rejected
                // (web CallContext ignores it there). Only a still-ringing device
                // was handled "elsewhere".
                if (current.phase != CallPhase.Ringing) return false
                terminate(
                    if (event.action == "rejected") CallEvent.RemoteRejected else CallEvent.RemoteEnded,
                    "handled_${event.action}",
                )
            }
            is CallRealtimeEvent.Busy -> {
                if (current.route != null || current.outgoingConversationId != event.conversationId) return false
                terminate(CallEvent.RemoteBusy, event.reason ?: "busy")
            }
            is CallRealtimeEvent.Error -> {
                val conversationId = current.route?.conversationId ?: current.outgoingConversationId
                if (event.conversationId != null && conversationId != event.conversationId) return false
                if (conversationId == null) return false
                terminate(CallEvent.Failure, event.reason ?: "call_error")
            }
        }
        return true
    }

    @Synchronized
    fun localEnd(reason: String = "local_end") = terminate(CallEvent.LocalEnd, reason)

    /** True when this device already answered [callId] (Accepting or later). */
    fun acceptedHere(callId: Long): Boolean {
        val current = _state.value
        return current.route?.callId == callId && !current.phase.isTerminal() &&
            current.phase != CallPhase.Ringing && current.phase != CallPhase.Idle
    }

    @Synchronized
    fun mediaConnected() = transition(CallEvent.MediaConnected)

    @Synchronized
    fun mediaDisconnected() = transition(CallEvent.MediaDisconnected)

    @Synchronized
    fun fail(reason: String) = terminate(CallEvent.Failure, reason)

    @Synchronized
    fun localReject(reason: String = "local_reject") = terminate(CallEvent.RemoteRejected, reason)

    @Synchronized
    fun expire() = terminate(CallEvent.RingExpired, "expired")

    @Synchronized
    fun reset(): Boolean {
        if (!_state.value.phase.isTerminal() || !cleanupComplete) return false
        _state.value = CallSessionUiState()
        cleanupComplete = false
        return true
    }

    private fun transition(event: CallEvent) {
        val current = _state.value
        _state.value = current.copy(phase = reduceCallPhase(current.phase, event))
    }

    private fun matches(callId: Long, conversationId: Long): Boolean =
        _state.value.route?.let { it.callId == callId && it.conversationId == conversationId } == true

    private fun matchesOptional(callId: Long?, conversationId: Long?): Boolean {
        val current = _state.value
        val route = current.route
        if (route != null) return (callId == null || route.callId == callId) &&
            (conversationId == null || route.conversationId == conversationId)
        return callId == null && conversationId != null && current.outgoingConversationId == conversationId
    }

    private fun terminate(event: CallEvent, reason: String) {
        val current = _state.value
        if (current.phase.isTerminal()) return
        _state.value = current.copy(phase = reduceCallPhase(current.phase, event), terminalReason = reason)
        if (!cleanupComplete) {
            cleanupComplete = true
            deduplicator.clear()
            resources.release(current.route?.callId)
        }
    }
}

fun CallRealtimeEvent.Incoming.toRoute() = IncomingCallRoute(
    callId = callId,
    conversationId = conversationId,
    callerId = callerId,
    callerName = callerName,
    callerAvatar = callerAvatar,
    callType = callType,
    meetingCode = meetingCode,
    meetingId = meetingId,
)

object CallSessionRuntime {
    @Volatile private var instance: CallSessionController? = null

    fun get(context: Context): CallSessionController = instance ?: synchronized(this) {
        instance ?: CallSessionController(
            CallResourceOwner { callId ->
                CallRingService.stop(context.applicationContext)
                ActiveCallService.stop(context.applicationContext)
                PendingCallActionStore.clear(context.applicationContext)
                callId?.let(IncomingCallDismissals::dismiss)
            },
        ).also { instance = it }
    }
}