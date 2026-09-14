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

fun isTerminalRealtimeClose(code: Int): Boolean = code in setOf(4001, 4003, 4029)