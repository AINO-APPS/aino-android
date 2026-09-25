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
    private var isManager: Boolean = false

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

    fun setManager(manager: Boolean) {
        if (manager != isManager) { isManager = manager; refresh() } else isManager = manager
    }

    private var refreshAgain = false

    fun refresh() {
        // Coalesce: a request that arrives mid-load (e.g. the manager flag flipping
        // during the first load) runs once after, instead of being dropped.
        if (_ui.value.loading) { refreshAgain = true; return }
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.load(isManager) }.fold(
                onSuccess = { snapshot ->
                    val (floor, breaks) = liveDurations(snapshot.status, snapshot.loadedAtEpochMs, System.currentTimeMillis())
                    _ui.value = DashboardUiState(false, snapshot, floor, breaks)
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load dashboard") },
            )
            if (refreshAgain) { refreshAgain = false; refresh() }
        }
    }

    fun approve(id: Long) = act { repository.approveRequest(id) }
    fun reject(id: Long) = act { repository.rejectRequest(id) }

    private fun act(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { block() }
            refresh()
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                val tokens = container.tokens
                val api = container.api
                return DashboardViewModel(DashboardRepository(api)) as T
            }
        }
    }
}