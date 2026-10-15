package app.aino.mobile.core.storage

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.media.VideoCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Signal "Manage storage": what the app keeps on the phone and a safe "Clear cache".
 * Outgoing media (`chat-outgoing`, voice notes being sent), staged shares, the
 * offline message database and the API response cache are never touched.
 */
object StorageUsage {
    data class Breakdown(val images: Long, val videos: Long, val other: Long, val database: Long) {
        val total: Long get() = images + videos + other + database
    }

    /** Cache folders that only hold re-downloadable copies. */
    internal val CLEARABLE_OTHER = listOf("chat-media/files", "chat-media/audio", "downloads", "task-files", "giphy", "call_avatars")
    private const val IMAGES = "image-cache"
    private const val VIDEOS = "video-cache"

    suspend fun measure(context: Context): Breakdown = withContext(Dispatchers.IO) {
        val cache = context.cacheDir
        Breakdown(
            images = sizeOf(File(cache, IMAGES)),
            videos = sizeOf(File(cache, VIDEOS)),
            other = CLEARABLE_OTHER.sumOf { sizeOf(File(cache, it)) },
            database = context.getDatabasePath("aino.db").parentFile
                ?.listFiles { file -> file.name.startsWith("aino.db") }?.sumOf { it.length() } ?: 0L,
        )
    }

    suspend fun clearCache(context: Context) = withContext(Dispatchers.IO) {
        val loader = AppContainer.get(context).imageLoader
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
        VideoCache.clear(context)
        clearFolders(context.cacheDir, CLEARABLE_OTHER)
    }

    internal fun clearFolders(root: File, names: List<String>) {
        names.forEach { name -> File(root, name).listFiles()?.forEach { it.deleteRecursively() } }
    }

    internal fun sizeOf(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.walkTopDown().filter(File::isFile).sumOf(File::length)
    }
}
