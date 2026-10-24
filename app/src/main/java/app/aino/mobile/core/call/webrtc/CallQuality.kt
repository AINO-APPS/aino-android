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
    VideoEncodingTier(2_000_000, 1.0, 30),
    VideoEncodingTier(1_000_000, 1.0, 30),
    VideoEncodingTier(600_000, 1.0, 24),
    VideoEncodingTier(350_000, 1.5, 20),
    VideoEncodingTier(200_000, 2.0, 15),
)

const val MOBILE_TOP_TIER_BITRATE = 1_500_000
const val BOTTOM_TIER_INDEX = 4
/** "Use less data for calls" caps video at 350 kbps, 1.5× downscaled. */
const val LOW_DATA_TIER_INDEX = 3

/** The controller's rung, never better than [floorIndex] (low-data calls). */
fun cappedTier(tierIndex: Int, floorIndex: Int, isMobile: Boolean = true): VideoEncodingTier =
    buildTiers(isMobile)[maxOf(tierIndex, floorIndex).coerceIn(0, BOTTOM_TIER_INDEX)]

/**
 * Calls start uncapped at the top rung. WebRTC's congestion controller already
 * ramps the real send rate to what the path can carry; starting low only added
 * a slow app-level ramp on top of it (blurry first ~30 s of every call).
 */
const val START_TIER_INDEX = 0
const val AUDIO_MAX_BITRATE = 48_000
const val QUALITY_STATS_INTERVAL_MS = 2_000L

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
    /** Loss the peer reports for *our* video (remote-inbound-rtp fractionLost, 0..1). */
    val outboundFractionLost: Double? = null,
    /**
     * Candidate types of the nominated pair. A change means the media path
     * moved (e.g. direct → TURN relay), so the learned RTT floor is stale.
     */
    val localCandidateType: String? = null,
    val remoteCandidateType: String? = null,
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

/**
 * Two separate judgements:
 *  - the badge ([QualityDecision.quality]) reflects everything the user experiences,
 *    including the peer's video freezing or arriving lossy;
 *  - the sender cap ([QualityDecision.tier]) only reacts to problems on *our uplink*
 *    (loss the peer reports for our stream, sustained queueing delay). Inbound
 *    trouble is the peer's uplink and their own controller handles it; downgrading
 *    our sender for it just made both sides blurry. Our own cap showing up as
 *    `qualityLimitationReason=bandwidth` is not a signal either.
 */
class CallQualityController(isMobile: Boolean = true) {
    private val tiers = buildTiers(isMobile)
    private var tierIndex = START_TIER_INDEX
    private var previous: RawStatsSample? = null
    private var rttEwma: Double? = null
    private var lossEwma = 0.0
    private var outboundLossEwma = 0.0
    private var goodStreak = 0
    private var badStreak = 0
    private var baselineRtt: Double? = null
    private val baselineSamples = mutableListOf<Double>()
    private var firstSampleAt: Long? = null
    /** Candidate types of the pair the current baseline was learned on. */
    private var baselinePathKey: String? = null
    /** True only between a path change and the point where the new floor is trusted. */
    private var relearning = false

