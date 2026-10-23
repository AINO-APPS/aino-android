package app.aino.mobile.core.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call that ends while the activity is in picture-in-picture used to leave the
 * PiP window - and the whole app behind it - floating over the launcher, because
 * the only code that closes a call screen lives in the call surface, which is
 * already gone. These are the frames that must pull the activity back.
 */
class CallPipExitTest {
    @Test
    fun restoresWhenTheCallIsOverWhileTheActivityIsStillInPip() {
        assertTrue(CallPipExit.shouldRestore(active = true, surfaceCovering = false, inPip = true))
    }

    @Test
    fun leavesTheWindowAloneWhileACallSurfaceIsStillUp() {
        // An active call in PiP is a legitimate floating call: do not fight it.
        assertFalse(CallPipExit.shouldRestore(active = true, surfaceCovering = true, inPip = true))
    }

    @Test
    fun leavesTheWindowAloneWhenTheChatIsNotResumed() {
        // A backgrounded thread must not pull the task forward in someone's way.
        assertFalse(CallPipExit.shouldRestore(active = false, surfaceCovering = false, inPip = true))
    }

    @Test
    fun doesNothingWhenTheActivityIsNotInPip() {
        assertFalse(CallPipExit.shouldRestore(active = true, surfaceCovering = false, inPip = false))
    }
}