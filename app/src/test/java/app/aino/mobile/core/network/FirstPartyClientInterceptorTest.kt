package app.aino.mobile.core.network

import app.aino.mobile.BuildConfig
import app.aino.mobile.core.update.ClientUpgradeState
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class FirstPartyClientInterceptorTest {
    @Test
    fun aRedirectToAThirdPartyDoesNotForwardClientIdentityOrApplyIts426() {
        MockWebServer().use { firstParty ->
            MockWebServer().use { thirdParty ->
                firstParty.start()
                thirdParty.start()
                val upgrades = ClientUpgradeState()
                val http = OkHttpClient.Builder()
                    .addNetworkInterceptor(FirstPartyClientInterceptor(firstParty.url("api/").toString(), upgrades))
                    .build()
                firstParty.enqueue(MockResponse().setResponseCode(302).setHeader("Location", thirdParty.url("image.png")))
                thirdParty.enqueue(MockResponse().setResponseCode(426))
                http.newCall(Request.Builder().url(firstParty.url("api/avatar")).build()).execute().close()
                val request = thirdParty.takeRequest()
                assertNull(request.getHeader("X-AINO-Client"))
                assertNull(request.getHeader("X-AINO-Client-Version"))
                assertNull(upgrades.required.value)
            }
        }
    }

    @Test
    fun rawMediaAndRedirectedRequestsReportVersionsAndBlockOn426WithoutAJsonRepository() {
        MockWebServer().use { server ->
            server.start()
            val upgrades = ClientUpgradeState()
            val http = OkHttpClient.Builder()
                .addNetworkInterceptor(FirstPartyClientInterceptor(server.url("api/").toString(), upgrades))
                .build()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/api/files/2"))
            server.enqueue(MockResponse().setResponseCode(426).setBody("""{"code":"CLIENT_UPGRADE_REQUIRED","client":"android","min_version":"0.27.18","current_version":"0.27.17"}"""))
            http.newCall(Request.Builder().url(server.url("api/files/1")).build()).execute().use {
                assertEquals(426, it.code)
            }
            repeat(2) {
                val request = server.takeRequest()
                assertEquals("android", request.getHeader("X-AINO-Client"))
                assertEquals(BuildConfig.VERSION_NAME, request.getHeader("X-AINO-Client-Version"))
            }
            assertEquals("0.27.18", upgrades.required.value?.minVersion)
        }
    }

    @Test
    fun thirdPartyRequestsReceiveNoClientIdentityAndCannotBlockTheApp() {
        MockWebServer().use { firstParty ->
            MockWebServer().use { thirdParty ->
                firstParty.start()
                thirdParty.start()
                val upgrades = ClientUpgradeState()
                val http = OkHttpClient.Builder()
                    .addNetworkInterceptor(FirstPartyClientInterceptor(firstParty.url("api/").toString(), upgrades))
                    .build()
                thirdParty.enqueue(MockResponse().setResponseCode(426).setBody("Not an AINO upgrade"))
                http.newCall(Request.Builder().url(thirdParty.url("image.png")).build()).execute().close()
                val request = thirdParty.takeRequest()
                assertNull(request.getHeader("X-AINO-Client"))
                assertNull(request.getHeader("X-AINO-Client-Version"))
                assertNull(upgrades.required.value)
            }
        }
    }
}
