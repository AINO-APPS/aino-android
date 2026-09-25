package app.aino.mobile.core.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.aino.mobile.MainActivity

/**
 * ActiveCallService
 *
 * A foreground service that runs for the LIFETIME OF A CONNECTED CALL (the
 * Signal `ActiveCallManager` / `CallForegroundService` model). Unlike
 * CallRingService (which is the short-lived INCOMING-RING service), this service
 * is started when the call transitions to connecting/connected and stopped when
 * the call ends.
 *
 * WHY IT EXISTS — STABILITY:
 * Without an ongoing foreground service Android can deprioritize / doze /
 * throttle the app's process mid-call (especially after brief backgrounding or
 * heavy UI interaction). On a real-time WebRTC video call that throttling
 * surfaces as STUTTER, LAG and FREEZES. Holding a foreground service with the
 * MICROPHONE (and CAMERA for video) foreground-service types keeps the process
 * at foreground priority for the whole call — exactly how Signal keeps its calls
 * smooth. The media itself is owned by ActiveCallController (1:1) or
 * MeetingSession (meetings, group calls); this service's
 * sole job is the process-priority + ongoing-call notification.
 */
class ActiveCallService : Service() {

  companion object {
    const val ACTION_START = "app.aino.mobile.core.call.ACTIVE_CALL_START"
    const val ACTION_STOP = "app.aino.mobile.core.call.ACTIVE_CALL_STOP"

    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_CALL_TYPE = "callType" // "voice" | "video"
    const val EXTRA_SCHEME = "scheme"

    private const val CHANNEL_ID = "active_call_fgs_v1"
    const val NOTIFICATION_ID = 909091

    private val BRAND_COLOR = Color.parseColor("#22C55E")

    /** A 1:1 call and a meeting can overlap; the service lives while either owns it. */
    const val OWNER_CALL = "call"
    const val OWNER_MEETING = "meeting"
    private val owners = mutableSetOf<String>()

    fun start(context: Context, extras: Map<String, String>, owner: String = OWNER_CALL) {
      // startForegroundService() obliges startForeground(), which needs a granted
      // microphone/camera type on API 34+; without one, never start at all.
      val mic = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
      val cam = extras[EXTRA_CALL_TYPE] == "video" &&
        context.checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
      if (!mic && !cam) return
      synchronized(owners) { owners += owner }
      val intent = Intent(context, ActiveCallService::class.java).apply {
        action = ACTION_START
        for ((k, v) in extras) putExtra(k, v)
      }
      try {
        context.startForegroundService(intent)
      } catch (_: Throwable) {
        // Background FGS-start restriction: the call continues without the priority boost.
      }
    }

    fun stop(context: Context, owner: String = OWNER_CALL) {
      val remaining = synchronized(owners) {
        owners -= owner
        owners.isNotEmpty()
      }
      if (remaining) return
      val intent = Intent(context, ActiveCallService::class.java).apply {
        action = ACTION_STOP
      }
      try {
        context.startService(intent)
      } catch (_: Throwable) {
        context.stopService(Intent(context, ActiveCallService::class.java))
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopEverything()
        return START_NOT_STICKY
      }
      else -> {
        startOngoing(intent)
      }
    }
    // Do NOT auto-restart if the OS kills us — a stale ongoing-call notification
    // must never resurrect after the call is gone.
    return START_NOT_STICKY
  }

  private fun startOngoing(intent: Intent?) {
    ensureChannel()

    val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Ongoing call"
    val body = intent?.getStringExtra(EXTRA_BODY) ?: "Tap to return to the call"
    val callType = intent?.getStringExtra(EXTRA_CALL_TYPE) ?: "voice"
    val scheme = intent?.getStringExtra(EXTRA_SCHEME) ?: "aino"

    val notification = buildNotification(title, body, scheme)

    // foregroundServiceType must match the AndroidManifest declaration, and on
    // API 34+ each type needs its runtime permission granted — so only claim
    // CAMERA/MICROPHONE when the user allowed them (a denied camera must not
    // crash a voice-only meeting).
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        var type = 0
        if (granted(android.Manifest.permission.RECORD_AUDIO)) {
          type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (callType == "video" && granted(android.Manifest.permission.CAMERA)) {
          type = type or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (type == 0) {
          stopSelf()
          return
        }
        startForeground(NOTIFICATION_ID, notification, type)
      } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
    } catch (_: Throwable) {
      // Background FGS-start restrictions or a revoked permission: the call
      // still works in the foreground, it just loses process priority.
      stopSelf()
    }
  }

  private fun granted(permission: String): Boolean =
    checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED

  private fun buildNotification(
    title: String,
    body: String,
    scheme: String,
  ): Notification {
    // Tap → reopen the app (the call screen is already mounted; the deep link
    // simply brings the existing task to the foreground).
    val viewIntent = Intent(this, MainActivity::class.java).apply {
      setAction(Intent.ACTION_VIEW)
      setData(Uri.parse("$scheme://"))
      addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_SINGLE_TOP,
      )
    }
    val contentPending = PendingIntent.getActivity(
      this,
      2000,
      viewIntent,
      pendingIntentFlags(),
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(applicationInfo.icon)
      .setContentTitle(title)
      .setContentText(body)
      .setCategory(NotificationCompat.CATEGORY_CALL)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setAutoCancel(false)
      .setColor(BRAND_COLOR)
      .setOnlyAlertOnce(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setContentIntent(contentPending)
      .setUsesChronometer(true)
      .build()
  }

  private fun pendingIntentFlags(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    } else {
      PendingIntent.FLAG_UPDATE_CURRENT
    }
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    // LOW importance + silent — this is a persistent status notification for an
    // ongoing call, not an alert. No sound, no vibration.
    val channel = NotificationChannel(
      CHANNEL_ID,
      "Ongoing call",
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "Shown while a call is in progress"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
  }

  private fun stopEverything() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
        @Suppress("DEPRECATION")
        stopForeground(true)
      }
    } catch (_: Throwable) {
      // ignore
    }
    stopSelf()
  }
}