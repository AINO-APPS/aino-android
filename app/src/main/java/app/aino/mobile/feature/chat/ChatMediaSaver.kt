package app.aino.mobile.feature.chat

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.aino.mobile.core.AppContainer
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Public collection a saved chat attachment lands in (Signal `SaveAttachmentTask`).
 * Values equal `Environment.DIRECTORY_*`; literals because those fields are not
 * compile-time constants (and are null on the unit-test android.jar).
 */
internal enum class SaveTarget(val directory: String) {
    Pictures("Pictures"),
    Movies("Movies"),
    Music("Music"),
    Downloads("Download"),
}

internal fun saveTargetFor(mimeType: String?): SaveTarget = when {
    mimeType == null -> SaveTarget.Downloads
    mimeType.startsWith("image/") -> SaveTarget.Pictures
    mimeType.startsWith("video/") -> SaveTarget.Movies
    mimeType.startsWith("audio/") -> SaveTarget.Music
    else -> SaveTarget.Downloads
}

/** A filesystem-safe display name that keeps (or adds) a sensible extension. */
internal fun safeSaveName(
    fileName: String?,
    url: String,
    mimeType: String?,
    extensionFor: (String) -> String? = ::extensionForMime,
): String {
    val raw = fileName?.takeIf(String::isNotBlank)
        ?: url.substringBefore('?').substringAfterLast('/').takeIf(String::isNotBlank)
        ?: "aino-${System.currentTimeMillis()}"
    val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "aino-file" }
    if (cleaned.contains('.')) return cleaned
    val ext = mimeType?.let(extensionFor)
    return if (ext.isNullOrBlank()) cleaned else "$cleaned.$ext"
}

private fun extensionForMime(mime: String): String? = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)

internal const val SAVE_ALBUM = "AINO"

/** Android 8/9 still need WRITE_EXTERNAL_STORAGE to write to shared storage. */
fun chatSaveNeedsPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

/**
 * Saves a chat attachment to the public Pictures / Movies / Music / Download
 * folder under "AINO" (Signal "Save" in the media viewer and message menu),
 * then toasts where it went.
 */
suspend fun saveChatMedia(context: Context, url: String, fileName: String?, mimeType: String?): Boolean {
    val target = saveTargetFor(mimeType)
    val name = safeSaveName(fileName, url, mimeType)
    val saved = withContext(Dispatchers.IO) {
        runCatching { openSource(context, url).use { input -> writeToCollection(context, input, name, mimeType, target) } }
            .onFailure { android.util.Log.w("AinoChat", "Save media failed", it) }
            .getOrNull()
    }
    Toast.makeText(
        context,
        if (saved != null) "Saved to ${target.directory}/$SAVE_ALBUM" else "Couldn't save $name",
        Toast.LENGTH_SHORT,
    ).show()
    return saved != null
}

/**
 * Returns a `save(url, fileName, mimeType)` action for composables: on
 * Android 8/9 it first requests WRITE_EXTERNAL_STORAGE, then saves.
 */
@androidx.compose.runtime.Composable
fun rememberChatMediaSaver(): (url: String, fileName: String?, mimeType: String?) -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pending = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Triple<String, String?, String?>?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val request = pending.value
        pending.value = null
        if (request == null) return@rememberLauncherForActivityResult
        if (granted) scope.launch { saveChatMedia(context, request.first, request.second, request.third) }
        else Toast.makeText(context, "Storage permission is needed to save", Toast.LENGTH_SHORT).show()
    }
    return androidx.compose.runtime.remember(context, scope) {
        { url, fileName, mimeType ->
            if (chatSaveNeedsPermission(context)) {
                pending.value = Triple(url, fileName, mimeType)
                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                scope.launch { saveChatMedia(context, url, fileName, mimeType) }
            }
        }
    }
}

/** Reuses the Share/Open download cache when present; otherwise streams with the auth headers. */
private fun openSource(context: Context, url: String): InputStream {
    val parsed = url.toUri()
    if (parsed.scheme == "content" || parsed.scheme == "file") {
        return context.contentResolver.openInputStream(parsed) ?: error("Unreadable $url")
    }
    val prefix = "${url.hashCode().toUInt()}-"
    File(context.cacheDir, "chat-media/files")
        .listFiles { f -> f.name.startsWith(prefix) && f.length() > 0 }
        ?.firstOrNull()
        ?.let { return it.inputStream() }
    val response = AppContainer.get(context).mediaHttp.newCall(Request.Builder().url(url).build()).execute()
    if (!response.isSuccessful) {
        response.close()
        error("HTTP ${response.code}")
    }
    return response.body?.byteStream() ?: run { response.close(); error("Empty body") }
}

private fun writeToCollection(context: Context, input: InputStream, name: String, mimeType: String?, target: SaveTarget): Uri {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        val collection = when (target) {
            SaveTarget.Pictures -> MediaStore.Images.Media.getContentUri(volume)
            SaveTarget.Movies -> MediaStore.Video.Media.getContentUri(volume)
            SaveTarget.Music -> MediaStore.Audio.Media.getContentUri(volume)
            SaveTarget.Downloads -> MediaStore.Downloads.getContentUri(volume)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            mimeType?.let { put(MediaStore.MediaColumns.MIME_TYPE, it) }
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${target.directory}/$SAVE_ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) } ?: error("No output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
    @Suppress("DEPRECATION")
    val dir = File(Environment.getExternalStoragePublicDirectory(target.directory), SAVE_ALBUM).apply { mkdirs() }
    val base = name.substringBeforeLast('.')
    val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
    var file = File(dir, name)
    var n = 1
    while (file.exists()) file = File(dir, "$base (${n++})$ext")
    file.outputStream().use { out -> input.copyTo(out) }
    android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
    return Uri.fromFile(file)
}
