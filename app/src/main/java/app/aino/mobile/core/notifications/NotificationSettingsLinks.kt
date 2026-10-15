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
        val channel = context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId) ?: return null
        return soundTitle(context, channel.sound) ?: "None (silent)"
    }

    fun channelVibrates(context: Context, channelId: String): Boolean? =
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(channelId)?.shouldVibrate()

    fun openChannelSettings(context: Context, channelId: String) {
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
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

    /** System ringtone picker for the incoming-call ringtone. */
    fun ringtonePickerIntent(context: Context): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Incoming call ringtone")
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                NotificationSoundPrefs.ringtoneUri(context) ?: Settings.System.DEFAULT_RINGTONE_URI,
            )

    /** Stores the picker result; the system default is kept as "no override". */
    fun onRingtonePicked(context: Context, picked: Uri?) {
        val isDefault = picked == null || picked == Settings.System.DEFAULT_RINGTONE_URI
        NotificationSoundPrefs.setRingtoneUri(context, if (isDefault) null else picked)
    }

    fun ringtoneTitle(context: Context): String? =
        soundTitle(context, NotificationSoundPrefs.ringtoneUri(context) ?: Settings.System.DEFAULT_RINGTONE_URI)

    /** Android 11+ per-chat channels the user switched off. */
    fun disabledConversationChannels(context: Context, parentChannelId: String): List<android.app.NotificationChannel> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        return context.getSystemService(NotificationManager::class.java)?.notificationChannels
            ?.filter { it.parentChannelId == parentChannelId && it.importance == NotificationManager.IMPORTANCE_NONE }
            .orEmpty()
    }
}
