package app.aino.mobile.core.realtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeClientCloseTest {
    private val server = MockWebServer()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun serverSessionEndStopsTheClientRightAwayWithItsReason() = runBlocking {
        // The server closes the socket and waits for the echo (it never drops TCP itself).
        server.enqueue(
            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.close(4001, "Signed in on another device")
                }
            }),
        )
        server.start()
        val client = RealtimeClient(
            tokens = { "token" },
            baseUrl = server.url("/ws").toString().replaceFirst("http", "ws"),
        )
        client.connect()

        val stopped = withTimeout(5_000) { client.state.first { it is RealtimeState.Stopped } } as RealtimeState.Stopped

        assertEquals(4001, stopped.code)
        assertEquals("Signed in on another device", stopped.reason)
        client.close()
    }
}
