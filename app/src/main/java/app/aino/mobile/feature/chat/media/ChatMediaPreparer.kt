package app.aino.mobile.feature.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** A send-ready file: what the multipart body streams. [release] deletes any temporary output. */
class PreparedMedia(
    val fileName: String,
    val mimeType: String,
    val length: Long,
    val width: Int? = null,
    val height: Int? = null,
    val open: () -> InputStream,
    private val temporary: File? = null,
) {
    fun release() {
        temporary?.delete()
    }
}

/** [quality] is null for documents / voice notes / GIFs, which are sent untouched. */
data class MediaPrepRequest(
    val uri: Uri,
    val mimeType: String,
    val fileName: String,
    val quality: String?,
    val width: Int? = null,
    val height: Int? = null,
)

fun interface MediaPreparer {
    suspend fun prepare(request: MediaPrepRequest): PreparedMedia
}

/**
 * Signal `MediaRepository.transformMedia` + `AttachmentCompressionJob`: images are
 * resized/re-encoded to the SD/HD constraints, videos transcoded on device; anything
 * else streams straight from its content Uri.
 */
class ContentMediaPreparer(
    private val context: Context,
    private val maxUploadBytes: Long,
) : MediaPreparer {
    private val outputDir = File(context.cacheDir, "chat-outgoing").apply {
        mkdirs()
        // Leftovers from a process that died mid-send.
        val stale = System.currentTimeMillis() - 24 * 60 * 60_000L
        listFiles()?.filter { it.lastModified() < stale }?.forEach(File::delete)
    }

    override suspend fun prepare(request: MediaPrepRequest): PreparedMedia = withContext(Dispatchers.IO) {
        val size = sourceSize(request.uri)
        val mime = request.mimeType.lowercase()
        if (request.quality != null && mime.startsWith("image/") && mime != "image/gif") {
            // Decode failures (odd formats) fall back to the original, as Signal does.
            runCatching { compressImage(request, ImageSendConstraints.forQuality(request.quality)) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.let { return@withContext it }
        }
        if (request.quality != null && mime.startsWith("video/")) {
            if (size > VideoSendConstraints.MAX_SOURCE_BYTES) throw IllegalArgumentException("Videos must be 500 MB or smaller")
            transcodeVideo(request, size, VideoSendConstraints.forQuality(request.quality))?.let { return@withContext it }
        }
        original(request, size)
    }

    private fun sourceSize(uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path.orEmpty()).length()
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
    }

    private fun openSource(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri) ?: throw IOException("Could not read the selected file")

    private suspend fun original(request: MediaPrepRequest, knownSize: Long): PreparedMedia {
        if (knownSize > maxUploadBytes) throw IllegalArgumentException("Files must be 25 MB or smaller")
        if (knownSize > 0) {
            return PreparedMedia(request.fileName, request.mimeType, knownSize, request.width, request.height, { openSource(request.uri) })
        }
        // Unknown length: spool to disk so the multipart body has an exact size.
        val file = newOutputFile(request.fileName.substringAfterLast('.', "bin"))
        try {
            var total = 0L
            openSource(request.uri).use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxUploadBytes) throw IllegalArgumentException("Files must be 25 MB or smaller")
                        out.write(buffer, 0, read)
                    }
                }
            }
            return PreparedMedia(request.fileName, request.mimeType, total, request.width, request.height, { file.inputStream() }, file)
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    /** Signal `ImageCompressionUtil.compress`: walk the dimension targets until the encoded size fits. */
    private suspend fun compressImage(request: MediaPrepRequest, constraints: ImageSendConstraints): PreparedMedia {
        val decoded = loadEditorBitmap(context, request.uri, constraints.dimensionTargets.first())
        val png = decoded.hasAlpha() && request.mimeType.lowercase() in setOf("image/png", "image/webp")
        val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        var best: ByteArray? = null
        var bestSize = decoded.width to decoded.height
        try {
            withContext(Dispatchers.Default) {
                for (target in constraints.dimensionTargets) {
                    ensureActive()
                    val (w, h) = fitLongEdge(decoded.width, decoded.height, target)
                    if (best != null && max(w, h) >= max(bestSize.first, bestSize.second)) continue
                    val scaled = if (w == decoded.width && h == decoded.height) decoded else decoded.scale(w, h)
                    val bytes = ByteArrayOutputStream().also { scaled.compress(format, constraints.jpegQuality, it) }.toByteArray()
                    if (scaled !== decoded) scaled.recycle()
                    best = bytes
                    bestSize = w to h
                    if (bytes.size <= constraints.maxBytes) break
                }
            }
        } finally {
            decoded.recycle()
        }
        val bytes = best ?: throw IOException("Could not compress image")
        val ext = if (png) "png" else "jpg"
        val file = newOutputFile(ext)
        file.writeBytes(bytes)
        return PreparedMedia(
            fileName = request.fileName.substringBeforeLast('.', request.fileName).ifBlank { "image" } + ".$ext",
            mimeType = if (png) "image/png" else "image/jpeg",
            length = bytes.size.toLong(),
            width = bestSize.first,
            height = bestSize.second,
            open = { file.inputStream() },
            temporary = file,
        )
    }

    private class VideoInfo(val width: Int, val height: Int, val bitrate: Int, val durationMs: Long)

    private fun videoInfo(uri: Uri): VideoInfo? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            fun int(key: Int) = retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val rotated = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0
            val codedWidth = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val codedHeight = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            VideoInfo(
                width = if (rotated) codedHeight else codedWidth,
                height = if (rotated) codedWidth else codedHeight,
                bitrate = int(MediaMetadataRetriever.METADATA_KEY_BITRATE),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
            )
        } finally {
            retriever.release()
        }
    }.getOrNull()

    /** Null when the original already fits (or transcoding failed but the original is still sendable). */
    @androidx.annotation.OptIn(UnstableApi::class)
    private suspend fun transcodeVideo(request: MediaPrepRequest, size: Long, constraints: VideoSendConstraints): PreparedMedia? {
        val info = videoInfo(request.uri)
        if (info == null || info.width <= 0 || info.height <= 0) return null
        if (!constraints.needsTranscode(info.width, info.height, info.bitrate, size, maxUploadBytes)) return null
        val output = newOutputFile("mp4")
        val bitrate = constraints.bitrateFor(info.durationMs, maxUploadBytes)
        // Effects see display-oriented frames, so the target height is the rotated one.
        val (w, h) = constraints.outputSize(info.width, info.height)
        val result = runCatching {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { cont ->
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                                .build(),
                        )
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    val item = EditedMediaItem.Builder(MediaItem.fromUri(request.uri))
                        .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(h))))
                        .build()
                    transformer.start(item, output.absolutePath)
                    cont.invokeOnCancellation {
                        android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() }
                    }
                }
            }
        }
        result.exceptionOrNull()?.let { error ->
            output.delete()
            if (error is kotlinx.coroutines.CancellationException) throw error
            if (size in 1..maxUploadBytes) return null
            throw IllegalArgumentException("Files must be 25 MB or smaller")
        }
        val length = output.length()
        if (length <= 0 || length > maxUploadBytes) {
            output.delete()
            if (size in 1..maxUploadBytes) return null
            throw IllegalArgumentException("Files must be 25 MB or smaller")
        }
        return PreparedMedia(
            fileName = request.fileName.substringBeforeLast('.', request.fileName).ifBlank { "video" } + ".mp4",
            mimeType = "video/mp4",
            length = length,
            width = request.width ?: w,
            height = request.height ?: h,
            open = { output.inputStream() },
            temporary = output,
        )
    }

    private fun newOutputFile(ext: String) = File(outputDir, "send_${UUID.randomUUID()}.$ext")
}
