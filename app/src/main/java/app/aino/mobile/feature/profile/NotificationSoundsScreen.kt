package app.aino.mobile.feature.profile

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.notifications.NotificationSoundPrefs
import app.aino.mobile.core.push.PushNotifications

/**
 * Web `NotificationSoundsModal` as a full page. Prefs sync with the web; tone
 * choices map to Android's own sound settings (system ringtone picker for
 * calls, the Messages channel for messages and mentions). Volume sliders,
 * outgoing tone and reaction sound have no Android counterpart and are omitted.
 */
@Composable
fun NotificationSoundsScreen(viewModel: ProfileViewModel, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val prefs = ui.prefs
    val muted = prefs.muteAll
    // Bumped on resume and after the ringtone picker so the current sound names re-read.
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose { } }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            val isDefault = picked == null || picked == Settings.System.DEFAULT_RINGTONE_URI
            NotificationSoundPrefs.setRingtoneUri(context, if (isDefault) null else picked)
            revision++
        }
    }
    val ringtoneName = remember(revision) { soundTitle(context, NotificationSoundPrefs.ringtoneUri(context) ?: Settings.System.DEFAULT_RINGTONE_URI) }
    val messageSound = remember(revision) { channelSoundTitle(context, PushNotifications.MESSAGES) }
    LaunchedEffect(Unit) { PushNotifications.createChannels(context) }

    ProfilePage("Notification sounds", onBack) {
        ProfileSection {
            ProfileRow(
                icon = if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
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

        ProfileSection("Calls") {
            ProfileRow(
                Icons.Outlined.Phone, "Incoming call ringtone",
                onClick = {
                    ringtonePicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Incoming call ringtone")
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(
                                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                NotificationSoundPrefs.ringtoneUri(context) ?: Settings.System.DEFAULT_RINGTONE_URI,
                            ),
                    )
                },
                supporting = ringtoneName,
                enabled = !muted,
            )
        }

        ProfileSection("Messages") {
            ProfileRow(Icons.Outlined.ChatBubbleOutline, "New message", { openChannelSettings(context, PushNotifications.MESSAGES) }, supporting = messageSound, enabled = !muted)
            RowDivider()
            ProfileRow(Icons.Outlined.AlternateEmail, "Mention / @-tag", { openChannelSettings(context, PushNotifications.MESSAGES) }, supporting = messageSound, enabled = !muted)
        }

        ProfileSection("Behavior") {
            CheckRow(
                "Play sounds even when app is focused",
                "By default, sounds only play when the window is in the background.",
                prefs.playWhenFocused,
            ) { viewModel.updatePref("playWhenFocused", it) }
            CheckRow(
                "Play a sound when I send a message",
                "A subtle confirmation tone after each message is sent.",
                prefs.playOnSend,
            ) { viewModel.updatePref("playOnSend", it) }
        }

        ProfileSection("Privacy") {
            CheckRow(
                "Read receipts",
                "When off, others won't see when you've read their messages — and you won't see theirs either.",
                prefs.readReceipts,
            ) { viewModel.updatePref("readReceipts", it) }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.resetPrefs(); revision++ }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.RestartAlt, null, Modifier.size(16.dp))
                Text("  Reset to defaults")
            }
            androidx.compose.material3.Button(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Done") }
        }
    }
}

@Composable
private fun CheckRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = colors.primary))
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 12.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp)
            Text(hint, color = colors.textMuted, fontSize = 12.sp)
        }
    }
}

private fun soundTitle(context: Context, uri: Uri?): String? =
    uri?.let { runCatching { RingtoneManager.getRingtone(context, it)?.getTitle(context) }.getOrNull() }

private fun channelSoundTitle(context: Context, channelId: String): String? {
    val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId) ?: return null
    return soundTitle(context, channel.sound) ?: "None (silent)"
}

private fun openChannelSettings(context: Context, channelId: String) {
    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
