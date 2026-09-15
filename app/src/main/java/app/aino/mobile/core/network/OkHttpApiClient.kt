package app.aino.mobile.core.network

import java.io.IOException
import java.util.TimeZone
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OkHttpApiClient(
    private val baseUrl: String = NetworkConfig.apiUrl,
    private val tokenProvider: TokenProvider = TokenProvider { null },
    private val client: OkHttpClient = defaultClient(),
    private val timeZoneProvider: () -> TimeZone = TimeZone::getDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) : ApiClient {
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
        val body = request.body?.toRequestBody(contentType)
        builder.method(method, body)
        try {
            client.newCall(builder.build()).execute().use { response ->
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

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}