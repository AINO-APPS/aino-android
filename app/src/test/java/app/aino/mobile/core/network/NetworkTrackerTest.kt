package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTrackerTest {
    @Test fun `starts fail-open and the first network is not a reset`() {
        val tracker = NetworkTracker()
        assertTrue(tracker.status.usable)
        assertFalse(tracker.onAvailable("100"))
        assertEquals(NetworkStatus.Available(validated = false), tracker.status)
    }

    @Test fun `switching the default network resets connections`() {
        val tracker = NetworkTracker()
        tracker.onAvailable("wifi")
        tracker.onCapabilities("wifi", internet = true, isValidated = true)
        assertTrue(tracker.onAvailable("cell"))
        assertEquals(NetworkStatus.Available(validated = false), tracker.status)
    }

    @Test fun `losing the network makes it unusable and its return resets`() {
        val tracker = NetworkTracker()
        tracker.onAvailable("wifi")
        tracker.onLost("wifi")
        assertEquals(NetworkStatus.Unavailable, tracker.status)
        assertFalse(tracker.status.usable)
        assertTrue(tracker.onAvailable("wifi"))
        assertTrue(tracker.status.usable)
    }

    @Test fun `losing a network that is no longer the default is ignored`() {
        val tracker = NetworkTracker()
        tracker.onAvailable("wifi")
        tracker.onAvailable("cell")
        tracker.onLost("wifi")
        assertTrue(tracker.status.usable)
    }

    @Test fun `first validation of a network is not a reset, regaining it after a stall is`() {
        val tracker = NetworkTracker()
        tracker.onAvailable("wifi")
        assertFalse(tracker.onCapabilities("wifi", internet = true, isValidated = true))
        assertFalse(tracker.onCapabilities("wifi", internet = true, isValidated = true))
        assertFalse(tracker.onCapabilities("wifi", internet = true, isValidated = false))
        assertEquals(NetworkStatus.Available(validated = false), tracker.status)
        assertTrue(tracker.status.usable)
        assertTrue(tracker.onCapabilities("wifi", internet = true, isValidated = true))
        assertFalse(tracker.onCapabilities("wifi", internet = true, isValidated = true))
    }
}
