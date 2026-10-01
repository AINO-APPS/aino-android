package app.aino.mobile.core.network

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseCacheTest {
    private fun cache() = ResponseCache(Files.createTempDirectory("api-cache").toFile())

    private fun json(body: String) = ApiResponse(200, mapOf("content-type" to listOf("application/json; charset=utf-8")), body.toByteArray())

    @Test
    fun recordsJsonGetsPerUserAndServesThemOffline() {
        val responses = cache().apply { scope = "1_7" }
        val live = CachingApiClient(ApiClient { json("""{"ok":true}""") }, responses)
        live.execute(ApiRequest(path = "tracker/status"))

        val warm = CacheOnlyApiClient(responses)
        assertArrayEquals("""{"ok":true}""".toByteArray(), warm.execute(ApiRequest(path = "tracker/status")).body)
        assertThrows(ApiError.Network::class.java) { warm.execute(ApiRequest(path = "tasks")) }

        // Another user never sees this user's responses.
        responses.scope = "1_8"
        assertNull(responses.get("tracker/status"))
    }

    @Test
    fun skipsWritesPagesNonJsonAndSignedOutState() {
        val responses = cache().apply { scope = "1_7" }
        val live = CachingApiClient(ApiClient { request ->
            if (request.path.endsWith(".pdf")) ApiResponse(200, mapOf("content-type" to listOf("application/pdf")), ByteArray(3))
            else json("[]")
        }, responses)
        live.execute(ApiRequest(method = "POST", path = "tasks", body = ByteArray(0)))
        live.execute(ApiRequest(path = "chat/conversations/4/messages?limit=50&before=10"))
        live.execute(ApiRequest(path = "slip.pdf"))
        assertNull(responses.get("tasks"))
        assertNull(responses.get("chat/conversations/4/messages?limit=50&before=10"))
        assertNull(responses.get("slip.pdf"))

        responses.scope = null
        live.execute(ApiRequest(path = "profile"))
        responses.scope = "1_7"
        assertNull(responses.get("profile"))
    }

    @Test
    fun clearAllForgetsEverything() {
        val responses = cache().apply { scope = "1_7" }
        responses.put("profile", "{}".toByteArray())
        responses.clearAll()
        assertNull(responses.get("profile"))
        responses.put("profile", "{}".toByteArray())
        assertEquals("{}", responses.get("profile")?.toString(Charsets.UTF_8))
    }
}
