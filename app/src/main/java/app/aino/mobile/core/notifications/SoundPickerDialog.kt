package app.aino.mobile.core.notifications

import android.app.Activity
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.IntentCompat
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Signal-style sound chooser for one slot: the AINO tones shared with web/desktop
 * (tap to preview), the system default, or any sound on the device. AINO tones
 * are synced through [onPreset]; device choices stay on this phone.
 */
@Composable
fun SoundPickerDialog(category: SoundCategory, onDismiss: () -> Unit, onPreset: (String) -> Unit) {
    val context = LocalContext.current
    val colors = LocalWebColors.current
    var selected by remember { mutableStateOf(NotificationSounds.choice(context, category)) }
    val preview = remember { mutableStateOf<Ringtone?>(null) }
    fun play(uri: Uri?) {
        preview.value?.stop()
        preview.value = uri?.let { runCatching { RingtoneManager.getRingtone(context, it) }.getOrNull() }?.also { runCatching { it.play() } }
    }
    DisposableEffect(Unit) { onDispose { preview.value?.stop() } }
    val apply: (SoundChoice) -> Unit = { choice ->
        preview.value?.stop()
        NotificationSounds.setOverride(context, category, choice)
        if (choice is SoundChoice.Preset) onPreset(choice.id)
        applySoundChannels(context)
        onDismiss()
    }
    val devicePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val picked = result.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            apply(NotificationSettingsLinks.pickedChoice(picked, NotificationSounds.systemDefaultUri(category)))
        }
    }

    @Composable
    fun Option(label: String, checked: Boolean, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = checked, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = colors.primary))
            Text(label, Modifier.padding(start = 12.dp), color = colors.text, fontSize = 16.sp)
        }
    }

    AlertDialog(
        onDismissRequest = { preview.value?.stop(); onDismiss() },
        containerColor = colors.surface,
        title = { Text(category.title, color = colors.text) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                category.presets.forEach { preset ->
                    Option(preset.label, selected == SoundChoice.Preset(preset.id)) {
                        selected = SoundChoice.Preset(preset.id)
                        play(NotificationSounds.presetUri(context, preset))
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.border)
                Option("System default", selected == SoundChoice.SystemDefault) {
                    selected = SoundChoice.SystemDefault
                    play(NotificationSounds.systemDefaultUri(category))
                }
                val device = selected as? SoundChoice.Device
                Option(
                    device?.let { NotificationSettingsLinks.soundTitle(context, it.uri) } ?: "Choose from device…",
                    device != null,
                ) {
                    preview.value?.stop()
                    devicePicker.launch(NotificationSettingsLinks.soundPickerIntent(context, category))
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(selected) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { preview.value?.stop(); onDismiss() }) { Text("Cancel") } },
    )
}

/** Re-creates the channels whose sound follows a slot, after a choice changed. */
fun applySoundChannels(context: android.content.Context) {
    runCatching { app.aino.mobile.core.push.PushNotifications.createChannels(context) }
    runCatching { app.aino.mobile.core.call.IncomingCallNotifications.ensureChannels(context) }
}
