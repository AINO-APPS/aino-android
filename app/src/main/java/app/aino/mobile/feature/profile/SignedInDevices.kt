package app.aino.mobile.feature.profile

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `GET sessions` row (P2.7): one signed-in device of the current user. */
@Serializable
data class SignedInDevice(
    val id: String,
    val device: String,
    val clientClass: String = "web",
    val createdAt: String? = null,
    val lastActiveAt: String? = null,
    val current: Boolean = false,
)

class SignedInDevicesRepository(private val api: ApiClient, private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun list(): List<SignedInDevice> =
        json.decodeFromString(api.execute(ApiRequest(path = "sessions")).bodyAsString())

    fun signOut(sessionId: String) {
        // @api DELETE sessions/:id
        api.execute(ApiRequest(method = "DELETE", path = "sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}"))
    }
}

data class SignedInDevicesUi(
    val devices: List<SignedInDevice> = emptyList(),
    val loading: Boolean = true,
    val busyId: String? = null,
    val error: String? = null,
)

class SignedInDevicesViewModel(private val repository: SignedInDevicesRepository) : ViewModel() {
    private val _ui = MutableStateFlow(SignedInDevicesUi())
    val ui: StateFlow<SignedInDevicesUi> = _ui.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching(repository::list) }
            _ui.update { state ->
                result.fold(
                    onSuccess = { state.copy(devices = it, loading = false) },
                    onFailure = { state.copy(loading = false, error = it.userMessage("Couldn't load your devices")) },
                )
            }
        }
    }

    fun signOut(device: SignedInDevice) {
        if (device.current || _ui.value.busyId != null) return
        viewModelScope.launch {
            _ui.update { it.copy(busyId = device.id, error = null) }
            val result = withContext(Dispatchers.IO) { runCatching { repository.signOut(device.id) } }
            _ui.update { state ->
                result.fold(
                    onSuccess = { state.copy(devices = state.devices.filterNot { it.id == device.id }, busyId = null) },
                    onFailure = { state.copy(busyId = null, error = it.userMessage("Couldn't sign that device out")) },
                )
            }
        }
    }

    private fun Throwable.userMessage(fallback: String): String =
        (this as? ApiError.Http)?.let { app.aino.mobile.core.network.serverErrorText(it.responseBody) } ?: fallback

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SignedInDevicesViewModel(SignedInDevicesRepository(app.aino.mobile.core.AppContainer.get(context).api)) as T
        }
    }
}

/** "x min ago" style label for an ISO timestamp; empty when unparseable. */
internal fun lastActiveLabel(iso: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val at = iso?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return ""
    val minutes = ((nowMillis - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 2 -> "Active now"
        minutes < 60 -> "Active $minutes min ago"
        minutes < 48 * 60 -> "Active ${minutes / 60} h ago"
        else -> "Active ${minutes / (24 * 60)} days ago"
    }
}

/**
 * Profile → Signed-in devices: where this account is signed in (one phone and
 * one browser / desktop at a time), with "Sign out" for the others.
 */
@Composable
fun SignedInDevicesScreen(viewModel: SignedInDevicesViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    var confirm by remember { mutableStateOf<SignedInDevice?>(null) }
    LaunchedEffect(Unit) { viewModel.refresh() }

    ProfilePage("Signed-in devices", onBack) {
        Text(
            "Your account can be signed in on one phone and one browser or desktop app at a time. Signing in somewhere new signs the old device of that kind out.",
            Modifier.padding(horizontal = 4.dp),
            color = colors.textSecondary, fontSize = 13.sp,
        )
        ui.error?.let { Text(it, Modifier.padding(horizontal = 4.dp), color = colors.danger, fontSize = 13.sp) }
        if (ui.loading && ui.devices.isEmpty()) {
            CircularProgressIndicator(Modifier.padding(24.dp).size(28.dp), color = colors.primary)
        }
        if (ui.devices.isNotEmpty()) {
            ProfileSection {
                ui.devices.forEachIndexed { index, device ->
                    if (index > 0) RowDivider()
                    val subtitle = listOfNotNull(
                        "This device".takeIf { device.current },
                        lastActiveLabel(device.lastActiveAt).takeIf(String::isNotEmpty),
                    ).joinToString(" · ")
                    ProfileRow(
                        icon = if (device.clientClass == "mobile") HeroIcons.DevicePhoneMobile else HeroIcons.ComputerDesktop,
                        label = device.device,
                        supporting = subtitle.ifEmpty { null },
                        onClick = null,
                        trailing = {
                            if (!device.current) {
                                if (ui.busyId == device.id) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.primary)
                                else TextButton(onClick = { confirm = device }) { Text("Sign out", color = colors.danger) }
                            }
                        },
                    )
                }
            }
        }
    }

    confirm?.let { device ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sign out ${device.device}?") },
            text = { Text("That device will be signed out right away and will need your password to sign in again.") },
            confirmButton = {
                TextButton(onClick = { viewModel.signOut(device); confirm = null }) { Text("Sign out", color = colors.danger) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Suppress("unused")
private val spacing = Arrangement.spacedBy(8.dp)
