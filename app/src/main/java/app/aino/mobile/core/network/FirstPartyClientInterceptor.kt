package app.aino.mobile.core.network

import app.aino.mobile.core.update.ClientUpgradeState
import app.aino.mobile.core.update.ClientUpgrades
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl

class FirstPartyClientInterceptor(
    private val baseUrl: String = NetworkConfig.apiUrl,
    private val upgrades: ClientUpgradeState = ClientUpgrades.state,
) : Interceptor {
    private val origin = baseUrl.toHttpUrl()

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val firstParty = original.url.scheme == origin.scheme &&
            original.url.host == origin.host && original.url.port == origin.port
        if (!firstParty) return chain.proceed(original)
        val request = original.newBuilder()
            .header(RequestHeaders.CLIENT, RequestHeaders.ANDROID)
            .header(RequestHeaders.CLIENT_VERSION, app.aino.mobile.BuildConfig.VERSION_NAME)
            .build()
        return chain.proceed(request).also { response ->
            if (response.code == 426) {
                upgrades.observe(426, "")
                runCatching { response.peekBody(64 * 1024).string() }.getOrNull()?.let { upgrades.observe(426, it) }
            }
        }
    }
}
