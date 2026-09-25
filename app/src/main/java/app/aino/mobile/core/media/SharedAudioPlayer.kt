package app.aino.mobile.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Web `FilePreview.tsx` `SPEEDS`. */
val PLAYBACK_SPEEDS = listOf(1f, 1.5f, 2f)

fun nextPlaybackSpeed(current: Float): Float =
    PLAYBACK_SPEEDS[(PLAYBACK_SPEEDS.indexOf(current).coerceAtLeast(0) + 1) % PLAYBACK_SPEEDS.size]

data class AudioPlaybackState(
    val url: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

/**
 * One app-wide player for voice notes, audio files and voice drafts, so starting
 * one note always stops the previous (Signal's `VoiceNoteMediaController`
 * pattern). Remote URLs go through the authenticated media OkHttp client;
 * content:// and file:// (drafts) through the default data source.
 * Must be created on the main thread (ExoPlayer is looper-bound).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SharedAudioPlayer(context: Context, http: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val durations = mutableMapOf<String, Long>()
    private val _state = MutableStateFlow(AudioPlaybackState())
    val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()
    private var ticker: Job? = null

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish()
                if (isPlaying) startTicker() else ticker?.cancel()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                }
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                _state.value = _state.value.copy(playing = false, buffering = false, error = "Unable to play")
            }
        })
    }

    fun durationOf(url: String): Long = durations[url] ?: 0

    fun toggle(url: String) {
        if (_state.value.url == url && _state.value.error == null) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        _state.value = AudioPlaybackState(url = url, buffering = true, speed = _state.value.speed, durationMs = durationOf(url))
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.setPlaybackSpeed(_state.value.speed)
        player.prepare()
        player.play()
    }

    fun seekTo(url: String, fraction: Float) {
        if (_state.value.url != url) return
        val duration = player.duration.takeIf { it > 0 } ?: return
        player.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        publish()
    }

    fun cycleSpeed() {
        val next = nextPlaybackSpeed(_state.value.speed)
        player.setPlaybackSpeed(next)
        _state.value = _state.value.copy(speed = next)
    }

    /** Stops [url] if it is the current item (e.g. a deleted draft); `null` stops anything. */
    fun stop(url: String? = null) {
        if (url != null && _state.value.url != url) return
        player.stop()
        player.clearMediaItems()
        _state.value = AudioPlaybackState(speed = _state.value.speed)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) { publish(); delay(100) }
        }
    }

    private fun publish() {
        val url = _state.value.url ?: return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: durationOf(url)
        if (duration > 0) durations[url] = duration
        _state.value = _state.value.copy(
            playing = player.isPlaying,
            buffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            error = player.playerError?.let { "Unable to play" },
        )
    }
}
