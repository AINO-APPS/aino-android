package app.aino.mobile.core.call

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person as PersonCompat
import androidx.core.content.ContextCompat
import app.aino.mobile.MainActivity
import app.aino.mobile.core.push.NotificationAvatars

/** Everything the incoming-call notification shows and its Answer / Decline / full-screen intents carry. */
data class IncomingCallSpec(
    val title: String,
    val body: String,
    val callId: String,
    val conversationId: String,
    val callerId: String,
    val callerName: String,
    val callerAvatar: String,
    val callType: String,
    val scheme: String = "aino",
    val meetingCode: String = "",
) {
    companion object {
        /** From [incomingCallServiceExtras] / [socketCallRingExtras] keys. */
        fun from(extras: Map<String, String?>): IncomingCallSpec {
            val title = extras[CallRingService.EXTRA_TITLE] ?: "Incoming call"
            return IncomingCallSpec(
                title = title,
                body = extras[CallRingService.EXTRA_BODY].orEmpty(),
                callId = extras[CallRingService.EXTRA_CALL_ID].orEmpty(),
                conversationId = extras[CallRingService.EXTRA_CONVERSATION_ID].orEmpty(),
                callerId = extras[CallRingService.EXTRA_CALLER_ID].orEmpty(),
                callerName = extras[CallRingService.EXTRA_CALLER_NAME]?.takeIf(String::isNotEmpty) ?: title,
                callerAvatar = extras[CallRingService.EXTRA_CALLER_AVATAR].orEmpty(),
                callType = extras[CallRingService.EXTRA_CALL_TYPE] ?: "voice",
                scheme = extras[CallRingService.EXTRA_SCHEME] ?: "aino",
                meetingCode = extras[CallRingService.EXTRA_MEETING_CODE].orEmpty(),
            )
        }
    }
}

/**
 * The CallStyle incoming-call notification (green Answer / red Decline via
 * [CallActionActivity], full-screen intent over the lock screen). Posted by
 * [CallRingService] as its foreground notification, and by the push path
 * directly when Android refuses the background foreground-service start.
 */
object IncomingCallNotifications {
    const val NOTIFICATION_ID = 909090

    /** Silent: [CallRingService] plays the ringtone and vibration itself. */
    const val SERVICE_CHANNEL = "call_ringer_fgs_v2"

    /** No service may run: the channel rings, insistent until answered, declined or timed out. */
    const val RINGING_CHANNEL = "aino_call_ring_v1"

