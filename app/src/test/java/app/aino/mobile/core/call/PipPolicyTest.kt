package app.aino.mobile.core.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipPolicyTest {
    @Test
    fun safeRatioPreservesValidDimensions() {
        assertEquals(PipRatio(9, 16), PipPolicy.safeRatio(9, 16))
        assertEquals(PipRatio(1, 1), PipPolicy.safeRatio(1, 1))
        assertEquals(PipRatio(16, 9), PipPolicy.safeRatio(16, 9))
    }

    @Test
    fun safeRatioClampsWideAndTallInputsToAndroidBounds() {
        assertEquals(PipRatio(239, 100), PipPolicy.safeRatio(1000, 1))
        assertEquals(PipRatio(100, 239), PipPolicy.safeRatio(1, 1000))
    }

    @Test
    fun safeRatioFallsBackToSquareForInvalidDimensions() {
        assertEquals(PipRatio(1, 1), PipPolicy.safeRatio(0, 0))
        assertEquals(PipRatio(1, 1), PipPolicy.safeRatio(-4, -3))
    }

    @Test
    fun supportRequiresApi26AndAdvertisedFeature() {
        assertFalse(PipPolicy.isSupported(25, true))
        assertFalse(PipPolicy.isSupported(35, false))
        assertTrue(PipPolicy.isSupported(26, true))
    }

    @Test
    fun userLeaveEntryIsOnlyForActiveCallsBeforeSystemAutoEnter() {
        assertFalse(PipPolicy.shouldEnterOnUserLeave(30, true, false))
        assertFalse(PipPolicy.shouldEnterOnUserLeave(25, true, true))
        assertTrue(PipPolicy.shouldEnterOnUserLeave(30, true, true))
        assertFalse(PipPolicy.shouldEnterOnUserLeave(31, true, true))
    }
}