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
    fun loginHydratesFeatureGatesFromProfileImmediately() {
        val paths = mutableListOf<String>()
        val repository = AuthRepository(FakeApiClient { request ->
            paths += request.path
            when (request.path) {
                "auth/login" -> response("""{"user":{"id":9,"username":"user","role":"employee","tenant_id":3},"token":"jwt"}""")
                "profile" -> response("""{"id":9,"username":"user","role":"employee","tenant_id":3,"tenant_features":{"attendance":true,"tasks":true,"chat":false}}""")
                else -> error("unexpected ${request.path}")
            }
        }, MemoryTokenStore())

        val state = repository.login("user", "secret") as AuthState.Authenticated

        assertEquals(listOf("auth/login", "profile"), paths)
        assertEquals(true, state.user.tenantFeatures["attendance"])
        assertEquals(true, state.user.tenantFeatures["tasks"])
        assertEquals(false, state.user.tenantFeatures["chat"])
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
        assertEquals(false, repository.hasStoredCredential())
    }

    @Test
    fun transientRestoreFailurePreservesCredentialAndScopedCacheEligibility() {
        val store = MemoryTokenStore().apply { saveToken("still-valid") }
        val repository = AuthRepository(FakeApiClient {
            throw app.aino.mobile.core.network.ApiError.Network("GET", "url", java.io.IOException("offline"))
        }, store)

        runCatching(repository::restoreSession)

        assertEquals(true, repository.hasStoredCredential())
        assertEquals("still-valid", store.value)
    }

    @Test
    fun biometricEnrollmentUsesAndroidPlatformAndRequiresTenantCredential() {
        var body = ""
        val repository = AuthRepository(FakeApiClient { request ->
            body = request.body?.toString(Charsets.UTF_8).orEmpty()
            response("""{"credentialId":"42.credential","deviceSecret":"secret"}""")
        }, MemoryTokenStore())

        val credential = repository.enrollBiometric("Pixel 9")

        assertEquals("42.credential", credential.credentialId)
        assertTrue(body.contains("\"platform\":\"android\""))
        assertTrue(body.contains("\"deviceLabel\":\"Pixel 9\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun biometricEnrollmentRejectsPlatformMasterCredential() {
        val repository = AuthRepository(FakeApiClient {
            response("""{"credentialId":"0.credential","deviceSecret":"secret"}""")
        }, MemoryTokenStore())

        repository.enrollBiometric("Android device")
    }

    @Test
    fun biometricLoginStoresReturnedBearerToken() {
        val store = MemoryTokenStore()
        var body = ""
        val repository = AuthRepository(FakeApiClient { request ->
            if (request.path == "profile") {
                response("""{"id":4,"username":"member","role":"employee","tenant_id":42,"tenant_features":{"chat":true}}""")
            } else {
                body = request.body?.toString(Charsets.UTF_8).orEmpty()
                response("""{"user":{"id":4,"username":"member","role":"employee","tenant_id":42},"token":"bio-jwt"}""")
            }
        }, store)

        val state = repository.biometricLogin(BiometricCredential("42.credential", "secret"))

        assertTrue(state is AuthState.Authenticated)
        assertEquals("bio-jwt", store.value)
        assertTrue(body.contains("\"credentialId\":\"42.credential\""))
        assertTrue(body.contains("\"deviceSecret\":\"secret\""))
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