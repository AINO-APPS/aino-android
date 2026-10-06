package app.aino.mobile.core.call

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.aino.mobile.core.media.resolveServerMediaUrl

/**
 * CallRingService
 *
 * A short-lived FOREGROUND SERVICE that drives the incoming-call ring in EVERY
 * app state (foreground, background, locked, killed) — the Signal/Teams model.
 *
 * WHY A FOREGROUND SERVICE INSTEAD OF THE NOTIFICATION CHANNEL SOUND:
 * An Android notification channel's sound is IMMUTABLE once created and is
 * subject to per-channel user overrides; relying on it makes the "selected
 * ringtone" brittle and gives no precise control over when the ring stops. By
 * owning a MediaPlayer (looping the selected ringtone) + a Vibrator (repeating
 * pattern) in a foreground service we get:
 *   • the user's SELECTED ringtone, played consistently in all states,
 *   • a guaranteed buzz pattern, and
 *   • an INSTANT, deterministic stop the moment the call is answered/declined/
 *     cancelled (just stopService) — no lingering ring.
 *
 * The service posts its OWN call notification using the Android CALL STYLE
 * template (NotificationCompat.CallStyle.forIncomingCall) so the system renders
 * a branded incoming-call UI with a GREEN "Answer" button and a RED "Decline"
 * button, plus a full-screen intent that surfaces the call over the lock screen.
 * The action buttons fire PendingIntent.getActivity() PendingIntents handled by
 * CallActionActivity (a transparent trampoline), which stops the ring and deep-
 * links into the JS call screen (single accept/reject path). Routing through an
 * Activity — NOT a BroadcastReceiver — is what makes "Answer" reliably bring the
 * app forward (a background BroadcastReceiver cannot startActivity() on Android
 * 10+ due to background-activity-start restrictions). This is what fixes both
 * "no Answer/Decline buttons in the status bar" and "Answer stops the ring but
 * the call screen never opens — I have to open the app manually".
 */
class CallRingService : Service() {

  private var mediaPlayer: MediaPlayer? = null
  private var vibrator: Vibrator? = null
  private val timeoutHandler = Handler(Looper.getMainLooper())
  private val timeoutStop = Runnable { onRingTimeout() }

  companion object {
    const val ACTION_START = "app.aino.mobile.core.call.START"
    const val ACTION_STOP = "app.aino.mobile.core.call.STOP"

    const val EXTRA_RINGTONE_RES = "ringtoneRes" // raw resource name, or empty
    const val EXTRA_TITLE = "title"
    const val EXTRA_BODY = "body"
    const val EXTRA_VIBRATE = "vibrate" // "1" / "0"
    const val EXTRA_SILENT = "silent" // "1" → no sound (muteAll / none)

    // Caller / call identity used to build the CallStyle UI + action deep links.
    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_CALLER_ID = "callerId"
    const val EXTRA_CALLER_NAME = "callerName"
    const val EXTRA_CALLER_AVATAR = "callerAvatar"
    // Bearer token (the user's JWT). The caller avatar lives behind the
    // server's `/uploads` auth middleware, so AvatarLoader must send it as an
    // Authorization header or the fetch 401s and no photo is shown.
    const val EXTRA_TOKEN = "token"
    const val EXTRA_CALL_TYPE = "callType"
    const val EXTRA_SCHEME = "scheme"
    const val EXTRA_EXPIRES_AT = "expiresAt"
    /** Group-call (huddle) rings: the meeting to join; the push `callId` is the meeting id. */
    const val EXTRA_MEETING_CODE = "meetingCode"

    const val NOTIFICATION_ID = IncomingCallNotifications.NOTIFICATION_ID

    private val EXTRA_KEYS = listOf(
      EXTRA_TITLE, EXTRA_BODY, EXTRA_CALL_ID, EXTRA_CONVERSATION_ID, EXTRA_CALLER_ID, EXTRA_CALLER_NAME,
      EXTRA_CALLER_AVATAR, EXTRA_CALL_TYPE, EXTRA_SCHEME, EXTRA_EXPIRES_AT, EXTRA_MEETING_CODE, EXTRA_TOKEN,
    )

    /**
     * Start the incoming-call ring foreground service.
     *
     * Returns TRUE when the (foreground) service start request was accepted by
     * the OS, FALSE when it was REFUSED. On Android 12+ (API 31+) calling
     * startForegroundService() from a BACKGROUNDED-but-alive process throws
     * ForegroundServiceStartNotAllowedException (background FGS-start
     * restriction). We surface that as `false` so the push path can FALL BACK
     * to [IncomingCallNotifications.postWithoutService] — the same CallStyle
     * full-screen-intent notification without a foreground service — instead
     * of leaving the incoming call with no surface at all.
     */
    fun start(context: Context, extras: Map<String, String>): Boolean {
      val intent = Intent(context, CallRingService::class.java).apply {
        action = ACTION_START
        for ((k, v) in extras) putExtra(k, v)
      }
      return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
        true
      } catch (_: Throwable) {
        // ForegroundServiceStartNotAllowedException (API 31+) when started from
        // the background, or any OEM-specific refusal.
        false
      }
    }

