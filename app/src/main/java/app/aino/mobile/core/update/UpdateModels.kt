package app.aino.mobile.core.update

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class UpdateManifest(
    val version: String,
    val apkUrl: String,
    val notes: String = "",
    val releaseUrl: String = "",
)

data class AvailableUpdate(
    val version: String,
    val apkUrl: String,
    val notes: String,
)

fun compareVersions(left: String, right: String): Int {
    fun parse(value: String): List<Int> {
        val match = Regex("^v?(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$").matchEntire(value.trim())
            ?: throw IllegalArgumentException("Invalid version: $value")
        return match.groupValues.drop(1).map(String::toInt)
    }
    val a = parse(left)
    val b = parse(right)
    return a.zip(b).firstOrNull { (x, y) -> x != y }?.let { (x, y) -> x.compareTo(y) } ?: 0
}

fun requireTrustedApkUrl(baseUrl: String, apkUrl: String) {
    val base = URI(baseUrl)
    val apk = URI(apkUrl)
    require(apk.scheme == "https") { "Update APK must use HTTPS" }
    require(apk.host.equals(base.host, ignoreCase = true)) { "Update APK must use the configured CDN" }
    require(apk.path.startsWith("/android/releases/") && apk.path.endsWith(".apk")) {
        "Update APK must use the native Android release path"
    }
}