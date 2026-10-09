package app.aino.mobile.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshingApiClientTest {
    @Test
    fun refreshesAndRetriesOnceAfterUnauthorized() {
        val tokens = MemoryTokens("old")
        val calls = mutableListOf<String>()
        val delegate = ApiClient { request ->
            calls += "${request.path}:${tokens.getToken()}"
            when {
                request.path == "auth/refresh" -> response("""{"token":"new"}""")
                tokens.getToken() == "old" -> throw http401(request)
                else -> response("ok")
            }
        }

        val result = RefreshingApiClient(delegate, tokens).execute(ApiRequest(path = "profile"))

        assertEquals("ok", result.bodyAsString())
        assertEquals(listOf("profile:old", "auth/refresh:old", "profile:new"), calls)
        assertEquals("new", tokens.value)
    }

    @Test
    fun doesNotRefreshAnUnauthenticatedRequest() {
        val tokens = MemoryTokens(null)
        var calls = 0
        val client = RefreshingApiClient(ApiClient { request -> calls++; throw http401(request) }, tokens)

        runCatching { client.execute(ApiRequest(method = "POST", path = "auth/login", body = ByteArray(0))) }

        assertEquals(1, calls)
        assertNull(tokens.value)
    }

    @Test
    fun clearsCredentialWhenRefreshFails() {
        val tokens = MemoryTokens("expired")
        val delegate = ApiClient { request ->
            throw ApiError.Http(401, "{}", request.method, request.path)
        }

        runCatching { RefreshingApiClient(delegate, tokens).execute(ApiRequest(path = "profile")) }

        assertNull(tokens.value)
    }

    @Test
    fun keepsCredentialWhenRefreshHitsANetworkError() {
        val tokens = MemoryTokens("valid")
        val delegate = ApiClient { request ->
            if (request.path == "auth/refresh") throw ApiError.Network(request.method, request.path, java.io.IOException("offline"))
            throw http401(request)
        }

        runCatching { RefreshingApiClient(delegate, tokens).execute(ApiRequest(path = "profile")) }

        assertEquals("valid", tokens.value)
    }

    @Test
    fun rotatesTheRefreshTokenWhenTheSessionHasOne() {
        val tokens = MemoryTokens("old").apply { refresh = "3.sid.r1" }
        val bodies = mutableListOf<String>()
        val delegate = ApiClient { request ->
            when {
                request.path == "sessions/token" -> {
                    bodies += String(request.body ?: ByteArray(0))
                    response("""{"token":"new","refreshToken":"3.sid.r2","expiresIn":900}""")
                }
                request.path == "auth/refresh" -> error("long-lived refresh must not be used")
                tokens.getToken() == "old" -> throw http401(request)
                else -> response("ok")
            }
        }

        assertEquals("ok", RefreshingApiClient(delegate, tokens).execute(ApiRequest(path = "profile")).bodyAsString())
        assertEquals(listOf("""{"refreshToken":"3.sid.r1"}"""), bodies)
        assertEquals("new", tokens.value)
        assertEquals("3.sid.r2", tokens.refresh)
    }

    @Test
    fun aRefusedRefreshTokenSignsOut() {
        val tokens = MemoryTokens("old").apply { refresh = "3.sid.stolen" }
        val delegate = ApiClient { request -> throw ApiError.Http(401, """{"code":"REFRESH_REUSED"}""", request.method, request.path) }

        runCatching { RefreshingApiClient(delegate, tokens).execute(ApiRequest(path = "profile")) }

        assertNull(tokens.value)
        assertNull(tokens.refresh)
    }

    private fun response(text: String) = ApiResponse(200, emptyMap(), text.toByteArray())

    private fun http401(request: ApiRequest) = ApiError.Http(401, "{}", request.method, request.path)
}

private class MemoryTokens(initial: String?) : TokenStore {
    var value: String? = initial
    var refresh: String? = null
    override fun saveToken(token: String) { value = token }
    override fun getToken(): String? = value
    override fun clearToken() { value = null; refresh = null }
    override fun saveRefreshToken(token: String) { refresh = token }
    override fun getRefreshToken(): String? = refresh
}