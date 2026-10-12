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
}
