package app.aino.mobile.core.update

import android.app.Activity
import app.aino.mobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Sideloaded pilot builds: the R2 `android/latest.json` channel and the system package installer. */
class DirectAppUpdater(
    private val repository: UpdateRepository = UpdateRepository(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AppUpdater {
    private val _ui = MutableStateFlow(UpdateUiState())
    override val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()
    private var dismissedVersion: String? = null

    override fun check(activity: Activity, userInitiated: Boolean) {
        if (_ui.value.loading) return
        if (userInitiated) {
            dismissedVersion = null
            _ui.value = UpdateUiState(loading = true)
        }
        scope.launch {
            runCatching(repository::check)
                .onSuccess { update ->
                    _ui.value = when {
                        update != null && update.version != dismissedVersion -> UpdateUiState(available = update)
                        userInitiated -> UpdateUiState(message = "AINO ${BuildConfig.VERSION_NAME} is up to date.")
                        else -> UpdateUiState()
                    }
                }
                .onFailure { if (userInitiated) _ui.value = UpdateUiState(message = "Could not check for updates. Try again later.") }
        }
    }

    override fun install(activity: Activity) {
        val update = _ui.value.available ?: return
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, message = null)
        val context = activity.applicationContext
        scope.launch {
            runCatching { repository.downloadAndInstall(context, update) }
                .onSuccess { _ui.value = _ui.value.copy(loading = false) }
                .onFailure { _ui.value = _ui.value.copy(loading = false, message = it.message ?: "Could not download the update.") }
        }
    }

    override fun dismiss() {
        dismissedVersion = _ui.value.available?.version
        _ui.value = UpdateUiState()
    }
}

fun createAppUpdater(): AppUpdater = DirectAppUpdater()
