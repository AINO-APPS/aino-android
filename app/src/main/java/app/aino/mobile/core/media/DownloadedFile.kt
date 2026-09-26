package app.aino.mobile.core.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The web's blob download (`URL.createObjectURL` + `<a download>`): bytes the
 * authenticated API already returned (for example a salary-slip PDF) are
 * written to the FileProvider `downloads/` cache (res/xml/file_paths.xml) and
 * handed to a viewer. Each download overwrites the previous copy, so a
 * regenerated document is never served stale.
 */
suspend fun openDownloadedBytes(context: Context, bytes: ByteArray, fileName: String, mimeType: String) {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, "downloads").apply { mkdirs() }
            val target = File(directory, safeDownloadName(fileName))
            target.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrNull()
    }
    if (uri == null) {
        Toast.makeText(context, "Could not save file", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

/** A file-system-safe name: anything outside `[A-Za-z0-9._-]` becomes `_`. */
fun safeDownloadName(fileName: String): String =
    fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifEmpty { "file" }
