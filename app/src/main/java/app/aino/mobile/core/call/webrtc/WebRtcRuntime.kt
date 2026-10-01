package app.aino.mobile.core.call.webrtc

import android.content.Context
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

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
            // Host TCP only; TURN over TCP/TLS relays are unaffected.
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            audioJitterBufferMaxPackets = AUDIO_JITTER_BUFFER_MAX_PACKETS
            audioJitterBufferFastAccelerate = true
        }
    }

    fun dispose() {
        factory.dispose()
        eglBase.release()
    }

    companion object {
        @Volatile private var initialized = false
        @Volatile private var shared: WebRtcRuntime? = null

        /** Latest local microphone level (0..1), fed by the audio device module. */
        @Volatile var micLevel: Float = 0f
            private set

        /** One factory + EGL context for the process; calls and meetings share it. */
        fun shared(context: Context): WebRtcRuntime = shared ?: synchronized(this) {
            shared ?: create(context).also { shared = it }
        }

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
            val processing = selectAudioProcessing(
                sdkInt = Build.VERSION.SDK_INT,
                hardwareAecAvailable = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
                hardwareNsAvailable = runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
            )
            Log.i(TAG, "Audio processing: $processing")
            val admLogger = AudioDeviceModuleLogger()
            val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
                .setUseHardwareAcousticEchoCanceler(processing.hardwareAec)
                .setUseHardwareNoiseSuppressor(processing.hardwareNs)
                .setAudioRecordErrorCallback(admLogger)
                .setAudioRecordStateCallback(admLogger)
                .setAudioTrackErrorCallback(admLogger)
                .setAudioTrackStateCallback(admLogger)
                .setSamplesReadyCallback { samples -> micLevel = pcm16Level(samples.data) }
                .createAudioDeviceModule()
            // DefaultVideo*Factory here already wraps hardware codecs with software fallback.
            val factory = PeerConnectionFactory.builder()
                .setAudioDeviceModule(audioModule)
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            audioModule.release()
            return WebRtcRuntime(factory, egl)
        }

        private const val TAG = "WebRtcRuntime"
    }
}

const val AUDIO_JITTER_BUFFER_MAX_PACKETS = 50

data class AudioProcessing(val hardwareAec: Boolean, val hardwareNs: Boolean)

/**
 * Signal `AudioDeviceConfig.applyOverrides`: platform AEC/NS only where the
 * effect exists and on API 29+; otherwise WebRTC's software AEC/NS.
 */
fun selectAudioProcessing(sdkInt: Int, hardwareAecAvailable: Boolean, hardwareNsAvailable: Boolean): AudioProcessing {
    val platformOk = sdkInt >= Build.VERSION_CODES.Q
    return AudioProcessing(
        hardwareAec = platformOk && hardwareAecAvailable,
        hardwareNs = platformOk && hardwareNsAvailable,
    )
}

/** Surfaces audio device module failures and state changes in logcat. */
private class AudioDeviceModuleLogger :
    JavaAudioDeviceModule.AudioRecordErrorCallback,
    JavaAudioDeviceModule.AudioRecordStateCallback,
    JavaAudioDeviceModule.AudioTrackErrorCallback,
    JavaAudioDeviceModule.AudioTrackStateCallback {
    override fun onWebRtcAudioRecordInitError(errorMessage: String) { Log.e(TAG, "AudioRecord init error: $errorMessage") }
    override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) {
        Log.e(TAG, "AudioRecord start error $errorCode: $errorMessage")
    }
    override fun onWebRtcAudioRecordError(errorMessage: String) { Log.e(TAG, "AudioRecord error: $errorMessage") }
    override fun onWebRtcAudioRecordStart() { Log.i(TAG, "AudioRecord started") }
    override fun onWebRtcAudioRecordStop() { Log.i(TAG, "AudioRecord stopped") }
    override fun onWebRtcAudioTrackInitError(errorMessage: String) { Log.e(TAG, "AudioTrack init error: $errorMessage") }
    override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String) {
        Log.e(TAG, "AudioTrack start error $errorCode: $errorMessage")
    }
    override fun onWebRtcAudioTrackError(errorMessage: String) { Log.e(TAG, "AudioTrack error: $errorMessage") }
    override fun onWebRtcAudioTrackStart() { Log.i(TAG, "AudioTrack started") }
    override fun onWebRtcAudioTrackStop() { Log.i(TAG, "AudioTrack stopped") }

    private companion object {
        const val TAG = "AinoAudioDevice"
    }
}

/**
 * RMS of little-endian PCM16 scaled to roughly match the web's analyser level
 * (speech ≈ 0.1–0.5), clamped to 0..1.
 */
fun pcm16Level(data: ByteArray): Float {
    val count = data.size / 2
    if (count == 0) return 0f
    var sum = 0.0
    for (i in 0 until count) {
        val sample = ((data[2 * i + 1].toInt() shl 8) or (data[2 * i].toInt() and 0xFF)).toShort().toDouble()
        sum += sample * sample
    }
    return (kotlin.math.sqrt(sum / count) / 32768.0 * 5).toFloat().coerceIn(0f, 1f)
}