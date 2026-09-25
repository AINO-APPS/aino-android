package app.aino.mobile.feature.notifications

import app.aino.mobile.core.common.LenientIntSerializer
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A row of `SELECT n.*, t.title AS task_title FROM notifications n LEFT JOIN tasks t`. */
@Serializable
data class NotificationItem(
    val id: Long,
    val type: String? = null,
    val title: String? = null,
    val body: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("is_read") val isRead: Boolean = false,
    @SerialName("link_task_id") val linkTaskId: Long? = null,
    @SerialName("task_title") val taskTitle: String? = null,
)

/** `GET /api/notifications` → `{ notifications, unread, total, page, perPage }` (bare JSON). */
@Serializable
data class NotificationsPage(
    val notifications: List<NotificationItem> = emptyList(),
    @Serializable(with = LenientIntSerializer::class) val unread: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val total: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val page: Int = 1,
    @Serializable(with = LenientIntSerializer::class) val perPage: Int = 50,
)

/** Icon keys mirroring NotificationBell `TYPE_ICON`; anything else is the default bell. */
enum class NotificationIcon { Mention, Leave, Task, Approval, MeetingInvite, Default }

fun notificationIcon(type: String?): NotificationIcon = when (type) {
    "mention" -> NotificationIcon.Mention
    "leave" -> NotificationIcon.Leave
    "task" -> NotificationIcon.Task
    "approval" -> NotificationIcon.Approval
    "meeting_invite" -> NotificationIcon.MeetingInvite
    else -> NotificationIcon.Default
}

/**
 * NotificationBell `handleClick` target: a linked task opens the task board,
 * a meeting invite opens the calendar, everything else only marks read.
 */
fun notificationLink(item: NotificationItem): String? = when {
    item.linkTaskId != null && item.linkTaskId != 0L -> "/tasks?task=${item.linkTaskId}"
    item.type == "meeting_invite" -> "/calendar"
    else -> null
}

/** `notif-badge` text: hidden at zero, capped at "99+". */
fun unreadBadgeLabel(unread: Int): String? = when {
    unread <= 0 -> null
    unread > 99 -> "99+"
    else -> unread.toString()
}

/** Realtime events NotificationBell answers with a refetch. */
val NOTIFICATION_REFRESH_EVENTS: Set<String> = setOf(
    "notification",
    "leave_update",
    "task_assigned",
    "approval_update",
    "meeting_invite",
    "meeting_started",
)

/** `NOTIFICATION_POLL_INTERVAL` in `client/src/constants/index.ts`. */
const val NOTIFICATION_POLL_INTERVAL_MS = 30_000L

/**
 * NotificationBell `timeAgo`. Zone-less timestamps are UTC (the web appends
 * `Z`); an unparsable value yields "" instead of the web's "NaNd ago".
 */
fun notificationTimeAgo(dateStr: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val instant = parseServerInstant(dateStr) ?: return ""
    val diff = (nowMillis - instant.toEpochMilli()) / 1000.0
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${(diff / 60).toInt()}m ago"
        diff < 86400 -> "${(diff / 3600).toInt()}h ago"
        else -> "${(diff / 86400).toInt()}d ago"
    }
}

internal fun parseServerInstant(value: String?): Instant? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }
    return null
}