    fun stop(context: Context) {
      // A service-less (push fallback) ring has no service to remove its notification.
      IncomingCallNotifications.cancel(context)
      val intent = Intent(context, CallRingService::class.java).apply {
        action = ACTION_STOP
      }
      try {
        context.startService(intent)
      } catch (_: Throwable) {
        // If the service isn't running startService may throw on some OEMs —
        // fall back to a direct stop.
        context.stopService(Intent(context, CallRingService::class.java))
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private var ringingCallId: String = ""
  private var ringing: MissedCallInfo? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP -> {
        stopEverything()
        return START_NOT_STICKY
      }
      else -> {
        startRinging(intent)
      }
    }
    // Do NOT restart automatically if killed — a stale ring must never resurrect.
    return START_NOT_STICKY
  }

  private fun startRinging(intent: Intent?) {
    IncomingCallNotifications.ensureChannels(this)

    val extras = EXTRA_KEYS.associateWith { intent?.getStringExtra(it) }
    val spec = IncomingCallSpec.from(extras)
    val silent = intent?.getStringExtra(EXTRA_SILENT) == "1"
    val vibrate = intent?.getStringExtra(EXTRA_VIBRATE) != "0"
    val ringtoneRes = intent?.getStringExtra(EXTRA_RINGTONE_RES) ?: ""
    val token = intent?.getStringExtra(EXTRA_TOKEN) ?: ""
    val expiresAt = intent?.getStringExtra(EXTRA_EXPIRES_AT)?.takeIf(String::isNotBlank)

    timeoutHandler.removeCallbacks(timeoutStop)
    val remaining = remainingRingMillis(expiresAt)
    if (remaining <= 0L) {
      stopEverything()
      return
    }
    timeoutHandler.postDelayed(timeoutStop, remaining)
    ringing = MissedCallInfo.of(spec)?.also { RingingCallStore.save(this, it, System.currentTimeMillis() + remaining) }

    // Post the foreground notification FIRST, with only the local initials
    // avatar (required within ~5s of startForegroundService or the OS throws — a
    // network avatar fetch must never block this). The photo is loaded
    // asynchronously below and the notification is re-posted once it lands.
    val notification = IncomingCallNotifications.build(
      this,
      IncomingCallNotifications.SERVICE_CHANNEL,
      spec,
      IncomingCallNotifications.fallbackAvatar(spec),
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }

    // The server sends the stored upload path (e.g. `/uploads/<tenant>/avatars/x.png`),
    // resolved against the server origin like chat notifications / UserAvatar do.
    // Best-effort: any failure leaves the posted initials notification untouched.
    val avatarUrl = callAvatarUrl(spec.callerAvatar)
    if (avatarUrl != null) {
      Thread {
        val bitmap = AvatarLoader.load(applicationContext, avatarUrl, token)
        if (bitmap != null) {
          try {
            val withAvatar = IncomingCallNotifications.build(
              applicationContext,
              IncomingCallNotifications.SERVICE_CHANNEL,
              spec,
              bitmap,
            )
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, withAvatar)
          } catch (_: Throwable) {
            // Re-notify is best-effort; the original notification still shows.
          }
        }
      }.apply { isDaemon = true }.start()
    }

