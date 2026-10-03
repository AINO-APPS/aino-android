package app.aino.mobile.core.push

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * Tags that keep notification ids of different kinds apart. Chat ids are the
 * conversation id and alert ids the `notifications` row id — both small
 * integers — so without a tag alert #4 and conversation #4 replaced (and
 * cancelled) each other.
 */
object NotificationTags {
    const val CHAT = "aino_chat"
    const val ALERT = "aino_alert"
    const val CALL = "aino_call"
}

/** What the tray currently shows, as far as reconciliation needs to know. */
data class ActiveNotification(
    val tag: String?,
    val id: Int,
    val postedAtMs: Long,
    /** Ongoing / foreground-service notifications (calls) are never touched. */
    val ongoing: Boolean = false,
)

/**
 * The server's view of what is still unread. A null `*SyncedAtMs` means that
 * source has not been fetched yet, so its notifications are left alone.
 */
data class UnreadTruth(
    val unreadConversationIds: Set<Long> = emptySet(),
    val chatSyncedAtMs: Long? = null,
    val unreadAlertIds: Set<Long> = emptySet(),
    /**
     * The bell fetches one page only; alert ids below this were not in the
     * page, so their read state is unknown. 0 = the whole list was loaded.
     */
    val alertWindowMinId: Long = 0,
    val alertsSyncedAtMs: Long? = null,
)

/** Foreground-service notifications posted by the call services without a tag. */
private val CALL_SERVICE_IDS = setOf(909090, 909091)

/**
 * Notifications the server says are already read (or deleted). Samsung One UI
 * badges the launcher icon with the tray's AINO notifications, so leftovers
 * showed as a false count (e.g. "6") after the messages were read elsewhere.
 *
 * A notification posted after the data was fetched is never removed — the
 * data cannot know about it yet.
 */
fun staleNotifications(active: List<ActiveNotification>, truth: UnreadTruth): List<ActiveNotification> {
    val unreadChats = truth.unreadConversationIds.mapTo(mutableSetOf()) { it.hashCode() }
    val unreadAlerts = truth.unreadAlertIds.mapTo(mutableSetOf()) { it.hashCode() }
    fun chatStale(n: ActiveNotification): Boolean {
        val synced = truth.chatSyncedAtMs ?: return false
        return n.postedAtMs <= synced && n.id !in unreadChats
    }
    fun alertStale(n: ActiveNotification): Boolean {
        val synced = truth.alertsSyncedAtMs ?: return false
        return n.postedAtMs <= synced && n.id >= truth.alertWindowMinId && n.id !in unreadAlerts
    }
    return active.filter { n ->
        if (n.ongoing) return@filter false
        when (n.tag) {
            NotificationTags.CHAT -> chatStale(n)
            NotificationTags.ALERT -> alertStale(n)
            // Untagged rows were posted by an older app version: chat and alert
            // ids shared one namespace, so drop one only when neither source
            // still has that id unread.
            null -> n.id !in CALL_SERVICE_IDS && chatStale(n) && alertStale(n)
            else -> false
        }
    }
}

/** Applies [staleNotifications] to the system tray. All calls are best effort. */
object NotificationReconciler {
    fun reconcile(context: Context, truth: UnreadTruth) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { manager.activeNotifications.toList() }.getOrNull() ?: return
        val mine = active.filter { it.packageName == context.packageName }.map {
            ActiveNotification(
                tag = it.tag,
                id = it.id,
                postedAtMs = it.postTime,
                ongoing = it.isOngoing || (it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0,
            )
        }
        val stale = staleNotifications(mine, truth)
        if (stale.isEmpty()) return
        val compat = NotificationManagerCompat.from(context)
        stale.forEach { n ->
            if (n.tag == NotificationTags.CHAT || n.tag == null) ChatNotificationHistory.clear(n.id.toLong())
            runCatching { compat.cancel(n.tag, n.id) }
        }
    }

    /** Sign-out: nothing from the previous account may stay in the tray (calls excepted). */
    fun clearAll(context: Context) {
        ChatNotificationHistory.clearAll()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = runCatching { manager.activeNotifications.toList() }.getOrNull() ?: return
        val compat = NotificationManagerCompat.from(context)
        active.filter { it.packageName == context.packageName && !it.isOngoing && it.tag != NotificationTags.CALL && it.id !in CALL_SERVICE_IDS }
            .forEach { runCatching { compat.cancel(it.tag, it.id) } }
    }
}
