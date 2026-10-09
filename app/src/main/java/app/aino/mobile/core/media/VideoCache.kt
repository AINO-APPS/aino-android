package app.aino.mobile.core.media

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import java.io.File
import okhttp3.OkHttpClient

/**
 * Disk cache for streamed chat videos: a video plays from local bytes on every
 * open after the first, and the first open starts as soon as a fraction of a
 * second is buffered instead of ExoPlayer's 2.5 s default.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object VideoCache {
    private const val MAX_BYTES = 256L * 1024 * 1024

    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "video-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Authenticated network reads through the disk cache; content:// and file:// read directly. */
    fun dataSourceFactory(context: Context, http: OkHttpClient): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Start playback quickly; rebuffer a little longer so a slow link doesn't stutter. */
    fun quickStartLoadControl(): LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(2_000, 30_000, 250, 1_000)
        .build()

    /** The previous account's videos must not survive sign-out. */
    fun clear(context: Context) {
        runCatching { cache(context).let { current -> current.keys.toList().forEach(current::removeResource) } }
    }
}
