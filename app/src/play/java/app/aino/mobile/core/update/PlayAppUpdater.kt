package app.aino.mobile.core.update

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Google Play builds: the In-App Updates API (flexible flow). Play downloads
 * in the background; the banner then offers a restart to finish installing.
 */
class PlayAppUpdater : AppUpdater {
    private val _ui = MutableStateFlow(UpdateUiState())
    override val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()
    private var manager: AppUpdateManager? = null
    private var info: AppUpdateInfo? = null
    private var dismissedCode: Int? = null

    private val listener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> _ui.value = _ui.value.copy(loading = false, readyToInstall = true)
            InstallStatus.DOWNLOADING, InstallStatus.PENDING -> _ui.value = _ui.value.copy(loading = true)
            InstallStatus.FAILED -> _ui.value = _ui.value.copy(loading = false, message = "The update could not be downloaded.")
            InstallStatus.CANCELED -> _ui.value = _ui.value.copy(loading = false)
            else -> Unit
        }
    }

    private fun manager(activity: Activity): AppUpdateManager =
        manager ?: AppUpdateManagerFactory.create(activity.applicationContext).also {
            it.registerListener(listener)
            manager = it
        }

    override fun check(activity: Activity) {
        manager(activity).appUpdateInfo.addOnSuccessListener { update ->
            info = update
            when {
                update.installStatus() == InstallStatus.DOWNLOADED ->
                    _ui.value = UpdateUiState(available = update.asAvailable(), readyToInstall = true)
                update.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    update.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                    update.availableVersionCode() != dismissedCode ->
                    _ui.value = UpdateUiState(available = update.asAvailable())
                else -> if (!_ui.value.loading) _ui.value = UpdateUiState()
            }
        }
        // No Play Store / not installed from Play (e.g. internal sideload): stay silent.
    }

    override fun install(activity: Activity) {
        val manager = manager(activity)
        if (_ui.value.readyToInstall) {
            manager.completeUpdate()
            return
        }
        val update = info ?: return
        _ui.value = _ui.value.copy(loading = true, message = null)
        runCatching {
            manager.startUpdateFlow(update, activity, AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build())
                .addOnSuccessListener { accepted -> if (accepted != Activity.RESULT_OK) _ui.value = _ui.value.copy(loading = false) }
                .addOnFailureListener { _ui.value = _ui.value.copy(loading = false, message = "Could not start the update.") }
        }.onFailure { _ui.value = _ui.value.copy(loading = false, message = "Could not start the update.") }
    }

    override fun dismiss() {
        dismissedCode = info?.availableVersionCode()
        _ui.value = UpdateUiState()
    }

    private fun AppUpdateInfo.asAvailable() =
        AvailableUpdate(version = availableVersionCode().toString(), apkUrl = "", notes = "")
}

fun createAppUpdater(): AppUpdater = PlayAppUpdater()
