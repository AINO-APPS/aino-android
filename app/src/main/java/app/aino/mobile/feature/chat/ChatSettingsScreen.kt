package app.aino.mobile.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.notifications.NotificationSettingsLinks
import app.aino.mobile.core.notifications.NotificationSounds
import app.aino.mobile.core.notifications.SoundCategory
import app.aino.mobile.core.notifications.SoundPickerDialog
import app.aino.mobile.core.push.PushNotifications
import app.aino.mobile.core.settings.AutoDownloadKind
import app.aino.mobile.core.settings.ChatPreferences
import app.aino.mobile.core.settings.ChatPrefs
import app.aino.mobile.core.settings.LowDataCalls
import app.aino.mobile.core.settings.NotificationContent
import app.aino.mobile.core.settings.SCREEN_LOCK_TIMEOUTS
import app.aino.mobile.core.storage.StorageUsage
import kotlinx.coroutines.launch

/** Signal Settings pages reachable from the chat list's ⋮ → Settings. */
internal enum class ChatSettingsPage(val title: String) {
    Main("Settings"),
    Notifications("Notifications"),
    Privacy("Privacy"),
    Blocked("Blocked"),
    Data("Data and storage"),
    Storage("Manage storage"),
    Chats("Chats"),
}

/** The signed-in user shown on the settings profile card. */
data class ChatSettingsProfile(val name: String, val username: String, val avatar: String?)

/**
 * Signal-style settings for chat. Synced keys go through [onSyncedChange] (the
 * profile prefs endpoint shared with web and desktop); device keys are stored here.
 */
@Composable
fun ChatSettingsScreen(
    viewModel: ChatViewModel,
    profile: ChatSettingsProfile,
    onEditProfile: () -> Unit,
    onSyncedChange: (key: String, value: Any) -> Unit,
    screenLockAvailable: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs by ChatPreferences.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { ChatPreferences.ensureLoaded(context) }
    var page by remember { mutableStateOf(ChatSettingsPage.Main) }
    val up = {
        page = when (page) {
            ChatSettingsPage.Main -> { onBack(); ChatSettingsPage.Main }
            ChatSettingsPage.Blocked -> ChatSettingsPage.Privacy
            ChatSettingsPage.Storage -> ChatSettingsPage.Data
            else -> ChatSettingsPage.Main
        }
    }
    BackHandler(enabled = page != ChatSettingsPage.Main) { up() }
    val local: ((ChatPrefs) -> ChatPrefs) -> Unit = { change -> ChatPreferences.update(context, change) }
    val synced: (String, Any, (ChatPrefs) -> ChatPrefs) -> Unit = { key, value, change ->
        local(change)
        onSyncedChange(key, value)
    }
    Column(Modifier.fillMaxSize().background(signalColors.background)) {
        GroupTopBar(page.title, onBack = up)
        when (page) {
            ChatSettingsPage.Main -> MainPage(profile, onEditProfile) { page = it }
            ChatSettingsPage.Notifications -> NotificationsPage(prefs, local, synced)
            ChatSettingsPage.Privacy -> PrivacyPage(prefs, screenLockAvailable, local, synced) { page = ChatSettingsPage.Blocked }
            ChatSettingsPage.Blocked -> BlockedPage(viewModel)
            ChatSettingsPage.Data -> DataPage(prefs, local, synced) { page = ChatSettingsPage.Storage }
            ChatSettingsPage.Storage -> StoragePage()
            ChatSettingsPage.Chats -> ChatsPage(prefs, local, synced)
        }
    }
}

