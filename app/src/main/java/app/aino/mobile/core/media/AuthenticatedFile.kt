package app.aino.mobile.core.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import app.aino.mobile.core.AppContainer
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Downloads through the authenticated client (other apps cannot send our token), then ACTION_VIEW. */
suspend fun openAuthenticatedFile(context: Context, url: String, fileName: String?, mimeType: String?) {
    val local = withContext(Dispatchers.IO) {
        runCatching {
            // `task-files/` is the FileProvider cache path (res/xml/file_paths.xml).
            val directory = File(context.cacheDir, "task-files").apply { mkdirs() }
            val safe = (fileName ?: Uri.parse(url).lastPathSegment ?: "file").replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            val target = File(directory, "${url.hashCode().toUInt()}-$safe")
            if (!target.exists() || target.length() == 0L) {
                AppContainer.get(context).mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    target.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrNull()
    }
    if (local == null) {
        Toast.makeText(context, "Could not download file", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(local, mimeType ?: context.contentResolver.getType(local) ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
