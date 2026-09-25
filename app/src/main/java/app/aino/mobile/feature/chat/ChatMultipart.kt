package app.aino.mobile.feature.chat

import java.io.ByteArrayOutputStream
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
    val bytes: ByteArray,
    val content: String? = null,
    /** Server `buildUploadedMediaMetadata` fields (Signal view-once / HD / dimensions). */
    val viewOnce: Boolean = false,
    val quality: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class MultipartPayload(val contentType: String, val body: ByteArray)

fun validateChatUpload(fileName: String, mimeType: String, size: Long): String? = when {
    fileName.isBlank() -> "Choose a valid file"
    mimeType !in CHAT_ALLOWED_MIME_TYPES -> "File type not allowed"
    size <= 0 -> "The selected file is empty"
    size > MAX_CHAT_FILE_BYTES -> "Files must be 25 MB or smaller"
    else -> null
}

fun buildChatMultipart(upload: ChatUpload, boundary: String): MultipartPayload {
    require(boundary.matches(Regex("^[A-Za-z0-9._-]{8,70}$")))
    require(validateChatUpload(upload.fileName, upload.mimeType, upload.bytes.size.toLong()) == null)
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
    out.write(upload.bytes)
    text("\r\n--$boundary--\r\n")
    return MultipartPayload("multipart/form-data; boundary=$boundary", out.toByteArray())
}