package app.aino.mobile.core.notifications

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings

/** Shortcuts into Android's own sound and channel settings, shared by the chat and profile settings pages. */
object NotificationSettingsLinks {
    fun soundTitle(context: Context, uri: Uri?): String? =
        uri?.let { runCatching { RingtoneManager.getRingtone(context, it)?.getTitle(context) }.getOrNull() }

    fun channelSoundTitle(context: Context, channelId: String): String? {
        val channel = context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(app.aino.mobile.core.push.PushNotifications.channel(context, channelId)) ?: return null
        return soundTitle(context, channel.sound) ?: "None (silent)"
    }

    fun channelVibrates(context: Context, channelId: String): Boolean? =
        context.getSystemService(NotificationManager::class.java)
            ?.getNotificationChannel(app.aino.mobile.core.push.PushNotifications.channel(context, channelId))?.shouldVibrate()

    fun openChannelSettings(context: Context, channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, app.aino.mobile.core.push.PushNotifications.channel(context, channelId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun openAppNotificationSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** System sound picker for "Choose from device…" on a sound slot. */
    fun soundPickerIntent(context: Context, category: SoundCategory): Intent {
        val current = NotificationSounds.choice(context, category)
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, category.pickerType)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, category.title)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, NotificationSounds.systemDefaultUri(category))
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                (current as? SoundChoice.Device)?.uri ?: NotificationSounds.systemDefaultUri(category),
            )
    }

    /** Picker result: the system default, silence (the AINO "None" tone) or a device sound. */
    internal fun pickedChoice(picked: Uri?, systemDefault: Uri): SoundChoice = when (picked) {
        null -> SoundChoice.Preset("none")
        systemDefault -> SoundChoice.SystemDefault
        else -> SoundChoice.Device(picked)
    }

    /** Android 11+ per-chat channels the user switched off. */
    fun disabledConversationChannels(context: Context, parentChannelId: String): List<android.app.NotificationChannel> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val parent = app.aino.mobile.core.push.PushNotifications.channel(context, parentChannelId)
        return context.getSystemService(NotificationManager::class.java)?.notificationChannels
            ?.filter { it.parentChannelId == parent && it.importance == NotificationManager.IMPORTANCE_NONE }
            .orEmpty()
    }
}
