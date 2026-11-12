package app.aino.mobile.core.network

import app.aino.mobile.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone

class HttpUrlConnectionApiClient(
    private val baseUrl: String = BuildConfig.AINO_API_URL,
    private val tokenProvider: TokenProvider = TokenProvider { null },
    private val timeZoneProvider: () -> TimeZone = TimeZone::getDefault,
    private val clock: () -> Long = System::currentTimeMillis,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val upgrades: app.aino.mobile.core.update.ClientUpgradeState = app.aino.mobile.core.update.ClientUpgrades.state,
) : ApiClient {
    override fun execute(request: ApiRequest): ApiResponse {
        val method = request.method.uppercase()
        val requestUrl = resolveApiUrl(baseUrl, request.path)
        val connection = try {
            connectionFactory(URL(requestUrl))
        } catch (error: IOException) {
            throw ApiError.Network(method, requestUrl, error)
        }

        try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.instanceFollowRedirects = true

            request.platformHeaders(tokenProvider.getToken(), timezoneOffsetMinutes(timeZoneProvider(), clock()))
                .forEach(connection::setRequestProperty)

            request.streamBody?.let { stream ->
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", stream.contentType)
                connection.setFixedLengthStreamingMode(stream.contentLength)
                connection.outputStream.use(stream.writeTo)
            } ?: request.body?.let { body ->
                connection.doOutput = true
                if (connection.getRequestProperty("Content-Type") == null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }

            val statusCode = connection.responseCode
            if (statusCode == 426) upgrades.observe(426, "")
            val responseBody = (if (statusCode >= 400) connection.errorStream else connection.inputStream)
                ?.use { it.readBytes() }
                ?: ByteArray(0)
            if (statusCode !in 200..299) {
                upgrades.observe(statusCode, responseBody.toString(Charsets.UTF_8))
                throw ApiError.Http(statusCode, responseBody.toString(Charsets.UTF_8), method, requestUrl)
            }
            return ApiResponse(
                statusCode = statusCode,
                headers = connection.headerFields.mapNotNull { (name, values) ->
                    name?.let { it to values }
                }.toMap(),
                body = responseBody,
            )
        } catch (error: ApiError) {
            throw error
        } catch (error: IOException) {
            throw ApiError.Network(method, requestUrl, error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MILLIS = 60_000
    }
}
