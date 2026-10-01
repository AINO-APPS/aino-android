package app.aino.mobile.feature.chat

import app.aino.mobile.core.network.StreamBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

const val MAX_CHAT_FILE_BYTES: Long = 25L * 1024 * 1024

val CHAT_ALLOWED_MIME_TYPES = setOf(
    "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp",
    "video/mp4", "video/webm", "video/quicktime",
    "audio/webm", "audio/mp4", "audio/mpeg", "audio/ogg", "audio/wav", "audio/x-wav",
    "application/pdf", "application/zip", "application/x-zip-compressed",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/msword", "application/vnd.ms-excel", "text/plain", "text/csv",
)

data class ChatUpload(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray = ByteArray(0),
    val content: String? = null,
    /** Server `buildUploadedMediaMetadata` fields (Signal view-once / HD / dimensions). */
    val viewOnce: Boolean = false,
    val quality: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    /** Re-openable streamed source (prepared media on disk); [bytes] is ignored when set. */
    val source: (() -> InputStream)? = null,
    val sourceLength: Long = bytes.size.toLong(),
)

data class MultipartPayload(val contentType: String, val body: ByteArray)

fun validateChatUpload(fileName: String, mimeType: String, size: Long): String? = when {
    fileName.isBlank() -> "Choose a valid file"
    mimeType !in CHAT_ALLOWED_MIME_TYPES -> "File type not allowed"
    size <= 0 -> "The selected file is empty"
    size > MAX_CHAT_FILE_BYTES -> "Files must be 25 MB or smaller"
    else -> null
}

private fun multipartHead(upload: ChatUpload, boundary: String): ByteArray {
    require(boundary.matches(Regex("^[A-Za-z0-9._-]{8,70}$")))
    require(validateChatUpload(upload.fileName, upload.mimeType, upload.sourceLength) == null)
    val safeName = upload.fileName.replace(Regex("[\\r\\n\\\"/\\\\]"), "_").take(255).ifBlank { "file" }
    val out = ByteArrayOutputStream()
    fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
    fun field(name: String, value: String) = text("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
    upload.content?.takeIf(String::isNotBlank)?.let { field("content", it) }
    if (upload.viewOnce) field("viewOnce", "true")
    upload.quality?.takeIf { it == "standard" || it == "hd" }?.let { field("quality", it) }
    if (upload.width != null && upload.height != null && upload.width > 0 && upload.height > 0) {
        field("width", upload.width.toString())
        field("height", upload.height.toString())
    }
    text("--$boundary\r\n")
    text("Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"\r\n")
    text("Content-Type: ${upload.mimeType}\r\n\r\n")
    return out.toByteArray()
}

private fun multipartTail(boundary: String) = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)

fun buildChatMultipart(upload: ChatUpload, boundary: String): MultipartPayload {
    val out = ByteArrayOutputStream()
    out.write(multipartHead(upload, boundary))
    out.write(upload.bytes)
    out.write(multipartTail(boundary))
    return MultipartPayload("multipart/form-data; boundary=$boundary", out.toByteArray())
}

/** Same bytes as [buildChatMultipart], streamed from the source so a large file is never held in memory twice. */
fun streamChatMultipart(upload: ChatUpload, boundary: String): StreamBody {
    val head = multipartHead(upload, boundary)
    val tail = multipartTail(boundary)
    return StreamBody(
        contentType = "multipart/form-data; boundary=$boundary",
        contentLength = head.size + upload.sourceLength + tail.size,
    ) { out ->
        out.write(head)
        val source = upload.source
        if (source == null) {
            out.write(upload.bytes)
        } else {
            val copied = source().use { it.copyTo(out, 64 * 1024) }
            if (copied != upload.sourceLength) throw IOException("The file changed while it was being sent")
        }
        out.write(tail)
    }
}