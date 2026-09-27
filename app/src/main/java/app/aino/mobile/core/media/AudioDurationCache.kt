package app.aino.mobile.core.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice-note lengths known before playback (Signal derives duration from the
 * audio file on-device and keeps it). The server stores no duration, so a
 * remote note is fetched once through the authenticated media client into the
 * cache dir, measured, and the result persisted; local files are measured in place.
 */
class AudioDurationCache(context: Context, private val http: OkHttpClient) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("audio_durations", Context.MODE_PRIVATE)
    private val memory = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap<String, Deferred<Long>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cached(url: String): Long =
        memory[url] ?: prefs.getLong(key(url), 0L).also { if (it > 0) memory[url] = it }

    fun remember(url: String, durationMs: Long) {
        if (durationMs <= 0) return
        memory[url] = durationMs
        if (prefs.all.size > MAX_ENTRIES) prefs.edit().clear().apply()
        prefs.edit().putLong(key(url), durationMs).apply()
    }

    /** Duration in ms, or 0 if it can't be determined. Concurrent callers share one fetch. */
    suspend fun duration(url: String): Long {
        cached(url).takeIf { it > 0 }?.let { return it }
        val job = inFlight.computeIfAbsent(url) { scope.async { measure(url) } }
        return try {
            job.await().also { remember(url, it) }
        } finally {
            inFlight.remove(url)
        }
    }

    private suspend fun measure(url: String): Long = withContext(Dispatchers.IO) {
        runCatching {
            if (url.startsWith("content:") || url.startsWith("file:")) return@runCatching retrieve { setDataSource(app, Uri.parse(url)) }
            val dir = File(app.cacheDir, "chat-media/audio").apply { mkdirs() }
            val file = File(dir, key(url))
            if (!file.exists()) {
                http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching 0L
                    val body = response.body ?: return@runCatching 0L
                    if (body.contentLength() > MAX_BYTES) return@runCatching 0L
                    val tmp = File(dir, "${file.name}.part")
                    tmp.outputStream().use { body.byteStream().copyTo(it) }
                    tmp.renameTo(file)
                }
            }
            retrieve { setDataSource(file.absolutePath) }
        }.getOrDefault(0L)
    }

    private fun retrieve(source: MediaMetadataRetriever.() -> Unit): Long = MediaMetadataRetriever().run {
        try {
            source()
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            release()
        }
    }

    private fun key(url: String): String =
        MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_ENTRIES = 1_000
        const val MAX_BYTES = 25L * 1024 * 1024
    }
}
