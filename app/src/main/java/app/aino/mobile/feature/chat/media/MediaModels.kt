package app.aino.mobile.feature.chat.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

data class MediaSendItem(val uri: Uri, val mimeType: String, val width: Int? = null, val height: Int? = null)

internal val MediaSendItem.isVideo get() = mimeType.startsWith("video/")

/** New file in the FileProvider-shared `cache/chat-media` dir. */
internal fun newChatMediaFile(context: Context, prefix: String, ext: String): File {
    val dir = File(context.cacheDir, "chat-media").apply { mkdirs() }
    return File(dir, "${prefix}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$ext")
}

internal fun chatMediaUri(context: Context, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
