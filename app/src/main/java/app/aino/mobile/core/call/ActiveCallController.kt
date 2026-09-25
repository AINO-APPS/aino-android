package app.aino.mobile.core.call

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.call.webrtc.CallSignal
import app.aino.mobile.core.call.webrtc.CallSignalCommand
import app.aino.mobile.core.call.webrtc.IceCandidateSignal
import app.aino.mobile.core.call.webrtc.IceConfigDto
import app.aino.mobile.core.call.webrtc.IceConfigRepository
import app.aino.mobile.core.call.webrtc.LocalMedia
import app.aino.mobile.core.call.webrtc.RtcPeer
import app.aino.mobile.core.call.webrtc.WebRtcRuntime
import app.aino.mobile.core.call.webrtc.callCancelEnvelope
import app.aino.mobile.core.call.webrtc.callEndEnvelope
import app.aino.mobile.core.call.webrtc.callInitiateEnvelope
import app.aino.mobile.core.call.webrtc.callReadyEnvelope
import app.aino.mobile.core.call.webrtc.callSignalEnvelope
import app.aino.mobile.core.call.webrtc.callSubscribeEnvelope
import app.aino.mobile.core.call.webrtc.fallbackIceConfig
import app.aino.mobile.core.realtime.RealtimeEnvelope
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

data class ActiveCallUi(
    val visible: Boolean = false,
    val callType: String = "voice",
    val incoming: Boolean = false,
    val peerName: String = "",
    val peerAvatar: String? = null,
    val phase: CallPhase = CallPhase.Idle,
    /** Caller side: the callee accepted (Ringing… → Connecting…). */
    val accepted: Boolean = false,
    val connectedAt: Long? = null,
    val muted: Boolean = false,
    val videoOff: Boolean = false,
    val speakerOn: Boolean = false,
    val remoteMuted: Boolean = false,
    val remoteVideoOff: Boolean = false,
    /** `No answer` / `Couldn't connect` / `{name} is on another call`, shown 1.8 s before closing. */
    val endMessage: String? = null,
    val localVideo: VideoTrack? = null,
    val remoteVideo: VideoTrack? = null,
) {
    val isVideo: Boolean get() = callType == "video"

    /** `CallOverlay` status line (three ASCII dots, verbatim). */
    val statusText: String get() = endMessage ?: when {
        phase == CallPhase.Reconnecting -> "Reconnecting..."
        !incoming && !accepted -> "Ringing..."
        else -> "Connecting..."
    }
}

/**
 * The web `CallOverlay` + `useWebRTC` for 1:1 calls: owns the local media, the
 * one [RtcPeer] and the call audio, driven by [CallSessionController]'s
 * lifecycle. The caller always creates the offer (impolite); the callee is
 * polite. Signals: `call_signal` with `offer`/`answer`/`ice-candidate` plus
 * `audio-state` / `video-state` so the peer shows mute / camera-off.
 * ponytail: no relay-only rebuild after a failed ICE restart, no hold, noise
 * toggle, screen share, recording or reactions — add with the matching web control.
 */
