package app.aino.mobile.core.call

import app.aino.mobile.core.call.webrtc.CallSignal
import app.aino.mobile.core.call.webrtc.IceCandidateSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class CallSignalQueueTest {
    private fun candidate(n: Int) = CallSignal("ice-candidate", candidate = IceCandidateSignal("c$n", "0", 0))

    @Test
    fun keepsOrderAndOnlyTheNewestDescription() {
        val queue = CallSignalQueue()
        queue.add(CallSignal("offer", sdp = "old"))
        queue.add(candidate(1))
        queue.add(CallSignal("offer", sdp = "new"))
        queue.add(candidate(2))
        val sent = mutableListOf<String>()
        assertEquals(3, queue.flush { sent += it.sdp ?: it.candidate!!.candidate; true })
        assertEquals(listOf("c1", "new", "c2"), sent)
        assertEquals(0, queue.size())
    }

    @Test
    fun stopsAtTheFirstFailureAndKeepsTheRest() {
        val queue = CallSignalQueue()
        repeat(3) { queue.add(candidate(it)) }
        var budget = 1
        assertEquals(1, queue.flush { budget-- > 0 })
        assertEquals(2, queue.size())
        assertEquals(2, queue.flush { true })
    }

    @Test
    fun dropsOldestBeyondCapacity() {
        val queue = CallSignalQueue(capacity = 2)
        repeat(3) { queue.add(candidate(it)) }
        val sent = mutableListOf<String>()
        queue.flush { sent += it.candidate!!.candidate; true }
        assertEquals(listOf("c1", "c2"), sent)
    }

    @Test
    fun restartBackoffGrowsThenCaps() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 8_000L), (0..3).map(::iceRestartDelayMs))
    }
}
