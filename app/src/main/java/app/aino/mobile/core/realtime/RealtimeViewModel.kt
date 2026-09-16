package app.aino.mobile.core.realtime

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class RealtimeViewModel(
    private val client: RealtimeClient,
    val dispatcher: RealtimeDispatcher = RealtimeDispatcher(),
) : ViewModel() {
    val state: StateFlow<RealtimeState> = client.state

    /** Raw transport stream. Prefer [domain] so parity stays registry-driven. */
    val events: SharedFlow<RealtimeEnvelope> = client.events

    /** Typed events for one domain (A-100). */
    fun domain(target: RealtimeDomain): Flow<RoutedRealtimeEvent> = dispatcher.domain(target)

    /** Typed events across several domains. */
    fun domains(vararg targets: RealtimeDomain): Flow<RoutedRealtimeEvent> =
        dispatcher.domains(*targets)

    init {
        // One collector owns registry routing so every consumer sees the same
        // typed stream instead of re-parsing envelopes independently.
        viewModelScope.launch {
            client.events.collect(dispatcher::dispatch)
        }
    }

    fun setAuthenticatedTenant(active: Boolean) {
        if (active) client.connect() else client.disconnect()
    }

    fun send(envelope: RealtimeEnvelope): Boolean = client.send(envelope)

    override fun onCleared() {
        client.close()
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return RealtimeViewModel(RealtimeClient(KeystoreTokenStore(context))) as T
            }
        }
    }
}