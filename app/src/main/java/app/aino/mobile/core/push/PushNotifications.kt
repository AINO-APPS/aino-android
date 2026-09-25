package app.aino.mobile.core.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.aino.mobile.MainActivity
import app.aino.mobile.R

object PushNotifications {
    const val MESSAGES = "aino_messages"
    const val GENERAL = "aino_general"
    const val CALLS = "aino_incoming_calls"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(GENERAL, "Notifications", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming AINO voice and video calls"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
            ),
        )
    }

    fun display(context: Context, push: ValidatedPush) {
        if (push.kind == PushKind.CallHandledElsewhere) {
            NotificationManagerCompat.from(context).cancel(notificationId(push))
            return
        }
        val privateCall = push.kind == PushKind.IncomingCall && !push.data.containsKey("callerName")
        val title = if (privateCall) push.data.getValue("title") else push.data["title"] ?: push.data["callerName"] ?: "AINO"
        val body = if (privateCall) "Tap to answer" else push.data["body"].orEmpty()
        val channel = when (push.kind) {
            PushKind.ChatMessage -> MESSAGES
            PushKind.IncomingCall -> CALLS
            else -> GENERAL
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putPushTapExtras(push)
            putExtra("call_id", push.data["callId"])
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(push),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Notification Sounds prefs: mute-all silences everything; other
        // alerts stay quiet while the app is open unless "play when focused".
        val silent = if (push.kind == PushKind.IncomingCall) {
            app.aino.mobile.core.notifications.NotificationSoundPrefs.muteAll(context)
        } else {
            !app.aino.mobile.core.notifications.NotificationSoundPrefs.notificationAudible(context)
        }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.aino_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setSilent(silent)
            .setPriority(if (push.kind == PushKind.IncomingCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (push.kind == PushKind.IncomingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (privateCall) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { NotificationManagerCompat.from(context).notify(notificationId(push), notification) }
        }
    }
}