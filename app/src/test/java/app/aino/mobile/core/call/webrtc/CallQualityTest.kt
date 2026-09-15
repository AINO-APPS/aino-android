package app.aino.mobile.core.call.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallQualityTest {
    private fun sample(
        time: Long,
        rtt: Double? = .1,
        lost: Long = 0,
        received: Long = 0,
        freezes: Long = 0,
        jitterDelay: Double = 0.0,
        jitterCount: Long = 0,
        limitation: String? = null,
    ) = RawStatsSample(time, rtt, lost, received, freezes, jitterDelay, jitterCount, limitation)

    @Test
    fun ladderMatchesLegacyAndMobileCap() {
        VIDEO_TIERS.zipWithNext().forEach { (better, worse) ->
            assertTrue(worse.maxBitrate < better.maxBitrate)
            assertTrue(worse.scaleResolutionDownBy >= better.scaleResolutionDownBy)
            assertTrue(worse.maxFramerate <= better.maxFramerate)
        }
        assertEquals(MOBILE_TOP_TIER_BITRATE, buildTiers()[0].maxBitrate)
        assertEquals(1_500_000, buildTiers(false)[0].maxBitrate)
    }

    @Test
    fun intervalLossRecoversInsteadOfUsingLifetimeAverage() {
        val controller = CallQualityController()
        controller.setTierIndex(0)
        controller.observe(sample(0, lost = 0, received = 0))
        controller.observe(sample(2_000, lost = 200, received = 200))
        var received = 200L
        var decision: QualityDecision? = null
        repeat(6) { index ->
            received += 300
            decision = controller.observe(sample(4_000L + index * 2_000, lost = 200, received = received))
        }
        assertTrue(200.0 / (200 + received) > .05)
        assertTrue(requireNotNull(decision).smoothedLossRate < .02)
        assertEquals(ConnectionQuality.Good, decision!!.quality)
    }

    @Test
    fun badSamplesDownshiftOneRungAndStopAtBottom() {
        val controller = CallQualityController()
        controller.setTierIndex(0)
        controller.observe(sample(0))
        val path = mutableListOf<Int>()
        repeat(5) { index ->
            path += controller.observe(sample((index + 1) * 2_000L, freezes = (index + 1).toLong())).tierIndex
        }
        assertEquals(listOf(1, 2, 3, 4, 4), path)
    }

    @Test
    fun resolutionUpshiftNeedsSixCleanSamples() {
        val controller = CallQualityController()
        controller.observe(sample(0))
        val dropped = controller.observe(sample(2_000, freezes = 1))
        assertEquals(BOTTOM_TIER_INDEX, dropped.tierIndex)
        repeat(5) { index ->
            assertFalse(controller.observe(sample(4_000L + index * 2_000, freezes = 1)).changed)
        }
        val recovered = controller.observe(sample(14_000, freezes = 1))
        assertTrue(recovered.changed)
        assertEquals(BOTTOM_TIER_INDEX - 1, recovered.tierIndex)
    }

    @Test
    fun warningBandHoldsTierWithoutFlapping() {
        val controller = CallQualityController()
        repeat(4) { controller.observe(sample(it * 2_000L)) }
        var decision = controller.observe(sample(8_000, rtt = .22))
        repeat(3) { index -> decision = controller.observe(sample(10_000L + index * 2_000, rtt = .22)) }
        assertEquals(ConnectionQuality.Fair, decision.quality)
        val tier = controller.getTierIndex()
        repeat(4) { index ->
            decision = controller.observe(sample(16_000L + index * 2_000, rtt = .22))
            assertEquals(tier, decision.tierIndex)
            assertFalse(decision.changed)
        }
    }

    @Test
    fun bandwidthAndJitterTriggerLegacyGrades() {
        val controller = CallQualityController()
        controller.observe(sample(0))
        assertEquals(ConnectionQuality.Poor, controller.observe(sample(2_000, limitation = "bandwidth")).quality)
        controller.reset()
        controller.observe(sample(0, jitterDelay = 0.0, jitterCount = 0))
        assertEquals(ConnectionQuality.Poor, controller.observe(sample(2_000, jitterDelay = 6.0, jitterCount = 10)).quality)
    }

    @Test
    fun resetRestoresConnectRampTier() {
        val controller = CallQualityController()
        controller.observe(sample(0)); controller.observe(sample(2_000, freezes = 5))
        assertEquals(BOTTOM_TIER_INDEX, controller.getTierIndex())
        controller.reset()
        assertEquals(RAMP_START_TIER_INDEX, controller.getTierIndex())
    }

    @Test
    fun opusFecAddsAndOverridesParameters() {
        val input = "v=0\r\na=rtpmap:111 opus/48000/2\r\na=fmtp:111 minptime=10;usedtx=1\r\n"
        val output = preferOpusFec(input)
        assertTrue(output.contains("useinbandfec=1"))
        assertTrue(output.contains("usedtx=0"))
        assertTrue(output.contains("maxaveragebitrate=32000"))
        assertFalse(output.contains("usedtx=1"))
    }
}