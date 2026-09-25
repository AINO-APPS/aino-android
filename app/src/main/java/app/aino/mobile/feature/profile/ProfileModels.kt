package app.aino.mobile.feature.profile

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/profile` returns the tenant user row plus derived fields
 * (`has_reports`, `tenant_id`, impersonation markers, tenant plan/features).
 * Every non-identity field is optional because the same route serves platform
 * users, impersonated sessions and virtual (id 0) admins.
 */
@Serializable
data class ProfileUser(
    val id: Long,
    val username: String,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String = "employee",
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("department_id") val departmentId: Long? = null,
    @SerialName("tenant_id") val tenantId: Long? = null,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    @SerialName("has_reports") val hasReports: Boolean = false,
    @SerialName("tenant_plan") val tenantPlan: String? = null,
    @SerialName("tenant_features") val tenantFeatures: Map<String, Boolean> = emptyMap(),
    val impersonated: Boolean = false,
) {
    fun display(): String = fullName?.takeIf(String::isNotBlank) ?: username
}

@Serializable
data class UpdateProfilePayload(
    @SerialName("full_name") val fullName: String,
    val username: String,
)

@Serializable
data class UpdateEmailPayload(val email: String)

@Serializable
data class DeleteAccountPayload(val password: String)

@Serializable
data class FaceStatus(
    val enrolled: Boolean = false,
    @SerialName("enrolled_at") val enrolledAt: String? = null,
)

/** `POST/DELETE /api/profile/avatar` → `{ avatar }` (null after removal). */
@Serializable
data class AvatarResponse(val avatar: String? = null)

// ── Status v2 (`/api/me/status`) ─────────────────────────────────────────────
// The resolver payload is camelCase (`services/status/index.ts` StatusPayload),
// both from REST and inside the `user_status` WS event.

@Serializable
data class StatusPayload(
    val userId: Long? = null,
    /** available · busy · dnd · brb · away · in_call · in_meeting · offline */
    val effective: String = "available",
    val presence: String = "offline",
    /** The user's manual choice (available · busy · dnd · brb) or null. */
    val manualStatus: String? = null,
    /** auto · invisible ("Appear Offline"). */
    val presencePreference: String = "auto",
    val statusMessage: String? = null,
    val statusMessageExpiresAt: String? = null,
    val source: String? = null,
)

/** `PUT /api/me/status`; `status = null` clears the manual choice. */
@Serializable
data class SetStatusRequest(
    val status: String?,
    val message: String? = null,
    val messageExpiresAt: String? = null,
)

@Serializable
data class PresencePreferenceRequest(val preference: String)

// ── Theme (`/api/tracker/theme`) ─────────────────────────────────────────────

@Serializable
data class ThemePayload(val theme: String)

// ── Notification prefs (`/api/profile/notification-prefs`) ───────────────────
// `client/src/utils/sounds.ts` DEFAULT_PREFS. The server merges partial updates.

@Serializable
data class NotificationPrefs(
    val ringtone: String = "classic",
    val ringtoneVolume: Double = 0.6,
    val outgoingTone: String = "ringback",
    val outgoingVolume: Double = 0.4,
    val messageTone: String = "ding",
    val messageVolume: Double = 0.5,
    val mentionTone: String = "mention",
    val mentionVolume: Double = 0.6,
    val reactionTone: String = "subtle",
    val reactionVolume: Double = 0.4,
    val muteAll: Boolean = false,
    val playWhenFocused: Boolean = false,
    val playOnSend: Boolean = false,
    val readReceipts: Boolean = true,
)

// ── Biometric devices (`GET /api/auth/biometric`) ────────────────────────────

@Serializable
data class BiometricDevice(
    val id: String,
    @SerialName("device_label") val deviceLabel: String? = null,
    val platform: String = "android",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("last_used_at") val lastUsedAt: String? = null,
)

@Serializable
data class BiometricDevicesResponse(val devices: List<BiometricDevice> = emptyList())
