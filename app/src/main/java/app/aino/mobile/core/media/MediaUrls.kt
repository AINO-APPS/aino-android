package app.aino.mobile.core.media

import app.aino.mobile.core.network.NetworkConfig

/**
 * Resolves stored upload paths (avatars, attachments) to absolute URLs. The web
 * resolves any relative path against the server origin (`<img src>`), so every
 * relative form (`/uploads/..`, `uploads/..`, `/api/..`) maps onto the server
 * origin; absolute and local (content/file/data) URIs pass through.
 */
fun resolveServerMediaUrl(value: String, origin: String = NetworkConfig.serverOrigin): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)) return trimmed
    if (trimmed.startsWith("//")) return "https:$trimmed"
    return origin.trimEnd('/') + "/" + trimmed.trimStart('/')
}
