package app.aino.mobile.core.network

import java.io.IOException
import java.util.TimeZone
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.buffer
import java.util.concurrent.TimeUnit

/** Reports at most one callback per whole percent so a multi-MB upload doesn't flood the UI state. */
internal class ProgressThrottle(private val total: Long) {
    private var lastPercent = -1

    /** The new whole percent for [written] bytes, or null when it hasn't advanced. */
    fun advance(written: Long): Int? {
        if (total <= 0) return null
        val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
        if (percent <= lastPercent) return null
        lastPercent = percent
        return percent
    }
}

/** Reports bytes written to the socket so uploads can show real progress. */
private class ProgressRequestBody(
    private val delegate: okhttp3.RequestBody,
    private val onProgress: (Long, Long) -> Unit,
) : okhttp3.RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()
    override fun writeTo(sink: okio.BufferedSink) {
        val total = contentLength()
        val throttle = ProgressThrottle(total)
        var written = 0L
        val counting = object : okio.ForwardingSink(sink) {
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (throttle.advance(written) != null) onProgress(written, total)
            }
        }.buffer()
        delegate.writeTo(counting)
        counting.flush()
    }
}

private class StreamRequestBody(private val stream: StreamBody) : okhttp3.RequestBody() {
    override fun contentType() = stream.contentType.toMediaType()
    override fun contentLength() = stream.contentLength
    override fun writeTo(sink: okio.BufferedSink) {
        val out = sink.outputStream()
        stream.writeTo(out)
        out.flush()
    }
}

class OkHttpApiClient(
    private val baseUrl: String = NetworkConfig.apiUrl,
    private val tokenProvider: TokenProvider = TokenProvider { null },
    private val client: OkHttpClient = defaultClient(),
    private val timeZoneProvider: () -> TimeZone = TimeZone::getDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : ApiClient {
    /**
     * Uploads get their own connection pool (Signal sends attachments over a
     * separate connection): on HTTP/2 a multi-MB body would otherwise sit in
     * front of every text send multiplexed onto the same socket.
     */
    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder().connectionPool(ConnectionPool()).writeTimeout(5, TimeUnit.MINUTES).build()
    }

    override fun execute(request: ApiRequest): ApiResponse {
        val method = request.method.uppercase()
        val url = resolveApiUrl(baseUrl, request.path)
        val headers = request.headers + standardHeaders(
            tokenProvider.getToken(),
            timezoneOffsetMinutes(timeZoneProvider(), clock()),
        )
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value?.toMediaType() ?: JSON
        val raw = request.streamBody?.let(::StreamRequestBody) ?: request.body?.toRequestBody(contentType)
        val body = raw?.let {
            val progress = request.onUploadProgress ?: return@let it
            ProgressRequestBody(it, progress)
        } ?: ByteArray(0).toRequestBody(contentType).takeIf { method in BODY_REQUIRED }
        // OkHttp rejects a body-less POST/PUT/PATCH ("method POST must have a
        // request body"); callers with nothing to send (mark read, delivered,
        // call accept …) get an empty body instead.
        builder.method(method, body)
        val transport = if (request.streamBody != null || request.onUploadProgress != null) uploadClient else client
        try {
            transport.newCall(builder.build()).execute().use { response ->
                val bytes = response.body?.bytes() ?: ByteArray(0)
                if (!response.isSuccessful) throw ApiError.Http(response.code, bytes.toString(Charsets.UTF_8), method, url)
                return ApiResponse(response.code, response.headers.toMultimap(), bytes)
            }
        } catch (error: ApiError) {
            throw error
        } catch (error: IOException) {
            throw ApiError.Network(method, url, error)
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val BODY_REQUIRED = setOf("POST", "PUT", "PATCH")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}