package app.aino.mobile.feature.profile

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * `validateUsername` on the server (`server/utils/validation`) is mirrored here
 * so an invalid edit never reaches the network. The rules the route enforces are
 * a non-empty name capped at 100 characters and a username that is unique and
 * well-formed; uniqueness can only be decided server-side, so only the format
 * checks are performed locally.
 */
fun validateProfileEdit(fullName: String, username: String): String? {
    val name = fullName.trim()
    val handle = username.trim()
    if (name.isEmpty() || handle.isEmpty()) return "Name and username are required"
    if (name.length > 100) return "Full name must be 100 characters or less"
    if (handle.length < 3 || handle.length > 30) return "Username must be between 3 and 30 characters"
    if (!Regex("^[a-zA-Z0-9._-]+$").matches(handle)) {
        return "Username may only contain letters, numbers, dots, underscores and hyphens"
    }
    return null
}

/** EditProfileModal's username input: `toLowerCase().replace(/\s/g, "")`. */
fun normalizeUsernameInput(value: String): String = value.lowercase().replace(Regex("\\s"), "")

/** Matches the server's email regex on `PUT /api/profile/email`. */
fun validateEmail(email: String): String? {
    val value = email.trim()
    if (value.isEmpty()) return "Email is required"
    if (!Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value)) return "Invalid email address"
    return null
}

/** EditProfileModal `handlePasswordSave` client checks (verbatim copy); empty fields are blocked by the disabled button like the web's `required`. */
fun validateProfilePassword(next: String, confirmation: String): String? = when {
    next != confirmation -> "New passwords do not match"
    next.length < 8 -> "Password must be at least 8 characters"
    else -> null
}

fun roleLabel(role: String): String = when (role) {
    "super_admin" -> "Super admin"
    "hr_admin" -> "HR admin"
    "platform_admin" -> "Platform admin"
    "team_lead" -> "Team lead"
    else -> role.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

// ── Avatar (ProfileMenu `handleAvatarUpload`) ────────────────────────────────

const val MAX_AVATAR_BYTES: Long = 5L * 1024 * 1024
val AVATAR_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
const val AVATAR_TOO_LARGE = "File is too large. Maximum size is 5MB."

/** True when the picked image can be uploaded untouched (the web sends the original file). */
fun avatarUploadableAsIs(mimeType: String?, size: Long): Boolean =
    mimeType in AVATAR_MIME_TYPES && size in 1..MAX_AVATAR_BYTES

// ── Sign out (ProfileMenu `confirmSignOut`) ──────────────────────────────────

sealed interface SignOutPlan {
    data object SignOut : SignOutPlan
    /** Remote sessions are clocked out automatically before signing out. */
    data object ClockOutThenSignOut : SignOutPlan
    data class Blocked(val message: String) : SignOutPlan
}

fun signOutPlan(workState: String?, workMode: String?): SignOutPlan = when {
    workState != "on_floor" && workState != "on_break" -> SignOutPlan.SignOut
    workMode == "office" -> SignOutPlan.Blocked(
        "Please clock out from the Work Timer before signing out. Office clock-out requires location and face verification.",
    )
    else -> SignOutPlan.ClockOutThenSignOut
}

// ── Biometric devices (EditProfileModal `platformLabel`) ─────────────────────

fun biometricPlatformLabel(platform: String): String = when (platform) {
    "ios" -> "iPhone / iPad"
    "android" -> "Android device"
    "desktop" -> "Desktop app"
    "web" -> "Web browser"
    else -> platform
}

/** `new Date(x).toLocaleDateString()` equivalent in the device locale. */
fun localDate(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (value.isNullOrBlank()) return null
    val instant = runCatching { Instant.parse(value) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: return null
    return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withZone(zone).format(instant)
}