    // AINO brand green used to theme the CallStyle notification accent.
    private val BRAND_COLOR = Color.parseColor("#22C55E")

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(SERVICE_CHANNEL) == null) {
            // Silent so the service's own MediaPlayer/Vibrator is the only ring.
            manager.createNotificationChannel(
                NotificationChannel(SERVICE_CHANNEL, "Incoming call", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Active incoming call ring"
                    setSound(null, null)
                    enableVibration(false)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
        if (manager.getNotificationChannel(RINGING_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(RINGING_CHANNEL, "Incoming call (ringing)", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Rings when the call ringer cannot run in the background"
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 700, 1000)
                    setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                },
            )
        }
    }

    /** Signal-style initials avatar rendered locally, so the caller is identifiable before the photo loads. */
    fun fallbackAvatar(spec: IncomingCallSpec): Bitmap? = runCatching {
        NotificationAvatars.fallback(spec.callerName.ifEmpty { spec.title }, if (spec.callerId.isNotBlank()) "aino-user-${spec.callerId}" else spec.callerName)
    }.getOrNull()

    fun build(
        context: Context,
        channelId: String,
        spec: IncomingCallSpec,
        avatarBitmap: Bitmap?,
        timeoutAfterMs: Long? = null,
        publicOnLockScreen: Boolean = true,
        insistent: Boolean = false,
    ): Notification {
        val contentPending = openCallPendingIntent(context, spec, requestCode = 1000)
        val answerPending = actionPendingIntent(context, spec, CallActionActivity.ACTION_ANSWER, requestCode = 1001)
        val declinePending = actionPendingIntent(context, spec, CallActionActivity.ACTION_DECLINE, requestCode = 1002)
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(spec.callerName.ifEmpty { spec.title })
            .setContentText(spec.body.ifEmpty { if (spec.callType == "video") "Incoming video call" else "Incoming voice call" })
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setColor(BRAND_COLOR)
            .setColorized(true)
            .setVisibility(if (publicOnLockScreen) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setFullScreenIntent(contentPending, true)
        timeoutAfterMs?.takeIf { it > 0 }?.let(builder::setTimeoutAfter)
        if (avatarBitmap != null) builder.setLargeIcon(avatarBitmap)
        try {
            val person = PersonCompat.Builder().setName(spec.callerName.ifEmpty { spec.title }).setImportant(true)
            if (avatarBitmap != null) {
                runCatching { person.setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(avatarBitmap)) }
            }
            builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person.build(), declinePending, answerPending))
        } catch (_: Throwable) {
            // Explicit buttons so Answer / Decline still appear without CallStyle.
            builder.setContentIntent(contentPending)
            builder.addAction(0, "Decline", declinePending)
            builder.addAction(0, "Answer", answerPending)
        }
        return builder.build().also { if (insistent) it.flags = it.flags or Notification.FLAG_INSISTENT }
    }

    /**
     * Push fallback when the ring service may not start (Android 12+ background
     * FGS restriction): the same CallStyle notification with its full-screen
     * intent, ringing through [RINGING_CHANNEL] and removed by the system when
     * the ring expires. Runs on the push worker thread (the avatar is fetched inline).
     */
    fun postWithoutService(context: Context, extras: Map<String, String>): Boolean {
        val remaining = remainingRingMillis(extras[CallRingService.EXTRA_EXPIRES_AT]?.takeIf(String::isNotBlank))
        if (remaining <= 0L) return false
        val spec = IncomingCallSpec.from(extras)
        ensureChannels(context)
        MissedCallInfo.of(spec)?.let { RingingCallStore.save(context, it, System.currentTimeMillis() + remaining) }
        if (!canPost(context)) return false
        val silent = app.aino.mobile.core.notifications.NotificationSoundPrefs.muteAll(context)
        // A push without a caller name is the privacy-safe payload: keep it off the lock screen.
        val public = extras[CallRingService.EXTRA_CALLER_NAME].orEmpty().isNotBlank()
        fun post(avatar: Bitmap?) = runCatching {
            context.getSystemService(NotificationManager::class.java)?.notify(
                NOTIFICATION_ID,
                build(
                    context,
                    if (silent) SERVICE_CHANNEL else RINGING_CHANNEL,
                    spec,
                    avatar,
                    timeoutAfterMs = remaining,
                    publicOnLockScreen = public,
                    insistent = !silent,
                ),
            )
        }.isSuccess
        if (!post(fallbackAvatar(spec))) return false
        callAvatarUrl(spec.callerAvatar)?.let { url ->
            AvatarLoader.load(context, url, extras[CallRingService.EXTRA_TOKEN])?.let { avatar ->
                // Answer / Decline / a cancel may have removed the ring during the download.
                if (stillRinging(context, spec)) post(avatar)
            }
        }
        return true
    }

    private fun stillRinging(context: Context, spec: IncomingCallSpec): Boolean {
        if (RingingCallStore.read(context)?.info?.callId != spec.callId.toLongOrNull()) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { manager.activeNotifications.any { it.id == NOTIFICATION_ID } }.getOrDefault(false)
    }

    /** Removes a service-less ring (a running service removes its own notification when it stops). */
    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    private fun canPost(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Body tap / full-screen intent: the in-app ringing screen, never an auto-answer. */
    private fun openCallPendingIntent(context: Context, spec: IncomingCallSpec, requestCode: Int): PendingIntent {
        val viewIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse(incomingCallUri(spec))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, requestCode, viewIntent, PENDING_FLAGS)
    }

    // Answer / Decline go through an Activity trampoline: a background
    // BroadcastReceiver may not start the call screen on Android 10+.
    private fun actionPendingIntent(context: Context, spec: IncomingCallSpec, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CallActionActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(CallActionActivity.EXTRA_MEETING_CODE, spec.meetingCode)
            putExtra(CallActionActivity.EXTRA_CALL_ID, spec.callId)
            putExtra(CallActionActivity.EXTRA_CONVERSATION_ID, spec.conversationId)
            putExtra(CallActionActivity.EXTRA_CALLER_ID, spec.callerId)
            putExtra(CallActionActivity.EXTRA_CALLER_NAME, spec.callerName)
            putExtra(CallActionActivity.EXTRA_CALLER_AVATAR, spec.callerAvatar)
            putExtra(CallActionActivity.EXTRA_CALL_TYPE, spec.callType)
            putExtra(CallActionActivity.EXTRA_SCHEME, spec.scheme)
        }
        return PendingIntent.getActivity(context, requestCode, intent, PENDING_FLAGS)
    }

    private fun incomingCallUri(spec: IncomingCallSpec): String = buildString {
        append(spec.scheme).append("://call/").append(spec.conversationId)
        append("?mode=incoming")
        append("&callId=").append(Uri.encode(spec.callId))
        append("&callType=").append(Uri.encode(spec.callType))
        append("&peerId=").append(Uri.encode(spec.callerId))
        append("&peerName=").append(Uri.encode(spec.callerName))
        append("&peerAvatar=").append(Uri.encode(spec.callerAvatar))
        if (spec.meetingCode.isNotBlank()) {
            append("&meetingCode=").append(Uri.encode(spec.meetingCode))
            append("&meetingId=").append(Uri.encode(spec.callId))
        }
    }

    private const val PENDING_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
}
