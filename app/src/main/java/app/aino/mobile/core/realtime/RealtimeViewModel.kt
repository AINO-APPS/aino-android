package app.aino.mobile.core.realtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RealtimeViewModel(
    private val client: RealtimeClient,
    val dispatcher: RealtimeDispatcher = RealtimeDispatcher(),
    private val connectivity: ConnectivityManager? = null,
) : ViewModel() {
    val state: StateFlow<RealtimeState> = client.state

    /** Raw transport stream. Prefer [domain] so parity stays registry-driven. */
    val events: SharedFlow<RealtimeEnvelope> = client.events

    /** Bumps when events may have been missed; screens revalidate silently. */
    val resync: StateFlow<Long> = client.resync

    /** Typed events for one domain (A-100). */
    fun domain(target: RealtimeDomain): Flow<RoutedRealtimeEvent> = dispatcher.domain(target)

    /** Typed events across several domains. */
    fun domains(vararg targets: RealtimeDomain): Flow<RoutedRealtimeEvent> =
        dispatcher.domains(*targets)

    // Web `online` handler: reconnect the moment a network is available
    // instead of waiting out the backoff.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = client.reconnectNow()
    }

    init {
        // One collector owns registry routing so every consumer sees the same
        // typed stream instead of re-parsing envelopes independently.
        viewModelScope.launch {
            client.events.collect { envelope -> dispatcher.dispatch(envelope, onDropped = client::requestResync) }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
    }

    fun setAuthenticatedTenant(active: Boolean) {
        if (active) client.connect() else client.disconnect()
    }

    /** App returned to the foreground. */
    fun reconnectNow() = client.reconnectNow()

    fun send(envelope: RealtimeEnvelope): Boolean = client.send(envelope)

    override fun onCleared() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        client.close()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return RealtimeViewModel(
                    RealtimeClient(container.tokens),
                    connectivity = context.getSystemService(ConnectivityManager::class.java),
                ) as T
            }
        }
    }
}
