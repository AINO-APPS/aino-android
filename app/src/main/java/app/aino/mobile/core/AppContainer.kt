package app.aino.mobile.core

import android.content.Context
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import app.aino.mobile.core.network.standardHeaders
import app.aino.mobile.core.network.timezoneOffsetMinutes
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Process-wide singletons. One [api] instance matters for correctness, not just
 * efficiency: [RefreshingApiClient] serialises token refresh with a per-instance
 * lock, so separate instances per ViewModel could race two refreshes and the
 * loser would clear the rotated token (forced sign-out).
 */
class AppContainer private constructor(private val context: Context) {
    init {
        app.aino.mobile.core.network.DeviceId.value = app.aino.mobile.core.network.DeviceId.load(context)
    }

    val tokens = KeystoreTokenStore(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Un-refreshing client for the auth flow itself (login, refresh). */
    val rawApi: ApiClient = OkHttpApiClient(tokenProvider = tokens, client = http)

    /** Last successful GET bodies of the signed-in user (scope set by AuthViewModel). */
    val responses = app.aino.mobile.core.network.ResponseCache(context.cacheDir.resolve("api-cache"))
    val api: ApiClient = app.aino.mobile.core.network.CachingApiClient(RefreshingApiClient(rawApi, tokens), responses)

    /** Serves GETs from [responses] only: repositories rebuild the last state instantly with it. */
    val cachedApi: ApiClient = app.aino.mobile.core.network.CacheOnlyApiClient(responses)

    private val apiHost: String = runCatching { java.net.URI(app.aino.mobile.core.network.NetworkConfig.apiUrl).host }.getOrNull().orEmpty()

    /**
     * Adds the same bearer/timezone headers as the JSON API to media fetches
     * (images, audio, video, files) on the AINO origin only. Third-party URLs
     * (GIPHY, link-preview images) must never receive the session token.
     */
    val mediaHttp: OkHttpClient = http.newBuilder()
        .addInterceptor { chain ->
            val original = chain.request()
            if (!original.url.host.equals(apiHost, ignoreCase = true)) return@addInterceptor chain.proceed(original)
            val headers = standardHeaders(tokens.getToken(), timezoneOffsetMinutes(TimeZone.getDefault(), System.currentTimeMillis()))
            val request = original.newBuilder().apply { headers.forEach(::header) }.build()
            chain.proceed(request)
        }
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { mediaHttp }))
            add(VideoFrameDecoder.Factory())
            // GIFs / stickers from the web's GIPHY picker animate in chat.
            if (android.os.Build.VERSION.SDK_INT >= 28) add(coil3.gif.AnimatedImageDecoder.Factory()) else add(coil3.gif.GifDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("image-cache")).maxSizeBytes(128L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

    /** P10.4: the org logo + accent (web `BrandingContext`). */
    val branding: app.aino.mobile.core.branding.BrandingStore by lazy {
        app.aino.mobile.core.branding.BrandingStore(app.aino.mobile.core.branding.BrandingRepository(api))
    }

    /** Lazily created on first (main-thread) use from the UI. */
    val audio: app.aino.mobile.core.media.SharedAudioPlayer by lazy {
        app.aino.mobile.core.media.SharedAudioPlayer(context, mediaHttp)
    }
    val audioDurations: app.aino.mobile.core.media.AudioDurationCache by lazy {
        app.aino.mobile.core.media.AudioDurationCache(context, mediaHttp)
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
    }
}
