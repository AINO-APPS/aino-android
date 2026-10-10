package app.aino.mobile.core.call.webrtc

import android.util.Log
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.json.Json

/**
 * TURN/STUN servers for a call. Real relay credentials are cached until shortly
 * before they expire and prefetched while the app is connected, so a call never
 * waits on this request and never falls back to the throttled public relay just
 * because one request failed.
 */
class IceConfigRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    fun load(): IceConfigDto {
        cachedIfFresh()?.let { return it }
        val config = runCatching { fetch() }.getOrElse { error ->
            if (error !is ApiError.Network) throw error
            fetch() // One quick retry: a network change mid-request is common on mobile.
        }
        runCatching { Log.i(TAG, "ICE config mode=${config.mode} realTurn=${hasRealTurn(config)}") }
        if (hasRealTurn(config)) cache = Cached(config, clockMs())
        return config
    }

    /** Warms the cache; failures are ignored (the call path loads again). */
    fun prefetch() {
        runCatching { load() }
    }

    private fun fetch(): IceConfigDto {
        val response = api.execute(ApiRequest(path = "chat/ice-config"))
        return json.decodeFromString<IceConfigDto>(response.bodyAsString()).let { config ->
            config.copy(iceServers = applyPublicTurnPolicy(config.iceServers, config.allowPublicFallback))
        }
    }

    private fun cachedIfFresh(): IceConfigDto? {
        val entry = cache ?: return null
        val now = clockMs()
        val fresh = entry.config.expiresAt?.let { expiresAt -> expiresAt * 1000 - now > MIN_REMAINING_MS }
            ?: (now - entry.fetchedAtMs < NO_EXPIRY_CACHE_MS)
        return entry.config.takeIf { fresh }
    }

    private data class Cached(val config: IceConfigDto, val fetchedAtMs: Long)

    companion object {
        private const val TAG = "IceConfig"

        /** Credentials must outlive a long call: refetch when less than this remains. */
        const val MIN_REMAINING_MS = 2 * 60 * 60 * 1000L
        const val NO_EXPIRY_CACHE_MS = 10 * 60 * 1000L

        @Volatile private var cache: Cached? = null

        /** Signed out / switched account: never reuse another session's relay credentials. */
        fun clearCache() {
            cache = null
        }
    }
}
