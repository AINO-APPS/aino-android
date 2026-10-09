package app.aino.mobile.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class AinoUser(
    val id: Long,
    val username: String,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val role: String,
    @SerialName("org_id") val orgId: Long? = null,
    @SerialName("tenant_id") val tenantId: Long? = null,
    @SerialName("has_reports") val hasReports: Boolean = false,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    @SerialName("tenant_features") val tenantFeatures: Map<String, Boolean> = emptyMap(),
    @SerialName("tenant_plan") val tenantPlan: String? = null,
    @SerialName("team_id") val teamId: Long? = null,
    @SerialName("team_name") val teamName: String? = null,
)

@Serializable
/** [refreshToken]: P2.7 rotating refresh token, present when the server issued one. */
data class AuthResponse(val user: AinoUser, val token: String, val refreshToken: String? = null)

@Serializable
data class TokenResponse(val token: String, val refreshToken: String? = null)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class BiometricEnrollRequest(val platform: String, val deviceLabel: String)

@Serializable
data class BiometricEnrollResponse(val credentialId: String, val deviceSecret: String)

@Serializable
data class BiometricLoginRequest(val credentialId: String, val deviceSecret: String)

@Serializable
data class MfaStepUpRequest(val code: String)

const val MFA_STEP_UP_REQUIRED = "MFA_STEP_UP_REQUIRED"

/**
 * `POST /auth/mfa/step-up` re-issues the session token with a fresh `mfa_at`
 * proof as a `Set-Cookie` only (tenant `token`, console `aino_console`). The
 * app authenticates with a bearer header, so lift the token out of the cookie.
 */
fun stepUpTokenFromCookies(headers: Map<String, List<String>>): String? =
    headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { it.value }
        .firstNotNullOfOrNull { cookie ->
            val pair = cookie.substringBefore(';').trim()
            val name = pair.substringBefore('=', "").trim()
            pair.substringAfter('=', "").trim().takeIf { name in STEP_UP_COOKIES && it.isNotEmpty() }
        }

private val STEP_UP_COOKIES = setOf("token", "aino_console")

fun requireTenantBiometricCredential(credential: BiometricCredential) {
    val tenantId = credential.credentialId.substringBefore('.').toLongOrNull()
    require(tenantId != null && tenantId > 0) {
        "Biometric sign-in is available only for tenant workspace accounts on Android"
    }
    require(credential.deviceSecret.isNotBlank()) { "Biometric credential is missing its device secret" }
}

@Serializable
data class RealmChoiceRequest(
    @SerialName("login_ticket") val loginTicket: String,
    val realm: String,
)

@Serializable
data class RealmOption(
    val realm: String,
    val label: String,
    @SerialName("tenant_id") val tenantId: Long? = null,
    val default: Boolean = false,
)

@Serializable
data class ApiErrorBody(
    val error: String,
    val code: String? = null,
    @SerialName("login_ticket") val loginTicket: String? = null,
    val realms: List<RealmOption> = emptyList(),
)

@Serializable
data class PasswordChangeRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class PasswordChangeResponse(
    val message: String,
    @SerialName("must_change_password") val mustChangePassword: Boolean,
    val token: String,
)

sealed interface AuthState {
    data object Initializing : AuthState
    data object SignedOut : AuthState
    data class ChoosingRealm(val ticket: String, val realms: List<RealmOption>) : AuthState
    data class PasswordChangeRequired(val user: AinoUser) : AuthState

    /**
     * @param featuresDegraded true when `tenant_features` could not be refreshed
     * from the server and the user's gates came from a last-known-good cache or
     * are unknown. The shell surfaces a warning banner instead of silently
     * hiding tabs.
     */
    data class Authenticated(val user: AinoUser, val featuresDegraded: Boolean = false) : AuthState
}

private val FeatureJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

fun encodeFeatures(features: Map<String, Boolean>): String =
    FeatureJson.encodeToString(features)

fun decodeFeatures(raw: String?): Map<String, Boolean>? = raw?.let {
    runCatching { FeatureJson.decodeFromString<Map<String, Boolean>>(it) }.getOrNull()
}

fun stateFor(user: AinoUser): AuthState =
    if (user.mustChangePassword) AuthState.PasswordChangeRequired(user)
    else AuthState.Authenticated(user)

fun stateFor(response: AuthResponse): AuthState = stateFor(response.user)

fun validatePasswordChange(current: String, next: String, confirmation: String): String? = when {
    current.isBlank() || next.isBlank() -> "Both current and new password are required"
    next != confirmation -> "New passwords do not match"
    next.length < 8 -> "New password must be at least 8 characters"
    next.length > 72 -> "New password must be 72 characters or less"
    current == next -> "New password must be different from current password"
    else -> null
}