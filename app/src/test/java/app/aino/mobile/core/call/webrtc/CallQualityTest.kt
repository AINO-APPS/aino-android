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
        outLoss: Double? = null,
    ) = RawStatsSample(time, rtt, lost, received, freezes, jitterDelay, jitterCount, limitation, outLoss)

    @Test
    fun ladderMatchesLegacyAndMobileCap() {
        VIDEO_TIERS.zipWithNext().forEach { (better, worse) ->
            assertTrue(worse.maxBitrate < better.maxBitrate)
            assertTrue(worse.scaleResolutionDownBy >= better.scaleResolutionDownBy)
            assertTrue(worse.maxFramerate <= better.maxFramerate)
        }
        assertEquals(MOBILE_TOP_TIER_BITRATE, buildTiers()[0].maxBitrate)
        assertEquals(2_000_000, buildTiers(false)[0].maxBitrate)
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
    fun sustainedUplinkLossDownshiftsOneRungPerTwoSamplesAndStopsAtBottom() {
        val controller = CallQualityController()
        controller.observe(sample(0))
        val path = mutableListOf<Int>()
        repeat(10) { index -> path += controller.observe(sample((index + 1) * 2_000L, outLoss = .3)).tierIndex }
        assertEquals(listOf(0, 1, 1, 2, 2, 3, 3, 4, 4, 4), path)
    }

    @Test
    fun inboundFreezesAndOurOwnBandwidthCapDoNotLowerTheSender() {
        val controller = CallQualityController()
        controller.observe(sample(0))
        repeat(6) { index ->
            val decision = controller.observe(sample((index + 1) * 2_000L, freezes = (index + 1).toLong(), limitation = "bandwidth"))
            assertEquals(ConnectionQuality.Poor, decision.quality)
            assertEquals(START_TIER_INDEX, decision.tierIndex)
        }
    }

    @Test
    fun resolutionUpshiftNeedsFiveCleanSamples() {
        val controller = CallQualityController()
        controller.setTierIndex(BOTTOM_TIER_INDEX)
        repeat(4) { index -> assertFalse(controller.observe(sample(index * 2_000L)).changed) }
        val recovered = controller.observe(sample(8_000))
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
    fun jitterGradesTheBadge() {
        val controller = CallQualityController()
        controller.observe(sample(0, jitterDelay = 0.0, jitterCount = 0))
        assertEquals(ConnectionQuality.Poor, controller.observe(sample(2_000, jitterDelay = 6.0, jitterCount = 10)).quality)
    }

    @Test
    fun resetRestoresTopTier() {
        val controller = CallQualityController()
        controller.observe(sample(0))
        repeat(4) { controller.observe(sample((it + 1) * 2_000L, outLoss = .5)) }
        assertTrue(controller.getTierIndex() > START_TIER_INDEX)
        controller.reset()
        assertEquals(START_TIER_INDEX, controller.getTierIndex())
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