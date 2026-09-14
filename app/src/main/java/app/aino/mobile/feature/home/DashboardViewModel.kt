package app.aino.mobile.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = false,
    val snapshot: DashboardSnapshot? = null,
    val floorSeconds: Long = 0,
    val breakSeconds: Long = 0,
    val error: String? = null,
)

class DashboardViewModel(private val repository: DashboardRepository) : ViewModel() {
    private val _ui = MutableStateFlow(DashboardUiState())
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val snapshot = _ui.value.snapshot ?: continue
                val (floor, breaks) = liveDurations(snapshot.status, snapshot.loadedAtEpochMs, System.currentTimeMillis())
                _ui.value = _ui.value.copy(floorSeconds = floor, breakSeconds = breaks)
            }
        }
    }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching(repository::load).fold(
                onSuccess = { snapshot ->
                    val (floor, breaks) = liveDurations(snapshot.status, snapshot.loadedAtEpochMs, System.currentTimeMillis())
                    _ui.value = DashboardUiState(false, snapshot, floor, breaks)
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load dashboard") },
            )
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                return DashboardViewModel(DashboardRepository(api)) as T
            }
        }
    }
}