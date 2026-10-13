package app.aino.mobile.core.call

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.aino.mobile.MainActivity
import app.aino.mobile.R
import app.aino.mobile.core.push.ChatNotifications
import app.aino.mobile.core.push.NotificationAvatars
import app.aino.mobile.core.push.NotificationTags
import app.aino.mobile.core.push.PushNotifications
import app.aino.mobile.core.push.putMissedCallTapExtras
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import app.aino.mobile.core.push.PostingOutcome
import app.aino.mobile.core.push.notificationBlocker
import app.aino.mobile.core.push.notificationAccount
import app.aino.mobile.feature.chat.CallLog

internal class MissedCallStateStore(context: Context, account: String) {
    private val prefs = context.getSharedPreferences("aino_missed_calls_$account", Context.MODE_PRIVATE)
    fun handled(id: Long) = prefs.getBoolean("handled:$id", false)
    fun posted(id: Long) = prefs.getBoolean("posted:$id", false)
    fun markHandled(id: Long) { prefs.edit().putBoolean("handled:$id", true).commit() }
    fun recordPosting(id: Long, outcome: PostingOutcome) {
        if (outcome == PostingOutcome.Posted) prefs.edit().putBoolean("posted:$id", true).commit()
    }
}

/** The caller identity a missed-call notification needs, kept from the ring (route / ring-service extras). */
data class MissedCallInfo(
    val callId: Long,
    val conversationId: Long,
    val callerId: Long?,
    val callerName: String,
    val callType: String,
    val meetingCode: String? = null,
    val isGroup: Boolean = meetingCode != null,
) {
    companion object {
        fun of(route: IncomingCallRoute) = MissedCallInfo(
            route.callId, route.conversationId, route.callerId, route.callerName, route.callType, route.meetingCode,
        )

        fun of(spec: IncomingCallSpec): MissedCallInfo? {
            val callId = spec.callId.toLongOrNull()?.takeIf { it > 0 } ?: return null
            val conversationId = spec.conversationId.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return MissedCallInfo(
                callId = callId,
                conversationId = conversationId,
                callerId = spec.callerId.toLongOrNull(),
                // A privacy-safe push has no name; the spec then falls back to the generic title.
                callerName = spec.callerName.takeUnless { it == spec.title }.orEmpty(),
                callType = if (spec.callType == "video") "video" else "voice",
                meetingCode = spec.meetingCode.takeIf(String::isNotBlank),
            )
        }
    }
}

/**
 * The ring currently surfaced on this device (ring service or push fallback),
 * persisted so a later `call_handled_elsewhere` push — possibly in a fresh
 * process — knows the call rang here and who called. Cleared once the ring is
 * answered / declined / reported missed, and on cold start once expired.
 */
object RingingCallStore {
    private const val FILE = "aino_call_ring"
    private const val KEY = "ring"

    data class Record(val info: MissedCallInfo, val expiresAtMs: Long)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        "${FILE}_${notificationAccount(context) ?: "signed_out"}", Context.MODE_PRIVATE)

    fun save(context: Context, info: MissedCallInfo, expiresAtMs: Long) {
        runCatching {
            val json = JSONObject()
                .put("callId", info.callId)
                .put("conversationId", info.conversationId)
                .put("callerId", info.callerId ?: 0L)
                .put("callerName", info.callerName)
                .put("callType", info.callType)
                .put("meetingCode", info.meetingCode.orEmpty())
                .put("expiresAtMs", expiresAtMs)
            prefs(context).edit().putString(KEY, json.toString()).apply()
        }
    }

    fun read(context: Context): Record? = runCatching {
        val json = JSONObject(prefs(context).getString(KEY, null) ?: return null)
        Record(
            MissedCallInfo(
                callId = json.getLong("callId"),
                conversationId = json.getLong("conversationId"),
                callerId = json.optLong("callerId").takeIf { it > 0 },
                callerName = json.optString("callerName"),
                callType = json.optString("callType", "voice"),
                meetingCode = json.optString("meetingCode").takeIf(String::isNotBlank),
            ),
            json.optLong("expiresAtMs"),
        )
    }.getOrNull()

    /** Clears the record (only when it belongs to [callId], if given). */
    fun clear(context: Context, callId: Long? = null) {
        if (callId != null && read(context)?.info?.callId != callId) return
        runCatching { prefs(context).edit().remove(KEY).apply() }
    }

    /** Cold start: drops a ring whose `expiresAt` passed while the app was not running. */
    fun clearIfStale(context: Context, nowMs: Long = System.currentTimeMillis()): Record? {
        val record = read(context) ?: return null
        if (record.expiresAtMs > nowMs) return null
        clear(context)
        return record
    }
}