@Composable
private fun MainPage(profile: ChatSettingsProfile, onEditProfile: () -> Unit, onPage: (ChatSettingsPage) -> Unit) {
    val signal = signalColors
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            // Signal's profile card: avatar, name and username; tap to edit the profile.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onEditProfile).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UserAvatar(profile.name, profile.avatar, 64.dp)
                Column(Modifier.padding(start = 20.dp).weight(1f)) {
                    Text(profile.name, color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profile.username.isNotBlank()) Text("@${profile.username}", color = signal.textSecondary, fontSize = 15.sp, maxLines = 1)
                }
            }
        }
        item { SectionDivider() }
        item { SettingsRow(Icons.Outlined.Notifications, "Notifications") { onPage(ChatSettingsPage.Notifications) } }
        item { SettingsRow(Icons.Outlined.Lock, "Privacy") { onPage(ChatSettingsPage.Privacy) } }
        item { SettingsRow(Icons.Outlined.DataUsage, "Data and storage") { onPage(ChatSettingsPage.Data) } }
        item { SettingsRow(Icons.Outlined.ChatBubbleOutline, "Chats") { onPage(ChatSettingsPage.Chats) } }
    }
}

// ── Notifications ────────────────────────────────────────────────────────

@Composable
private fun NotificationsPage(
    prefs: ChatPrefs,
    local: ((ChatPrefs) -> ChatPrefs) -> Unit,
    synced: (String, Any, (ChatPrefs) -> ChatPrefs) -> Unit,
) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose { } }
    LaunchedEffect(Unit) { PushNotifications.createChannels(context) }
    var soundSlot by remember { mutableStateOf<SoundCategory?>(null) }
    // Re-read after a pick (prefs change) or a return from Android's settings.
    fun sound(category: SoundCategory) = NotificationSounds.title(context, category)
    val soundKey = listOf(revision, prefs.messageTone, prefs.mentionTone, prefs.reactionTone, prefs.ringtone, prefs.outgoingTone, soundSlot)
    val vibrates = remember(revision) { NotificationSettingsLinks.channelVibrates(context, PushNotifications.MESSAGES) }
    val messages = remember(revision) { app.aino.mobile.core.push.notificationBlocker(context, PushNotifications.MESSAGES)?.reason }
    val reactions = remember(revision) { app.aino.mobile.core.push.notificationBlocker(context, PushNotifications.REACTIONS)?.reason }
    val missed = remember(revision) { app.aino.mobile.core.push.notificationBlocker(context, PushNotifications.MISSED_CALLS)?.reason }
    val titles = remember(soundKey) { SoundCategory.entries.associateWith(::sound) }
    val disabledChats = remember(revision) { NotificationSettingsLinks.disabledConversationChannels(context, PushNotifications.MESSAGES) }
    var showPicker by remember { mutableStateOf(false) }
    val muted = prefs.muteAll
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (muted) item { Hint("Mute all sounds is on in Profile → Notification sounds, so chat sounds stay silent.") }
        item { SectionTitle("Messages") }
        item {
            SettingsRow(null, "Notifications", subtitle = messages ?: "On") {
                NotificationSettingsLinks.openChannelSettings(context, PushNotifications.MESSAGES)
            }
        }
        item { SettingsRow(null, "Sound", subtitle = titles[SoundCategory.Message]) { soundSlot = SoundCategory.Message } }
        item {
            SettingsRow(null, "Vibrate", subtitle = when (vibrates) { true -> "On"; false -> "Off"; null -> "System default" }) {
                NotificationSettingsLinks.openChannelSettings(context, PushNotifications.MESSAGES)
            }
        }
        item { SettingsRow(null, "Show", subtitle = prefs.notificationContent.label) { showPicker = true } }
        item {
            ToggleRow("In-chat sounds", "Play the message sound when a message arrives in the chat you have open.", prefs.inChatSounds, enabled = !muted) { on ->
                local { it.copy(inChatSounds = on) }
            }
        }
        item {
            ToggleRow("Sound when sending", "Play a short sound after each message you send.", prefs.playOnSend, enabled = !muted) { on ->
                synced("playOnSend", on) { it.copy(playOnSend = on) }
            }
        }
        item { SettingsRow(null, "Mention sound", subtitle = titles[SoundCategory.Mention]) { soundSlot = SoundCategory.Mention } }
        item { SettingsRow(null, "Reactions", subtitle = reactions ?: "On") { NotificationSettingsLinks.openChannelSettings(context, PushNotifications.REACTIONS) } }
        item { SettingsRow(null, "Reaction sound", subtitle = titles[SoundCategory.Reaction]) { soundSlot = SoundCategory.Reaction } }
        items(disabledChats, key = { it.id }) { channel ->
            SettingsRow(null, channel.name.toString(), subtitle = "Notifications off for this chat") {
                NotificationSettingsLinks.openChannelSettings(context, channel.id)
            }
        }
        item { SectionDivider() }
        item { SectionTitle("Calls") }
        item { SettingsRow(Icons.Outlined.Call, "Ringtone", subtitle = titles[SoundCategory.Ringtone]) { soundSlot = SoundCategory.Ringtone } }
        item { SettingsRow(null, "Outgoing call ring", subtitle = titles[SoundCategory.Outgoing]) { soundSlot = SoundCategory.Outgoing } }
        item { SettingsRow(null, "Missed calls", subtitle = missed ?: "On") { NotificationSettingsLinks.openChannelSettings(context, PushNotifications.MISSED_CALLS) } }
    }
    soundSlot?.let { category ->
        SoundPickerDialog(category, onDismiss = { soundSlot = null; revision++ }) { id ->
            synced(category.syncKey, id) { category.withSyncedId(it, id) }
        }
    }
    if (showPicker) ChoiceDialog(
        title = "Show",
        options = NotificationContent.entries.map { it to it.label },
        selected = prefs.notificationContent,
        onDismiss = { showPicker = false },
    ) { choice ->
        showPicker = false
        // Hiding the content also hides it in pushes the server builds (shared with web and desktop).
        synced("hideSensitiveContent", choice != NotificationContent.NameAndMessage) {
            it.copy(notificationContent = choice, hideSensitiveContent = choice != NotificationContent.NameAndMessage)
        }
    }
}

