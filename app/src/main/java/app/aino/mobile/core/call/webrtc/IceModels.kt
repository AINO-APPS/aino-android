package app.aino.mobile.core.call.webrtc

import kotlinx.serialization.Serializable

@Serializable
data class IceServerDto(
    val urls: IceUrls,
    val username: String? = null,
    val credential: String? = null,
)

@Serializable(with = IceUrlsSerializer::class)
data class IceUrls(val values: List<String>)

@Serializable
data class IceConfigDto(
    val iceServers: List<IceServerDto> = emptyList(),
    val mode: String? = null,
    val allowPublicFallback: Boolean = true,
    val expiresAt: Long? = null,
)

private val REAL_TURN_MODES = setOf("cloudflare-calls", "coturn-rest", "static")
private const val PUBLIC_RELAY_HOST = "openrelay.metered.ca"

fun hasRealTurn(config: IceConfigDto): Boolean {
    if (config.mode in REAL_TURN_MODES) return true
    return config.iceServers.any { server ->
        server.urls.values.any { url ->
            val lower = url.lowercase()
            (lower.startsWith("turn:") || lower.startsWith("turns:")) && PUBLIC_RELAY_HOST !in lower
        }
    }
}

fun applyPublicTurnPolicy(servers: List<IceServerDto>, allowPublic: Boolean): List<IceServerDto> {
    if (allowPublic) return servers
    return servers.mapNotNull { server ->
        val kept = server.urls.values.filterNot { PUBLIC_RELAY_HOST in it.lowercase() }
        if (kept.isEmpty()) null else server.copy(urls = IceUrls(kept))
    }
}

fun relayOnlyServers(servers: List<IceServerDto>): List<IceServerDto> = servers.mapNotNull { server ->
    val kept = server.urls.values.filter { url ->
        val lower = url.lowercase()
        lower.startsWith("turn:") || lower.startsWith("turns:")
    }
    if (kept.isEmpty()) null else server.copy(urls = IceUrls(kept))
}

enum class OfferCollisionDecision { Accept, RollbackThenAccept, Ignore }

fun offerCollisionDecision(
    polite: Boolean,
    makingOffer: Boolean,
    signalingStable: Boolean,
): OfferCollisionDecision {
    val collision = makingOffer || !signalingStable
    return when {
        !collision -> OfferCollisionDecision.Accept
        polite -> OfferCollisionDecision.RollbackThenAccept
        else -> OfferCollisionDecision.Ignore
    }
}

data class BufferedIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

class IceCandidateBuffer {
    private val pending = ArrayDeque<BufferedIceCandidate>()

    fun add(candidate: BufferedIceCandidate?) {
        if (candidate == null || candidate.candidate.isBlank()) return
        pending.addLast(candidate)
    }

    fun size(): Int = pending.size

    fun drain(): List<BufferedIceCandidate> = buildList {
        while (pending.isNotEmpty()) add(pending.removeFirst())
    }

    fun clear() = pending.clear()
}