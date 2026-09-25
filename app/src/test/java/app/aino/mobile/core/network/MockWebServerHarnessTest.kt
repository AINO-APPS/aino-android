package app.aino.mobile.core.network

import java.util.TimeZone
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class MockWebServerHarnessTest {
    @Test
    fun clientSendsPlatformHeadersAndParsesResponse() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            server.start()
            val client = OkHttpApiClient(
                baseUrl = server.url("api/").toString(),
                tokenProvider = TokenProvider { "test-token" },
                timeZoneProvider = { TimeZone.getTimeZone("UTC") },
                clock = { 0L },
            )

            val response = client.execute(ApiRequest(path = "health"))
            val request = server.takeRequest()

            assertEquals(200, response.statusCode)
            assertEquals("{\"ok\":true}", response.bodyAsString())
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertEquals("/api/health", request.path)
        }
    }

    @Test
    fun bodylessPostSendsAnEmptyBodyInsteadOfThrowing() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            server.start()
            val client = OkHttpApiClient(
                baseUrl = server.url("api/").toString(),
                tokenProvider = TokenProvider { "test-token" },
                timeZoneProvider = { TimeZone.getTimeZone("UTC") },
                clock = { 0L },
            )

            // Previously: IllegalArgumentException "method POST must have a request body."
            client.execute(ApiRequest("POST", "chat/conversations/5/read"))
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals(0L, request.bodySize)
        }
    }
}