// ── Privacy ──────────────────────────────────────────────────────────────

@Composable
private fun PrivacyPage(
    prefs: ChatPrefs,
    screenLockAvailable: Boolean,
    local: ((ChatPrefs) -> ChatPrefs) -> Unit,
    synced: (String, Any, (ChatPrefs) -> ChatPrefs) -> Unit,
    onBlocked: () -> Unit,
) {
    var timeoutPicker by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { SettingsRow(HeroIcons.NoSymbol, "Blocked", onClick = onBlocked) }
        item { SectionDivider() }
        item { SectionTitle("Messaging") }
        item {
            ToggleRow("Read receipts", "If read receipts are disabled, you won't be able to see read receipts from others.", prefs.readReceipts) { on ->
                synced("readReceipts", on) { it.copy(readReceipts = on) }
            }
        }
        item {
            ToggleRow("Typing indicators", "If typing indicators are disabled, you won't be able to see typing indicators from others.", prefs.typingIndicators) { on ->
                synced("typingIndicators", on) { it.copy(typingIndicators = on) }
            }
        }
        item { SectionDivider() }
        item { SectionTitle("App security") }
        item {
            ToggleRow(
                "Screen lock",
                if (screenLockAvailable) "Lock AINO with your fingerprint, face or phone PIN." else "Set up a screen lock on this phone to use this.",
                prefs.screenLock, enabled = screenLockAvailable || prefs.screenLock,
            ) { on -> local { it.copy(screenLock = on) } }
        }
        if (prefs.screenLock) item {
            SettingsRow(null, "Screen lock timeout", subtitle = SCREEN_LOCK_TIMEOUTS.firstOrNull { it.first == prefs.screenLockTimeoutMs }?.second ?: "Instant") {
                timeoutPicker = true
            }
        }
        item {
            ToggleRow("Screen security", "Block screenshots in the recents list and inside the app.", prefs.screenSecurity) { on ->
                local { it.copy(screenSecurity = on) }
            }
        }
        item {
            ToggleRow("Incognito keyboard", "Ask the keyboard not to learn what you type in chats.", prefs.incognitoKeyboard) { on ->
                local { it.copy(incognitoKeyboard = on) }
            }
        }
    }
    if (timeoutPicker) ChoiceDialog(
        title = "Screen lock timeout",
        options = SCREEN_LOCK_TIMEOUTS,
        selected = prefs.screenLockTimeoutMs,
        onDismiss = { timeoutPicker = false },
    ) { value -> timeoutPicker = false; local { it.copy(screenLockTimeoutMs = value) } }
}

