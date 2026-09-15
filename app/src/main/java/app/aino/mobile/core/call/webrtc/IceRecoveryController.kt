package app.aino.mobile.core.call.webrtc

import org.webrtc.PeerConnection

class IceRecoveryController(
    private val session: PeerConnectionSession,
    private val hasRelayServers: Boolean,
    private val onRelayOnlyRebuild: () -> Unit,
    private val onGiveUp: () -> Unit,
) {
    private var failureCount = 0
    private var relayOnly = false

    fun onConnectionState(state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> failureCount = 0
            PeerConnection.PeerConnectionState.FAILED,
            PeerConnection.PeerConnectionState.DISCONNECTED -> recover()
            else -> Unit
        }
    }

    fun onConnectTimeout(elapsedMs: Long, connected: Boolean) {
        if (shouldFastRelayFallback(elapsedMs, connected, relayOnly, hasRelayServers)) {
            relayOnly = true
            onRelayOnlyRebuild()
        }
    }

    private fun recover() {
        when (iceRecoveryAction(failureCount++, relayOnly, hasRelayServers)) {
            IceRecoveryAction.RestartIce -> session.restartIce()
            IceRecoveryAction.RebuildRelayOnly -> {
                relayOnly = true
                onRelayOnlyRebuild()
            }
            IceRecoveryAction.GiveUp -> onGiveUp()
            IceRecoveryAction.Wait -> Unit
        }
    }
}