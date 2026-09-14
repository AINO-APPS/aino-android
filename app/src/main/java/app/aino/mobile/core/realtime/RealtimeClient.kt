package app.aino.mobile.core.realtime

import app.aino.mobile.core.network.NetworkConfig
import app.aino.mobile.core.network.TokenProvider
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RealtimeClient(
    private val tokens: TokenProvider,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: String = NetworkConfig.webSocketUrl,
    private val random: () -> Double = Random::nextDouble,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<RealtimeState>(RealtimeState.Disconnected)
    private val _events = MutableSharedFlow<RealtimeEnvelope>(extraBufferCapacity = 64)
    private val lastInboundAt = AtomicLong(0)
    private var socket: WebSocket? = null
    private var heartbeat: Job? = null
    private var reconnect: Job? = null
    private var generation = 0L
    private var shouldRun = false
    private var retryCount = 0

    val state: StateFlow<RealtimeState> = _state.asStateFlow()
    val events: SharedFlow<RealtimeEnvelope> = _events.asSharedFlow()

    @Synchronized
    fun connect() {
        shouldRun = true
        if (socket != null || reconnect?.isActive == true) return
        open(++generation)
    }

    @Synchronized
    fun disconnect() {
        shouldRun = false
        generation++
        reconnect?.cancel()
        stopHeartbeat()
        socket?.close(1000, "Client signed out")
        socket = null
        retryCount = 0
        _state.value = RealtimeState.Disconnected
    }

    fun send(envelope: RealtimeEnvelope): Boolean = socket?.send(json.encodeToString(envelope)) == true

    fun close() {
        disconnect()
        scope.cancel()
    }

    @Synchronized
    private fun open(connectionGeneration: Long) {
        val token = tokens.getToken()
        if (!shouldRun || token.isNullOrBlank()) {
            _state.value = RealtimeState.Disconnected
            return
        }
        _state.value = RealtimeState.Connecting(retryCount)
        val request = Request.Builder().url(realtimeUrl(baseUrl, token)).build()
        socket = client.newWebSocket(request, listener(connectionGeneration))
    }

    private fun listener(connectionGeneration: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@RealtimeClient) {
                if (connectionGeneration != generation || !shouldRun) {
                    webSocket.close(1000, "Superseded")
                    return
                }
                socket = webSocket
                retryCount = 0
                lastInboundAt.set(System.currentTimeMillis())
                _state.value = RealtimeState.Connected
                startHeartbeat(connectionGeneration)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastInboundAt.set(System.currentTimeMillis())
            val envelope = runCatching { json.decodeFromString<RealtimeEnvelope>(text) }.getOrNull() ?: return
            if (envelope.type != "pong") _events.tryEmit(envelope)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = handleClose(connectionGeneration, code, reason)

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            handleClose(connectionGeneration, response?.code ?: 1006, error.message ?: "Connection failed")
        }
    }

    @Synchronized
    private fun handleClose(connectionGeneration: Long, code: Int, reason: String) {
        if (connectionGeneration != generation) return
        socket = null
        stopHeartbeat()
        if (!shouldRun) {
            _state.value = RealtimeState.Disconnected
            return
        }
        if (isTerminalRealtimeClose(code)) {
            shouldRun = false
            _state.value = RealtimeState.Stopped(code, reason)
            return
        }
        scheduleReconnect(connectionGeneration)
    }

    @Synchronized
    private fun scheduleReconnect(connectionGeneration: Long) {
        reconnect?.cancel()
        val attempt = retryCount++
        val delayMs = reconnectDelay(attempt, random())
        _state.value = RealtimeState.Connecting(attempt)
        reconnect = scope.launch {
            delay(delayMs)
            synchronized(this@RealtimeClient) {
                reconnect = null
                if (shouldRun && connectionGeneration == generation && socket == null) open(connectionGeneration)
            }
        }
    }

    private fun startHeartbeat(connectionGeneration: Long) {
        stopHeartbeat()
        heartbeat = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val sentAt = System.currentTimeMillis()
                val current = synchronized(this@RealtimeClient) { socket }
                if (connectionGeneration != generation || current?.send("{\"type\":\"ping\"}") != true) break
                delay(HEARTBEAT_TIMEOUT_MS)
                if (lastInboundAt.get() < sentAt) {
                    current.cancel() // onFailure performs canonical reconnect.
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeat?.cancel()
        heartbeat = null
    }

    private companion object {
        const val HEARTBEAT_INTERVAL_MS = 25_000L
        const val HEARTBEAT_TIMEOUT_MS = 10_000L
    }
}