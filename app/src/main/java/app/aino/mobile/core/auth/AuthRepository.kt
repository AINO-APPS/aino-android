package app.aino.mobile.core.auth

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.TokenStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthRepository(
    private val api: ApiClient,
    private val tokens: TokenStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun hasStoredCredential(): Boolean = !tokens.getToken().isNullOrBlank()

    /** Profile fetch with backoff (3 attempts). Returns null only on 401/403. */
    private fun fetchProfile(attempts: Int = 3): AinoUser? {
        var lastError: Exception? = null
        for (attempt in 0 until attempts) {
            try {
                val response = api.execute(ApiRequest(path = "profile", headers = mapOf("Accept" to "application/json")))
                return json.decodeFromString<AinoUser>(response.bodyAsString())
            } catch (error: ApiError.Http) {
                if (error.statusCode == 401 || error.statusCode == 403) return null
                lastError = error
            } catch (error: ApiError.Network) {
                lastError = error
            } catch (error: Exception) {
                lastError = error
            }
            // Exponential backoff between attempts; no sleep after the last one.
            if (attempt < attempts - 1) Thread.sleep(300L shl attempt)
        }
        throw lastError ?: ApiError.Network("GET", "profile", java.io.IOException("profile fetch failed"))
    }

    fun restoreSession(): AuthState {
        if (tokens.getToken().isNullOrBlank()) return AuthState.SignedOut
        return try {
            val user = fetchProfile()
            if (user == null) {
                tokens.clearToken()
                tokens.clearFeatures()
                AuthState.SignedOut
            } else {
                tokens.saveFeatures(encodeFeatures(user.tenantFeatures))
                stateFor(user)
            }
        } catch (error: ApiError.Http) {
            if (error.statusCode == 401 || error.statusCode == 403) {
                tokens.clearToken()
                tokens.clearFeatures()
            }
            throw decodeFailure(error)
        }
    }

    fun login(username: String, password: String): AuthState = try {
        complete(api.execute(jsonRequest("POST", "auth/login", LoginRequest(username.trim(), password))))
    } catch (error: ApiError.Http) {
        val body = runCatching { json.decodeFromString<ApiErrorBody>(error.responseBody) }.getOrNull()
        if (error.statusCode == 409 && body?.code == "REALM_CHOICE_REQUIRED" && body.loginTicket != null) {
            AuthState.ChoosingRealm(body.loginTicket, body.realms)
        } else {
            throw AuthFailure(body?.error ?: "Sign in failed", body?.code, error)
        }
    }

    fun chooseRealm(ticket: String, realm: String): AuthState = try {
        complete(api.execute(jsonRequest("POST", "auth/login/realm", RealmChoiceRequest(ticket, realm))))
    } catch (error: ApiError.Http) {
        throw decodeFailure(error)
    }

    fun enrollBiometric(deviceLabel: String): BiometricCredential {
        try {
            val response = api.execute(
                jsonRequest(
                    "POST",
                    "auth/biometric/enroll",
                    BiometricEnrollRequest(platform = "android", deviceLabel = deviceLabel),
                ),
            )
            val enrolled = json.decodeFromString<BiometricEnrollResponse>(response.bodyAsString())
            return BiometricCredential(enrolled.credentialId, enrolled.deviceSecret).also(::requireTenantBiometricCredential)
        } catch (error: ApiError.Http) {
            throw decodeFailure(error)
        }
    }

    fun biometricLogin(credential: BiometricCredential): AuthState = try {
        requireTenantBiometricCredential(credential)
        complete(
            api.execute(
                jsonRequest(
                    "POST",
                    "auth/biometric/login",
                    BiometricLoginRequest(credential.credentialId, credential.deviceSecret),
                ),
            ),
        )
    } catch (error: ApiError.Http) {
        if (error.statusCode == 401 || error.statusCode == 403) {
            throw AuthFailure(
                "Biometric sign-in is no longer available. Sign in with your password and enroll again.",
                "BIOMETRIC_CREDENTIAL_INVALID",
                error,
            )
        }
        throw decodeFailure(error)
    }

    fun changePassword(current: String, next: String): AuthState {
        try {
            val response = api.execute(jsonRequest("PUT", "profile/password", PasswordChangeRequest(current, next)))
            val changed = json.decodeFromString<PasswordChangeResponse>(response.bodyAsString())
            // Validate and persist the rotated credential before applying the
            // web-compatible policy that requires a fresh login after change.
            tokens.saveToken(changed.token)
            tokens.clearToken()
            return AuthState.SignedOut
        } catch (error: ApiError.Http) {
            throw decodeFailure(error)
        }
    }

    fun logout() {
        runCatching { api.execute(jsonRequest("POST", "auth/logout", Unit)) }
        tokens.clearToken()
        tokens.clearFeatures()
    }

    fun recordActivity() {
        api.execute(jsonRequest("POST", "auth/activity", Unit))
    }

    private fun complete(response: app.aino.mobile.core.network.ApiResponse): AuthState {
        val text = response.bodyAsString()
        if (text.contains("\"redirect\"")) {
            throw AuthFailure("Platform administration is currently available in the web console.", "PLATFORM_CONSOLE_REQUIRED")
        }
        val auth = json.decodeFromString<AuthResponse>(text)
        tokens.saveToken(auth.token)
        // The login response intentionally stays small and does not include the
        // plan's effective `tenant_features`. Hydrate the profile (with retry)
        // before applying navigation gates so a fresh login does not hide
        // Attendance/Tasks/Chat until the next app launch. A profile fetch
        // failure must NOT invalidate an otherwise valid login: fall back to the
        // persisted last-known-good features, and mark the session degraded so
        // the shell surfaces a warning banner instead of silently hiding tabs.
        val hydrated = runCatching { fetchProfile() }.getOrNull()
        return when {
            hydrated != null -> {
                tokens.saveFeatures(encodeFeatures(hydrated.tenantFeatures))
                logInfo("hydrated tenant_features=${hydrated.tenantFeatures}")
                stateFor(hydrated)
            }
            else -> {
                val cached = decodeFeatures(tokens.getFeatures())
                val user = if (cached != null) auth.user.copy(tenantFeatures = cached) else auth.user
                logWarn("tenant_features hydration failed; using cached=${cached != null} degraded=true")
                when (val degraded = stateFor(user)) {
                    is AuthState.Authenticated -> degraded.copy(featuresDegraded = true)
                    else -> degraded
                }
            }
        }
    }

    // android.util.Log is not on the plain-JVM unit-test classpath; keep the
    // required INFO logging but never let it crash a test.
    private fun logInfo(message: String) = runCatching { android.util.Log.i("AinoAuth", message) }
    private fun logWarn(message: String) = runCatching { android.util.Log.w("AinoAuth", message) }

    /** Re-hydrate `tenant_features` for the current session (P0.1/P0.2 retry). */
    fun refreshFeatures(): AuthState {
        val hydrated = fetchProfile()
            ?: throw AuthFailure("Session expired", cause = null)
        tokens.saveFeatures(encodeFeatures(hydrated.tenantFeatures))
        return stateFor(hydrated)
    }

    private inline fun <reified T> jsonRequest(method: String, path: String, value: T) = ApiRequest(
        method = method,
        path = path,
        headers = mapOf("Accept" to "application/json"),
        // OkHttp requires a body for POST/PUT. Logout has no payload, but still
        // needs an explicit zero-length JSON body rather than null.
        body = if (value is Unit) ByteArray(0) else json.encodeToString(value).toByteArray(),
    )

    private fun decodeFailure(error: ApiError.Http): AuthFailure {
        val body = runCatching { json.decodeFromString<ApiErrorBody>(error.responseBody) }.getOrNull()
        return AuthFailure(body?.error ?: "Authentication failed", body?.code, error)
    }
}

class AuthFailure(message: String, val code: String? = null, cause: Throwable? = null) : Exception(message, cause)