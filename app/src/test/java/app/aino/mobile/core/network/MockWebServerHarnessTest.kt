package app.aino.mobile.core.network

import java.util.TimeZone
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class MockWebServerHarnessTest {
    @Test
    fun streamed401RetryAndTokenRotationAllReportTheInstalledVersion() {
        MockWebServer().use { server ->
            server.start()
            val tokens = object : TokenStore {
                var access = "old-access"
                var refresh = "old-refresh"
                override fun getToken() = access
                override fun getRefreshToken() = refresh
                override fun saveToken(token: String) { access = token }
                override fun saveRefreshToken(token: String) { refresh = token }
                override fun clearToken() = org.junit.Assert.fail("A valid refresh must preserve the session")
            }
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"token":"new-access","refreshToken":"new-refresh"}"""))
            server.enqueue(MockResponse().setBody("{}"))
            val client = RefreshingApiClient(OkHttpApiClient(server.url("api/").toString(), tokens), tokens)
            client.execute(ApiRequest("POST", "chat/upload", streamBody = StreamBody("application/octet-stream", 4) { it.write("file".toByteArray()) }))
            val upload = server.takeRequest()
            val refresh = server.takeRequest()
            val retry = server.takeRequest()
            assertEquals(listOf("/api/chat/upload", "/api/sessions/token", "/api/chat/upload"), listOf(upload.path, refresh.path, retry.path))
            for (request in listOf(upload, refresh, retry)) {
                assertEquals("android", request.getHeader("X-AINO-Client"))
                assertEquals(app.aino.mobile.BuildConfig.VERSION_NAME, request.getHeader("X-AINO-Client-Version"))
            }
            assertEquals("file", retry.body.readUtf8())
            assertEquals("new-access", tokens.access)
            assertEquals("new-refresh", tokens.refresh)
        }
    }

    @Test
    fun requestHeadersCannotSpoofClientIdentityButCanUseAStepUpTokenOnEitherTransport() {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("api/").toString()
            for (client in listOf(
                OkHttpApiClient(base, TokenProvider { "stored" }),
                HttpUrlConnectionApiClient(base, TokenProvider { "stored" }),
            )) {
                server.enqueue(MockResponse().setBody("{}"))
                client.execute(ApiRequest("POST", "auth/biometric/enroll", headers = linkedMapOf(
                    "X-AINO-Client" to "web", "x-aino-client" to "desktop",
                    "X-AINO-Client-Version" to "0.0.0", "x-aino-client-version" to "1.0.0",
                    "Authorization" to "Bearer step-up",
                )))
                val request = server.takeRequest()
                assertEquals(listOf("android"), request.headers.values("X-AINO-Client"))
                assertEquals(listOf(app.aino.mobile.BuildConfig.VERSION_NAME), request.headers.values("X-AINO-Client-Version"))
                assertEquals("Bearer step-up", request.getHeader("Authorization"))
            }
        }
    }

    @Test
    fun everyTransportIdentifiesTheInstalledAndroidVersionEvenForAuthAndUploads() {
        MockWebServer().use { server ->
            server.start()
            val baseUrl = server.url("api/").toString()
            val clients = listOf(OkHttpApiClient(baseUrl), HttpUrlConnectionApiClient(baseUrl))
            for (client in clients) {
                for (request in listOf(
                    ApiRequest(path = "tasks"),
                    ApiRequest("POST", "auth/login", body = "{}".toByteArray()),
                    ApiRequest("POST", "tasks/1/comments", headers = mapOf("Content-Type" to "multipart/form-data; boundary=example"), body = "upload".toByteArray()),
                    ApiRequest("POST", "chat/upload", streamBody = StreamBody("application/octet-stream", 4) { it.write("file".toByteArray()) }),
                )) {
                    server.enqueue(MockResponse().setBody("{}"))
                    client.execute(request)
                    val received = server.takeRequest()
                    assertEquals("android", received.getHeader("X-AINO-Client"))
                    assertEquals(app.aino.mobile.BuildConfig.VERSION_NAME, received.getHeader("X-AINO-Client-Version"))
                }
            }
        }
    }

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
    fun callerSuppliedAuthorizationReplacesStoredToken() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
            server.start()
            val client = OkHttpApiClient(
                baseUrl = server.url("api/").toString(),
                tokenProvider = TokenProvider { "stored-token" },
                timeZoneProvider = { TimeZone.getTimeZone("UTC") },
                clock = { 0L },
            )

            client.execute(ApiRequest("POST", "auth/biometric/enroll", headers = mapOf("Authorization" to "Bearer step-up")))
            val request = server.takeRequest()

            assertEquals(listOf("Bearer step-up"), request.headers.values("Authorization"))
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