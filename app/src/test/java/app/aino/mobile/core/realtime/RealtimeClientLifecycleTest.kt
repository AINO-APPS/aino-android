package app.aino.mobile.core.realtime

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RealtimeClientLifecycleTest {
    private val server = MockWebServer()
    private lateinit var client: RealtimeClient

    private fun acceptSocket() = server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }))

    @Before fun setUp() {
        repeat(3) { acceptSocket() }
        server.start()
        client = RealtimeClient(tokens = { "token" }, baseUrl = server.url("/ws").toString().replaceFirst("http", "ws"))
    }

    @After fun tearDown() {
        client.close()
        server.shutdown()
    }

    private fun awaitConnected() = runBlocking { withTimeout(5_000) { client.state.first { it == RealtimeState.Connected } } }

    @Test fun `a paused session reconnects as a catch-up`() {
        client.connect()
        awaitConnected()
        assertEquals(0L, client.resync.value)

        client.pause()
        assertEquals(RealtimeState.Disconnected, client.state.value)

        client.connect()
        awaitConnected()
        runBlocking { withTimeout(5_000) { client.resync.first { it == 1L } } }
        assertEquals(2, server.requestCount)
    }

    @Test fun `a network change replaces a socket that still looks open`() {
        client.connect()
        awaitConnected()

        client.forceReconnect()
        awaitConnected()
        runBlocking { withTimeout(5_000) { client.resync.first { it == 1L } } }
        assertEquals(2, server.requestCount)
    }

    @Test fun `nothing reconnects while paused or signed out`() {
        client.pause()
        client.forceReconnect()
        assertEquals(RealtimeState.Disconnected, client.state.value)
        assertEquals(0, server.requestCount)
    }
}
