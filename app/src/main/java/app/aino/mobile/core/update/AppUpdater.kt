package app.aino.mobile.core.update

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

data class UpdateUiState(
    val loading: Boolean = false,
    val available: AvailableUpdate? = null,
    val message: String? = null,
    /** Play only: the update is downloaded and waits for a restart. */
    val readyToInstall: Boolean = false,
)

/**
 * Distribution-specific update channel. `play` uses the Google Play In-App
 * Updates API; `direct` (sideloaded pilots) downloads the signed APK from the
 * R2 `android/` channel. Each flavor provides [createAppUpdater].
 */
interface AppUpdater {
    val ui: StateFlow<UpdateUiState>

    /** Silent check on launch / resume; never shows an error for "no update". */
    fun check(activity: Activity)

    /** User accepted the update banner. */
    fun install(activity: Activity)

    fun dismiss()
}
