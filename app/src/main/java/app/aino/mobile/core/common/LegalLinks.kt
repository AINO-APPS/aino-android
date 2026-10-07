package app.aino.mobile.core.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.aino.mobile.BuildConfig

/** Public legal pages served by the web app (also listed in the Play Console). */
object LegalLinks {
    const val PRIVACY_PATH = "/privacy"
    const val TERMS_PATH = "/terms"
    const val ACCOUNT_DELETION_PATH = "/account-deletion"

    /** The web origin is the API host without the `/api` suffix. */
    fun url(path: String, apiUrl: String = BuildConfig.AINO_API_URL): String {
        val origin = Uri.parse(apiUrl).let { "${it.scheme}://${it.authority}" }
        return origin + path
    }

    fun open(context: Context, path: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url(path))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser installed: nothing sensible to do.
        }
    }
}
