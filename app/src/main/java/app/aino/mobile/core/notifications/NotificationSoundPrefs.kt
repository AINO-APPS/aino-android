package app.aino.mobile.core.notifications

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.media.SoundPool
import android.net.Uri
import app.aino.mobile.R
import app.aino.mobile.core.settings.ChatPreferences

/**
 * When chat alerts make a sound (Signal `DefaultMessageNotifier` /
 * `NotificationFactory.notifyInThread`):
 * - the thread on screen never posts; it plays the message sound only with "In-chat sounds";
 * - everything else (chat list, other screens, background) alerts with its channel sound;
 * - "Mute all" silences both. Calls ring through [CallRingService] and only honour mute.
 *
 * Which sound each slot plays is resolved by [NotificationSounds].
 */
object NotificationSoundPrefs {
    /** Signal `DefaultMessageNotifier.MIN_AUDIBLE_PERIOD_MILLIS`. */
    const val MIN_AUDIBLE_PERIOD_MS = 2_000L

    /** Set by MainActivity onStart/onStop. */
    @Volatile var appVisible: Boolean = false

    @Volatile private var lastInThreadToneAt = 0L

    fun muteAll(context: Context): Boolean = ChatPreferences.get(context).muteAll
    fun playOnSend(context: Context): Boolean = ChatPreferences.get(context).playOnSend

    /** A posted (non-visible-thread) alert is audible unless everything is muted. */
    fun notificationAudible(context: Context): Boolean = !muteAll(context)

    /** Signed-out devices must not keep the previous user's choices. */
    fun clear(context: Context) {
        NotificationSounds.clear(context)
        SoundChannels.clear(context)
        ChatPreferences.clear(context)
    }

    /** Pure decision for the open-thread tone (Signal `notifyInThread` guards). */
    internal fun inThreadToneAllowed(
        inChatSounds: Boolean,
        muteAll: Boolean,
        ringerNormal: Boolean,
        nowMs: Long,
        lastToneMs: Long,
    ): Boolean = inChatSounds && !muteAll && ringerNormal && nowMs - lastToneMs >= MIN_AUDIBLE_PERIOD_MS

    /** Pure decision for the send confirmation tone. */
    internal fun sendToneAllowed(playOnSend: Boolean, muteAll: Boolean, ringerNormal: Boolean): Boolean =
        playOnSend && !muteAll && ringerNormal

    private fun ringerNormal(context: Context): Boolean =
        context.getSystemService(AudioManager::class.java)?.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /** A message landed in the thread on screen: short notification tone only when the user asked for it. */
    fun playInThreadTone(context: Context): Boolean {
        val prefs = ChatPreferences.get(context)
        val now = System.currentTimeMillis()
        if (!inThreadToneAllowed(prefs.inChatSounds, prefs.muteAll, ringerNormal(context), now, lastInThreadToneAt)) return false
        lastInThreadToneAt = now
        return inThreadToneOutput(context)
    }

    internal fun resetToneThrottleForTest() { lastInThreadToneAt = 0L }

    /** Plays the chosen message sound; replaceable in tests. */
    internal var inThreadToneOutput: (Context) -> Boolean = { playOnce(it, NotificationSounds.uri(it, SoundCategory.Message)) }

    /** One-shot playback on the notification stream (in-chat tone, picker previews). */
    fun playOnce(context: Context, uri: Uri?): Boolean = runCatching {
        if (uri == null) return false
        val ringtone = RingtoneManager.getRingtone(context, uri) ?: return false
        ringtone.audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        ringtone.play()
        true
    }.getOrDefault(false)

    @Volatile private var pool: SoundPool? = null
    @Volatile private var sentSound = 0
    @Volatile private var sentReady = false

    /** Loads the send tone ahead of the first send so it plays without delay. */
    fun preload(context: Context) {
        if (pool != null) return
        synchronized(this) {
            if (pool != null) return
            val created = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .build()
            created.setOnLoadCompleteListener { _, id, status -> if (id == sentSound && status == 0) sentReady = true }
            sentSound = runCatching { created.load(context.applicationContext, R.raw.message_sent, 1) }.getOrDefault(0)
            pool = created
        }
    }

    /** "Play a sound when I send a message": the bundled confirmation tone (not the touch-click effect, which "Touch sounds" mutes). */
    fun playSendConfirmation(context: Context) {
        val prefs = ChatPreferences.get(context)
        if (!sendToneAllowed(prefs.playOnSend, prefs.muteAll, ringerNormal(context))) return
        preload(context)
        val current = pool ?: return
        if (sentReady) current.play(sentSound, .6f, .6f, 1, 0, 1f)
        else current.setOnLoadCompleteListener { p, id, status ->
            if (id == sentSound && status == 0) { sentReady = true; p.play(id, .6f, .6f, 1, 0, 1f) }
        }
    }
}
