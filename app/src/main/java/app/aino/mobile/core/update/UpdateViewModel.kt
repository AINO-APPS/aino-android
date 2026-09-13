package app.aino.mobile.core.update

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UpdateUiState(
    val loading: Boolean = false,
    val available: AvailableUpdate? = null,
    val message: String? = null,
)

class UpdateViewModel(private val repository: UpdateRepository) : ViewModel() {
    private val _ui = MutableStateFlow(UpdateUiState())
    val ui: StateFlow<UpdateUiState> = _ui.asStateFlow()

    fun check() {
        if (_ui.value.loading) return
        _ui.value = UpdateUiState(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = runCatching(repository::check).fold(
                onSuccess = { update -> UpdateUiState(available = update, message = if (update == null) "AINO is up to date." else null) },
                onFailure = { UpdateUiState(message = it.message ?: "Could not check for updates.") },
            )
        }
    }

    fun install(context: Context) {
        val update = _ui.value.available ?: return
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.downloadAndInstall(context, update) }
                .onFailure { _ui.value = _ui.value.copy(loading = false, message = it.message) }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdateViewModel(UpdateRepository()) as T
        }
    }
}