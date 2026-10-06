package app.aino.mobile.core.call

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import app.aino.mobile.MainActivity

/**
 * A no-UI, transparent trampoline for the Decline button on the CallStyle
 * incoming-call notification. (Answer opens [MainActivity] directly, the way
 * Signal-Android opens its call Activity with ANSWER_AUDIO/ANSWER_VIDEO.)
 *
 * It is an Activity, not a BroadcastReceiver, because a background receiver
 * may not start an Activity on Android 10+; a notification action's
 * PendingIntent.getActivity() may, and this activity may then start the next.
 *
 * On launch it records the choice ([PendingCallActionStore]) so a cold start
 * applies it, opens the call deep link with `action=decline` (the single reject
 * path), stops the ring and finishes.
 */
class CallActionActivity : Activity() {

  companion object {
    const val ACTION_DECLINE = "app.aino.mobile.core.call.DECLINE"

    const val EXTRA_CALL_ID = "callId"
    const val EXTRA_CONVERSATION_ID = "conversationId"
    const val EXTRA_CALLER_ID = "callerId"
    const val EXTRA_CALLER_NAME = "callerName"
    const val EXTRA_CALLER_AVATAR = "callerAvatar"
    const val EXTRA_CALL_TYPE = "callType"
    const val EXTRA_SCHEME = "scheme"
    const val EXTRA_MEETING_CODE = "meetingCode"
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      try {
        val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        keyguard?.requestDismissKeyguard(this, null)
      } catch (_: Throwable) {
        // best-effort
      }
    }
    handle(intent)
    finish()
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    handle(intent)
    finish()
  }

  private fun handle(intent: Intent?) {
    if (intent?.action != ACTION_DECLINE) {
      stopRing()
      return
    }

    val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: ""
    val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: ""
    val callerId = intent.getStringExtra(EXTRA_CALLER_ID) ?: ""
    val callerName = intent.getStringExtra(EXTRA_CALLER_NAME) ?: ""
    val callerAvatar = intent.getStringExtra(EXTRA_CALLER_AVATAR) ?: ""
    val callType = intent.getStringExtra(EXTRA_CALL_TYPE) ?: "voice"
    val scheme = intent.getStringExtra(EXTRA_SCHEME) ?: "aino"

    if (conversationId.isEmpty() || callId.isEmpty()) {
      stopRing()
      return
    }

    callId.toLongOrNull()?.let { MissedCallNotifier.markHandled(this, it) }
    runCatching { PendingCallActionStore.write(this, "decline", callId, conversationId) }

    val sb = StringBuilder()
    sb.append(scheme).append("://call/").append(conversationId)
    sb.append("?mode=incoming")
    sb.append("&callId=").append(Uri.encode(callId))
    sb.append("&callType=").append(Uri.encode(callType))
    sb.append("&peerId=").append(Uri.encode(callerId))
    sb.append("&peerName=").append(Uri.encode(callerName))
    sb.append("&peerAvatar=").append(Uri.encode(callerAvatar))
    intent.getStringExtra(EXTRA_MEETING_CODE)?.takeIf(String::isNotBlank)?.let {
      sb.append("&meetingCode=").append(Uri.encode(it))
      sb.append("&meetingId=").append(Uri.encode(callId))
    }
    sb.append("&action=decline")

    try {
      val viewIntent = Intent(this, MainActivity::class.java).apply {
        setAction(Intent.ACTION_VIEW)
        setData(Uri.parse(sb.toString()))
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
      }
      startActivity(viewIntent)
    } catch (_: Throwable) {
      try {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) startActivity(launch)
      } catch (_: Throwable) {
        // give up silently
      }
    }

    stopRing()
  }

  private fun stopRing() {
    try {
      CallRingService.stop(this)
    } catch (_: Throwable) {
      // best-effort
    }
    try {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
      nm?.cancel(CallRingService.NOTIFICATION_ID)
    } catch (_: Throwable) {
      // best-effort
    }
  }
}
