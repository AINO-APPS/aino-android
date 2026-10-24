package app.aino.mobile.core.call

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call that ends while the activity is in picture-in-picture used to leave the
 * PiP window - and the whole app behind it - floating over the launcher, because
 * the only code that closes a call screen lives in the call surface, which is
 * already gone.
 *
 * The gate is deliberately independent of the resume state: a PiP window is not
 * "resumed" the way a full-screen activity is, so requiring RESUMED is exactly
 * what stranded the window.
 */
class CallPipExitTest {
    @Test
    fun restoresWhenTheCallIsOverWhileTheActivityIsStillInPip() {
        assertTrue(CallPipExit.shouldRestore(surfaceCovering = false, inPip = true))
    }

    @Test
    fun leavesTheWindowAloneWhileACallSurfaceIsStillUp() {
        // An active call in PiP is a legitimate floating call: do not fight it.
        assertFalse(CallPipExit.shouldRestore(surfaceCovering = true, inPip = true))
    }

    @Test
    fun doesNothingWhenTheActivityIsNotInPip() {
        assertFalse(CallPipExit.shouldRestore(surfaceCovering = false, inPip = false))
        assertFalse(CallPipExit.shouldRestore(surfaceCovering = true, inPip = false))
    }
}
