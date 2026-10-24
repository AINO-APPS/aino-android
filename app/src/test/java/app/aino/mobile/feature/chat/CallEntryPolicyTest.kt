package app.aino.mobile.feature.chat

import app.aino.mobile.core.call.CallPipExit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call surface must take the whole app's text entry away, and the app must
 * leave system PiP when the call it was showing ends.
 *
 * Two hard-won constraints are pinned here:
 *
 * 1. The chat has TWO keyboards - the system IME and the emoji/attachment drawer,
 *    which is inline Compose content. Clearing focus and hiding the IME alone
 *    leaves the drawer painted over the incoming call.
 * 2. A PiP window is PAUSED, so anything watched with a lifecycle-aware collector
 *    stops updating in PiP. Watching the call state that way is exactly why the
 *    app stayed in PiP after the call ended: the shell never learned the call was
 *    over. [CallPipExit.shouldRestore] is therefore fed by plain collectors.
 */
class CallEntryPolicyTest {
    @Test fun `a call surface ends entry and closes the drawer`() {
        assertTrue(CallInputDismissalPlan.decide(callOnScreen = true).closePanel)
        assertTrue(CallInputDismissalPlan.decide(callOnScreen = true).endEntry)
    }

    @Test fun `with no call the composer is left completely alone`() {
        // If this were true, opening the drawer would be undone by the very
        // boundary that is supposed to be idle - which broke the emoji keyboard.
        val decision = CallInputDismissalPlan.decide(callOnScreen = false)
        assertFalse("no call must never close the drawer", decision.closePanel)
        assertFalse("no call must never steal the editor's focus", decision.endEntry)
    }

    @Test fun `the app leaves PiP as soon as the call ends`() {
        // The end state of the reported bug: call over, activity still pinned.
        assertTrue(CallPipExit.shouldRestore(surfaceCovering = false, inPip = true))
    }

    @Test fun `a running call in PiP is left floating`() {
        assertFalse(CallPipExit.shouldRestore(surfaceCovering = true, inPip = true))
    }

    @Test fun `nothing happens outside PiP`() {
        assertFalse(CallPipExit.shouldRestore(surfaceCovering = false, inPip = false))
        assertFalse(CallPipExit.shouldRestore(surfaceCovering = true, inPip = false))
    }
}

/** The decision the editor-side boundary applies when a call surface is up. */
data class CallInputDismissalDecision(val endEntry: Boolean, val closePanel: Boolean)

object CallInputDismissalPlan {
    fun decide(callOnScreen: Boolean): CallInputDismissalDecision =
        CallInputDismissalDecision(endEntry = callOnScreen, closePanel = callOnScreen)
}
