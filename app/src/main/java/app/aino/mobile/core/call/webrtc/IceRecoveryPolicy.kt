package app.aino.mobile.core.call.webrtc

enum class IceRecoveryAction { Wait, RestartIce, RebuildRelayOnly, GiveUp }

fun iceRecoveryAction(
    failureCount: Int,
    relayOnly: Boolean,
    hasRelayServers: Boolean,
): IceRecoveryAction = when {
    failureCount < 0 -> IceRecoveryAction.Wait
    failureCount < 2 -> IceRecoveryAction.RestartIce
    !relayOnly && hasRelayServers -> IceRecoveryAction.RebuildRelayOnly
    else -> IceRecoveryAction.GiveUp
}

fun shouldFastRelayFallback(elapsedMs: Long, connected: Boolean, relayOnly: Boolean, hasRelayServers: Boolean): Boolean =
    elapsedMs >= 5_000 && !connected && !relayOnly && hasRelayServers