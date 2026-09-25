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
    val tokens = KeystoreTokenStore(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** Un-refreshing client for the auth flow itself (login, refresh). */
    val rawApi: ApiClient = OkHttpApiClient(tokenProvider = tokens, client = http)
    val api: ApiClient = RefreshingApiClient(rawApi, tokens)

    /** Adds the same bearer/timezone headers as the JSON API to media fetches (images, audio, video, files). */
    val mediaHttp: OkHttpClient = http.newBuilder()
        .addInterceptor { chain ->
            val headers = standardHeaders(tokens.getToken(), timezoneOffsetMinutes(TimeZone.getDefault(), System.currentTimeMillis()))
            val request = chain.request().newBuilder().apply { headers.forEach(::header) }.build()
            chain.proceed(request)
        }
        .build()

    val imageLoader: ImageLoader = ImageLoader.Builder(context)
        .components {
            add(OkHttpNetworkFetcherFactory(callFactory = { mediaHttp }))
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
        .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("image-cache")).maxSizeBytes(128L * 1024 * 1024).build() }
        .crossfade(true)
        .build()

    /** Lazily created on first (main-thread) use from the UI. */
    val audio: app.aino.mobile.core.media.SharedAudioPlayer by lazy {
        app.aino.mobile.core.media.SharedAudioPlayer(context, mediaHttp)
    }

    companion object {
        @Volatile private var instance: AppContainer? = null

        fun get(context: Context): AppContainer = instance ?: synchronized(this) {
            instance ?: AppContainer(context.applicationContext).also { instance = it }
        }
    }
}