    // The same call can arrive over the socket and as a push: keep ringing once.
    if (spec.callId.isNotEmpty() && spec.callId == ringingCallId && mediaPlayer != null) return
    ringingCallId = spec.callId
    // Start the ringtone (unless silent or muted in Notification Sounds).
    if (!silent && !app.aino.mobile.core.notifications.NotificationSoundPrefs.muteAll(this)) {
      startRingtone(ringtoneRes)
    }
    // Start vibration (unless silent or explicitly disabled).
    if (!silent && vibrate) {
      startVibration()
    }
  }

  /** Nobody answered before `expiresAt`: expire the ringing session and leave a missed call. */
  private fun onRingTimeout() {
    val missed = ringing
    stopEverything()
    if (missed != null) MissedCalls.onLocalRingTimeout(applicationContext, missed)
  }

  private fun startRingtone(ringtoneRes: String) {
    stopMediaPlayer()
    try {
      val uri: Uri = resolveRingtoneUri(ringtoneRes)
      val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
      mediaPlayer = MediaPlayer().apply {
        setDataSource(this@CallRingService, uri)
        setAudioAttributes(attrs)
        isLooping = true
        setOnPreparedListener { start() }
        // If preparation/playback fails, do not crash the service.
        setOnErrorListener { _, _, _ -> true }
        prepareAsync()
      }
    } catch (_: Throwable) {
      // Fall back to the system default ringtone if our bundled resource fails.
      try {
        val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        mediaPlayer = MediaPlayer().apply {
          setDataSource(this@CallRingService, fallback)
          isLooping = true
          setOnPreparedListener { start() }
          setOnErrorListener { _, _, _ -> true }
          prepareAsync()
        }
      } catch (_: Throwable) {
        // Give up silently — the notification still surfaces the call.
      }
    }
  }

  /**
   * Resolve a bundled res/raw resource name (e.g. "ringtone_classic") to a
   * content URI. Falls back to the system default ringtone when the name is
   * empty or the resource cannot be found.
   */
  private fun resolveRingtoneUri(ringtoneRes: String): Uri {
    if (ringtoneRes.isNotEmpty()) {
      val resId = resources.getIdentifier(ringtoneRes, "raw", packageName)
      if (resId != 0) {
        return Uri.parse("android.resource://$packageName/$resId")
      }
    }
    // The ringtone the user picked on the Notification Sounds page, else the system default.
    return app.aino.mobile.core.notifications.NotificationSoundPrefs.ringtoneUri(this)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
  }

  private fun startVibration() {
    try {
      val vib = obtainVibrator()
      vibrator = vib
      // Repeating real-call cadence: wait 0, buzz 700, pause 1000, repeat from
      // index 1 (so it loops the buzz/pause until cancelled).
      val timings = longArrayOf(0, 700, 1000)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0)
        vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 1))
      } else {
        @Suppress("DEPRECATION")
        vib.vibrate(timings, 1)
      }
    } catch (_: Throwable) {
      // best-effort
    }
  }

  private fun obtainVibrator(): Vibrator {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val manager =
        getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
      manager.defaultVibrator
    } else {
      @Suppress("DEPRECATION")
      getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
  }

  private fun stopMediaPlayer() {
    try {
      mediaPlayer?.let {
        if (it.isPlaying) it.stop()
        it.release()
      }
    } catch (_: Throwable) {
      // ignore
    }
    mediaPlayer = null
  }

  private fun stopVibration() {
    try {
      vibrator?.cancel()
    } catch (_: Throwable) {
      // ignore
    }
    vibrator = null
  }

  private fun stopEverything() {
    timeoutHandler.removeCallbacks(timeoutStop)
    ringingCallId = ""
    ringing = null
    stopMediaPlayer()
    stopVibration()
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

  override fun onDestroy() {
    timeoutHandler.removeCallbacks(timeoutStop)
    stopMediaPlayer()
    stopVibration()
    super.onDestroy()
  }
}

/**
 * The caller avatar as an absolute, loadable URL, or null when there is none.
 * Pushes / socket events carry the stored upload path (`/uploads/...`), which
 * must be resolved against the server origin before AvatarLoader can fetch it.
 */
internal fun callAvatarUrl(
  callerAvatar: String?,
  origin: String = app.aino.mobile.core.network.NetworkConfig.serverOrigin,
): String? = callerAvatar?.takeIf(String::isNotBlank)?.let { resolveServerMediaUrl(it, origin) }