package app.aino.mobile.core.realtime

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.min
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RealtimeEnvelope(
    val type: String,
    val data: JsonElement? = null,
)

sealed interface RealtimeState {
    data object Disconnected : RealtimeState
    data class Connecting(val attempt: Int) : RealtimeState
    data object Connected : RealtimeState
    data class Stopped(val code: Int, val reason: String) : RealtimeState
}

fun realtimeUrl(baseUrl: String, token: String): String {
    require(token.isNotBlank()) { "Realtime token must not be blank" }
    val base = URI(baseUrl.trimEnd('/'))
    require(base.scheme == "wss" || base.scheme == "ws") { "Realtime endpoint must use ws or wss" }
    val path = if (base.path.isNullOrBlank() || base.path == "/") "/ws" else base.path
    val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8.name()).replace("+", "%20")
    val authority = buildString {
        if (!base.userInfo.isNullOrBlank()) append(base.userInfo).append('@')
        append(base.host)
        if (base.port >= 0) append(':').append(base.port)
    }
    return "${base.scheme}://$authority$path?token=$encoded"
}

fun reconnectCeiling(attempt: Int, baseMs: Long = 1_000, maxMs: Long = 15_000): Long {
    val exponent = attempt.coerceIn(0, 30)
    return min(maxMs, baseMs * (1L shl exponent).coerceAtMost(maxMs))
}

fun reconnectDelay(attempt: Int, randomFraction: Double): Long {
    require(randomFraction in 0.0..1.0)
    return (reconnectCeiling(attempt) * randomFraction).toLong()
}

/** Only "this session is over" stops the socket; AinoApp then verifies the session and may retry. */
fun isTerminalRealtimeClose(code: Int): Boolean = code == 4001

/**
 * The server is up but asked us to come back later: tenant pool unavailable
 * (4003 / 1013) or too many sockets for this user (4029, stale ones expire).
 * Retrying slowly recovers on its own; stopping left chat stuck until restart.
 */
fun isSlowRetryRealtimeClose(code: Int): Boolean = code in setOf(1013, 4003, 4029)

/** 15–30 s, jittered so a fleet of clients does not return in lockstep. */
fun slowRetryDelay(randomFraction: Double): Long {
    require(randomFraction in 0.0..1.0)
    return SLOW_RETRY_MIN_MS + ((SLOW_RETRY_MAX_MS - SLOW_RETRY_MIN_MS) * randomFraction).toLong()
}

const val SLOW_RETRY_MIN_MS = 15_000L
const val SLOW_RETRY_MAX_MS = 30_000L