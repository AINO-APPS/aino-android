package app.aino.mobile.core.call.webrtc

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ConnectionQuality { Good, Fair, Poor, Unknown }

data class VideoEncodingTier(
    val maxBitrate: Int,
    val scaleResolutionDownBy: Double,
    val maxFramerate: Int,
)

val VIDEO_TIERS = listOf(
    VideoEncodingTier(1_500_000, 1.0, 30),
    VideoEncodingTier(800_000, 1.0, 30),
    VideoEncodingTier(500_000, 1.0, 24),
    VideoEncodingTier(300_000, 1.5, 20),
    VideoEncodingTier(150_000, 2.0, 15),
)

const val MOBILE_TOP_TIER_BITRATE = 1_200_000
const val BOTTOM_TIER_INDEX = 4
const val RAMP_START_TIER_INDEX = 3
const val AUDIO_MAX_BITRATE = 48_000

fun buildTiers(isMobile: Boolean = true): List<VideoEncodingTier> = VIDEO_TIERS.mapIndexed { index, tier ->
    if (index == 0 && isMobile) tier.copy(maxBitrate = min(tier.maxBitrate, MOBILE_TOP_TIER_BITRATE)) else tier.copy()
}

data class RawStatsSample(
    val timestampMs: Long,
    val rttSeconds: Double?,
    val packetsLost: Long = 0,
    val packetsReceived: Long = 0,
    val freezeCount: Long = 0,
    val jitterBufferDelay: Double = 0.0,
    val jitterBufferEmittedCount: Long = 0,
    val qualityLimitationReason: String? = null,
)

data class QualityDecision(
    val quality: ConnectionQuality,
    val tierIndex: Int,
    val tier: VideoEncodingTier,
    val changed: Boolean,
    val smoothedRttSeconds: Double?,
    val baselineRttSeconds: Double?,
    val smoothedLossRate: Double,
    val freezeDelta: Long,
    val jitterBufferSeconds: Double,
    val reason: String,
)

class CallQualityController(isMobile: Boolean = true) {
    private val tiers = buildTiers(isMobile)
    private var tierIndex = RAMP_START_TIER_INDEX
    private var previous: RawStatsSample? = null
    private var rttEwma: Double? = null
    private var lossEwma = 0.0
    private var goodStreak = 0
    private var baselineRtt: Double? = null
    private val baselineSamples = mutableListOf<Double>()
    private var firstSampleAt: Long? = null

    fun observe(sample: RawStatsSample): QualityDecision {
        val prev = previous
        previous = sample
        sample.rttSeconds?.takeIf(Double::isFinite)?.let { rtt ->
            rttEwma = rttEwma?.let { EWMA_ALPHA * rtt + (1 - EWMA_ALPHA) * it } ?: rtt
            updateBaseline(requireNotNull(rttEwma), sample.timestampMs)
        }

        var intervalLoss = 0.0
        if (prev != null) {
            val lost = max(0, sample.packetsLost - prev.packetsLost)
            val received = max(0, sample.packetsReceived - prev.packetsReceived)
            val total = lost + received
            if (total > 0) intervalLoss = lost.toDouble() / total
        }
        lossEwma = if (prev != null) EWMA_ALPHA * intervalLoss + (1 - EWMA_ALPHA) * lossEwma else intervalLoss
        val freezeDelta = if (prev != null) max(0, sample.freezeCount - prev.freezeCount) else 0
        val jitter = if (prev != null) {
            val delay = sample.jitterBufferDelay - prev.jitterBufferDelay
            val emitted = sample.jitterBufferEmittedCount - prev.jitterBufferEmittedCount
            if (emitted > 0 && delay >= 0) delay / emitted else 0.0
        } else 0.0
        val rttExcess = if (rttEwma != null && baselineRtt != null) max(0.0, rttEwma!! - baselineRtt!!) else 0.0

        if (rttEwma == null) return decision(ConnectionQuality.Unknown, false, freezeDelta, jitter, "no rtt sample yet")
        var quality = ConnectionQuality.Good
        var reason = "stable"
        when {
            freezeDelta > 0 -> { quality = ConnectionQuality.Poor; reason = "$freezeDelta freeze(s) observed" }
            sample.qualityLimitationReason == "bandwidth" -> { quality = ConnectionQuality.Poor; reason = "encoder bandwidth-limited" }
            lossEwma >= LOSS_BAD -> { quality = ConnectionQuality.Poor; reason = "loss ${"%.1f".format(lossEwma * 100)}%" }
            rttExcess >= RTT_EXCESS_BAD -> { quality = ConnectionQuality.Poor; reason = "rtt +${(rttExcess * 1000).roundToInt()}ms over baseline" }
            jitter >= JITTER_BUFFER_BAD -> { quality = ConnectionQuality.Poor; reason = "jitter buffer ${(jitter * 1000).roundToInt()}ms" }
            lossEwma >= LOSS_WARN || rttExcess >= RTT_EXCESS_WARN || jitter >= JITTER_BUFFER_WARN || sample.qualityLimitationReason == "cpu" -> {
                quality = ConnectionQuality.Fair; reason = "degrading"
            }
        }

        val before = tierIndex
        when (quality) {
            ConnectionQuality.Poor -> { goodStreak = 0; if (tierIndex < BOTTOM_TIER_INDEX) tierIndex++ }
            ConnectionQuality.Fair -> goodStreak = 0
            ConnectionQuality.Good -> {
                goodStreak++
                if (tierIndex > 0) {
                    val resolutionChange = tiers[tierIndex - 1].scaleResolutionDownBy != tiers[tierIndex].scaleResolutionDownBy
                    val required = UPSHIFT_SAMPLES + if (resolutionChange) UPSHIFT_RESOLUTION_PENALTY else 0
                    if (goodStreak >= required) { tierIndex--; goodStreak = 0; reason = "recovered" }
                }
            }
            ConnectionQuality.Unknown -> Unit
        }
        return decision(quality, tierIndex != before, freezeDelta, jitter, reason)
    }

