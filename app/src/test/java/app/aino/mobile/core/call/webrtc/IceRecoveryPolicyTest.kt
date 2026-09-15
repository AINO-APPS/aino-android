package app.aino.mobile.core.call.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IceRecoveryPolicyTest {
    @Test
    fun escalatesFromTwoRestartsToRelayOnlyThenGiveUp() {
        assertEquals(IceRecoveryAction.RestartIce, iceRecoveryAction(0, false, true))
        assertEquals(IceRecoveryAction.RestartIce, iceRecoveryAction(1, false, true))
        assertEquals(IceRecoveryAction.RebuildRelayOnly, iceRecoveryAction(2, false, true))
        assertEquals(IceRecoveryAction.GiveUp, iceRecoveryAction(3, true, true))
        assertEquals(IceRecoveryAction.GiveUp, iceRecoveryAction(2, false, false))
    }

    @Test
    fun fastRelayFallbackRequiresFiveSecondsAndProvisionedRelay() {
        assertFalse(shouldFastRelayFallback(4_999, false, false, true))
        assertFalse(shouldFastRelayFallback(5_000, true, false, true))
        assertFalse(shouldFastRelayFallback(5_000, false, true, true))
        assertFalse(shouldFastRelayFallback(5_000, false, false, false))
        assertTrue(shouldFastRelayFallback(5_000, false, false, true))
    }
}