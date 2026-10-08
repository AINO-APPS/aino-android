package app.aino.mobile.core.navigation

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A group invite link opened from outside the app (`aino://chat/join/<token>`)
 * or pasted as the web link (`https://<origin>/chat/join/<token>`). The
 * signed-in shell consumes it and shows the join preview.
 */
object PendingGroupInvite {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    fun set(token: String) { _token.value = token }

    fun consume(): String? = _token.value.also { _token.value = null }
}

private val INVITE_TOKEN = Regex("^[A-Za-z0-9_-]{16,64}$")

/** The token in a web group link (`/chat/join/<token>`, relative or absolute), or null. */
fun webGroupInviteToken(link: String): String? {
    val path = link.substringBefore('#').substringBefore('?').trimEnd('/')
    val token = path.substringAfter("/chat/join/", "").takeIf { path.contains("/chat/join/") } ?: return null
    return token.takeIf { '/' !in it && INVITE_TOKEN.matches(it) }
}

/** The invite token in `aino://chat/join/<token>`, or null for any other link. */
fun groupInviteTokenFrom(uri: Uri?): String? {
    if (uri == null || uri.scheme != "aino" || uri.host != "chat") return null
    val segments = uri.pathSegments
    return segments.getOrNull(1)?.takeIf { segments.size == 2 && segments[0] == "join" && INVITE_TOKEN.matches(it) }
}
