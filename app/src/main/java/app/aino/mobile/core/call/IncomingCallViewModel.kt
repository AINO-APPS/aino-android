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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class IncomingCallState { Ringing, Answering, Declining, Ended, Error }

data class IncomingCallUiState(
    val route: IncomingCallRoute? = null,
    val state: IncomingCallState = IncomingCallState.Ended,
    val error: String? = null,
)

@Serializable private data class CallActionRequest(val conversationId: Long)

fun buildCallActionRequest(route: IncomingCallRoute, action: String): ApiRequest {
    require(action in setOf("accept", "reject"))
    // @api POST chat/calls/:callId/accept
    // @api POST chat/calls/:callId/reject
    return ApiRequest(
        "POST",
        "chat/calls/${route.callId}/$action",
        body = Json.encodeToString(CallActionRequest(route.conversationId)).toByteArray(),
    )
}

class IncomingCallViewModel(
    private val api: ApiClient,
    private val context: Context,
    private val callSession: CallSessionController = CallSessionRuntime.get(context),
) : ViewModel() {
    private val _ui = MutableStateFlow(IncomingCallUiState())
    val ui: StateFlow<IncomingCallUiState> = _ui.asStateFlow()

    /** Injected by the shell; group-call declines go over the socket (`huddle_decline`). */
    var realtimeSend: (app.aino.mobile.core.realtime.RealtimeEnvelope) -> Boolean = { false }

    init {
        viewModelScope.launch {
            IncomingCallDismissals.events.collect { callId ->
                if (_ui.value.route?.callId == callId) dismissUi()
            }
        }
    }

    fun route(route: IncomingCallRoute): Boolean {
        if (!callSession.incoming(route)) return false
        _ui.value = IncomingCallUiState(route, IncomingCallState.Ringing)
        when (route.action) {
            "answer" -> answer()
            "decline" -> decline()
        }
        return true
    }

    /** [withoutVideo]: Signal's "Answer without video" — accept a video call camera-off. */
    fun answer(withoutVideo: Boolean = false) {
        val route = _ui.value.route ?: return
        val code = route.meetingCode
        if (code != null) {
            // Group call: the ring is only an invite; the huddle meeting carries the media.
            finishRing()
            app.aino.mobile.core.navigation.RouteRequests.open(app.aino.mobile.core.navigation.huddleRoute(code))
            return
        }
        callSession.accepting()
        act("accept", IncomingCallState.Answering, IncomingCallState.Ended, withoutVideo)
    }

    fun decline() {
        val route = _ui.value.route ?: return
        if (route.meetingCode != null) {
            realtimeSend(app.aino.mobile.core.call.webrtc.huddleDeclineEnvelope(route.meetingId ?: route.callId))
            finishRing()
            return
        }
        act("reject", IncomingCallState.Declining, IncomingCallState.Ended)
    }

    private fun finishRing() {
        CallRingService.stop(context)
        callSession.localEnd("huddle")
        callSession.reset()
        _ui.value = IncomingCallUiState()
    }

    fun expireIfRinging() {
        if (_ui.value.state != IncomingCallState.Ringing) return
        CallRingService.stop(context)
        callSession.expire()
        callSession.reset()
        _ui.value = IncomingCallUiState()
    }

    fun clear() {
        CallRingService.stop(context)
        if (!callSession.state.value.phase.isTerminal()) callSession.localEnd()
        callSession.reset()
        _ui.value = IncomingCallUiState()
    }

    private fun dismissUi() {
        CallRingService.stop(context)
        _ui.value = IncomingCallUiState()
    }

    private fun act(path: String, pending: IncomingCallState, success: IncomingCallState, withoutVideo: Boolean = false) {
        val route = _ui.value.route ?: return
        if (_ui.value.state in setOf(IncomingCallState.Answering, IncomingCallState.Declining, IncomingCallState.Ended)) return
        _ui.value = _ui.value.copy(state = pending, error = null)
        CallRingService.stop(context)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                api.execute(buildCallActionRequest(route, path))
            }.fold(
                onSuccess = {
                    if (path == "reject") {
                        callSession.localReject()
                        callSession.reset()
                        // Signal closes the incoming screen straight away on Decline.
                        _ui.value = IncomingCallUiState()
                    } else {
                        // The in-call screen takes over; the ring UI goes away.
                        withContext(Dispatchers.Main) { ActiveCallRuntime.get(context).startIncoming(route, startVideoOff = withoutVideo) }
                        _ui.value = IncomingCallUiState()
                    }
                },
                onFailure = { _ui.value = _ui.value.copy(state = IncomingCallState.Error, error = it.message ?: "Call action failed") },
            )
        }
    }

    companion object {
        private val JSON = Json
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return IncomingCallViewModel(
                    app.aino.mobile.core.AppContainer.get(context).api,
                    context.applicationContext,
                    CallSessionRuntime.get(context.applicationContext),
                ) as T
            }
        }
    }
}