@Composable
private fun BlockedPage(viewModel: ChatViewModel) {
    val signal = signalColors
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<ChatUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<ChatUser?>(null) }
    LaunchedEffect(Unit) {
        viewModel.loadBlockedUsers().onSuccess { users = it }.onFailure { error = "Couldn't load blocked people." }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Hint("Blocked people can't call you or send you messages.") }
        val list = users
        when {
            list == null && error == null -> item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = signal.primary, strokeWidth = 2.dp)
                }
            }
            error != null -> item { Hint(error.orEmpty()) }
            list.isNullOrEmpty() -> item { Hint("No blocked people") }
            else -> items(list, key = { it.id }) { user ->
                Row(
                    Modifier.fillMaxWidth().clickable { confirm = user }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(user.display(), user.avatar, 40.dp)
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(user.display(), color = signal.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        user.username?.takeIf(String::isNotBlank)?.let { Text("@$it", color = signal.textSecondary, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
    confirm?.let { user ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = signal.surface,
            title = { Text("Unblock ${user.display()}?", color = signal.text) },
            text = { Text("You'll be able to message and call each other.", color = signal.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        viewModel.unblockUser(user.id)
                            .onSuccess { users = users?.filterNot { it.id == user.id } }
                            .onFailure { error = "Couldn't unblock. Try again." }
                    }
                }) { Text("Unblock") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

// ── Data and storage ─────────────────────────────────────────────────────

@Composable
private fun DataPage(
    prefs: ChatPrefs,
    local: ((ChatPrefs) -> ChatPrefs) -> Unit,
    synced: (String, Any, (ChatPrefs) -> ChatPrefs) -> Unit,
    onStorage: () -> Unit,
) {
    var editing by remember { mutableStateOf<String?>(null) }
    var callsPicker by remember { mutableStateOf(false) }
    fun label(kinds: Set<AutoDownloadKind>) = if (kinds.isEmpty()) "None" else AutoDownloadKind.entries.filter { it in kinds }.joinToString(", ") { it.label }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { SettingsRow(Icons.Outlined.DataUsage, "Manage storage", onClick = onStorage) }
        item { SectionDivider() }
        item { SectionTitle("Media auto-download") }
        item { SettingsRow(null, "When using mobile data", subtitle = label(prefs.autoDownloadMobile)) { editing = "mobile" } }
        item { SettingsRow(null, "When using Wi-Fi", subtitle = label(prefs.autoDownloadWifi)) { editing = "wifi" } }
        item { SettingsRow(null, "When roaming", subtitle = label(prefs.autoDownloadRoaming)) { editing = "roaming" } }
        item { Hint("Voice messages always download automatically. Media that isn't downloaded shows a tap-to-download button.") }
        item { SectionDivider() }
        item { SectionTitle("Media quality") }
        item {
            ToggleRow("Send high quality media", "Photos and videos you send use more data but keep more detail.", prefs.highQualityMedia) { on ->
                val quality = if (on) "high" else "standard"
                synced("sentMediaQuality", quality) { it.copy(sentMediaQuality = quality) }
            }
        }
        item { SectionDivider() }
        item { SectionTitle("Calls") }
        item { SettingsRow(null, "Use less data for calls", subtitle = prefs.lowDataCalls.label) { callsPicker = true } }
    }
    editing?.let { network ->
        val current = when (network) { "mobile" -> prefs.autoDownloadMobile; "wifi" -> prefs.autoDownloadWifi; else -> prefs.autoDownloadRoaming }
        MultiChoiceDialog(
            title = when (network) { "mobile" -> "When using mobile data"; "wifi" -> "When using Wi-Fi"; else -> "When roaming" },
            options = AutoDownloadKind.entries.map { it to it.label },
            selected = current,
            onDismiss = { editing = null },
        ) { chosen ->
            editing = null
            local {
                when (network) {
                    "mobile" -> it.copy(autoDownloadMobile = chosen)
                    "wifi" -> it.copy(autoDownloadWifi = chosen)
                    else -> it.copy(autoDownloadRoaming = chosen)
                }
            }
        }
    }
    if (callsPicker) ChoiceDialog(
        title = "Use less data for calls",
        options = LowDataCalls.entries.map { it to it.label },
        selected = prefs.lowDataCalls,
        onDismiss = { callsPicker = false },
    ) { choice -> callsPicker = false; local { it.copy(lowDataCalls = choice) } }
}

@Composable
private fun StoragePage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage.Breakdown?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { usage = StorageUsage.measure(context) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        val current = usage
        item { SectionTitle("Storage used: ${current?.let { formatChatFileSize(it.total).orEmpty() } ?: "…"}") }
        item { SettingsRow(HeroIcons.Photo, "Photos and images", trailing = current?.let { formatChatFileSize(it.images).orEmpty() } ?: "…") }
        item { SettingsRow(HeroIcons.Film, "Videos", trailing = current?.let { formatChatFileSize(it.videos).orEmpty() } ?: "…") }
        item { SettingsRow(HeroIcons.DocumentText, "Files and other cache", trailing = current?.let { formatChatFileSize(it.other).orEmpty() } ?: "…") }
        item { SettingsRow(HeroIcons.ChatBubbleOvalLeft, "Saved messages", trailing = current?.let { formatChatFileSize(it.database).orEmpty() } ?: "…") }
        item { SectionDivider() }
        item {
            SettingsRow(HeroIcons.Trash, if (clearing) "Clearing…" else "Clear cache", danger = true, onClick = if (clearing) null else ({ confirm = true }))
        }
        item { Hint("Clears downloaded photos, videos and files. Messages and anything waiting to be sent are kept; media downloads again when you open it.") }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        containerColor = signalColors.surface,
        title = { Text("Clear cache?", color = signalColors.text) },
        text = { Text("Downloaded media will be removed from this phone.", color = signalColors.textSecondary) },
        confirmButton = {
            TextButton(onClick = {
                confirm = false
                clearing = true
                scope.launch {
                    StorageUsage.clearCache(context)
                    usage = StorageUsage.measure(context)
                    clearing = false
                }
            }) { Text("Clear") }
        },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

// ── Chats ────────────────────────────────────────────────────────────────

@Composable
private fun ChatsPage(
    prefs: ChatPrefs,
    local: ((ChatPrefs) -> ChatPrefs) -> Unit,
    synced: (String, Any, (ChatPrefs) -> ChatPrefs) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ToggleRow("Generate link previews", "Retrieve link previews directly from websites for messages you send.", prefs.linkPreviews) { on ->
                synced("linkPreviews", on) { it.copy(linkPreviews = on) }
            }
        }
        item {
            ToggleRow("Send with enter", "Pressing the enter key will send text messages.", prefs.enterSends) { on ->
                local { it.copy(enterSends = on) }
            }
        }
    }
}

// ── Shared rows ──────────────────────────────────────────────────────────

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled) { onChange(!checked) }
            .padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) signal.text else signal.textSecondary, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = signal.textSecondary, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = signal.primary),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = signalColors.textSecondary, fontSize = 13.sp)
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onDismiss: () -> Unit, onPick: (T) -> Unit) {
    val signal = signalColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = signal.surface,
        title = { Text(title, color = signal.text) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(value) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = value == selected, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp), color = signal.text, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun <T> MultiChoiceDialog(title: String, options: List<Pair<T, String>>, selected: Set<T>, onDismiss: () -> Unit, onDone: (Set<T>) -> Unit) {
    val signal = signalColors
    var chosen by remember(selected) { mutableStateOf(selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = signal.surface,
        title = { Text(title, color = signal.text) },
        text = {
            Column {
                options.forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { chosen = if (value in chosen) chosen - value else chosen + value }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = value in chosen, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = signal.primary))
                        Text(label, Modifier.padding(start = 12.dp), color = signal.text, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
