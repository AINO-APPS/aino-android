package app.aino.mobile.core.network

import app.aino.mobile.core.auth.TokenResponse
import kotlinx.serialization.json.Json

/**
 * Retries one authenticated request after atomically refreshing its bearer
 * token. Concurrent 401s share the credential written by the first caller
 * instead of creating parallel sessions or refresh storms.
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
            if (error.statusCode != 401 || tokenAtStart.isNullOrBlank() || request.path == REFRESH_PATH) throw error
            synchronized(refreshLock) {
                val current = tokens.getToken()
                if (current == tokenAtStart) refreshToken() // First caller refreshes.
                else if (current.isNullOrBlank()) throw error // Another caller failed refresh.
            }
            delegate.execute(request) // Retry exactly once with the current token.
        }
    }

    private fun refreshToken() {
        try {
            val response = delegate.execute(
                ApiRequest(method = "POST", path = REFRESH_PATH, body = ByteArray(0)),
            )
            val refreshed = json.decodeFromString<TokenResponse>(response.bodyAsString())
            tokens.saveToken(refreshed.token)
        } catch (error: Exception) {
            tokens.clearToken()
            throw error
        }
    }

    private companion object {
        const val REFRESH_PATH = "auth/refresh"
    }
}