package app.aino.mobile.core.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallScreenAwakeTest {
    private val idle = ActiveCallUi()

    @Test
    fun idleAppLetsTheScreenSleep() {
        assertFalse(CallScreenAwake.shouldKeepScreenOn(idle, meetingActive = false, incomingRinging = false))
    }

    @Test
    fun visibleOneToOneCallKeepsScreenOnForAudioAndVideo() {
        val dialing = idle.copy(visible = true, callType = "voice")
        val connectedVideo = idle.copy(visible = true, callType = "video", connectedAt = 1L)
        assertTrue(CallScreenAwake.shouldKeepScreenOn(dialing, meetingActive = false, incomingRinging = false))
        assertTrue(CallScreenAwake.shouldKeepScreenOn(connectedVideo, meetingActive = false, incomingRinging = false))
    }

    @Test
    fun endedCallReleasesTheScreen() {
        val ending = idle.copy(visible = true, connectedAt = 1L, endMessage = "No answer")
        assertFalse(CallScreenAwake.shouldKeepScreenOn(ending, meetingActive = false, incomingRinging = false))
    }

    @Test
    fun meetingOrGroupCallKeepsScreenOn() {
        assertTrue(CallScreenAwake.shouldKeepScreenOn(idle, meetingActive = true, incomingRinging = false))
    }

    @Test
    fun ringingIncomingCallKeepsScreenOn() {
        assertTrue(CallScreenAwake.shouldKeepScreenOn(idle, meetingActive = false, incomingRinging = true))
    }
}
