package app.aino.mobile.core.realtime

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.aino.mobile.core.auth.KeystoreTokenStore
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class RealtimeViewModel(private val client: RealtimeClient) : ViewModel() {
    val state: StateFlow<RealtimeState> = client.state
    val events: SharedFlow<RealtimeEnvelope> = client.events

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