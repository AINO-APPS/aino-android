package app.aino.mobile.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.aino.mobile.BuildConfig
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Direct (sideloaded) update channel. The app only checks the manifest and hands the
 * trusted APK URL to the browser; it never downloads or installs packages itself.
 * An in-app installer (REQUEST_INSTALL_PACKAGES + downloading an APK from a remotely
 * supplied URL) is classified by Play Protect as POTENTIALLY_UNWANTED/generic_malware.
 */
class UpdateRepository(
    private val client: OkHttpClient = app.aino.mobile.core.network.CertificatePinning.apply(OkHttpClient.Builder()).build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val baseUrl: String = BuildConfig.AINO_OTA_BASE_URL,
) {
    fun check(currentVersion: String = BuildConfig.VERSION_NAME): AvailableUpdate? {
        val manifestUrl = "${baseUrl.trimEnd('/')}/android/latest.json"
        val request = Request.Builder().url(manifestUrl).header("Accept", "application/json").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Update server returned HTTP ${response.code}" }
            val manifest = json.decodeFromString<UpdateManifest>(response.body?.string().orEmpty())
            requireTrustedApkUrl(baseUrl, manifest.apkUrl)
            return if (compareVersions(manifest.version, currentVersion) > 0) {
                AvailableUpdate(manifest.version, manifest.apkUrl, manifest.notes)
            } else null
        }
    }

    fun openDownload(context: Context, update: AvailableUpdate) {
        requireTrustedApkUrl(baseUrl, update.apkUrl)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(update.apkUrl))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}