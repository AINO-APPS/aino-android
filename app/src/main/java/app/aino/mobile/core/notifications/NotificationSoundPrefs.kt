package app.aino.mobile.core.notifications

import android.content.Context
import android.net.Uri

/**
 * Device-side mirror of the server's notification prefs that the push and
 * ring services need without a network round-trip (the web keeps the same
 * cache in localStorage). The incoming-call ringtone is a device choice made
 * through the system ringtone picker, so it lives only here.
 */
object NotificationSoundPrefs {
    private const val FILE = "aino_notification_sounds"
    private const val MUTE_ALL = "muteAll"
    private const val PLAY_WHEN_FOCUSED = "playWhenFocused"
    private const val PLAY_ON_SEND = "playOnSend"
    private const val RINGTONE_URI = "ringtoneUri"

    /** Set by MainActivity onStart/onStop: "the window is focused" for web parity. */
    @Volatile var appVisible: Boolean = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(context: Context, muteAll: Boolean, playWhenFocused: Boolean, playOnSend: Boolean) {
        prefs(context).edit()
            .putBoolean(MUTE_ALL, muteAll)
            .putBoolean(PLAY_WHEN_FOCUSED, playWhenFocused)
            .putBoolean(PLAY_ON_SEND, playOnSend)
            .apply()
    }

    fun muteAll(context: Context): Boolean = prefs(context).getBoolean(MUTE_ALL, false)
    fun playOnSend(context: Context): Boolean = prefs(context).getBoolean(PLAY_ON_SEND, false)

    /** Web `shouldPlay`: never when muted; while focused only if `playWhenFocused`. */
    fun notificationAudible(context: Context): Boolean {
        val p = prefs(context)
        if (p.getBoolean(MUTE_ALL, false)) return false
        return !appVisible || p.getBoolean(PLAY_WHEN_FOCUSED, false)
    }

    fun ringtoneUri(context: Context): Uri? = prefs(context).getString(RINGTONE_URI, null)?.let(Uri::parse)

    /** `null` restores the system default ringtone. */
    fun setRingtoneUri(context: Context, uri: Uri?) {
        prefs(context).edit().apply { if (uri == null) remove(RINGTONE_URI) else putString(RINGTONE_URI, uri.toString()) }.apply()
    }

    /** Signed-out devices must not keep the previous user's choices. */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** "Play a sound when I send a message": the platform's own short confirmation sound. */
    fun playSendConfirmation(context: Context) {
        if (muteAll(context) || !playOnSend(context)) return
        val audio = context.getSystemService(android.media.AudioManager::class.java) ?: return
        audio.playSoundEffect(android.media.AudioManager.FX_KEY_CLICK, 1f)
    }
}
