package app.aino.mobile.core.push

import android.content.Context
import android.graphics.Bitmap
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

internal interface NotificationAvatarSource {
    fun cached(context: Context, url: String, account: String?): Bitmap?
    suspend fun load(context: Context, url: String, account: String?): Bitmap?
}

internal object NotificationAvatarLoader : NotificationAvatarSource {
    private const val SIZE = 128

    internal fun cacheKey(url: String, account: String?) = "notification-avatar:${account.orEmpty()}:${resolveServerMediaUrl(url)}"

    override fun cached(context: Context, url: String, account: String?): Bitmap? =
        AppContainer.get(context).imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey(url, account)))
            ?.image?.toBitmap()

    override suspend fun load(context: Context, url: String, account: String?): Bitmap? = withTimeoutOrNull(5_000) {
        val key = cacheKey(url, account)
        val request = ImageRequest.Builder(context)
            .data(resolveServerMediaUrl(url))
            .memoryCacheKey(key)
            .diskCacheKey(key)
            .size(SIZE)
            .scale(Scale.FILL)
            .allowHardware(false)
            .build()
        val result = AppContainer.get(context).imageLoader.execute(request)
        if (result.image == null) android.util.Log.w("AinoPush", "Notification avatar image load failed")
        result.image?.toBitmap()
    }.also { if (it == null) android.util.Log.w("AinoPush", "Notification avatar unavailable or timed out") }

    /** How long a posting thread may wait for photos before falling back to initials. */
    const val PRE_POST_BUDGET_MS = 1_500L

    /**
     * Signal builds a notification with its avatars already loaded. Warms the
     * notification cache (memory, then disk, then network, in parallel) within
     * [budgetMs] so the first post shows photos; slower photos still arrive through
     * the asynchronous re-post. Blocking: call off the main thread, outside any lock.
     */
    fun prefetchBlocking(context: Context, urls: Collection<String?>, account: String?, budgetMs: Long = PRE_POST_BUDGET_MS) {
        val wanted = urls.filterNotNull().filter(String::isNotBlank).distinct()
            .filter { runCatching { cached(context, it, account) }.getOrNull() == null }
        if (wanted.isEmpty()) return
        kotlinx.coroutines.runBlocking {
            withTimeoutOrNull(budgetMs) {
                coroutineScope {
                    wanted.map { url -> async(kotlinx.coroutines.Dispatchers.IO) { runCatching { load(context, url, account) } } }.awaitAll()
                }
            }
        }
    }
}

/** Every photo a chat notification draws: the sender, and for a group its photo or member collage. */
internal fun notificationAvatarUrls(data: Map<String, String>): List<String> {
    val group = if (data["isGroup"] == "true") pushGroupAvatarMetadata(data) else null
    return (listOfNotNull(data["senderAvatar"], group?.photoUrl) +
        (if (group?.photoUrl.isNullOrBlank()) group?.members?.mapNotNull { it.avatarUrl }.orEmpty() else emptyList()))
        .filter(String::isNotBlank).distinct()
}
