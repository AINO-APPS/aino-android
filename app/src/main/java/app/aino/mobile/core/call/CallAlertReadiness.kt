package app.aino.mobile.core.call

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import app.aino.mobile.core.push.PushNotifications

/** Device settings that stop an incoming call from ringing visibly. */
enum class CallAlertIssue(val title: String, val message: String) {
    NotificationsOff(
        "Turn on notifications",
        "AINO notifications are off, so incoming calls can't ring on this phone. Turn them on in Settings.",
    ),
    CallChannelBlocked(
        "Allow call notifications",
        "Incoming call notifications are turned off for AINO. Turn them on in Settings so calls can ring.",
    ),
    FullScreenIntentDenied(
        "Show calls on the lock screen",
        "Allow AINO to use full-screen notifications so incoming calls open over the lock screen.",
    ),
}

/** The most important blocker first; [includeNotificationsOff] = false where the runtime prompt already asks. */
fun callAlertIssue(
    notificationsEnabled: Boolean,
    callChannelBlocked: Boolean,
    sdkInt: Int,
    canUseFullScreenIntent: Boolean,
    includeNotificationsOff: Boolean = true,
): CallAlertIssue? = when {
    !notificationsEnabled -> if (includeNotificationsOff) CallAlertIssue.NotificationsOff else null
    callChannelBlocked -> CallAlertIssue.CallChannelBlocked
    sdkInt >= 34 && !canUseFullScreenIntent -> CallAlertIssue.FullScreenIntentDenied
    else -> null
}

object CallAlertReadiness {
    private const val FILE = "aino_call_alerts"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun currentIssue(context: Context, includeNotificationsOff: Boolean = true): CallAlertIssue? {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channelBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null &&
            listOf(IncomingCallNotifications.SERVICE_CHANNEL, IncomingCallNotifications.RINGING_CHANNEL, PushNotifications.CALLS)
                .any { id -> manager.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE }
        val fullScreen = if (Build.VERSION.SDK_INT >= 34) manager?.canUseFullScreenIntent() != false else true
        return callAlertIssue(
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            callChannelBlocked = channelBlocked,
            sdkInt = Build.VERSION.SDK_INT,
            canUseFullScreenIntent = fullScreen,
            includeNotificationsOff = includeNotificationsOff,
        )
    }

    /** The current issue when the user has not been asked about it before. */
    fun issueToPrompt(context: Context, includeNotificationsOff: Boolean = true): CallAlertIssue? =
        currentIssue(context, includeNotificationsOff)?.takeUnless { prefs(context).getBoolean(key(it), false) }

    fun markPrompted(context: Context, issue: CallAlertIssue) {
        prefs(context).edit().putBoolean(key(issue), true).apply()
    }

    fun openSettings(context: Context, issue: CallAlertIssue) {
        val pkg = context.packageName
        val intent = when {
            issue == CallAlertIssue.FullScreenIntentDenied && Build.VERSION.SDK_INT >= 34 ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    private fun key(issue: CallAlertIssue) = "prompted_${issue.name}"
}

/** Asks once per [issue]; either answer counts as asked. */
@Composable
fun CallAlertSetupDialog(issue: CallAlertIssue, onDone: () -> Unit) {
    val context = LocalContext.current
    fun finish(open: Boolean) {
        CallAlertReadiness.markPrompted(context, issue)
        if (open) CallAlertReadiness.openSettings(context, issue)
        onDone()
    }
    AlertDialog(
        onDismissRequest = { finish(open = false) },
        title = { Text(issue.title) },
        text = { Text(issue.message) },
        confirmButton = { TextButton(onClick = { finish(open = true) }) { Text("Open settings") } },
        dismissButton = { TextButton(onClick = { finish(open = false) }) { Text("Not now") } },
    )
}

/**
 * Shell check: a blocked call channel or (Android 14+) a revoked full-screen
 * intent permission silently hides incoming calls, so ask once. Notifications
 * being off entirely is left to the runtime permission request at sign-in.
 */
@Composable
fun CallAlertSetupPrompt() {
    val context = LocalContext.current
    var issue by remember { mutableStateOf<CallAlertIssue?>(null) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        if (issue == null && !ActiveCallRuntime.get(context).ui.value.visible) {
            issue = CallAlertReadiness.issueToPrompt(context, includeNotificationsOff = false)
        }
        onPauseOrDispose { }
    }
    issue?.let { current -> CallAlertSetupDialog(current) { issue = null } }
}