class ActiveCallController(private val context: Context, private val session: CallSessionController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = CallAudio(context, ActiveCallService.OWNER_CALL)
    private val _ui = MutableStateFlow(ActiveCallUi())
    val ui: StateFlow<ActiveCallUi> = _ui.asStateFlow()

    @Volatile var send: (RealtimeEnvelope) -> Boolean = { false }

    private var media: LocalMedia? = null
    private var peer: RtcPeer? = null
    private var ice = CompletableDeferred<IceConfigDto>()
    private val signalLock = Mutex()
    private var remoteUserId: Long? = null
    private var iceRestarted = false
    private var timeout: Job? = null
    private var recovery: Job? = null
    private var closing: Job? = null
    private val earlySignals = mutableListOf<JsonObject>()
    private var ringback: android.media.ToneGenerator? = null

    init {
        scope.launch { session.state.collect(::onSessionState) }
    }

    /** Returns an error message, or null when the ring started. */
    fun startOutgoing(conversationId: Long, callType: String, peerName: String, peerAvatar: String?, peerUserId: Long?): String? {
        if (_ui.value.visible || !session.outgoing(conversationId)) return "Another call is already active"
        remoteUserId = peerUserId
        begin(ActiveCallUi(visible = true, callType = callType, peerName = peerName, peerAvatar = peerAvatar, phase = CallPhase.Connecting))
        if (!send(callInitiateEnvelope(conversationId, callType, UUID.randomUUID().toString()))) {
            session.localEnd("realtime_unavailable")
            return "Realtime connection is unavailable"
        }
        startRingback()
        arm(35_000, "No answer")
        return null
    }

    /** Signal plays a ringback while the callee's phone rings. */
    private fun startRingback() {
        stopRingback()
        ringback = runCatching {
            android.media.ToneGenerator(android.media.AudioManager.STREAM_VOICE_CALL, 70).also {
                it.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE)
            }
        }.getOrNull()
    }

    private fun stopRingback() {
        ringback?.let { runCatching { it.stopTone(); it.release() } }
        ringback = null
    }

    /** After `POST chat/calls/:id/accept` succeeded (IncomingCallViewModel). */
    fun startIncoming(route: IncomingCallRoute, startVideoOff: Boolean = false) {
        if (_ui.value.visible) return
        // The caller may have hung up while the accept request was in flight.
        val current = session.state.value
        if (current.phase != CallPhase.Accepting || current.route?.callId != route.callId) return
        remoteUserId = route.callerId
        begin(
            ActiveCallUi(
                visible = true,
                callType = route.callType,
                incoming = true,
                peerName = route.callerName,
                peerAvatar = route.callerAvatar,
                phase = CallPhase.Accepting,
                accepted = true,
            ),
            startVideoOff,
        )
        send(callSubscribeEnvelope(route.callId, route.conversationId))
        send(callReadyEnvelope(route.callId, route.conversationId))
        arm(30_000, "Couldn't connect")
        val early = earlySignals.toList()
        earlySignals.clear()
        early.forEach { signal -> scope.launch { signalLock.withLock { applySignal(signal) } } }
    }

    private fun begin(initial: ActiveCallUi, startVideoOff: Boolean = false) {
        closing?.cancel()
        iceRestarted = false
        ice = CompletableDeferred()
        val video = initial.isVideo
        // The video track exists for any video call so the camera can be turned on later.
        val local = LocalMedia(context, WebRtcRuntime.shared(context), withVideo = video).also { media = it }
        val cameraOn = video && !startVideoOff && local.setVideoEnabled(true)
        audio.start(speaker = video)
        _ui.value = initial.copy(localVideo = local.videoTrack, videoOff = video && !cameraOn, speakerOn = video)
        ActiveCallService.start(
            context,
            mapOf(
                ActiveCallService.EXTRA_TITLE to initial.peerName.ifBlank { "Ongoing call" },
                ActiveCallService.EXTRA_CALL_TYPE to initial.callType,
            ),
        )
        val deferred = ice
        scope.launch {
            val config = withContext(Dispatchers.IO) {
                runCatching { IceConfigRepository(AppContainer.get(context).api).load() }.getOrDefault(fallbackIceConfig())
            }
            deferred.complete(config)
        }
    }

    /** Non-incoming call events from the shell; the session validates them first. */
    fun onEvent(event: CallRealtimeEvent) {
        if (!session.handle(event)) return
        val ui = _ui.value
        if (!ui.visible) {
            // The caller can offer before our REST accept returns; keep those for startIncoming.
            if (event is CallRealtimeEvent.Signal && session.state.value.phase == CallPhase.Accepting) earlySignals += event.signal
            return
        }
        when (event) {
            is CallRealtimeEvent.Accepted -> if (!ui.incoming) {
                stopRingback()
                remoteUserId = event.userId ?: remoteUserId
                _ui.update { it.copy(accepted = true) }
                arm(30_000, "Couldn't connect")
                scope.launch { signalLock.withLock { newPeer(offer = true) } }
            }
            is CallRealtimeEvent.PeerReady -> if (!ui.incoming && ui.accepted) {
                scope.launch { signalLock.withLock { if (peer?.resendLocalOffer() != true && peer == null) newPeer(offer = true) } }
            }
            // The other side reloaded: rebuild and re-offer (useWebRTC.ts:1528).
            is CallRealtimeEvent.Reconnect -> scope.launch { signalLock.withLock { newPeer(offer = true) } }
            is CallRealtimeEvent.Signal -> {
                remoteUserId = event.fromUserId ?: remoteUserId
                scope.launch { signalLock.withLock { applySignal(event.signal) } }
            }
            else -> Unit
        }
    }

    private suspend fun applySignal(signal: JsonObject) {
        fun str(key: String) = (signal[key] as? JsonPrimitive)?.contentOrNull
        when (str("type")) {
            "offer" -> ensurePeer()?.handleOffer(str("sdp").orEmpty())
            "answer" -> peer?.handleAnswer(str("sdp").orEmpty())
            "ice-candidate" -> {
                val c = signal["candidate"] as? JsonObject ?: return
                val text = (c["candidate"] as? JsonPrimitive)?.contentOrNull ?: return
                ensurePeer()?.handleCandidate(
                    IceCandidateSignal(text, (c["sdpMid"] as? JsonPrimitive)?.contentOrNull, (c["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0),
                )
            }
            "audio-state" -> (signal["muted"] as? JsonPrimitive)?.booleanOrNull?.let { m -> _ui.update { it.copy(remoteMuted = m) } }
            "video-state" -> (signal["videoOff"] as? JsonPrimitive)?.booleanOrNull?.let { v -> _ui.update { it.copy(remoteVideoOff = v) } }
        }
    }

    private suspend fun ensurePeer(): RtcPeer? = peer ?: newPeer(offer = false)

    private suspend fun newPeer(offer: Boolean): RtcPeer? {
        val config = ice.await()
        val local = media ?: return null
        if (!_ui.value.visible) return null
        peer?.close()
        _ui.update { it.copy(remoteVideo = null) }
        val created = runCatching {
            RtcPeer(WebRtcRuntime.shared(context), config, polite = _ui.value.incoming, listener = Listener())
        }.getOrElse { return null }
        created.addLocalTracks(local.audioTrack, local.videoTrack)
        peer = created
        if (offer) created.createOffer()
        return created
    }

    private inner class Listener : RtcPeer.Listener {
        override fun onLocalDescription(type: String, sdp: String) {
            scope.launch { sendSignal(CallSignal(type, sdp = sdp)) }
        }

        override fun onLocalCandidate(candidate: IceCandidateSignal) {
            scope.launch { sendSignal(CallSignal("ice-candidate", candidate = candidate)) }
        }

        override fun onRemoteTrack(track: MediaStreamTrack) {
            if (track is VideoTrack) scope.launch { _ui.update { it.copy(remoteVideo = track) } }
        }

        override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
            scope.launch { onPeerState(state) }
        }
    }

    private fun onPeerState(state: PeerConnection.PeerConnectionState) {
        if (!_ui.value.visible) return
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                timeout?.cancel()
                recovery?.cancel()
                session.mediaConnected()
                _ui.update { it.copy(connectedAt = it.connectedAt ?: System.currentTimeMillis()) }
                sendMediaState()
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                session.mediaDisconnected()
                // useWebRTC.ts:781 — one ICE restart after 2 s still disconnected.
                recovery?.cancel()
                recovery = scope.launch {
                    delay(2_000)
                    restartOnce()
                }
                arm(30_000, "Couldn't connect")
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                session.mediaDisconnected()
                if (!restartOnce()) {
                    timeout?.cancel()
                    closeWith("Couldn't connect")
                }
            }
            else -> Unit
        }
    }

    private fun restartOnce(): Boolean {
        val current = peer ?: return false
        if (current.connectionState == PeerConnection.PeerConnectionState.CONNECTED) return true
        if (iceRestarted) return false
        iceRestarted = true
        current.restartIce()
        return true
    }

    private fun sendSignal(signal: CallSignal) {
        val route = session.state.value.route ?: return
        val target = remoteUserId ?: return
        runCatching { send(callSignalEnvelope(CallSignalCommand(route.callId, route.conversationId, target, signal))) }
    }

    private fun sendMediaState() {
        val ui = _ui.value
        sendSignal(CallSignal("audio-state", muted = ui.muted))
        if (ui.isVideo) sendSignal(CallSignal("video-state", videoOff = ui.videoOff))
    }

    fun toggleMute() {
        val muted = !_ui.value.muted
        media?.setMuted(muted)
        _ui.update { it.copy(muted = muted) }
        if (_ui.value.connectedAt != null) sendSignal(CallSignal("audio-state", muted = muted))
    }

    fun toggleVideo() {
        val ui = _ui.value
        if (!ui.isVideo) return
        val on = media?.setVideoEnabled(ui.videoOff) ?: false
        _ui.update { it.copy(videoOff = !on) }
        if (ui.connectedAt != null) sendSignal(CallSignal("video-state", videoOff = !on))
    }

    fun switchCamera() {
        media?.switchCamera()
    }

    fun toggleSpeaker() {
        val on = !_ui.value.speakerOn
        audio.setSpeaker(on)
        _ui.update { it.copy(speakerOn = on) }
    }

    /** End call (E): `call_end` once the server assigned an id, `call_cancel` before that. */
    fun hangUp() {
        val state = session.state.value
        if (state.phase.isTerminal()) {
            // Nothing left to signal; make sure the UI and media still go away.
            releaseMedia()
            hide()
            return
        }
        val route = state.route
        val clientMsgId = UUID.randomUUID().toString()
        if (route != null) {
            if (!send(callEndEnvelope(route.callId, route.conversationId, clientMsgId))) {
                scope.launch(Dispatchers.IO) {
                    runCatching { app.aino.mobile.feature.chat.ChatRepository(AppContainer.get(context).api).endCall(route.callId, route.conversationId) }
                }
            }
        } else {
            state.outgoingConversationId?.let { send(callCancelEnvelope(it, clientMsgId)) }
        }
        session.localEnd()
    }

    /** `call/index.tsx` timeouts: message for 1.8 s, then `call_end`. */
    private fun arm(millis: Long, message: String) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(millis)
            closeWith(message)
        }
    }

    private fun closeWith(message: String) {
        if (closing?.isActive == true) return
        stopRingback()
        _ui.update { it.copy(endMessage = message) }
        closing = scope.launch {
            delay(1_800)
            hangUp()
        }
    }

    private fun onSessionState(state: CallSessionUiState) {
        val ui = _ui.value
        if (!ui.visible) return
        _ui.update { it.copy(phase = state.phase) }
        if (!state.phase.isTerminal()) return
        releaseMedia()
        // A timeout message was already on screen, or nothing to say: close now.
        if (ui.endMessage != null || state.phase != CallPhase.Busy) {
            hide()
            return
        }
        _ui.update { it.copy(endMessage = "${ui.peerName.ifBlank { "The other person" }} is on another call") }
        closing = scope.launch {
            delay(1_800)
            hide()
        }
    }

    private fun hide() {
        timeout?.cancel()
        closing?.cancel()
        earlySignals.clear()
        _ui.value = ActiveCallUi()
        session.reset()
    }

    private fun releaseMedia() {
        stopRingback()
        timeout?.cancel()
        recovery?.cancel()
        peer?.close()
        peer = null
        _ui.update { it.copy(localVideo = null, remoteVideo = null) }
        media?.dispose()
        media = null
        audio.stop()
        ActiveCallService.stop(context, ActiveCallService.OWNER_CALL)
    }
}

/** `CallDuration.tsx` `formatDuration`: `mm:ss`, or `h:mm:ss` past an hour. */
fun formatCallDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val h = s / 3600
    val mm = ((s % 3600) / 60).toString().padStart(2, '0')
    val ss = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}

object ActiveCallRuntime {
    @Volatile private var instance: ActiveCallController? = null

    fun get(context: Context): ActiveCallController = instance ?: synchronized(this) {
        instance ?: ActiveCallController(context.applicationContext, CallSessionRuntime.get(context.applicationContext)).also { instance = it }
    }
}
