package app.aino.mobile.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val loading: Boolean = false,
    val snapshot: DashboardSnapshot? = null,
    val error: String? = null,
)

class DashboardViewModel(
    private val repository: DashboardRepository,
    /** Same loads served from the last responses (instant paint before the network answers). */
    private val warm: DashboardRepository? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(DashboardUiState())
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()
    private var isManager: Boolean = false

    // No refresh() in init: this ViewModel is created by MainActivity before
    // sign-in, and a token-less `tracker/status` is rejected by the server's
    // requireTenant (HTTP 400). The shell calls refresh() once a tenant
    // session exists (AinoApp) and reset() on sign-out.

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
            if (_ui.value.snapshot == null && warm != null) {
                // A cache from an earlier day is not today's state: wait for the network instead.
                runCatching { warm.load(isManager) }.getOrNull()?.takeIf { it.status.isFromDayOf(System.currentTimeMillis()) }?.let { cached ->
                    if (_ui.value.snapshot == null) _ui.value = _ui.value.copy(snapshot = cached)
                }
            }
            runCatching { repository.load(isManager) }.fold(
                onSuccess = { snapshot -> _ui.value = DashboardUiState(false, snapshot) },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = app.aino.mobile.core.network.userFacingMessage(it, "Could not load dashboard")) },
            )
            if (refreshAgain) { refreshAgain = false; refresh() }
        }
    }

    /** Sign-out: drop the previous user's snapshot and any stale error. */
    fun reset() {
        refreshAgain = false
        _ui.value = DashboardUiState()
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
                return DashboardViewModel(DashboardRepository(api), DashboardRepository(container.cachedApi)) as T
            }
        }
    }
}