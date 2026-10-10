package app.aino.mobile.core.network

import java.io.IOException

/** A transport-neutral request. [path] must be relative to the configured API origin. */
data class ApiRequest(
    val method: String = "GET",
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    /** Optional upload progress (bytes sent, total); honoured by [OkHttpApiClient]. */
    val onUploadProgress: ((Long, Long) -> Unit)? = null,
    /** Large upload streamed from its source instead of [body]; sent on a dedicated upload connection. */
    val streamBody: StreamBody? = null,
)

/**
 * A request body written straight to the socket. [writeTo] must be repeatable:
 * a 401 retry (see [RefreshingApiClient]) writes it a second time.
 */
class StreamBody(
    val contentType: String,
    val contentLength: Long,
    val writeTo: (java.io.OutputStream) -> Unit,
)

data class ApiResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
) {
    fun bodyAsString(): String = body.toString(Charsets.UTF_8)
}

/** Expected API failures are exposed as typed exceptions rather than generated contracts. */
sealed class ApiError(message: String, cause: Throwable? = null) : IOException(message, cause) {
    class Http(
        val statusCode: Int,
        val responseBody: String,
        val requestMethod: String,
        val requestUrl: String,
    ) : ApiError("HTTP $statusCode for $requestMethod $requestUrl")

    class Network(
        val requestMethod: String,
        val requestUrl: String,
        cause: IOException,
    ) : ApiError("Network failure for $requestMethod $requestUrl", cause)
}

/**
 * A message fit for the UI: the server's `{"error": "..."}` text for HTTP
 * failures (never the raw "HTTP 400 for GET https://…" transport string), a
 * connectivity hint for network failures, otherwise the throwable's message.
 */
fun userFacingMessage(error: Throwable, fallback: String): String = when (error) {
    is ApiError.Http -> serverErrorText(error.responseBody) ?: fallback
    is ApiError.Network -> NETWORK_ERROR_MESSAGE
    else -> error.message?.takeUnless { it.startsWith("HTTP ") } ?: fallback
}

const val NETWORK_ERROR_MESSAGE = "Can't reach the server. Check your connection and try again."

private val ERROR_FIELD = Regex("\"(?:error|message)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

internal fun serverErrorText(body: String): String? =
    ERROR_FIELD.find(body)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")?.replace("\\n", " ")?.trim()
        ?.takeIf(String::isNotEmpty)

fun interface TokenProvider {
    fun getToken(): String?
}

interface TokenStore : TokenProvider {
    fun saveToken(token: String)

    /** Drops the access token and the refresh token: the session is over on this device. */
    fun clearToken()

    /**
     * P2.7 rotating refresh token (`<tenantId>.<sid>.<secret>`), swapped for a
     * new one on every `sessions/token` call. Null for sessions created before
     * rotation, which keep refreshing the long-lived token via `auth/refresh`.
     */
    fun saveRefreshToken(token: String) {}
    fun getRefreshToken(): String? = null

    /**
     * Last-known-good serialized `tenant_features` map, persisted alongside the
     * token so a cold start with a transient network failure does not collapse
     * the navigation gates to an empty (fail-closed) set. Default no-ops keep
     * in-memory test doubles source-compatible.
     */
    fun saveFeatures(features: String) {}
    fun getFeatures(): String? = null
    fun clearFeatures() {}
}

fun interface ApiClient {
    @Throws(ApiError::class)
    fun execute(request: ApiRequest): ApiResponse
}
