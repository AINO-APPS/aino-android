package app.aino.mobile.feature.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A call taking the screen must end text entry in the chat: the keyboard used to
 * stay over an incoming/active call, and a call left in picture-in-picture used
 * to give the keyboard back when the window returned and the call ended.
 */
class CallEntryPolicyTest {
    @Test fun `an incoming call over a half-typed message ends entry`() {
        val decision = CallEntryPolicy.decide(active = true, surfaceCovering = true, inPip = false)
        assertTrue("the call must take the keyboard away", decision.endEntry)
        assertTrue("the IME must be hidden", decision.hideIme)
    }

    @Test fun `a call in picture-in-picture keeps entry ended`() {
        val decision = CallEntryPolicy.decide(active = true, surfaceCovering = true, inPip = true)
        assertTrue("PiP is still a call", decision.endEntry)
        assertTrue("PiP must not keep the keyboard", decision.hideIme)
    }

    @Test fun `the release from PiP still ends entry`() {
        // The window is back and the call is over: without this the old bug
        // restored the keyboard over the chat.
        val decision = CallEntryPolicy.decide(active = true, surfaceCovering = false, inPip = false)
        assertTrue("leaving PiP must not restore the chat keyboard", decision.endEntry)
    }

    @Test fun `a resumed chat with no call ends entry so the editor is never left focused by a transition`() {
        val decision = CallEntryPolicy.decide(active = true, surfaceCovering = false, inPip = false)
        assertTrue(decision.endEntry)
        assertFalse("with no call up there is no IME to hide beyond the focus clear", decision.hideIme)
    }

    @Test fun `a stopped thread leaves entry alone`() {
        val decision = CallEntryPolicy.decide(active = false, surfaceCovering = true, inPip = true)
        assertFalse("a backgrounded chat must not fight the call's own window state", decision.endEntry)
        assertFalse(decision.hideIme)
    }
}