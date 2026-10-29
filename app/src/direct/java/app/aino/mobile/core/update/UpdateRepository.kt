package app.aino.mobile.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import app.aino.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

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
        if (signedByDifferentKey(installedSignatures(context), candidateSignatures(context, target))) {
            target.delete()
            error(
                "This update is signed with a different key than the one installed. " +
                    "Android cannot upgrade across a key change, so uninstall AINO and install the new build.",
            )
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    private fun installedSignatures(context: Context): Set<String> {
        val manager = context.packageManager
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
        }.getOrNull() ?: return emptySet()
        return info.signerDigests()
    }

    private fun candidateSignatures(context: Context, apk: File): Set<String> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }.getOrNull() ?: return emptySet()
        return info.signerDigests()
    }

    private companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
    }
}

private fun PackageInfo.signerDigests(): Set<String> {
    val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners
    } else {
        @Suppress("DEPRECATION")
        this.signatures
    }
    return signatures.orEmpty().mapTo(mutableSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHex()
    }
}

// Hand-rolled rather than `"%02x".format(...)`: String.format is locale-sensitive and
// emits non-ASCII digits under some locales, which would silently break the comparison.
private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
