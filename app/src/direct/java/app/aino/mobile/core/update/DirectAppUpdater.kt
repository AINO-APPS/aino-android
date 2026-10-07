package app.aino.mobile.core.update

import android.app.Activity
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

    override fun check(activity: Activity) {
        if (_ui.value.loading) return
        scope.launch {
            runCatching(repository::check).onSuccess { update ->
                _ui.value = UpdateUiState(available = update?.takeIf { it.version != dismissedVersion })
            }
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
