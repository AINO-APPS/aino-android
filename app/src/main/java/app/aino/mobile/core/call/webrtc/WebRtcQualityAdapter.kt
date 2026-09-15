package app.aino.mobile.core.call.webrtc

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters

fun collectStatsSample(report: RTCStatsReport, nowEpochMs: Long = System.currentTimeMillis()): RawStatsSample {
    var rtt: Double? = null
    var lost = 0L
    var received = 0L
    var freezes = 0L
    var jitterDelay = 0.0
    var jitterCount = 0L
    var limitation: String? = null
    report.statsMap.values.forEach { stat ->
        val values = stat.members
        fun number(key: String): Number? = values[key] as? Number
        val kind = values["kind"] as? String
        when {
            stat.type == "candidate-pair" && ((values["nominated"] as? Boolean) == true || values["state"] == "succeeded") -> {
                rtt = number("currentRoundTripTime")?.toDouble() ?: rtt
            }
            stat.type == "inbound-rtp" && kind in setOf("audio", "video") -> {
                lost += number("packetsLost")?.toLong() ?: 0
                received += number("packetsReceived")?.toLong() ?: 0
                jitterDelay += number("jitterBufferDelay")?.toDouble() ?: 0.0
                jitterCount += number("jitterBufferEmittedCount")?.toLong() ?: 0
                if (kind == "video") freezes += number("freezeCount")?.toLong() ?: 0
            }
            stat.type == "outbound-rtp" && kind == "video" -> limitation = values["qualityLimitationReason"] as? String ?: limitation
            stat.type == "remote-inbound-rtp" && rtt == null -> rtt = number("roundTripTime")?.toDouble()
        }
    }
    return RawStatsSample(nowEpochMs, rtt, lost, received, freezes, jitterDelay, jitterCount, limitation)
}

class EncodingParameterApplier {
    private val mutexByPeer = ConcurrentHashMap<PeerConnection, Mutex>()

    suspend fun applyVideoTier(peer: PeerConnection, tier: VideoEncodingTier): Boolean = lock(peer) {
        var applied = false
        peer.senders.filter { it.track()?.kind() == "video" }.forEach { sender ->
            val params = sender.parameters
            if (params.encodings.isEmpty()) params.encodings.add(RtpParameters.Encoding(null, true, 1.0))
            params.encodings[0].maxBitrateBps = tier.maxBitrate
            params.encodings[0].maxFramerate = tier.maxFramerate
            params.encodings[0].scaleResolutionDownBy = tier.scaleResolutionDownBy
            params.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            applied = sender.setParameters(params) || applied
        }
        applied
    }

    suspend fun applyAudioCap(peer: PeerConnection, maxBitrate: Int = AUDIO_MAX_BITRATE): Boolean = lock(peer) {
        var applied = false
        peer.senders.filter { it.track()?.kind() == "audio" }.forEach { sender ->
            val params = sender.parameters
            if (params.encodings.isEmpty()) params.encodings.add(RtpParameters.Encoding(null, true, 1.0))
            params.encodings[0].maxBitrateBps = maxBitrate
            applied = sender.setParameters(params) || applied
        }
        applied
    }

    private suspend fun <T> lock(peer: PeerConnection, action: () -> T): T =
        mutexByPeer.getOrPut(peer) { Mutex() }.withLock { action() }

    fun clear(peer: PeerConnection) { mutexByPeer.remove(peer) }
}