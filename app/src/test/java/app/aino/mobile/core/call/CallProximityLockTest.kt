package app.aino.mobile.core.call

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CallProximityLockTest {
    private lateinit var app: Application
    private lateinit var power: PowerManager

    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        power = app.getSystemService(PowerManager::class.java)
        ShadowPowerManager.clearWakeLocks()
    }

    @Test fun unsupportedDevicesDoNotAcquireALock() {
        shadowOf(power).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, false)
        assertFalse(CallProximity(app).update(true))
    }

    @Test fun acquireAndReleaseAreIdempotentAndLeaveNoHeldLock() {
        shadowOf(power).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        val owner = CallProximity(app)
        assertTrue(owner.update(true))
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue(owner.update(true))
        assertSame(lock, ShadowPowerManager.getLatestWakeLock())
        assertFalse(owner.update(false))
        assertFalse(lock.isHeld)
        assertFalse(owner.update(false))
        assertTrue(owner.update(true))
        assertFalse(owner.update(false))
    }

    @Test fun audioOwnersPreserveProximityAndExcludeRingingAndVideo() {
        shadowOf(power).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        val audioManager = app.getSystemService(AudioManager::class.java)
        val earpiece = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        shadowOf(audioManager).setOutputDevices(listOf(earpiece))
        val call = CallAudio(app, "test-call")
        val meeting = CallAudio(app, "test-meeting")
        try {
            CallAudio.setIncomingRinging(false)
            call.start(speaker = false)
            call.setProximityEligible(true)
            assertTrue(CallAudio.proximityActive.value)
            meeting.start(speaker = false)
            meeting.setProximityEligible(true)
            CallAudio.setIncomingRinging(true)
            assertFalse(CallAudio.proximityActive.value)
            CallAudio.setIncomingRinging(false)
            assertTrue(CallAudio.proximityActive.value)
            meeting.setVideoActive(true)
            assertFalse(CallAudio.proximityActive.value)
            meeting.setVideoActive(false)
            assertTrue(meeting.speakerOn)
            assertFalse(CallAudio.proximityActive.value)
            call.setSpeaker(false)
            call.stop()
            assertTrue(CallAudio.proximityActive.value)
            meeting.stop()
            assertFalse(CallAudio.proximityActive.value)
            assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        } finally {
            call.stop()
            meeting.stop()
            CallAudio.setIncomingRinging(false)
        }
    }
}
