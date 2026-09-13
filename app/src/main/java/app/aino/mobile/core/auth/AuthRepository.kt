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
    fun restoreSession(): AuthState {
        if (tokens.getToken().isNullOrBlank()) return AuthState.SignedOut
        return try {
            val response = api.execute(ApiRequest(path = "profile", headers = mapOf("Accept" to "application/json")))
            stateFor(json.decodeFromString<AinoUser>(response.bodyAsString()))
        } catch (error: ApiError.Http) {
            if (error.statusCode == 401 || error.statusCode == 403) tokens.clearToken()
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
        return stateFor(auth)
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