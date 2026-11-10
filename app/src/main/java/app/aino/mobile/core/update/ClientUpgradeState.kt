package app.aino.mobile.core.update

import android.content.Context
import app.aino.mobile.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ClientUpgradeRequired(
    val code: String = "CLIENT_UPGRADE_REQUIRED",
    val client: String = "android",
    @SerialName("min_version") val minVersion: String? = null,
    @SerialName("current_version") val currentVersion: String = BuildConfig.VERSION_NAME,
)

/** Installation-wide, not session-wide: only installing a new version clears a refusal. */
class ClientUpgradeState {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutableRequired = MutableStateFlow<ClientUpgradeRequired?>(null)
    val required = mutableRequired.asStateFlow()
    private var persist: (ClientUpgradeRequired) -> Unit = {}

    @Synchronized
    fun observe(statusCode: Int, body: String) {
        if (statusCode != 426) return
        val refusal = runCatching { json.decodeFromString<ClientUpgradeRequired>(body) }.getOrNull()
            ?: mutableRequired.value ?: ClientUpgradeRequired()
        mutableRequired.value = refusal
        persist(refusal)
    }

    @Synchronized
    fun attach(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences("client-upgrade", Context.MODE_PRIVATE)
        if (prefs.getString("installed-version", null) == BuildConfig.VERSION_NAME) {
            prefs.getString("refusal", null)?.let { observe(426, it) }
        } else {
            prefs.edit().clear().apply()
        }
        persist = { refusal ->
            prefs.edit().putString("installed-version", BuildConfig.VERSION_NAME)
                .putString("refusal", json.encodeToString(refusal)).apply()
        }
        mutableRequired.value?.let(persist)
    }
}

object ClientUpgrades {
    val state = ClientUpgradeState()
}
