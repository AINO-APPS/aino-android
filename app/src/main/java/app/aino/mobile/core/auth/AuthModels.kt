package app.aino.mobile.core.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
)

@Serializable
data class AuthResponse(val user: AinoUser, val token: String)

@Serializable
data class TokenResponse(val token: String)

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class BiometricEnrollRequest(val platform: String, val deviceLabel: String)

@Serializable
data class BiometricEnrollResponse(val credentialId: String, val deviceSecret: String)

@Serializable
data class BiometricLoginRequest(val credentialId: String, val deviceSecret: String)

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
    data class Authenticated(val user: AinoUser) : AuthState
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