package app.aino.mobile.core.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Signal "Show" for message notifications. */
@Serializable
enum class NotificationContent(val label: String) {
    NameAndMessage("Name and message"),
    NameOnly("Name only"),
    Nothing("No name or message"),
}

/** Media that downloads by itself in a thread; the rest waits for a tap. */
@Serializable
enum class AutoDownloadKind(val label: String) { Photos("Photos"), Videos("Videos") }

enum class NetworkKind { Wifi, Mobile, Roaming }

/** Signal "Use less data for calls". */
@Serializable
enum class LowDataCalls(val label: String) {
    Never("Off"),
    MobileOnly("Mobile data only"),
    Always("Wi-Fi and mobile data"),
}

/**
 * Chat settings. The first block mirrors the account's synced prefs
 * (`profile/notification-prefs`, shared with web and desktop); the rest are
 * device choices, as Signal keeps screen lock and auto-download per device.
 */
@Serializable
data class ChatPrefs(
    val muteAll: Boolean = false,
    val playOnSend: Boolean = false,
    val readReceipts: Boolean = true,
    val typingIndicators: Boolean = true,
    val linkPreviews: Boolean = true,
    val sentMediaQuality: String = "standard",
    val hideSensitiveContent: Boolean = false,
    val ringtone: String = "classic",
    val outgoingTone: String = "ringback",
    val messageTone: String = "ding",
    val mentionTone: String = "mention",
    val reactionTone: String = "subtle",
    val inChatSounds: Boolean = false,
    val notificationContent: NotificationContent = NotificationContent.NameAndMessage,
    val screenLock: Boolean = false,
    val screenLockTimeoutMs: Long = 0,
    val screenSecurity: Boolean = false,
    val incognitoKeyboard: Boolean = false,
    val autoDownloadWifi: Set<AutoDownloadKind> = AutoDownloadKind.entries.toSet(),
    val autoDownloadMobile: Set<AutoDownloadKind> = setOf(AutoDownloadKind.Photos),
    val autoDownloadRoaming: Set<AutoDownloadKind> = emptySet(),
    val lowDataCalls: LowDataCalls = LowDataCalls.Never,
    val enterSends: Boolean = false,
) {
    val highQualityMedia: Boolean get() = sentMediaQuality == "high"

    fun autoDownload(kind: AutoDownloadKind, network: NetworkKind): Boolean = kind in when (network) {
        NetworkKind.Wifi -> autoDownloadWifi
        NetworkKind.Mobile -> autoDownloadMobile
        NetworkKind.Roaming -> autoDownloadRoaming
    }

    fun lowDataFor(network: NetworkKind): Boolean = when (lowDataCalls) {
        LowDataCalls.Never -> false
        LowDataCalls.MobileOnly -> network != NetworkKind.Wifi
        LowDataCalls.Always -> true
    }

    /** Server-synced keys as sent to `PUT profile/notification-prefs`. */
    fun syncedValue(key: String): Any? = when (key) {
        "muteAll" -> muteAll
        "playOnSend" -> playOnSend
        "readReceipts" -> readReceipts
        "typingIndicators" -> typingIndicators
        "linkPreviews" -> linkPreviews
        "sentMediaQuality" -> sentMediaQuality
        "hideSensitiveContent" -> hideSensitiveContent
        "ringtone" -> ringtone
        "outgoingTone" -> outgoingTone
        "messageTone" -> messageTone
        "mentionTone" -> mentionTone
        "reactionTone" -> reactionTone
        else -> null
    }
}

val SCREEN_LOCK_TIMEOUTS: List<Pair<Long, String>> = listOf(
    0L to "Instant",
    60_000L to "1 minute",
    5 * 60_000L to "5 minutes",
    30 * 60_000L to "30 minutes",
    60 * 60_000L to "1 hour",
)

/** Process-wide chat settings, persisted on the device and observable by the UI. */
object ChatPreferences {
    private const val FILE = "aino_chat_prefs"
    private const val KEY = "prefs"
    private const val LEGACY_SOUNDS_FILE = "aino_notification_sounds"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(ChatPrefs())
    val state: StateFlow<ChatPrefs> = _state.asStateFlow()

    @Volatile private var loaded = false
    @Volatile private var appContext: Context? = null

    /** The current prefs; the first call with a context loads them from disk. */
    fun get(context: Context): ChatPrefs {
        ensureLoaded(context)
        return _state.value
    }

    /** Last loaded value, for callers without a context (WebRTC peers). */
    val current: ChatPrefs get() = _state.value

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = context.applicationContext
            appContext = app
            val stored = app.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            _state.value = stored?.let { runCatching { json.decodeFromString(ChatPrefs.serializer(), it) }.getOrNull() }
                ?: legacy(app)
            loaded = true
        }
    }

    fun update(context: Context, transform: (ChatPrefs) -> ChatPrefs) {
        ensureLoaded(context)
        val next = synchronized(this) {
            _state.update(transform)
            _state.value
        }
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, json.encodeToString(ChatPrefs.serializer(), next)).apply()
    }

    /** Signed-out devices must not keep the previous user's choices. */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        synchronized(this) { _state.value = ChatPrefs(); loaded = true }
    }

    /** The network media would be fetched over now. */
    fun network(context: Context? = appContext): NetworkKind {
        val manager = context?.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.Wifi
        val caps = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull() ?: return NetworkKind.Wifi
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Wifi
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) -> NetworkKind.Roaming
            else -> NetworkKind.Mobile
        }
    }

    /** Builds before 0.25 kept mute / send sound in the sound prefs file. */
    private fun legacy(context: Context): ChatPrefs {
        val old = context.getSharedPreferences(LEGACY_SOUNDS_FILE, Context.MODE_PRIVATE)
        return ChatPrefs(
            muteAll = old.getBoolean("muteAll", false),
            playOnSend = old.getBoolean("playOnSend", false),
        )
    }

    internal fun resetForTest() = synchronized(this) { loaded = false; _state.value = ChatPrefs() }
}
