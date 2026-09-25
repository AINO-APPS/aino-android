package app.aino.mobile.feature.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ProbeResult(
    val endpoint: String,
    val status: Int,
    val snippet: String,
    val ok: Boolean,
)

data class ApiProbeUiState(
    val running: Boolean = false,
    val results: List<ProbeResult> = emptyList(),
    val ranAt: Long? = null,
)

/** Debug-only probe over the 12 core endpoints (P0.3). */
class ApiProbeViewModel(private val api: ApiClient) : ViewModel() {
    private val _ui = MutableStateFlow(ApiProbeUiState())
    val ui: StateFlow<ApiProbeUiState> = _ui.asStateFlow()

    private fun endpoints(): List<String> {
        val today = LocalDate.now().toString()
        return listOf(
            "profile",
            "tracker/status",
            "tracker/task-summary",
            "tracker/entries/$today",
            "calendar?start=$today&end=$today",
            "notifications/announcements",
            "chat/conversations",
            "leaves",
            "leaves/balance",
            "leave-policy/policies",
            "tracker/manual-entries",
            "me/status",
        )
    }

    fun run() {
        if (_ui.value.running) return
        _ui.value = ApiProbeUiState(running = true)
        viewModelScope.launch(Dispatchers.IO) {
            val results = endpoints().map { path ->
                try {
                    val response = api.execute(ApiRequest(path = path, headers = mapOf("Accept" to "application/json")))
                    ProbeResult(path, response.statusCode, response.bodyAsString().take(300), response.statusCode in 200..299)
                } catch (error: ApiError.Http) {
                    ProbeResult(path, error.statusCode, error.responseBody.take(300), false)
                } catch (error: Exception) {
                    ProbeResult(path, -1, (error.message ?: "network error").take(300), false)
                }
            }
            _ui.value = ApiProbeUiState(running = false, results = results, ranAt = System.currentTimeMillis())
        }
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                val tokens = container.tokens
                val api = container.api
                return ApiProbeViewModel(api) as T
            }
        }
    }
}