    fun getTierIndex() = tierIndex
    fun getTier() = tiers[tierIndex]
    fun setTierIndex(index: Int) { tierIndex = index.coerceIn(0, BOTTOM_TIER_INDEX); goodStreak = 0 }
    fun reset() {
        tierIndex = RAMP_START_TIER_INDEX; previous = null; rttEwma = null; lossEwma = 0.0
        goodStreak = 0; baselineRtt = null; baselineSamples.clear(); firstSampleAt = null
    }

    private fun updateBaseline(rtt: Double, timestamp: Long) {
        if (firstSampleAt == null) firstSampleAt = timestamp
        if (timestamp - requireNotNull(firstSampleAt) <= BASELINE_WINDOW_MS) {
            baselineSamples += rtt
            baselineRtt = if (baselineSamples.size >= BASELINE_MIN_SAMPLES) percentile(baselineSamples.sorted(), .25) else baselineRtt ?: rtt
        } else if (baselineRtt == null || rtt < baselineRtt!!) baselineRtt = rtt
    }

    private fun decision(quality: ConnectionQuality, changed: Boolean, freeze: Long, jitter: Double, reason: String) =
        QualityDecision(quality, tierIndex, tiers[tierIndex], changed, rttEwma, baselineRtt, lossEwma, freeze, jitter, reason)

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val index = ((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private companion object {
        const val LOSS_BAD = .05
        const val LOSS_WARN = .02
        const val RTT_EXCESS_BAD = .2
        const val RTT_EXCESS_WARN = .08
        const val JITTER_BUFFER_BAD = .5
        const val JITTER_BUFFER_WARN = .25
        const val EWMA_ALPHA = .4
        const val UPSHIFT_SAMPLES = 4
        const val UPSHIFT_RESOLUTION_PENALTY = 2
        const val BASELINE_WINDOW_MS = 10_000
        const val BASELINE_MIN_SAMPLES = 3
    }
}

fun preferOpusFec(sdp: String): String {
    if (sdp.isEmpty()) return sdp
    val payloads = Regex("^a=rtpmap:(\\d+)\\s+opus/48000", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        .findAll(sdp).map { it.groupValues[1] }.toSet()
    if (payloads.isEmpty()) return sdp
    return sdp.split(Regex("\\r?\\n")).joinToString("\r\n") { line ->
        val match = Regex("^a=fmtp:(\\d+)\\s+(.*)$").matchEntire(line)
        if (match == null || match.groupValues[1] !in payloads) line else {
            val params = linkedMapOf<String, String>()
            match.groupValues[2].split(';').map(String::trim).filter(String::isNotEmpty).forEach { part ->
                val index = part.indexOf('=')
                if (index < 0) params[part] = "" else params[part.substring(0, index)] = part.substring(index + 1)
            }
            params["useinbandfec"] = "1"; params["usedtx"] = "0"; params["maxaveragebitrate"] = "32000"
            "a=fmtp:${match.groupValues[1]} " + params.entries.joinToString(";") { (key, value) -> if (value.isEmpty()) key else "$key=$value" }
        }
    }
}