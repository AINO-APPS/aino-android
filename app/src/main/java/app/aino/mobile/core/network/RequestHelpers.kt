package app.aino.mobile.core.network

import java.net.URI
import java.util.TimeZone

object RequestHeaders {
    const val AUTHORIZATION = "Authorization"
    const val REQUESTED_WITH = "X-Requested-With"
    const val TIMEZONE_OFFSET = "x-timezone-offset"
    const val AINO = "AINO"

    /** Marks app-minted tokens server-side; admin routes are web-only and refuse them. */
    const val CLIENT = "X-AINO-Client"
    const val ANDROID = "android"

    /** Stable install id: the server keeps one session per device (see [DeviceId]). */
    const val DEVICE_ID = "X-AINO-Device-Id"

    /** P2.7: ask for a 15-minute access token plus a rotating refresh token at sign-in. */
    const val TOKEN_REFRESH = "X-AINO-Token-Refresh"
    const val ROTATE = "rotate"
}

/**
 * This install's id, set once by `AppContainer`. The server keeps one session
 * per client class: signing in here ends the account's session on any other
 * phone (which gets a `session_revoked` push); web and desktop stay signed in.
 * The id also tags this install's push token so that push reaches the right phone.
 */
object DeviceId {
    @Volatile var value: String? = null

    /** Kept in `noBackupFilesDir` so a restored backup never clones another phone's id. */
    fun load(context: android.content.Context): String {
        val file = java.io.File(context.noBackupFilesDir, "aino_device_id")
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.length >= 8 }
            ?: java.util.UUID.randomUUID().toString().also { runCatching { file.writeText(it) } }
    }
}

/** Mirrors JavaScript Date.getTimezoneOffset(): UTC minus local time, in minutes. */
fun timezoneOffsetMinutes(timeZone: TimeZone, epochMillis: Long): Int =
    -timeZone.getOffset(epochMillis) / 60_000

fun standardHeaders(
    token: String?,
    timezoneOffsetMinutes: Int,
): Map<String, String> = buildMap {
    put(RequestHeaders.REQUESTED_WITH, RequestHeaders.AINO)
    put(RequestHeaders.CLIENT, RequestHeaders.ANDROID)
    put(RequestHeaders.TOKEN_REFRESH, RequestHeaders.ROTATE)
    put(RequestHeaders.TIMEZONE_OFFSET, timezoneOffsetMinutes.toString())
    DeviceId.value?.let { put(RequestHeaders.DEVICE_ID, it) }
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
