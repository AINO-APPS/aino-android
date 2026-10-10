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
import kotlinx.coroutines.flow.update
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
    private var everConnected = false
    private val _resync = MutableStateFlow(0L)

    val state: StateFlow<RealtimeState> = _state.asStateFlow()
    val events: SharedFlow<RealtimeEnvelope> = _events.asSharedFlow()

    /**
     * Bumped whenever events may have been missed: a reconnect after a drop
     * (the server keeps no replay log) or a full event buffer. Every live
     * screen then revalidates silently (Slack-style catch-up on reconnect).
     */
    val resync: StateFlow<Long> = _resync.asStateFlow()

    fun requestResync() { _resync.update { it + 1 } }

    /**
     * The network came back or the app returned to the foreground: skip the
     * remaining backoff, and replace a socket that has been silent longer than
     * a heartbeat (likely half-open after the device slept).
     */
    @Synchronized
    fun reconnectNow() {
        if (!shouldRun) return
        val current = socket
        if (current != null) {
            if (System.currentTimeMillis() - lastInboundAt.get() > HEARTBEAT_INTERVAL_MS + HEARTBEAT_TIMEOUT_MS) {
                retryCount = 0
                current.cancel() // onFailure performs the canonical reconnect.
            }
            return
        }
        reconnect?.cancel()
        reconnect = null
        retryCount = 0
        open(generation)
    }

    @Synchronized
    fun connect() {
        shouldRun = true
        if (socket != null || reconnect?.isActive == true) return
        open(++generation)
    }

    /**
     * Not needed for now (backgrounded, or no network): close without retrying
     * but remember the session, so the next [connect] counts as a reconnect
     * and screens catch up on what they missed. A terminal stop stays stopped.
     */
    @Synchronized
    fun pause() {
        if (_state.value is RealtimeState.Stopped || !shouldRun) return
        shouldRun = false
        generation++
        reconnect?.cancel()
        reconnect = null
        stopHeartbeat()
        socket?.close(1000, "Paused")
        socket = null
        retryCount = 0
        _state.value = RealtimeState.Disconnected
    }

    /**
     * The default network changed: the current socket is bound to a path that
     * no longer exists even if it was active a moment ago. Replace it now.
     */
    @Synchronized
    fun forceReconnect() {
        if (!shouldRun) return
        generation++
        reconnect?.cancel()
        reconnect = null
        stopHeartbeat()
        socket?.cancel()
        socket = null
        retryCount = 0
        open(generation)
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
        everConnected = false
        _state.value = RealtimeState.Disconnected
    }

    fun send(envelope: RealtimeEnvelope): Boolean = socket?.send(json.encodeToString(envelope)) == true

    fun close() {
        disconnect()
        scope.cancel()
    }

    @Synchronized
    private fun open(connectionGeneration: Long) {
        if (!shouldRun) {
            _state.value = RealtimeState.Disconnected
            return
        }
        _state.value = RealtimeState.Connecting(retryCount)
        // A handshake in flight is not "silent": don't let reconnectNow() cancel it as stale.
        lastInboundAt.set(System.currentTimeMillis())
        // The provider may refresh a near-expired token over the network: never on the caller's thread.
        reconnect = scope.launch {
            val token = tokens.getToken()
            synchronized(this@RealtimeClient) {
                reconnect = null
                if (!shouldRun || connectionGeneration != generation || socket != null) return@launch
                if (token.isNullOrBlank()) {
                    _state.value = RealtimeState.Disconnected
                    return@launch
                }
                val request = Request.Builder().url(realtimeUrl(baseUrl, token)).build()
                socket = client.newWebSocket(request, listener(connectionGeneration))
            }
        }
    }

    private fun listener(connectionGeneration: Long) = object : WebSocketListener() {
        private val closeHandled = java.util.concurrent.atomic.AtomicBoolean(false)

        private fun closeOnce(code: Int, reason: String) {
            if (closeHandled.compareAndSet(false, true)) handleClose(connectionGeneration, code, reason)
        }

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
                if (everConnected) _resync.update { it + 1 }
                everConnected = true
                startHeartbeat(connectionGeneration)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastInboundAt.set(System.currentTimeMillis())
            val envelope = runCatching { json.decodeFromString<RealtimeEnvelope>(text) }.getOrNull() ?: return
            if (envelope.type != "pong" && !_events.tryEmit(envelope)) _resync.update { it + 1 }
        }

        // OkHttp reports a server-initiated close here and only calls onClosed once the
        // close is echoed. Echo it and act now, or a 4001 (session ended) would hang until
        // the server drops the TCP connection and then look like a plain network failure.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            runCatching { webSocket.close(code, null) }
            closeOnce(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = closeOnce(code, reason)

        override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
            closeOnce(response?.code ?: 1006, error.message ?: "Connection failed")
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
        scheduleReconnect(connectionGeneration, code)
    }

    @Synchronized
    private fun scheduleReconnect(connectionGeneration: Long, code: Int) {
        reconnect?.cancel()
        val attempt = retryCount++
        val delayMs = if (isSlowRetryRealtimeClose(code)) slowRetryDelay(random()) else reconnectDelay(attempt, random())
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