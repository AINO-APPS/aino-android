package app.aino.mobile.feature.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Voice-note recording phases (Signal-style hold/lock, plus pause/resume and a
 * draft preview, which Signal and the web lack but mobile users asked for).
 *
 *  Idle ──press──▶ Holding ──release──▶ Idle (send, if ≥ [MIN_VOICE_NOTE_MS])
 *                    │  └──slide left──▶ Idle (discard)
 *                    └──slide up──▶ Locked ⇄ Paused ──stop──▶ Draft
 *  Locked/Paused/Draft ──send──▶ Idle (send)   ──delete──▶ Idle (discard)
 */
enum class VoicePhase { Idle, Holding, Locked, Paused, Draft }

enum class VoiceEvent { Press, Release, SlideCancel, Lock, Pause, Resume, Stop, Send, Delete }

enum class VoiceEffect { None, Start, Pause, Resume, StopToDraft, Send, Discard }

const val MIN_VOICE_NOTE_MS = 1_000L

/** Pure transition table; anything not listed is ignored (stays put, no effect). */
fun VoicePhase.reduce(event: VoiceEvent, elapsedMs: Long = 0): Pair<VoicePhase, VoiceEffect> = when (this) {
    VoicePhase.Idle -> if (event == VoiceEvent.Press) VoicePhase.Holding to VoiceEffect.Start else this to VoiceEffect.None
    VoicePhase.Holding -> when (event) {
        VoiceEvent.Release -> VoicePhase.Idle to if (elapsedMs >= MIN_VOICE_NOTE_MS) VoiceEffect.Send else VoiceEffect.Discard
        VoiceEvent.SlideCancel -> VoicePhase.Idle to VoiceEffect.Discard
        VoiceEvent.Lock -> VoicePhase.Locked to VoiceEffect.None
        else -> this to VoiceEffect.None
    }
    VoicePhase.Locked, VoicePhase.Paused -> when (event) {
        VoiceEvent.Pause -> if (this == VoicePhase.Locked) VoicePhase.Paused to VoiceEffect.Pause else this to VoiceEffect.None
        VoiceEvent.Resume -> if (this == VoicePhase.Paused) VoicePhase.Locked to VoiceEffect.Resume else this to VoiceEffect.None
        VoiceEvent.Stop -> VoicePhase.Draft to VoiceEffect.StopToDraft
        VoiceEvent.Send -> VoicePhase.Idle to VoiceEffect.Send
        VoiceEvent.Delete -> VoicePhase.Idle to VoiceEffect.Discard
        else -> this to VoiceEffect.None
    }
    VoicePhase.Draft -> when (event) {
        VoiceEvent.Send -> VoicePhase.Idle to VoiceEffect.Send
        VoiceEvent.Delete -> VoicePhase.Idle to VoiceEffect.Discard
        else -> this to VoiceEffect.None
    }
}

/** Normalises a MediaRecorder `maxAmplitude` (0..32767) to a 0..1 bar height (sqrt for perceptual loudness). */
fun amplitudeLevel(maxAmplitude: Int): Float = kotlin.math.sqrt(maxAmplitude.coerceIn(0, 32_767) / 32_767f)

/**
 * Thin MediaRecorder wrapper: AAC in MPEG-4 (.m4a, same container the web
 * already plays), with pause/resume (API 24+) and pause-aware elapsed time.
 */
class VoiceNoteRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set
    private var activeSinceMs = 0L
    private var accumulatedMs = 0L
    private var paused = false

    fun start() {
        discard()
        val directory = File(context.cacheDir, "chat-media").apply { mkdirs() }
        val output = File.createTempFile("voice-", ".m4a", directory)
        val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioEncodingBitRate(64_000)
            next.setAudioSamplingRate(44_100)
            next.setOutputFile(output.absolutePath)
            next.prepare()
            next.start()
        } catch (error: Exception) {
            next.release()
            output.delete()
            throw error
        }
        recorder = next
        file = output
        accumulatedMs = 0
        activeSinceMs = System.currentTimeMillis()
        paused = false
    }

    fun pause() {
        val current = recorder ?: return
        if (paused) return
        current.pause()
        accumulatedMs += System.currentTimeMillis() - activeSinceMs
        paused = true
    }

    fun resume() {
        val current = recorder ?: return
        if (!paused) return
        current.resume()
        activeSinceMs = System.currentTimeMillis()
        paused = false
    }

    fun elapsedMs(): Long = accumulatedMs + if (recorder != null && !paused) System.currentTimeMillis() - activeSinceMs else 0

    fun amplitude(): Float = if (paused) 0f else runCatching { amplitudeLevel(recorder?.maxAmplitude ?: 0) }.getOrDefault(0f)

    /** Finalises the file; returns it only if it is playable (non-empty). */
    fun stop(): File? {
        val current = recorder ?: return file?.takeIf { it.length() > 0 }
        if (!paused) accumulatedMs += System.currentTimeMillis() - activeSinceMs
        paused = true
        val ok = runCatching { current.stop() }.isSuccess
        current.release()
        recorder = null
        return file?.takeIf { ok && it.length() > 0 } ?: run { discard(); null }
    }

    fun discard() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        file?.delete()
        file = null
        accumulatedMs = 0
        paused = false
    }

    /** Hands the file to the caller (upload) without deleting it. */
    fun detach(): File? = file.also { file = null }
}
