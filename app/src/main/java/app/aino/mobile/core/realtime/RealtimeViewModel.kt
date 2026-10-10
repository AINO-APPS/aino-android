package app.aino.mobile.core.realtime

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.network.NetworkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the realtime socket's lifecycle. One decision ([realtimeLinkDecision])
 * opens, pauses or closes it from sign-in, app visibility, calls, push
 * availability and the default network, instead of each screen guessing from
 * its own failed requests.
 */
class RealtimeViewModel(
    private val client: RealtimeClient,
    val dispatcher: RealtimeDispatcher = RealtimeDispatcher(),
    networkStatus: StateFlow<NetworkStatus>? = null,
    networkResets: Flow<Unit> = emptyFlow(),
    /** Drops pooled HTTP connections of the previous network. */
    private val onNetworkReset: () -> Unit = {},
    private val pushAvailable: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val backgroundGraceMs: Long = REALTIME_BACKGROUND_GRACE_MS,
) : ViewModel() {
    val state: StateFlow<RealtimeState> = client.state

    /** Raw transport stream. Prefer [domain] so parity stays registry-driven. */
    val events: SharedFlow<RealtimeEnvelope> = client.events

    /** Bumps when events may have been missed; screens revalidate silently. */
    val resync: StateFlow<Long> = client.resync

    private val conditions = MutableStateFlow(RealtimeConditions(pushAvailable = pushAvailable()))
    private val network: StateFlow<NetworkStatus> = networkStatus ?: MutableStateFlow(NetworkStatus.Unknown)

    /** Debounced status for chat surfaces: brief reconnects show nothing. */
    val indicator: StateFlow<ConnectionIndicator> = combine(client.state, network, ::connectionIndicator)
        .debounceProblems()
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConnectionIndicator.Connected)

    /** Typed events for one domain (A-100). */
    fun domain(target: RealtimeDomain): Flow<RoutedRealtimeEvent> = dispatcher.domain(target)

    /** Typed events across several domains. */
    fun domains(vararg targets: RealtimeDomain): Flow<RoutedRealtimeEvent> =
        dispatcher.domains(*targets)

    init {
        // One collector owns registry routing so every consumer sees the same
        // typed stream instead of re-parsing envelopes independently.
        viewModelScope.launch {
            client.events.collect { envelope -> dispatcher.dispatch(envelope, onDropped = client::requestResync) }
        }
        viewModelScope.launch {
            combine(conditions, network) { current, status -> current.copy(network = status) }
                .collectLatest(::apply)
        }
        viewModelScope.launch {
            networkResets.collect {
                // Closing pooled TLS sockets does network I/O: never on the main thread.
                withContext(Dispatchers.IO) { runCatching(onNetworkReset) }
                client.forceReconnect()
            }
        }
    }

    private suspend fun apply(current: RealtimeConditions) {
        when (val decision = realtimeLinkDecision(current, clock(), backgroundGraceMs)) {
            RealtimeLinkDecision.Close -> client.disconnect()
            RealtimeLinkDecision.Pause -> client.pause()
            RealtimeLinkDecision.Open -> open()
            is RealtimeLinkDecision.OpenUntil -> {
                open()
                // Cancelled by any change (collectLatest); otherwise the grace ran out.
                delay((decision.untilMs - clock()).coerceAtLeast(0))
                client.pause()
            }
        }
    }

    /** A terminal close waits for the shell to verify the session ([retryStopped]). */
    private fun open() {
        if (client.state.value !is RealtimeState.Stopped) client.connect()
    }

    fun setAuthenticatedTenant(active: Boolean) {
        conditions.update { it.copy(authenticated = active, pushAvailable = pushAvailable()) }
    }

    fun setForeground(foreground: Boolean) {
        conditions.update {
            if (it.foreground == foreground) it
            else it.copy(foreground = foreground, backgroundedAtMs = if (foreground) null else clock(), pushAvailable = pushAvailable())
        }
    }

    fun setInCall(inCall: Boolean) {
        conditions.update { it.copy(inCall = inCall) }
    }

    /** The shell verified the session after a terminal close: try once more. */
    fun retryStopped() {
        if (client.state.value !is RealtimeState.Stopped) return
        val decision = realtimeLinkDecision(conditions.value.copy(network = network.value), clock(), backgroundGraceMs)
        if (decision == RealtimeLinkDecision.Open || decision is RealtimeLinkDecision.OpenUntil) client.connect()
    }

    /** App returned to the foreground. */
    fun reconnectNow() = client.reconnectNow()

    fun send(envelope: RealtimeEnvelope): Boolean = client.send(envelope)

    override fun onCleared() {
        client.close()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                val appContext = context.applicationContext
                return RealtimeViewModel(
                    RealtimeClient(container.freshTokens, client = container.http),
                    networkStatus = container.connectivity.status,
                    networkResets = container.connectivity.resets,
                    onNetworkReset = container::resetConnections,
                    pushAvailable = { pushCanWake(appContext) },
                ) as T
            }
        }

        /**
         * Push replaces the background socket only when it can actually deliver:
         * Firebase configured, Google Play services present, and notifications
         * allowed. Otherwise the socket stays the delivery path.
         */
        private fun pushCanWake(context: Context): Boolean = runCatching {
            com.google.firebase.FirebaseApp.getApps(context).isNotEmpty() &&
                com.google.android.gms.common.GoogleApiAvailability.getInstance()
                    .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS &&
                androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(false)
    }
}
