package app.aino.mobile.core.network

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Returns `(contentType, body)` for a single `avatar` file part (server `upload.single("avatar")`). */
fun buildAvatarMultipart(fileName: String, mimeType: String, bytes: ByteArray, boundary: String): Pair<String, ByteArray> {
    val safeName = fileName.replace(Regex("[\\r\\n\\\"/\\\\]"), "_").take(255).ifBlank { "avatar" }
    val out = ByteArrayOutputStream()
    fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
    text("--$boundary\r\n")
    text("Content-Disposition: form-data; name=\"avatar\"; filename=\"$safeName\"\r\n")
    text("Content-Type: $mimeType\r\n\r\n")
    out.write(bytes)
    text("\r\n--$boundary--\r\n")
    return "multipart/form-data; boundary=$boundary" to out.toByteArray()
}
