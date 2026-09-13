package app.aino.mobile.core.auth

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.network.TokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun loginStoresTokenAndRequiresPasswordChange() {
        val tokenStore = MemoryTokenStore()
        val repository = AuthRepository(FakeApiClient {
            response("""{"user":{"id":9,"username":"user","role":"employee","must_change_password":true},"token":"jwt"}""")
        }, tokenStore)

        val state = repository.login(" user ", "secret")

        assertTrue(state is AuthState.PasswordChangeRequired)
        assertEquals("jwt", tokenStore.value)
    }

    @Test
    fun loginMapsRealmChoiceConflict() {
        val repository = AuthRepository(FakeApiClient {
            throw ApiError.Http(
                409,
                """{"error":"Choose where you want to sign in.","code":"REALM_CHOICE_REQUIRED","login_ticket":"ticket","realms":[{"realm":"tenant","label":"Workspace","default":true}]}""",
                "POST",
                "https://next.aino.org.in/api/auth/login",
            )
        }, MemoryTokenStore())

        val state = repository.login("user", "secret") as AuthState.ChoosingRealm

        assertEquals("ticket", state.ticket)
        assertEquals("tenant", state.realms.single().realm)
    }

    @Test
    fun platformRedirectFailsClosed() {
        val repository = AuthRepository(FakeApiClient {
            response("""{"redirect":"https://console.aino.org.in/auth/handoff#t=secret"}""")
        }, MemoryTokenStore())

        val failure = runCatching { repository.login("admin", "secret") }.exceptionOrNull()

        assertTrue(failure is AuthFailure)
        assertEquals("PLATFORM_CONSOLE_REQUIRED", (failure as AuthFailure).code)
    }

    @Test
    fun passwordValidationMatchesPlatformRules() {
        assertEquals("New passwords do not match", validatePasswordChange("old", "NewPass1!", "different"))
        assertEquals("New password must be at least 8 characters", validatePasswordChange("old", "short", "short"))
        assertEquals("New password must be different from current password", validatePasswordChange("SamePass1!", "SamePass1!", "SamePass1!"))
        assertEquals(null, validatePasswordChange("OldPass1!", "NewPass1!", "NewPass1!"))
    }

    @Test
    fun logoutSendsAnExplicitEmptyPostBodyAndClearsToken() {
        val store = MemoryTokenStore().apply { saveToken("jwt") }
        var captured: ApiRequest? = null
        val repository = AuthRepository(FakeApiClient { request ->
            captured = request
            response("{}")
        }, store)

        repository.logout()

        assertEquals("POST", captured?.method)
        assertEquals("auth/logout", captured?.path)
        assertEquals(0, captured?.body?.size)
        assertEquals(null, store.value)
    }

    @Test
    fun passwordChangeSurfacesTheServerError() {
        val repository = AuthRepository(FakeApiClient {
            throw ApiError.Http(400, """{"error":"Current password is incorrect"}""", "PUT", "url")
        }, MemoryTokenStore())

        val failure = runCatching { repository.changePassword("wrong", "NewPass1!") }.exceptionOrNull()

        assertEquals("Current password is incorrect", failure?.message)
    }

    @Test
    fun restoreSessionHydratesTheUserFromProfile() {
        val store = MemoryTokenStore().apply { saveToken("jwt") }
        var captured: ApiRequest? = null
        val repository = AuthRepository(FakeApiClient { request ->
            captured = request
            response("""{"id":4,"username":"member","role":"employee","must_change_password":false}""")
        }, store)

        val state = repository.restoreSession()

        assertTrue(state is AuthState.Authenticated)
        assertEquals("profile", captured?.path)
        assertEquals("GET", captured?.method)
    }

    @Test
    fun restoreSessionClearsAnExpiredCredential() {
        val store = MemoryTokenStore().apply { saveToken("expired") }
        val repository = AuthRepository(FakeApiClient {
            throw ApiError.Http(401, """{"error":"Session expired"}""", "GET", "url")
        }, store)

        runCatching(repository::restoreSession)

        assertEquals(null, store.value)
    }

    private fun response(json: String) = ApiResponse(200, emptyMap(), json.toByteArray())
}

private class FakeApiClient(private val block: (ApiRequest) -> ApiResponse) : ApiClient {
    override fun execute(request: ApiRequest): ApiResponse = block(request)
}

private class MemoryTokenStore : TokenStore {
    var value: String? = null
    override fun saveToken(token: String) { value = token }
    override fun getToken(): String? = value
    override fun clearToken() { value = null }
}