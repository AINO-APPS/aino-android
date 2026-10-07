package app.aino.mobile.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import app.aino.mobile.BuildConfig
import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class UpdateRepository(
    private val client: OkHttpClient = OkHttpClient(),
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

    fun downloadAndInstall(context: Context, update: AvailableUpdate) {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "AINO-${update.version}.apk")
        val request = Request.Builder().url(update.apkUrl).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "APK download returned HTTP ${response.code}" }
            val body = response.body ?: error("APK download was empty")
            check(body.contentLength() != 0L) { "APK download was empty" }
            body.byteStream().use { input -> target.outputStream().use(input::copyTo) }
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            error("Allow AINO to install updates, then tap Install again.")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}