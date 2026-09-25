package app.aino.mobile.core.call.webrtc

import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/**
 * One WebRTC peer connection with perfect negotiation, independent of the wire
 * protocol: 1:1 calls (`call_signal`) and meeting mesh links (`meeting_signal`)
 * serialise [Listener] callbacks into their own frame shapes.
 */
class RtcPeer(
    runtime: WebRtcRuntime,
    config: IceConfigDto,
    private val polite: Boolean,
    private val listener: Listener,
    relayOnly: Boolean = false,
) {
    interface Listener {
        /** `type` is `offer` or `answer`; `sdp` already carries the Opus FEC fmtp. */
        fun onLocalDescription(type: String, sdp: String)
        fun onLocalCandidate(candidate: IceCandidateSignal)
        fun onRemoteTrack(track: MediaStreamTrack) {}
        fun onConnectionState(state: PeerConnection.PeerConnectionState) {}
        fun onIceState(state: PeerConnection.IceConnectionState) {}
        fun onFailure(message: String) {}
    }

    private val candidates = IceCandidateBuffer()
    @Volatile private var makingOffer = false
    @Volatile private var remoteDescriptionSet = false
    @Volatile private var closed = false

    val peer: PeerConnection = requireNotNull(
        runtime.factory.createPeerConnection(
            runtime.configuration(config, relayOnly),
            object : NoOpPeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (!closed) listener.onLocalCandidate(IceCandidateSignal(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex))
                }
                override fun onTrack(transceiver: RtpTransceiver) {
                    transceiver.receiver.track()?.let { if (!closed) listener.onRemoteTrack(it) }
                }
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                    if (!closed) listener.onConnectionState(newState)
                }
                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                    if (!closed) listener.onIceState(newState)
                }
            },
        ),
    ) { "Could not create WebRTC peer connection" }

    fun addLocalTracks(audio: AudioTrack?, video: VideoTrack?) {
        audio?.let { peer.addTrack(it, listOf(STREAM_ID)) }
        video?.let { peer.addTrack(it, listOf(STREAM_ID)) }
    }

    val connectionState: PeerConnection.PeerConnectionState get() =
        if (closed) PeerConnection.PeerConnectionState.CLOSED else peer.connectionState()

    fun createOffer(iceRestart: Boolean = false) {
        if (closed) return
        makingOffer = true
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peer.createOffer(object : Observer() {
            override fun onCreateSuccess(description: SessionDescription) {
                val sdp = preferOpusFec(description.description)
                peer.setLocalDescription(object : Observer() {
                    override fun onSetSuccess() {
                        makingOffer = false
                        if (!closed) listener.onLocalDescription("offer", sdp)
                    }
                    override fun onSetFailure(error: String) {
                        makingOffer = false
                        super.onSetFailure(error)
                    }
                }, SessionDescription(SessionDescription.Type.OFFER, sdp))
            }
            override fun onCreateFailure(error: String) {
                makingOffer = false
                super.onCreateFailure(error)
            }
        }, constraints)
    }

    fun restartIce() {
        if (closed) return
        peer.restartIce()
        createOffer(iceRestart = true)
    }

    /** Re-sends the pending local offer (peer-ready after a missed first offer). */
    fun resendLocalOffer(): Boolean {
        if (closed || peer.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return false
        val local = peer.localDescription ?: return false
        listener.onLocalDescription("offer", local.description)
        return true
    }

    fun handleOffer(sdp: String) {
        if (closed || sdp.isBlank()) return
        val stable = peer.signalingState() == PeerConnection.SignalingState.STABLE
        when (offerCollisionDecision(polite, makingOffer, stable)) {
            OfferCollisionDecision.Ignore -> return
            OfferCollisionDecision.RollbackThenAccept -> peer.setLocalDescription(
                object : Observer() {
                    override fun onSetSuccess() = acceptOffer(sdp)
                },
                SessionDescription(SessionDescription.Type.ROLLBACK, ""),
            )
            OfferCollisionDecision.Accept -> acceptOffer(sdp)
        }
    }

    private fun acceptOffer(sdp: String) {
        peer.setRemoteDescription(object : Observer() {
            override fun onSetSuccess() {
                drainCandidates()
                peer.createAnswer(object : Observer() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        val answer = preferOpusFec(description.description)
                        peer.setLocalDescription(object : Observer() {
                            override fun onSetSuccess() {
                                if (!closed) listener.onLocalDescription("answer", answer)
                            }
                        }, SessionDescription(SessionDescription.Type.ANSWER, answer))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun handleAnswer(sdp: String) {
        if (closed || sdp.isBlank()) return
        if (peer.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        peer.setRemoteDescription(object : Observer() {
            override fun onSetSuccess() = drainCandidates()
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun handleCandidate(candidate: IceCandidateSignal?) {
        if (closed || candidate == null || candidate.candidate.isBlank()) return
        val buffered = BufferedIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        // Signals arrive on the caller's thread, SDP callbacks on WebRTC's; the
        // lock keeps a candidate from landing in the buffer after it drained.
        synchronized(candidates) {
            if (!remoteDescriptionSet) {
                candidates.add(buffered)
                return
            }
        }
        peer.addIceCandidate(buffered.toWebRtc())
    }

    private fun drainCandidates() {
        val pending = synchronized(candidates) {
            remoteDescriptionSet = true
            candidates.drain()
        }
        pending.forEach { peer.addIceCandidate(it.toWebRtc()) }
    }

    /** Caps outgoing bitrates (web `applyBitrateCap`: video by mesh size, audio 48 kbps). */
    fun capBitrates(videoMaxBps: Int?, audioMaxBps: Int = AUDIO_MAX_BITRATE) {
        if (closed) return
        peer.senders.forEach { sender ->
            val kind = sender.track()?.kind() ?: return@forEach
            val cap = if (kind == MediaStreamTrack.VIDEO_TRACK_KIND) videoMaxBps else audioMaxBps
            val params = sender.parameters
            if (params.encodings.isEmpty()) return@forEach
            params.encodings.forEach { it.maxBitrateBps = cap }
            if (kind == MediaStreamTrack.VIDEO_TRACK_KIND) {
                params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            }
            sender.parameters = params
        }
    }

    fun close() {
        if (closed) return
        closed = true
        synchronized(candidates) { candidates.clear() }
        runCatching { peer.dispose() }
    }

    private open inner class Observer : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) { if (!closed) listener.onFailure(error) }
        override fun onSetFailure(error: String) { if (!closed) listener.onFailure(error) }
    }

    private companion object {
        const val STREAM_ID = "aino-local"
    }
}

private fun BufferedIceCandidate.toWebRtc() = IceCandidate(sdpMid, sdpMLineIndex, candidate)

/** Keeps [RtcPeer] focused on the callbacks it owns. */
open class NoOpPeerObserver : PeerConnection.Observer {
    override fun onSignalingChange(newState: PeerConnection.SignalingState) = Unit
    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
    override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = Unit
    override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) = Unit
    override fun onIceCandidate(candidate: IceCandidate) = Unit
    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
    override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
    override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
    override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
    override fun onRenegotiationNeeded() = Unit
    override fun onAddTrack(receiver: org.webrtc.RtpReceiver, mediaStreams: Array<out org.webrtc.MediaStream>) = Unit
    override fun onTrack(transceiver: RtpTransceiver) = Unit
}
