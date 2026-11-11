package app.aino.mobile.core.update

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import app.aino.mobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class DirectAppUpdaterTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).get()

    private fun updater(status: Int, latest: String): DirectAppUpdater {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = """{"version":"$latest","apkUrl":"https://cdn.test/android/releases/android-v$latest/AINO-$latest-direct.apk"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        // Unconfined runs the check inline, so state is final when check() returns.
        return DirectAppUpdater(UpdateRepository(client, baseUrl = "https://cdn.test"), CoroutineScope(Dispatchers.Unconfined))
    }

    @Test
    fun silentCheckWithNoUpdateShowsNothing() {
        val updater = updater(200, BuildConfig.VERSION_NAME)
        updater.check(activity)
        assertEquals(UpdateUiState(), updater.ui.value)
    }

    @Test
    fun manualCheckReportsUpToDate() {
        val updater = updater(200, BuildConfig.VERSION_NAME)
        updater.check(activity, userInitiated = true)
        assertEquals("AINO ${BuildConfig.VERSION_NAME} is up to date.", updater.ui.value.message)
    }

    @Test
    fun failuresAreSilentUnlessManual() {
        val updater = updater(503, BuildConfig.VERSION_NAME)
        updater.check(activity)
        assertNull(updater.ui.value.message)
        updater.check(activity, userInitiated = true)
        assertEquals("Could not check for updates. Try again later.", updater.ui.value.message)
    }

    @Test
    fun manualCheckBringsBackADismissedUpdate() {
        val updater = updater(200, "999.0.0")
        updater.check(activity)
        assertEquals("999.0.0", updater.ui.value.available?.version)
        updater.dismiss()
        updater.check(activity)
        assertNull(updater.ui.value.available)
        updater.check(activity, userInitiated = true)
        assertEquals("999.0.0", updater.ui.value.available?.version)
    }

    // Play Protect's server-side scan classified the in-app APK installer as
    // POTENTIALLY_UNWANTED/generic_malware; the same build without it scanned SAFE.
    @Test
    fun directBuildDoesNotRequestPackageInstalls() {
        val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    @Test
    fun installOpensTheReleaseInTheBrowserInsteadOfThePackageInstaller() {
        val updater = updater(200, "999.0.0")
        updater.check(activity)
        updater.install(activity)

        val intent = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "https://cdn.test/android/releases/android-v999.0.0/AINO-999.0.0-direct.apk",
            intent.data.toString(),
        )
        assertNull(intent.type)
        assertEquals(UpdateUiState(), updater.ui.value)
    }

    @Test
    fun requiredUpdateRechecksAndOpensTheTrustedDownloadEvenAfterDismissal() {
        val updater = updater(200, "999.0.0")
        updater.check(activity)
        updater.dismiss()
        updater.installRequired(activity)
        assertEquals(
            "https://cdn.test/android/releases/android-v999.0.0/AINO-999.0.0-direct.apk",
            shadowOf(activity).nextStartedActivity.data.toString(),
        )
    }

    @Test
    fun failedRequiredUpdateAllowsRetryWithoutClaimingTheClientIsCurrent() {
        val updater = updater(503, BuildConfig.VERSION_NAME)
        updater.installRequired(activity)
        assertFalse(updater.ui.value.loading)
        assertEquals("Could not open the update download. Check your connection and try again.", updater.ui.value.message)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
