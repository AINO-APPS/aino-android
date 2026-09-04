package app.aino.mobile.core.network

import app.aino.mobile.BuildConfig

object NetworkConfig {
    val apiUrl: String get() = BuildConfig.WORKPULSE_API_URL
    val webSocketUrl: String get() = BuildConfig.WORKPULSE_WS_URL
    val contractVersion: String get() = BuildConfig.AINO_CONTRACT_VERSION
}
