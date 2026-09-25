package app.aino.mobile.core.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * Communication audio for calls and meetings: in-call mode, voice focus and
 * earpiece/speaker routing (Signal's `SignalAudioManager`, reduced to the
 * built-in devices). Video calls default to the speaker, voice to the earpiece.
 * ponytail: no Bluetooth/wired-headset device picker; add one when users ask to switch mid-call.
 */
class CallAudio(context: Context, private val owner: String) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    var speakerOn: Boolean = false
        private set

    fun start(speaker: Boolean) {
        manager ?: return
        synchronized(owners) { owners += owner }
        if (focus == null) {
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()
                .also(manager::requestAudioFocus)
        }
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeaker(speaker)
    }

    fun setSpeaker(on: Boolean) {
        val am = manager ?: return
        speakerOn = on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let(am::setCommunicationDevice)
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun stop() {
        val am = manager ?: return
        focus?.let(am::abandonAudioFocusRequest)
        focus = null
        speakerOn = false
        // A call and a meeting can overlap: only the last owner restores normal audio.
        val othersActive = synchronized(owners) {
            owners -= owner
            owners.isNotEmpty()
        }
        if (othersActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        val owners = mutableSetOf<String>()
    }
}
