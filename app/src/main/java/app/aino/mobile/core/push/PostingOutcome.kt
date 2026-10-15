package app.aino.mobile.core.push

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

sealed interface PostingOutcome {
    data object Posted : PostingOutcome
    data class Suppressed(val reason: String) : PostingOutcome
    data class Blocked(val reason: String) : PostingOutcome
    data class Failed(val reason: String) : PostingOutcome
}

fun notificationBlocker(context: Context, channelId: String): PostingOutcome.Blocked? {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return PostingOutcome.Blocked("App notifications disabled")
    if (Build.VERSION.SDK_INT >= 26 &&
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(PushNotifications.channel(context, channelId))?.importance == NotificationManager.IMPORTANCE_NONE
    ) return PostingOutcome.Blocked("Channel disabled: $channelId")
    return null
}

/** Identifiers only: neither credentials nor notification content are persisted in the delivery ledger. */
internal fun notificationAccount(context: Context): String? {
    val token = app.aino.mobile.core.auth.KeystoreTokenStore(context).getToken() ?: return null
    val user = jwtLongClaim(token, "id") ?: jwtLongClaim(token, "userId") ?: jwtLongClaim(token, "sub") ?: return null
    val tenant = jwtLongClaim(token, "tenant_id") ?: jwtLongClaim(token, "tenantId") ?: 0
    return "$tenant:$user"
}

internal fun notificationUser(context: Context): Long? =
    notificationAccount(context)?.substringAfter(':')?.toLongOrNull()
