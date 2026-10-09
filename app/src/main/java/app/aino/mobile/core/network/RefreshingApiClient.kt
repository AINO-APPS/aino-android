package app.aino.mobile.core.network

import app.aino.mobile.core.auth.TokenResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Retries one authenticated request after atomically refreshing its bearer
 * token. Concurrent 401s share the credential written by the first caller
 * instead of creating parallel sessions or refresh storms.
 *
 * P2.7: a session with a rotating refresh token swaps it at `sessions/token`
 * (15-minute access token + next refresh token). Older sessions keep rolling
 * their long-lived token at `auth/refresh`.
 */
class RefreshingApiClient(
    private val delegate: ApiClient,
    private val tokens: TokenStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : ApiClient {
    private val refreshLock = Any()

    override fun execute(request: ApiRequest): ApiResponse {
        val tokenAtStart = tokens.getToken()
        return try {
            delegate.execute(request)
        } catch (error: ApiError.Http) {
            if (error.statusCode != 401 || tokenAtStart.isNullOrBlank() || request.path in REFRESH_PATHS) throw error
            synchronized(refreshLock) {
                val current = tokens.getToken()
                if (current == tokenAtStart) refreshToken() // First caller refreshes.
                else if (current.isNullOrBlank()) throw error // Another caller failed refresh.
            }
            delegate.execute(request) // Retry exactly once with the current token.
        }
    }

    /**
     * Refreshes on behalf of a client that is not this one (media fetches, the
     * realtime socket) when its request with [staleToken] was refused. Shares
     * the refresh lock, so it never races a JSON request's refresh. Returns the
     * token to retry with, or null when the session is over.
     */
    fun refreshFor(staleToken: String?): String? = synchronized(refreshLock) {
        val current = tokens.getToken()
        if (current.isNullOrBlank()) return null
        if (current != staleToken) return current // already refreshed by another caller
        runCatching { refreshToken() }.getOrNull() ?: return null
        tokens.getToken()
    }

    private fun refreshToken() {
        try {
            val refresh = tokens.getRefreshToken()
            if (refresh.isNullOrBlank()) {
                val response = delegate.execute(ApiRequest(method = "POST", path = REFRESH_PATH, body = ByteArray(0)))
                tokens.saveToken(json.decodeFromString<TokenResponse>(response.bodyAsString()).token)
            } else {
                val body = json.encodeToString(RefreshTokenRequest(refresh)).toByteArray()
                val response = delegate.execute(
                    ApiRequest(method = "POST", path = TOKEN_PATH, headers = mapOf("Content-Type" to "application/json"), body = body),
                )
                val rotated = json.decodeFromString<TokenResponse>(response.bodyAsString())
                // Persist the next refresh token first: losing it would mean signing in again.
                rotated.refreshToken?.let(tokens::saveRefreshToken)
                tokens.saveToken(rotated.token)
            }
        } catch (error: Exception) {
            // Only a refused refresh ends the session; a network blip or a 5xx
            // must not sign the user out (the next request simply retries).
            if (error is ApiError.Http && (error.statusCode == 401 || error.statusCode == 403)) tokens.clearToken()
            throw error
        }
    }

    private companion object {
        // @api POST auth/refresh
        const val REFRESH_PATH = "auth/refresh"
        // @api POST sessions/token
        const val TOKEN_PATH = "sessions/token"
        val REFRESH_PATHS = setOf(REFRESH_PATH, TOKEN_PATH)
    }
}

@kotlinx.serialization.Serializable
internal data class RefreshTokenRequest(val refreshToken: String)
/** True when [jwt]'s `exp` is within [seconds] from now (false when it has none or can't be read). */
fun jwtExpiresWithin(jwt: String, seconds: Long, nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean = runCatching {
    val part = jwt.split('.')[1]
    val payload = String(java.util.Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))
    val exp = (Json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject)["exp"]?.toString()?.toLongOrNull()
    exp != null && exp - nowSeconds <= seconds
}.getOrDefault(false)
