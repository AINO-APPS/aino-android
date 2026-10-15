package app.aino.mobile.core.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import okio.Buffer

/**
 * A chat video's poster frame (Signal shows a thumbnail, never downloads the
 * whole video for it). [url] is the resolved remote URL or a local content/file URI.
 */
data class VideoPoster(val url: String)

/** Disk/memory cache key of a poster; the upload path seeds it from the local file. */
fun videoPosterCacheKey(url: String): String = "poster:$url"

private const val POSTER_MAX_SIDE = 720
private const val POSTER_FRAME_US = 100_000L
private const val POSTER_QUALITY = 80

internal fun isRemoteMediaUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")

/**
 * Reads only the bytes the frame needs (MediaMetadataRetriever range requests),
 * then keeps the JPEG in Coil's disk cache. Failures throw so the caller can fall
 * back to the full-download `VideoFrameDecoder` path (which also refreshes a 401).
 */
class VideoPosterFetcher(
    private val data: VideoPoster,
    private val context: Context,
    private val diskCache: DiskCache?,
    private val headersFor: (String) -> Map<String, String>,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val remote = isRemoteMediaUrl(data.url)
        val key = videoPosterCacheKey(data.url)
        if (remote) diskCache?.openSnapshot(key)?.let { return it.toResult(diskCache, key) }
        val bitmap = extractVideoPoster(context, data.url, if (remote) headersFor(data.url) else emptyMap())
            ?: error("No poster frame for ${data.url}")
        val bytes = bitmap.toJpeg().also { bitmap.recycle() }
        if (remote && diskCache != null) {
            writePosterBytes(diskCache, key, bytes)?.let { return it.toResult(diskCache, key) }
        }
        return SourceFetchResult(ImageSource(Buffer().write(bytes), okio.FileSystem.SYSTEM), "image/jpeg", DataSource.NETWORK)
    }

    private fun DiskCache.Snapshot.toResult(cache: DiskCache, key: String) = SourceFetchResult(
        ImageSource(data, cache.fileSystem, key, this), "image/jpeg", DataSource.DISK,
    )

    class Factory(
        private val context: Context,
        private val headersFor: (String) -> Map<String, String>,
    ) : Fetcher.Factory<VideoPoster> {
        override fun create(data: VideoPoster, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoPosterFetcher(data, context, imageLoader.diskCache, headersFor)
    }

    class PosterKeyer : Keyer<VideoPoster> {
        override fun key(data: VideoPoster, options: Options): String = videoPosterCacheKey(data.url)
    }
}

/** First frame (~0.1 s) scaled to fit [POSTER_MAX_SIDE]; null when the source has none. Blocking. */
fun extractVideoPoster(context: Context, url: String, headers: Map<String, String> = emptyMap()): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        if (isRemoteMediaUrl(url)) retriever.setDataSource(url, headers)
        else retriever.setDataSource(context, Uri.parse(url))
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            retriever.getScaledFrameAtTime(POSTER_FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, POSTER_MAX_SIDE, POSTER_MAX_SIDE)
        } else {
            retriever.getFrameAtTime(POSTER_FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::scaleToPosterSize)
        }
        frame ?: retriever.frameAtTime?.let(::scaleToPosterSize)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun scaleToPosterSize(bitmap: Bitmap): Bitmap {
    val longSide = max(bitmap.width, bitmap.height)
    if (longSide <= POSTER_MAX_SIDE) return bitmap
    val ratio = POSTER_MAX_SIDE.toFloat() / longSide
    val scaled = Bitmap.createScaledBitmap(
        bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1), (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true,
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

private fun Bitmap.toJpeg(): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, POSTER_QUALITY, it) }.toByteArray()

/**
 * Writes [bytes] as a manual Coil disk-cache entry under [key]. Empty metadata
 * marks it as hand-written, which Coil's network fetcher serves as-is. Blocking.
 */
fun writePosterBytes(cache: DiskCache, key: String, bytes: ByteArray): DiskCache.Snapshot? {
    val editor = cache.openEditor(key) ?: return null
    return try {
        cache.fileSystem.write(editor.data) { write(bytes) }
        cache.fileSystem.write(editor.metadata) {}
        editor.commitAndOpenSnapshot()
    } catch (_: Exception) {
        runCatching { editor.abort() }
        null
    }
}

/** Seeds the poster of a just-uploaded video so the delivered bubble never waits on the network. Blocking. */
fun seedVideoPoster(context: Context, cache: DiskCache, remoteUrl: String, local: Uri) {
    val bitmap = extractVideoPoster(context, local.toString()) ?: return
    val bytes = bitmap.toJpeg().also { bitmap.recycle() }
    writePosterBytes(cache, videoPosterCacheKey(remoteUrl), bytes)?.close()
}

/** Seeds Coil's disk cache entry for [remoteUrl] (the key its network fetcher reads) with already-local bytes. Blocking. */
fun seedImageCache(cache: DiskCache, remoteUrl: String, open: () -> java.io.InputStream) {
    val editor = cache.openEditor(remoteUrl) ?: return
    try {
        open().use { input -> cache.fileSystem.write(editor.data) { input.copyTo(outputStream()) } }
        cache.fileSystem.write(editor.metadata) {}
        editor.commit()
    } catch (_: Exception) {
        runCatching { editor.abort() }
    }
}
