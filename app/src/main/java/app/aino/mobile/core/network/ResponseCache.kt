package app.aino.mobile.core.network

import java.io.File
import java.security.MessageDigest

/**
 * Last successful body of every authenticated GET, per signed-in user, so a
 * screen can paint its previous state instantly and revalidate in the
 * background (Slack / Teams open from local state, never from a spinner).
 * Lives in `cacheDir`: never backed up, and the OS may evict it.
 */
class ResponseCache(private val root: File) {
    /** `tenantId_userId`; null while signed out (nothing is read or written). */
    @Volatile var scope: String? = null

    fun put(path: String, body: ByteArray) {
        val dir = dir() ?: return
        if (body.size > MAX_BODY_BYTES) return
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, name(path) + ".tmp")
            tmp.writeBytes(body)
            tmp.renameTo(File(dir, name(path)))
        }
    }

    fun get(path: String): ByteArray? = dir()?.let { runCatching { File(it, name(path)).takeIf(File::isFile)?.readBytes() }.getOrNull() }

    /** Sign-out: the previous user's data must not survive on the device. */
    fun clearAll() {
        runCatching { root.deleteRecursively() }
    }

    private fun dir(): File? = scope?.let { File(root, it) }

    private fun name(path: String): String =
        MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        // ponytail: no LRU; one file per distinct GET path, wiped on sign-out
        // and evictable by the OS. Add a size cap if the directory ever grows large.
        const val MAX_BODY_BYTES = 1_000_000
    }
}

/** Records every successful GET body into [cache]. */
class CachingApiClient(private val delegate: ApiClient, private val cache: ResponseCache) : ApiClient {
    override fun execute(request: ApiRequest): ApiResponse {
        val response = delegate.execute(request)
        if (request.method.equals("GET", ignoreCase = true) && response.isJson() && !request.path.isPage()) cache.put(request.path, response.body)
        return response
    }

    /** Older / newer pages are not screen state; caching them would only pile up files. */
    private fun String.isPage(): Boolean = "before=" in this || "after=" in this

    private fun ApiResponse.isJson(): Boolean =
        headers.entries.any { (key, values) -> key.equals("Content-Type", ignoreCase = true) && values.any { "json" in it } }
}

/**
 * Serves GETs from [cache] only. Repositories run their normal load against it
 * to rebuild the last known state without touching the network; a miss fails
 * like an offline request, so partial-failure handling still applies.
 */
class CacheOnlyApiClient(private val cache: ResponseCache) : ApiClient {
    override fun execute(request: ApiRequest): ApiResponse {
        val body = request.method.takeIf { it.equals("GET", ignoreCase = true) }?.let { cache.get(request.path) }
            ?: throw ApiError.Network(request.method, request.path, java.io.IOException("Not cached"))
        return ApiResponse(200, emptyMap(), body)
    }
}
