package app.aino.mobile.core.network

import java.net.URI
import java.util.TimeZone

object RequestHeaders {
    const val AUTHORIZATION = "Authorization"
    const val REQUESTED_WITH = "X-Requested-With"
    const val TIMEZONE_OFFSET = "x-timezone-offset"
    const val AINO = "AINO"
}

/** Mirrors JavaScript Date.getTimezoneOffset(): UTC minus local time, in minutes. */
fun timezoneOffsetMinutes(timeZone: TimeZone, epochMillis: Long): Int =
    -timeZone.getOffset(epochMillis) / 60_000

fun standardHeaders(
    token: String?,
    timezoneOffsetMinutes: Int,
): Map<String, String> = buildMap {
    put(RequestHeaders.REQUESTED_WITH, RequestHeaders.AINO)
    put(RequestHeaders.TIMEZONE_OFFSET, timezoneOffsetMinutes.toString())
    token?.trim()?.takeIf(String::isNotEmpty)?.let {
        put(RequestHeaders.AUTHORIZATION, "Bearer $it")
    }
}

fun resolveApiUrl(baseUrl: String, path: String): String {
    val target = URI(path)
    require(!target.isAbsolute && target.rawAuthority == null) {
        "API request paths must be relative to the configured origin"
    }

    val normalizedBase = baseUrl.trimEnd('/') + "/"
    val normalizedPath = path.trimStart('/')
    return URI(normalizedBase).resolve(normalizedPath).toString()
}
