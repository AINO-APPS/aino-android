package app.aino.mobile.core.call.webrtc

import android.content.Context
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

class WebRtcRuntime private constructor(
    val factory: PeerConnectionFactory,
    val eglBase: EglBase,
) {
    fun createAudioTrack(trackId: String = "aino-audio"): Pair<AudioSource, AudioTrack> {
        val source = factory.createAudioSource(org.webrtc.MediaConstraints())
        return source to factory.createAudioTrack(trackId, source)
    }

    fun configuration(config: IceConfigDto, relayOnly: Boolean = false): PeerConnection.RTCConfiguration {
        val source = if (relayOnly) relayOnlyServers(config.iceServers) else config.iceServers
        val servers = source.map { dto ->
            PeerConnection.IceServer.builder(dto.urls.values)
                .setUsername(dto.username.orEmpty())
                .setPassword(dto.credential.orEmpty())
                .createIceServer()
        }
        return PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }
    }

    fun dispose() {
        factory.dispose()
        eglBase.release()
    }

    companion object {
        @Volatile private var initialized = false

        fun create(context: Context): WebRtcRuntime {
            synchronized(this) {
                if (!initialized) {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions(),
                    )
                    initialized = true
                }
            }
            val egl = EglBase.create()
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            return WebRtcRuntime(factory, egl)
        }
    }
}