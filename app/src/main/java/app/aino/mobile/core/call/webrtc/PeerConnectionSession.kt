package app.aino.mobile.core.call.webrtc

import app.aino.mobile.core.realtime.RealtimeEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * One peer-to-peer connection and its perfect-negotiation state.
 * Media tracks are deliberately attached by the later A-082 controller.
 */
class PeerConnectionSession(
    private val runtime: WebRtcRuntime,
    config: IceConfigDto,
    private val callId: Long,
    private val conversationId: Long,
    private val remoteUserId: Long,
    private val polite: Boolean,
    private val send: (RealtimeEnvelope) -> Boolean,
    relayOnly: Boolean = false,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val candidates = IceCandidateBuffer()
    private var makingOffer = false
    private var remoteDescriptionSet = false

    val peer: PeerConnection = requireNotNull(
        runtime.factory.createPeerConnection(
            runtime.configuration(config, relayOnly),
            object : PeerConnection.Observer by NoOpPeerObserver() {
                override fun onIceCandidate(candidate: IceCandidate) {
                    sendSignal(
                        CallSignal(
                            "ice-candidate",
                            candidate = IceCandidateSignal(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex),
                        ),
                    )
                }
            },
        ),
    ) { "Could not create WebRTC peer connection" }

    fun subscribe() {
        send(callSubscribeEnvelope(callId, conversationId))
        send(callReadyEnvelope(callId, conversationId))
    }

    fun createOffer(onFailure: (String) -> Unit = {}) {
        createOffer(iceRestart = false, onFailure = onFailure)
    }

    fun restartIce(onFailure: (String) -> Unit = {}) {
        peer.restartIce()
        createOffer(iceRestart = true, onFailure = onFailure)
    }

    private fun createOffer(iceRestart: Boolean, onFailure: (String) -> Unit) {
        makingOffer = true
        val constraints = MediaConstraints().apply {
            if (iceRestart) mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        peer.createOffer(object : SimpleSdpObserver(onFailure) {
            override fun onCreateSuccess(description: SessionDescription) {
                peer.setLocalDescription(object : SimpleSdpObserver(onFailure) {
                    override fun onSetSuccess() {
                        makingOffer = false
                        sendSignal(CallSignal("offer", preferOpusFec(description.description)))
                    }
                }, description)
            }

            override fun onCreateFailure(error: String) {
                makingOffer = false
                super.onCreateFailure(error)
            }
        }, constraints)
    }

    fun handle(signal: CallSignal, onFailure: (String) -> Unit = {}) {
        when (signal.type) {
            "offer" -> handleOffer(signal.sdp.orEmpty(), onFailure)
            "answer" -> handleAnswer(signal.sdp.orEmpty(), onFailure)
            "ice-candidate" -> handleCandidate(signal.candidate)
        }
    }

    private fun handleOffer(sdp: String, onFailure: (String) -> Unit) {
        val stable = peer.signalingState() == PeerConnection.SignalingState.STABLE
        when (offerCollisionDecision(polite, makingOffer, stable)) {
            OfferCollisionDecision.Ignore -> return
            OfferCollisionDecision.RollbackThenAccept -> peer.setLocalDescription(
                object : SimpleSdpObserver(onFailure) {
                    override fun onSetSuccess() = setRemoteOfferAndAnswer(sdp, onFailure)
                },
                SessionDescription(SessionDescription.Type.ROLLBACK, ""),
            )
            OfferCollisionDecision.Accept -> setRemoteOfferAndAnswer(sdp, onFailure)
        }
    }

    private fun setRemoteOfferAndAnswer(sdp: String, onFailure: (String) -> Unit) {
        peer.setRemoteDescription(object : SimpleSdpObserver(onFailure) {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainCandidates()
                peer.createAnswer(object : SimpleSdpObserver(onFailure) {
                    override fun onCreateSuccess(description: SessionDescription) {
                        peer.setLocalDescription(object : SimpleSdpObserver(onFailure) {
                            override fun onSetSuccess() = sendSignal(CallSignal("answer", preferOpusFec(description.description)))
                        }, SessionDescription(description.type, preferOpusFec(description.description)))
                    }
                }, MediaConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun handleAnswer(sdp: String, onFailure: (String) -> Unit) {
        if (peer.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
        peer.setRemoteDescription(object : SimpleSdpObserver(onFailure) {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainCandidates()
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun handleCandidate(candidate: IceCandidateSignal?) {
        if (candidate == null || candidate.candidate.isBlank()) return
        val buffered = BufferedIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.candidate)
        if (!remoteDescriptionSet) candidates.add(buffered)
        else peer.addIceCandidate(buffered.toWebRtc())
    }

    private fun drainCandidates() {
        candidates.drain().forEach { peer.addIceCandidate(it.toWebRtc()) }
    }

    private fun sendSignal(signal: CallSignal) {
        send(callSignalEnvelope(CallSignalCommand(callId, conversationId, remoteUserId, signal)))
    }

    fun close() {
        candidates.clear()
        peer.close()
        peer.dispose()
        scope.cancel()
    }
}

private fun BufferedIceCandidate.toWebRtc() = IceCandidate(sdpMid, sdpMLineIndex, candidate)

open class SimpleSdpObserver(private val onFailureCallback: (String) -> Unit = {}) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = Unit
    override fun onSetSuccess() = Unit
    override fun onCreateFailure(error: String) = onFailureCallback(error)
    override fun onSetFailure(error: String) = onFailureCallback(error)
}

/** Keeps PeerConnectionSession focused on the callbacks it owns. */
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
    override fun onTrack(transceiver: org.webrtc.RtpTransceiver) = Unit
}