package app.aino.mobile.core.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/** One file/photo/video handed to Aino by another app's share sheet. */
data class SharedItem(val uri: Uri, val mimeType: String, val fileName: String) {
    val isVisualMedia: Boolean get() = mimeType.startsWith("image/") || mimeType.startsWith("video/")
}

/**
 * Content shared into Aino (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`).
 * [conversationId] is set when the user picked a Direct Share target (a conversation shortcut).
 */
data class IncomingShare(
    val text: String?,
    val items: List<SharedItem>,
    val conversationId: Long? = null,
) {
    val isMediaOnly: Boolean get() = items.isNotEmpty() && items.all(SharedItem::isVisualMedia)
}

const val SHARE_TARGET_CATEGORY = "app.aino.mobile.SHARE_TARGET"
const val CONVERSATION_SHORTCUT_PREFIX = "wp-conversation-"
const val MAX_SHARED_ITEMS = 32
private const val SHARED_DIR = "shared"
private const val SHARED_TTL_MS = 24L * 60 * 60 * 1000

/** Conversation shortcut id (`wp-conversation-<id>`) back to its conversation id. */
fun conversationIdFromShortcut(shortcutId: String?): Long? =
    shortcutId?.takeIf { it.startsWith(CONVERSATION_SHORTCUT_PREFIX) }
        ?.removePrefix(CONVERSATION_SHORTCUT_PREFIX)?.toLongOrNull()?.takeIf { it > 0 }

/** Reads a share intent; null for anything else or an empty share. Names/types are refined in [stage]. */
fun parseShareIntent(intent: Intent?): IncomingShare? {
    if (intent == null) return null
    val action = intent.action
    if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return null
    val streams = when (action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        else -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
    }.ifEmpty {
        // Some senders only fill ClipData.
        intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
    }.filter { it.scheme == "content" }.distinct().take(MAX_SHARED_ITEMS)
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim()?.takeIf(String::isNotEmpty)
        ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.takeIf(String::isNotEmpty)
    if (streams.isEmpty() && text == null) return null
    val type = intent.type?.takeUnless { it.endsWith("/*") } ?: "application/octet-stream"
    return IncomingShare(
        text = text,
        items = streams.map { SharedItem(it, type, it.lastPathSegment ?: "file") },
        conversationId = conversationIdFromShortcut(intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)),
    )
}

/**
 * Copies every shared stream into app cache (as Signal/WhatsApp do): the sender's read grant
 * ends with this activity, while previews and queued uploads may outlive it. Call off the main thread.
 */
fun stage(context: Context, share: IncomingShare): IncomingShare {
    if (share.items.isEmpty()) return share
    val resolver = context.contentResolver
    val dir = File(context.cacheDir, "$SHARED_DIR/${UUID.randomUUID()}").apply { mkdirs() }
    val staged = share.items.mapIndexedNotNull { index, item ->
        runCatching {
            var name: String? = null
            resolver.query(item.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            val mime = resolver.getType(item.uri)?.takeUnless { it.endsWith("/*") } ?: item.mimeType
            val safeName = sanitizeFileName(name ?: item.fileName, index)
            val target = File(dir, "$index-$safeName")
            val copied = resolver.openInputStream(item.uri)?.use { input -> target.outputStream().use(input::copyTo) }
            if (copied == null) null
            else SharedItem(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target), mime, safeName)
        }.getOrNull()
    }
    return share.copy(items = staged)
}

internal fun sanitizeFileName(name: String, index: Int): String =
    name.substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|\\x00-\\x1f]"), "_").trim().take(120)
        .ifEmpty { "shared-$index" }

/** Drops staged share copies older than a day (abandoned shares). */
fun pruneStagedShares(context: Context, nowMs: Long = System.currentTimeMillis()) {
    File(context.cacheDir, SHARED_DIR).listFiles()?.forEach { dir ->
        if (nowMs - dir.lastModified() > SHARED_TTL_MS) dir.deleteRecursively()
    }
}

/** The one pending share: MainActivity sets it, the signed-in shell shows the picker and clears it. */
object PendingShare {
    private val _share = MutableStateFlow<IncomingShare?>(null)
    val share: StateFlow<IncomingShare?> = _share.asStateFlow()
    fun set(share: IncomingShare) { _share.value = share }
    fun clear() { _share.value = null }
}
