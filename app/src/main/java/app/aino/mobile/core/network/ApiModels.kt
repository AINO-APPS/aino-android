package app.aino.mobile.core.network

import java.io.IOException

/** A transport-neutral request. [path] must be relative to the configured API origin. */
data class ApiRequest(
    val method: String = "GET",
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
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

fun interface TokenProvider {
    fun getToken(): String?
}

interface TokenStore : TokenProvider {
    fun saveToken(token: String)
    fun clearToken()
}

fun interface ApiClient {
    @Throws(ApiError::class)
    fun execute(request: ApiRequest): ApiResponse
}
