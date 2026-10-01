package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressThrottleTest {
    @Test fun `reports each whole percent once`() {
        val throttle = ProgressThrottle(total = 1_000)
        val reported = (0..1_000 step 2).mapNotNull { throttle.advance(it.toLong()) }
        assertEquals((0..100).toList(), reported)
    }

    @Test fun `unknown length reports nothing`() {
        assertNull(ProgressThrottle(total = -1).advance(10))
    }
}
