package app.aino.mobile.core.call

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class IncomingCallState { Ringing, Answering, Declining, WaitingForMedia, Ended, Error }

data class IncomingCallUiState(
    val route: IncomingCallRoute? = null,
    val state: IncomingCallState = IncomingCallState.Ended,
    val error: String? = null,
)

@Serializable private data class CallActionRequest(val conversationId: Long)

fun buildCallActionRequest(route: IncomingCallRoute, action: String): ApiRequest {
    require(action in setOf("accept", "reject"))
    return ApiRequest(
        "POST",
        "chat/calls/${route.callId}/$action",
        body = Json.encodeToString(CallActionRequest(route.conversationId)).toByteArray(),
    )
}

class IncomingCallViewModel(
    private val api: ApiClient,
    private val context: Context,
) : ViewModel() {
    private val _ui = MutableStateFlow(IncomingCallUiState())
    val ui: StateFlow<IncomingCallUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            IncomingCallDismissals.events.collect { callId ->
                if (_ui.value.route?.callId == callId) clear()
            }
        }
    }

    fun route(route: IncomingCallRoute) {
        _ui.value = IncomingCallUiState(route, IncomingCallState.Ringing)
        when (route.action) {
            "answer" -> answer()
            "decline" -> decline()
        }
    }

    fun answer() = act("accept", IncomingCallState.Answering, IncomingCallState.WaitingForMedia)
    fun decline() = act("reject", IncomingCallState.Declining, IncomingCallState.Ended)

    fun expireIfRinging() {
        if (_ui.value.state != IncomingCallState.Ringing) return
        CallRingService.stop(context)
        _ui.value = _ui.value.copy(state = IncomingCallState.Ended)
    }

    fun clear() {
        CallRingService.stop(context)
        _ui.value = IncomingCallUiState()
    }

    private fun act(path: String, pending: IncomingCallState, success: IncomingCallState) {
        val route = _ui.value.route ?: return
        if (_ui.value.state in setOf(IncomingCallState.Answering, IncomingCallState.Declining, IncomingCallState.Ended)) return
        _ui.value = _ui.value.copy(state = pending, error = null)
        CallRingService.stop(context)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.execute(buildCallActionRequest(route, path))
            }.fold(
                onSuccess = { _ui.value = _ui.value.copy(state = success) },
                onFailure = { _ui.value = _ui.value.copy(state = IncomingCallState.Error, error = it.message ?: "Call action failed") },
            )
        }
    }

    companion object {
        private val JSON = Json
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                return IncomingCallViewModel(
                    RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens),
                    context.applicationContext,
                ) as T
            }
        }
    }
}