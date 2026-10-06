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
    const val MENTIONS = "aino_mentions"
    const val ASSIGNMENTS = "aino_assignments"
    const val APPROVALS = "aino_approvals"
    const val MISSED_CALLS = "aino_missed_calls"

    /**
     * Channel ids are never renamed or deleted: Android keeps the user's
     * per-channel customisation keyed by id, and re-creating an existing id
     * only refreshes its name. New categories are added alongside.
     */
    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When someone @-mentions you in a chat, task or note"
                },
                NotificationChannel(ASSIGNMENTS, "Assignments", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tasks assigned to you"
                },
                NotificationChannel(APPROVALS, "Approvals & requests", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Requests awaiting your approval and decisions on your leave, manual entry and overtime requests"
                },
                NotificationChannel(GENERAL, "Notifications", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming AINO voice and video calls"
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
                },
                NotificationChannel(MISSED_CALLS, "Missed calls", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Calls that rang on this device and were not answered"
                },
            ),
        )
    }

    /** Posts [push]; true when a notification is now showing for it. */
    fun display(context: Context, push: ValidatedPush): Boolean {
        if (push.kind == PushKind.CallHandledElsewhere) {
            val manager = NotificationManagerCompat.from(context)
            manager.cancel(NotificationTags.CALL, notificationId(push))
            // Posted untagged by app versions before 0.15.2.
            manager.cancel(notificationId(push))
            app.aino.mobile.core.call.IncomingCallNotifications.cancel(context)
            return false
        }
        if (push.kind == PushKind.IncomingCall) {
            // The ring service was refused: post the same CallStyle + full-screen-intent
            // notification without a foreground service (it rings through its channel).
            val extras = app.aino.mobile.core.call.incomingCallServiceExtras(
                push.data,
                app.aino.mobile.core.auth.KeystoreTokenStore(context).getToken(),
            )
            val posted = runCatching { app.aino.mobile.core.call.IncomingCallNotifications.postWithoutService(context, extras) }
                .onFailure { android.util.Log.w("AinoPush", "Incoming call notification failed", it) }
            if (posted.getOrDefault(false)) return true
        }
        val chatId = push.data["conversationId"]?.toLongOrNull()
        if (push.kind == PushKind.ChatMessage && chatId != null && VisibleThread.isVisible(chatId)) return false
        val privateCall = push.kind == PushKind.IncomingCall && !push.data.containsKey("callerName")
        val title = if (privateCall) push.data.getValue("title") else push.data["title"] ?: push.data["callerName"] ?: "AINO"
        val body = if (privateCall) "Tap to answer" else push.data["body"].orEmpty()
        val channel = when (push.kind) {
            PushKind.ChatMessage -> MESSAGES
            PushKind.IncomingCall -> CALLS
            else -> generalPushChannel(push.data["type"])
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
            // Status-bar icons are alpha-only masks, so a monochrome glyph is
            // used there; the coloured logo shows as the large icon when expanded.
            .setSmallIcon(R.drawable.ic_stat_aino)
            .setColor(ChatNotifications.brandColor(context))
            .setLargeIcon(ChatNotifications.appLogo(context))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setNumber(1)
            .setSilent(silent)
            .setPriority(if (push.kind == PushKind.IncomingCall) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (push.kind == PushKind.IncomingCall) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (privateCall) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            return runCatching { NotificationManagerCompat.from(context).notify(notificationTag(push), notificationId(push), notification) }.isSuccess
        }
        return false
    }

    /** Removes a bell alert's tray notification (read / deleted in the app or on another device). */
    fun cancelAlert(context: Context, notificationId: Long) {
        runCatching { NotificationManagerCompat.from(context).cancel(NotificationTags.ALERT, notificationId.hashCode()) }
    }
}

/** Channel for a generic (`notifications` table) alert by its `type`; unknown types stay on General. */
fun generalPushChannel(type: String?): String = when (type) {
    "mention", "note_mention", "chat_mention" -> PushNotifications.MENTIONS
    "task" -> PushNotifications.ASSIGNMENTS
    // Manual-entry / overtime decisions arrive as `approval` (to the requester).
    "approval", "leave", "agile_request", "agile_grant" -> PushNotifications.APPROVALS
    else -> PushNotifications.GENERAL
}