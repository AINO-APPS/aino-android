package app.aino.mobile.core.network

import app.aino.mobile.BuildConfig

object NetworkConfig {
    val apiUrl: String get() = BuildConfig.AINO_API_URL
    val webSocketUrl: String get() = BuildConfig.AINO_WS_URL
    val contractVersion: String get() = BuildConfig.AINO_CONTRACT_VERSION

    /**
     * Server origin without the trailing `/api`, used to resolve stored upload
     * paths such as `/uploads/...` into absolute URLs.
     */
    val serverOrigin: String get() = apiUrl.replace(Regex("/api/?$"), "")
}
