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

    @Test
    fun upgradeRefusalDuringAHandshakeBlocksGloballyInsteadOfJustReconnecting() = runBlocking {
        val upgrades = app.aino.mobile.core.update.ClientUpgradeState()
        server.enqueue(MockResponse().setResponseCode(426).setBody(
            """{"code":"CLIENT_UPGRADE_REQUIRED","client":"android","min_version":"0.27.18","current_version":"0.27.17"}""",
        ))
        server.start()
        val client = RealtimeClient(
            tokens = { "token" },
            baseUrl = server.url("/ws").toString().replaceFirst("http", "ws"),
            upgrades = upgrades,
        )
        try {
            client.connect()
            val required = withTimeout(5_000) { upgrades.required.first { it?.minVersion == "0.27.18" } }
            assertEquals("0.27.18", required?.minVersion)
        } finally {
            client.close()
        }
    }

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
        val handshake = server.takeRequest()
        assertEquals("android", handshake.getHeader("X-AINO-Client"))
        assertEquals(app.aino.mobile.BuildConfig.VERSION_NAME, handshake.getHeader("X-AINO-Client-Version"))
        client.close()
    }
}
