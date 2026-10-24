package app.aino.mobile.core.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the app during a connected call must enter system PiP on every
 * supported API. Auto-enter (API 31+) only fires on the transition to
 * background, so a device that already sat in the background when the call
 * started got no PiP window at all: `onUserLeaveHint` was gated off on API 31+
 * and nothing else asked for PiP. Reproduced on Android 15 (API 35) by pressing
 * Home during a live call: `mLastReportedPictureInPictureMode=false`.
 */
class PipEnterOnLeaveTest {
    @Test
    fun `leaving the app during a call enters PiP on API 31 and newer`() {
        assertTrue(
            "auto-enter alone leaves a backgrounded caller with no PiP window",
            PipPolicy.shouldEnterOnUserLeave(
                apiLevel = 35,
                deviceAdvertisesPip = true,
                callActive = true,
            ),
        )
    }

    @Test
    fun `leaving the app during a call enters PiP on the legacy range too`() {
        assertTrue(
            PipPolicy.shouldEnterOnUserLeave(
                apiLevel = 26,
                deviceAdvertisesPip = true,
                callActive = true,
            ),
        )
    }

    @Test
    fun `no call keeps the app out of PiP`() {
        assertFalse(PipPolicy.shouldEnterOnUserLeave(35, deviceAdvertisesPip = true, callActive = false))
        assertFalse(PipPolicy.shouldEnterOnUserLeave(26, deviceAdvertisesPip = true, callActive = false))
    }

    @Test
    fun `a device without PiP support never enters`() {
        assertFalse(PipPolicy.shouldEnterOnUserLeave(35, deviceAdvertisesPip = false, callActive = true))
        assertFalse(PipPolicy.shouldEnterOnUserLeave(26, deviceAdvertisesPip = false, callActive = true))
    }

    @Test
    fun `PiP is never attempted below the minimum API`() {
        assertFalse(PipPolicy.shouldEnterOnUserLeave(25, deviceAdvertisesPip = true, callActive = true))
    }
}
