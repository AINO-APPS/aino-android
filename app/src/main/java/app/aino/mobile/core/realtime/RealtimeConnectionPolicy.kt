package app.aino.mobile.core.realtime

import app.aino.mobile.core.network.NetworkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

/** Everything the shell knows that decides whether the realtime socket should be open. */
data class RealtimeConditions(
    val authenticated: Boolean = false,
    val foreground: Boolean = true,
    /** When the app last left the foreground; null while visible. */
    val backgroundedAtMs: Long? = null,
    /** A 1:1 call, meeting or ringing call needs its signaling channel whatever the app's visibility. */
    val inCall: Boolean = false,
    /** FCM can wake the app; without it the socket is the only delivery path. */
    val pushAvailable: Boolean = false,
    val network: NetworkStatus = NetworkStatus.Unknown,
)

sealed interface RealtimeLinkDecision {
    /** Signed out: close and forget the session. */
    data object Close : RealtimeLinkDecision

    /** Signed in but the socket is not needed now (no network, or backgrounded with push). */
    data object Pause : RealtimeLinkDecision

    data object Open : RealtimeLinkDecision

    /** Backgrounded with push: keep the socket for a short grace, then pause at [untilMs]. */
    data class OpenUntil(val untilMs: Long) : RealtimeLinkDecision
}

/** AINO's own grace for a backgrounded app: long enough to cover a quick app switch. */
const val REALTIME_BACKGROUND_GRACE_MS = 60_000L

fun realtimeLinkDecision(
    conditions: RealtimeConditions,
    nowMs: Long,
    graceMs: Long = REALTIME_BACKGROUND_GRACE_MS,
): RealtimeLinkDecision = with(conditions) {
    when {
        !authenticated -> RealtimeLinkDecision.Close
        !network.usable -> RealtimeLinkDecision.Pause
        foreground || inCall || !pushAvailable -> RealtimeLinkDecision.Open
        else -> {
            val until = (backgroundedAtMs ?: nowMs) + graceMs
            if (nowMs < until) RealtimeLinkDecision.OpenUntil(until) else RealtimeLinkDecision.Pause
        }
    }
}

/** The one connection status chat surfaces show instead of per-request errors. */
enum class ConnectionIndicator { Connected, Connecting, WaitingForNetwork }

fun connectionIndicator(state: RealtimeState, network: NetworkStatus): ConnectionIndicator = when {
    !network.usable -> ConnectionIndicator.WaitingForNetwork
    state == RealtimeState.Connected -> ConnectionIndicator.Connected
    else -> ConnectionIndicator.Connecting
}

/** A routine reconnect finishes in a moment: only a problem that outlasts [delayMs] is shown. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<ConnectionIndicator>.debounceProblems(delayMs: Long = CONNECTION_INDICATOR_DELAY_MS): Flow<ConnectionIndicator> =
    distinctUntilChanged().transformLatest { indicator ->
        if (indicator != ConnectionIndicator.Connected) delay(delayMs)
        emit(indicator)
    }.distinctUntilChanged()

const val CONNECTION_INDICATOR_DELAY_MS = 3_000L
