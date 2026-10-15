package app.aino.mobile.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.notifications.NotificationSettingsLinks
import app.aino.mobile.core.push.PushNotifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App-wide alert settings. Chat sounds, calls and chat privacy live in Chat →
 * Settings (Signal keeps them under its own Settings); this page keeps push
 * delivery, Mute all and the @-mention tone used by tasks and notes too.
 */
@Composable
fun NotificationSoundsScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val muted = ui.prefs.muteAll
    // Bumped on resume so the current sound names and channel states re-read.
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose { } }
    var picking by remember { mutableStateOf(false) }
    val mentionSound = remember(revision, ui.prefs.mentionTone, picking) {
        app.aino.mobile.core.notifications.NotificationSounds.title(context, app.aino.mobile.core.notifications.SoundCategory.Mention)
    }
    LaunchedEffect(Unit) { PushNotifications.createChannels(context) }
    if (picking) app.aino.mobile.core.notifications.SoundPickerDialog(
        app.aino.mobile.core.notifications.SoundCategory.Mention,
        onDismiss = { picking = false; revision++ },
    ) { id -> viewModel.updatePref("mentionTone", id) }

    ProfilePage("Notification sounds", onBack) {
        ProfileSection("Notification delivery") {
            val ready = remember(revision) { app.aino.mobile.core.push.PushTokenRegistrar.isRegistered(context) }
            ProfileRow(HeroIcons.ChatBubbleOvalLeft, "Background push",
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.aino.mobile.core.push.PushTokenRegistrar(context.applicationContext).syncCurrentToken() }
                        revision++
                    }
                },
                supporting = if (ready) "Registered for this account" else "Not registered — tap to retry. Local debug builds may lack Firebase.")
            ProfileRow(HeroIcons.ChatBubbleOvalLeft, "App notifications",
                onClick = { NotificationSettingsLinks.openAppNotificationSettings(context) },
                supporting = if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) "Allowed" else "Disabled — tap to open Android settings")
        }
        ProfileSection {
            ProfileRow(
                icon = if (muted) HeroIcons.SpeakerXMark else HeroIcons.SpeakerWave,
                label = "Mute all sounds",
                supporting = "Disables every ringtone, message and mention sound until turned off.",
                onClick = { viewModel.updatePref("muteAll", !muted) },
                trailing = {
                    Switch(
                        checked = muted,
                        onCheckedChange = { viewModel.updatePref("muteAll", it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.primary),
                    )
                },
            )
        }
        ProfileSection("Mentions") {
            // Task / note / chat @-mentions post on their own channel.
            ProfileRow(
                HeroIcons.AtSymbol, "Mention / @-tag",
                { picking = true },
                supporting = mentionSound, enabled = !muted,
            )
        }
        ProfileSection {
            ProfileRow(
                HeroIcons.ChatBubbleOvalLeft, "Chat notifications, calls and privacy",
                onClick = {},
                supporting = "Open Chat, tap ⋮ and then Settings.",
            )
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.resetPrefs(); revision++ }, modifier = Modifier.weight(1f)) {
                Icon(HeroIcons.ArrowPath, null, Modifier.size(16.dp))
                Text("  Reset to defaults")
            }
            androidx.compose.material3.Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}
