package app.aino.mobile.core.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Communication audio for calls and meetings: in-call mode, voice focus and
 * earpiece/speaker routing (Signal's `SignalAudioManager`, reduced to the
 * built-in devices). Video calls default to the speaker, voice to the earpiece.
 * ponytail: no Bluetooth/wired-headset device picker; add one when users ask to switch mid-call.
 */
class CallAudio(context: Context, private val owner: String) {
    private val manager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val appContext = context.applicationContext
    private val _speakerState = MutableStateFlow(false)
    val speakerState: StateFlow<Boolean> = _speakerState.asStateFlow()
    private var videoActive = false
    private var proximityEligible = false
    private var started = false
    private var route = CallAudioRoute.Unknown
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refreshRoute()
    }
    private var routeListener: AudioManager.OnCommunicationDeviceChangedListener? = null

    var speakerOn: Boolean = false
        private set

    fun start(speaker: Boolean) {
        manager ?: return
        synchronized(owners) {
            owners += this
            if (proximity == null) proximity = CallProximity(appContext)
        }
        if (!started) {
            started = true
            manager.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                routeListener = AudioManager.OnCommunicationDeviceChangedListener { refreshRoute() }.also {
                    manager.addOnCommunicationDeviceChangedListener(appContext.mainExecutor, it)
                }
            }
        }
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
        refreshRoute()
        if (route != CallAudioRoute.Headset) setSpeaker(speaker)
    }

    fun setSpeaker(on: Boolean) {
        val am = manager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { device ->
                        if (!am.setCommunicationDevice(device)) android.util.Log.w("AinoCall", "Speaker route was refused")
                    }
            } else {
                am.clearCommunicationDevice()
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
        refreshRoute()
        synchronized(owners) { owners.filter { it !== this } }.forEach { it.refreshRoute() }
    }

    fun setVideoActive(active: Boolean) {
        val becameVideo = active && !videoActive
        videoActive = active
        if (becameVideo && started && route == CallAudioRoute.Earpiece) setSpeaker(true)
        updateProximity()
    }

    fun setProximityEligible(eligible: Boolean) {
        proximityEligible = eligible
        updateProximity()
    }

    private fun refreshRoute() {
        val am = manager ?: return
        route = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = am.communicationDevice
            if (device == null) CallAudioRoute.Unknown else when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallAudioRoute.Earpiece
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallAudioRoute.Speaker
                else -> CallAudioRoute.Headset
            }
        } else {
            @Suppress("DEPRECATION")
            when {
                am.isSpeakerphoneOn -> CallAudioRoute.Speaker
                am.isBluetoothScoOn || am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type in setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
                } -> CallAudioRoute.Headset
                am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE } -> CallAudioRoute.Earpiece
                else -> CallAudioRoute.Unknown
            }
        }
        speakerOn = route == CallAudioRoute.Speaker
        _speakerState.value = speakerOn
        updateProximity()
    }

    private fun updateProximity() {
        synchronized(owners) {
            updateSharedProximity()
        }
    }

    fun stop() {
        val am = manager ?: return
        focus?.let(am::abandonAudioFocusRequest)
        focus = null
        started = false
        proximityEligible = false
        videoActive = false
        am.unregisterAudioDeviceCallback(devices)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            routeListener?.let(am::removeOnCommunicationDeviceChangedListener)
        }
        routeListener = null
        speakerOn = false
        _speakerState.value = false
        // A call and a meeting can overlap: only the last owner restores normal audio.
        val othersActive = synchronized(owners) {
            owners -= this
            owners.isNotEmpty()
        }
        updateProximity()
        if (othersActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
        else @Suppress("DEPRECATION") run { am.isSpeakerphoneOn = false }
        am.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        private val owners = mutableSetOf<CallAudio>()
        private var proximity: CallProximity? = null
        private var incomingRinging = false
        private val _proximityActive = MutableStateFlow(false)
        val proximityActive: StateFlow<Boolean> = _proximityActive.asStateFlow()

        fun setIncomingRinging(ringing: Boolean) {
            synchronized(owners) {
                incomingRinging = ringing
                updateSharedProximity()
            }
        }

        private fun updateSharedProximity() {
            val eligible = !incomingRinging && owners.isNotEmpty() && owners.none { it.videoActive } &&
                owners.any { shouldUseCallProximity(it.started && it.proximityEligible, it.videoActive, it.route) }
            _proximityActive.value = proximity?.update(eligible) == true
        }
    }
}