/**
 * Signal-style "Missed voice call" notification for a call that rang here and
 * ended unanswered. Tap / "Message" open the thread; "Call back" opens it and
 * places the same call type. Posted at most once per call id, never for a call
 * answered or declined on this device.
 */
object MissedCallNotifier {
    const val EXTRA_CALL_BACK = "aino_call_back_type"
    const val EXTRA_MISSED_CALL_ID = "aino_missed_call_id"

    private fun store(context: Context) = MissedCallStateStore(context, notificationAccount(context) ?: "signed_out")

    /** The user answered or declined [callId] on this device: it can never become a missed call here. */
    fun markHandled(context: Context, callId: Long) {
        store(context).markHandled(callId)
        cancel(context, callId)
        RingingCallStore.clear(context, callId)
    }

    fun isHandled(context: Context, callId: Long): Boolean = store(context).handled(callId)

    fun post(context: Context, info: MissedCallInfo): PostingOutcome {
        if (notificationAccount(context) == null) return PostingOutcome.Suppressed("Signed out")
        if (isHandled(context, info.callId) || store(context).posted(info.callId)) return PostingOutcome.Suppressed("Call already handled or posted")
        PushNotifications.createChannels(context)
        notificationBlocker(context, PushNotifications.MISSED_CALLS)?.let { return it }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return PostingOutcome.Blocked("Notification permission denied")
        val name = info.callerName.ifBlank { "Missed call" }
        val text = missedCallText(info.callType, group = info.isGroup)
        val builder = NotificationCompat.Builder(context, PushNotifications.MISSED_CALLS)
            .setSmallIcon(R.drawable.ic_stat_aino)
            .setColor(ChatNotifications.brandColor(context))
            .setContentTitle(name)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(threadPendingIntent(context, info, slot = 0, callBack = null))
        runCatching {
            NotificationAvatars.fallback(name, info.callerId?.let { "aino-user-$it" } ?: name)
        }.getOrNull()?.let(builder::setLargeIcon)
        if (!info.isGroup) builder.addAction(0, "Call back", threadPendingIntent(context, info, slot = 1, callBack = info.callType))
        builder.addAction(0, "Message", threadPendingIntent(context, info, slot = 2, callBack = null))
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return PostingOutcome.Failed("No notification manager")
            manager.notify(NotificationTags.MISSED_CALL, info.callId.hashCode(), builder.build())
            store(context).recordPosting(info.callId, PostingOutcome.Posted)
            RingingCallStore.clear(context, info.callId)
            PostingOutcome.Posted
        }.getOrElse { PostingOutcome.Failed("Missed-call posting failed") }
    }

    fun cancel(context: Context, callId: Long) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NotificationTags.MISSED_CALL, callId.hashCode()) }
    }

    /** MainActivity: a tapped missed-call notification / action. Returns true when [intent] was one. */
    fun consumeIntent(context: Context, intent: Intent?): Boolean {
        val callId = intent?.getLongExtra(EXTRA_MISSED_CALL_ID, 0L)?.takeIf { it > 0 } ?: return false
        cancel(context, callId)
        markHandled(context, callId)
        val conversationId = intent.getStringExtra("conversation_id")?.toLongOrNull()
        val callBack = intent.getStringExtra(EXTRA_CALL_BACK)
        if (conversationId != null && callBack != null) PendingCallBack.set(conversationId, callBack)
        intent.removeExtra(EXTRA_MISSED_CALL_ID)
        intent.removeExtra(EXTRA_CALL_BACK)
        return true
    }

    private fun threadPendingIntent(context: Context, info: MissedCallInfo, slot: Int, callBack: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "app.aino.mobile.notification.MISSED_CALL.${notificationAccount(context)}.${info.callId}.$slot"
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putMissedCallTapExtras(info.conversationId, info.callId)
            putExtra(EXTRA_MISSED_CALL_ID, info.callId)
            if (callBack != null) putExtra(EXTRA_CALL_BACK, callBack)
        }
        return PendingIntent.getActivity(
            context,
            (info.callId * 3 + slot).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

fun missedCallText(callType: String, group: Boolean = false): String {
    val kind = if (callType == "video") "video call" else "voice call"
    return if (group) "Missed group $kind" else "Missed $kind"
}

/** Ring endings that do not pass through a realtime `call_ended` on this device. */
object MissedCalls {
    /** First successful history load establishes a baseline, not a backlog of old notifications. */
    fun reconcileHistory(context: Context, calls: List<CallLog>, currentUserId: Long) {
        val account = notificationAccount(context) ?: return
        if (account.substringAfter(':').toLongOrNull() != currentUserId) return
        val prefs = context.getSharedPreferences("aino_call_history_$account", Context.MODE_PRIVATE)
        if (!prefs.contains("baseline")) {
            prefs.edit().putLong("baseline", calls.maxOfOrNull { it.id } ?: 0)
                .putStringSet("pending", calls.filter { it.status == "ringing" && it.callerId != currentUserId }.map { it.id.toString() }.toSet()).commit()
            return
        }
        val baseline = prefs.getLong("baseline", 0)
        val pending = prefs.getStringSet("pending", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        calls.filter { missedHistoryEligible(it, currentUserId, baseline, pending) }.forEach { call ->
            MissedCallNotifier.post(context, MissedCallInfo(call.id, call.conversationId, call.callerId,
                call.callerName.orEmpty(), call.callType, isGroup = call.isGroup))
        }
    }
    /** The local ring timer ran out: expire a still-ringing session and leave a missed call. */
    fun onLocalRingTimeout(context: Context, info: MissedCallInfo) {
        val session = CallSessionRuntime.get(context)
        val state = session.state.value
        if (state.route?.callId == info.callId && state.phase == CallPhase.Ringing) {
            // The session's missed-call hook posts the notification.
            session.expire()
            session.reset()
        }
        if (!session.acceptedHere(info.callId)) MissedCallNotifier.post(context, info)
    }

    /**
     * `call_handled_elsewhere` push: `cancelled` / `ended` while this device was
     * still ringing is a missed call; `accepted` / `rejected` / `handled_elsewhere`
     * means another of the user's devices took it.
     */
    fun onRingEndedRemotely(context: Context, callId: Long, conversationId: Long, reason: String?) {
        val session = CallSessionRuntime.get(context)
        if (session.acceptedHere(callId)) return
        if (reason in setOf("accepted", "rejected", "handled_elsewhere")) MissedCallNotifier.markHandled(context, callId)
        val record = RingingCallStore.read(context)?.info?.takeIf { it.callId == callId }
        val state = session.state.value
        val ringingRoute = state.route?.takeIf { it.callId == callId && state.incoming && state.phase == CallPhase.Ringing }
        if (ringingRoute != null) {
            // Bring the session in line with the server (the socket may be down).
            if (reason == "accepted" || reason == "rejected" || reason == "handled_elsewhere") {
                session.handle(CallRealtimeEvent.HandledElsewhere(callId, conversationId, reason))
            } else {
                session.handle(CallRealtimeEvent.Ended(callId, conversationId, reason))
            }
        }
        val info = record ?: ringingRoute?.let(MissedCallInfo::of)
        if (info != null && shouldNotifyMissedCall(ringingHere = true, handledHere = MissedCallNotifier.isHandled(context, callId), reason = reason)) {
            MissedCallNotifier.post(context, info)
        } else {
            RingingCallStore.clear(context, callId)
        }

    }
}

internal fun missedHistoryEligible(call: CallLog, userId: Long, baseline: Long, pending: Set<Long> = emptySet()): Boolean =
    (call.id > baseline || call.id in pending) && call.callerId != userId && call.status == "missed" && call.conversationId > 0

/** "Call back" from a missed-call notification, consumed by the chat thread once it is open. */
object PendingCallBack {
    data class Request(val conversationId: Long, val callType: String, val atMs: Long)

    private const val TTL_MS = 60_000L
    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request.asStateFlow()

    fun set(conversationId: Long, callType: String, nowMs: Long = System.currentTimeMillis()) {
        _request.value = Request(conversationId, if (callType == "video") "video" else "voice", nowMs)
    }

    /** The pending request for [conversationId] (cleared), or null; stale requests are dropped. */
    fun consume(conversationId: Long, nowMs: Long = System.currentTimeMillis()): Request? {
        val current = _request.value ?: return null
        if (current.conversationId != conversationId) return null
        _request.value = null
        return current.takeIf { nowMs - it.atMs in 0..TTL_MS }
    }
}
