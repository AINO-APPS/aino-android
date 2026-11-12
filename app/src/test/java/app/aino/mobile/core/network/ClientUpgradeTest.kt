package app.aino.mobile.core.network

import app.aino.mobile.core.update.ClientUpgradeState
import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ClientUpgradeTest {
    private val refusal = """{"code":"CLIENT_UPGRADE_REQUIRED","client":"android","min_version":"0.27.18","current_version":"0.27.17"}"""

    @Test
    fun a426WithAnUnreadableBodyStillBlocksInsteadOfLookingOffline() {
        MockWebServer().use { server ->
            server.start()
            val okUpgrades = ClientUpgradeState()
            val urlUpgrades = ClientUpgradeState()
            for (client in listOf(
                OkHttpApiClient(server.url("api/").toString(), upgrades = okUpgrades),
                HttpUrlConnectionApiClient(server.url("api/").toString(), upgrades = urlUpgrades),
            )) {
                server.enqueue(MockResponse().setResponseCode(426).setBody("x".repeat(8192))
                    .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
                runCatching { client.execute(ApiRequest(path = "tasks")) }
                server.takeRequest()
            }
            assertNotNull(okUpgrades.required.value)
            assertNotNull(urlUpgrades.required.value)
        }
    }

    @Test
    fun anyHttpTransportPublishesUpgradeRequiredBeforeItsCallerHandlesTheError() {
        MockWebServer().use { server ->
            server.start()
            val upgrades = ClientUpgradeState()
            val clients = listOf(
                OkHttpApiClient(server.url("api/").toString(), upgrades = upgrades),
                HttpUrlConnectionApiClient(server.url("api/").toString(), upgrades = upgrades),
            )
            for (client in clients) {
                server.enqueue(MockResponse().setResponseCode(426).setBody(refusal))
                val error = runCatching { client.execute(ApiRequest("POST", "auth/login")) }.exceptionOrNull()
                assertEquals(426, (error as ApiError.Http).statusCode)
                assertEquals("0.27.18", upgrades.required.value?.minVersion)
                assertEquals("android", upgrades.required.value?.client)
                server.takeRequest()
            }
        }
    }

    @Test
    fun refresh426DoesNotSignOutOrHideTheUpgradeBehindCachedSuccess() {
        val upgrades = ClientUpgradeState()
        val tokens = object : TokenStore {
            override fun getToken() = "old-access"
            override fun getRefreshToken() = "refresh-token"
            override fun saveToken(token: String) = fail("A refused refresh must not rotate credentials")
            override fun clearToken() = fail("426 must not sign out")
        }
        val requests = mutableListOf<String>()
        val fake = ApiClient {
            requests += it.path
            throw ApiError.Http(if (it.path == "sessions/token") 426 else 401, refusal, it.method, it.path)
        }
        val refreshing = RefreshingApiClient(UpgradeAwareApiClient(fake, upgrades), tokens)
        assertTrue(runCatching { refreshing.execute(ApiRequest(path = "tasks")) }.exceptionOrNull() is ApiError.Http)
        assertEquals(listOf("tasks", "sessions/token"), requests)
        assertEquals("0.27.18", upgrades.required.value?.minVersion)

        UpgradeAwareApiClient(ApiClient { ApiResponse(200, emptyMap(), "{}".toByteArray()) }, upgrades)
            .execute(ApiRequest(path = "auth/me"))
        assertNotNull(upgrades.required.value)
    }
}
