package app.aino.mobile.core.call

import android.net.Uri
import java.time.Instant
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class IncomingCallRoute(
    val callId: Long,
    val conversationId: Long,
    val callerId: Long?,
    val callerName: String,
    val callerAvatar: String?,
    val callType: String,
    val action: String? = null,
    val expiresAt: String? = null,
    /** Set for group-call (huddle) rings: answering joins `/huddle/:code`. */
    val meetingCode: String? = null,
    val meetingId: Long? = null,
)

fun parseIncomingCallRoute(uri: Uri?): IncomingCallRoute? = parseIncomingCallRoute(uri?.toString())

fun parseIncomingCallRoute(value: String?): IncomingCallRoute? {
    val uri = runCatching { value?.let(::URI) }.getOrNull() ?: return null
    if (uri.scheme != "aino" || uri.host != "call") return null
    val conversationId = uri.path.trim('/').substringBefore('/').toLongOrNull()?.takeIf { it > 0 } ?: return null
    val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val index = part.indexOf('=')
        if (index < 0) null else URLDecoder.decode(part.substring(0, index), StandardCharsets.UTF_8.name()) to
            URLDecoder.decode(part.substring(index + 1), StandardCharsets.UTF_8.name())
    }.toMap()
    val callId = query["callId"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
    val callType = query["callType"].orEmpty().takeIf { it in setOf("voice", "video") } ?: "voice"
    val action = when {
        query["autoAnswer"] == "1" -> "answer"
        query["action"] == "decline" -> "decline"
        else -> null
    }
    return IncomingCallRoute(
        callId = callId,
        conversationId = conversationId,
        callerId = query["peerId"]?.toLongOrNull(),
        callerName = query["peerName"].orEmpty(),
        callerAvatar = query["peerAvatar"]?.takeIf(String::isNotBlank),
        callType = callType,
        action = action,
        expiresAt = query["expiresAt"],
        meetingCode = query["meetingCode"]?.takeIf(String::isNotBlank),
        meetingId = query["meetingId"]?.toLongOrNull()?.takeIf { it > 0 },
    )
}

fun remainingRingMillis(expiresAt: String?, now: Instant = Instant.now()): Long {
    val expiry = runCatching { expiresAt?.let(Instant::parse) }.getOrNull()
        ?: now.plusSeconds(30)
    return (expiry.toEpochMilli() - now.toEpochMilli()).coerceIn(0L, 30_000L)
}