    fun observe(sample: RawStatsSample): QualityDecision {
        val prev = previous
        previous = sample

        // A path change (ICE restart, relay-only rebuild, nominated-pair swap)
        // moves the call onto a link with a different latency floor. Grading
        // the new path against the old floor reports permanent "excess RTT"
        // and walks the ladder to the bottom rung, where it then stays for the
        // rest of the call — the "reconnects, then quality drops" symptom.
        val pathKey = sample.localCandidateType?.let { local ->
            "$local/${sample.remoteCandidateType ?: "?"}"
        }
        if (pathKey != null) {
            if (baselinePathKey == null) baselinePathKey = pathKey
            else if (pathKey != baselinePathKey) {
                relearnBaseline()
                baselinePathKey = pathKey
            }
        }

        sample.rttSeconds?.takeIf(Double::isFinite)?.let { rtt ->
            rttEwma = rttEwma?.let { EWMA_ALPHA * rtt + (1 - EWMA_ALPHA) * it } ?: rtt
            updateBaseline(requireNotNull(rttEwma), sample.timestampMs)
        }
        sample.outboundFractionLost?.takeIf(Double::isFinite)?.let { fraction ->
            outboundLossEwma = EWMA_ALPHA * fraction.coerceIn(0.0, 1.0) + (1 - EWMA_ALPHA) * outboundLossEwma
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

        // After a path change there is nothing meaningful to compare RTT
        // against until the new floor is learned, so RTT-excess grading is
        // suspended for those few samples. Loss and CPU limitation are
        // absolute and stay authoritative.
        val learningBaseline = relearning && baselineSamples.size < BASELINE_MIN_SAMPLES
        if (!learningBaseline) relearning = false

        // Sender (uplink) health: the only input to the encoder cap.
        var send = ConnectionQuality.Good
        var reason = "stable"
        when {
            outboundLossEwma >= OUTBOUND_LOSS_BAD -> { send = ConnectionQuality.Poor; reason = "uplink loss ${"%.1f".format(outboundLossEwma * 100)}%" }
            !learningBaseline && rttExcess >= RTT_EXCESS_BAD -> { send = ConnectionQuality.Poor; reason = "rtt +${(rttExcess * 1000).roundToInt()}ms over baseline" }
            outboundLossEwma >= OUTBOUND_LOSS_WARN || (!learningBaseline && rttExcess >= RTT_EXCESS_WARN) || sample.qualityLimitationReason == "cpu" -> {
                send = ConnectionQuality.Fair; reason = if (learningBaseline) "learning path baseline" else "uplink degrading"
            }
        }

        // Badge: worst of uplink and what we receive.
        var quality = send
        when {
            freezeDelta > 0 -> { quality = ConnectionQuality.Poor; reason = "$freezeDelta inbound freeze(s)" }
            lossEwma >= LOSS_BAD -> { quality = ConnectionQuality.Poor; reason = "inbound loss ${"%.1f".format(lossEwma * 100)}%" }
            jitter >= JITTER_BUFFER_BAD -> { quality = ConnectionQuality.Poor; reason = "jitter buffer ${(jitter * 1000).roundToInt()}ms" }
            quality == ConnectionQuality.Good && (lossEwma >= LOSS_WARN || jitter >= JITTER_BUFFER_WARN) -> {
                quality = ConnectionQuality.Fair; reason = "inbound degrading"
            }
        }

        val before = tierIndex
        when (send) {
            ConnectionQuality.Poor -> {
                goodStreak = 0
                badStreak++
                if (badStreak >= DOWNSHIFT_SAMPLES && tierIndex < BOTTOM_TIER_INDEX) { tierIndex++; badStreak = 0 }
            }
            ConnectionQuality.Fair -> { goodStreak = 0; badStreak = 0 }
            ConnectionQuality.Good -> {
                badStreak = 0
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
    fun setTierIndex(index: Int) { tierIndex = index.coerceIn(0, BOTTOM_TIER_INDEX); goodStreak = 0; badStreak = 0 }

    /**
     * Reopen the learning window WITHOUT moving the ladder. Call this whenever
     * the media path changes: the new path has a different latency floor, and
     * grading it against the old one would pin the encoder at the bottom rung.
     */
    fun relearnBaseline() {
        baselineRtt = null; baselineSamples.clear(); firstSampleAt = null
        baselinePathKey = null; goodStreak = 0; badStreak = 0; relearning = true
    }

    fun reset() {
        tierIndex = START_TIER_INDEX; previous = null; rttEwma = null; lossEwma = 0.0; outboundLossEwma = 0.0
        goodStreak = 0; badStreak = 0; baselineRtt = null; baselineSamples.clear(); firstSampleAt = null
        baselinePathKey = null; relearning = false
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
        const val OUTBOUND_LOSS_BAD = .10
        const val OUTBOUND_LOSS_WARN = .04
        const val RTT_EXCESS_BAD = .25
        const val RTT_EXCESS_WARN = .1
        const val JITTER_BUFFER_BAD = .5
        const val JITTER_BUFFER_WARN = .25
        const val EWMA_ALPHA = .4
        const val DOWNSHIFT_SAMPLES = 2
        const val UPSHIFT_SAMPLES = 3
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