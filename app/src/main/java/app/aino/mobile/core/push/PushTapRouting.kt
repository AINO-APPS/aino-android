package app.aino.mobile.core.push

import android.content.Intent
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A tapped system notification waiting to be routed once the shell is signed in. */
data class PushTap(
    val type: String,
    val dedupeKey: String?,
    val conversationId: Long?,
    val messageId: String?,
    val tappedAtMs: Long,
    /** Generic alerts: the server's relative web path, when it sent one. */
    val link: String? = null,
    val taskId: Long? = null,
    /** `notifications.id`, marked read when the tap is routed. */
    val notificationId: Long? = null,
    val title: String? = null,
)

private const val EXTRA_TYPE = "push_type"
private const val EXTRA_CONVERSATION = "conversation_id"
private const val EXTRA_DEDUPE = "push_dedupe_key"
private const val EXTRA_MESSAGE = "push_message_id"
private const val EXTRA_LINK = "push_link"
private const val EXTRA_TASK = "push_task_id"
private const val EXTRA_NOTIFICATION = "push_notification_id"
private const val EXTRA_TITLE = "push_title"

fun Intent.putPushTapExtras(push: ValidatedPush): Intent = apply {
    putExtra(EXTRA_TYPE, push.data["type"])
    putExtra(EXTRA_CONVERSATION, push.data["conversationId"])
    putExtra(EXTRA_DEDUPE, push.dedupeKey)
    putExtra(EXTRA_MESSAGE, push.data["messageId"])
    if (push.kind == PushKind.General) {
        putExtra(EXTRA_LINK, pushLink(push.data))
        putExtra(EXTRA_TASK, pushLinkTaskId(push.data)?.toString())
        putExtra(EXTRA_NOTIFICATION, push.data["notificationId"])
        putExtra(EXTRA_TITLE, push.data["title"])
    }
}

/** Null for launches that did not come from one of our notifications. Calls are routed by the call stack. */
fun parsePushTap(intent: Intent?, nowMs: Long = System.currentTimeMillis()): PushTap? {
    val type = intent?.getStringExtra(EXTRA_TYPE)?.takeIf(String::isNotBlank) ?: return null
    if (type == "incoming_call" || type == "call_handled_elsewhere") return null
    return pushTapOf(
        type = type,
        dedupeKey = intent.getStringExtra(EXTRA_DEDUPE),
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION),
        messageId = intent.getStringExtra(EXTRA_MESSAGE),
        link = intent.getStringExtra(EXTRA_LINK),
        taskId = intent.getStringExtra(EXTRA_TASK),
        notificationId = intent.getStringExtra(EXTRA_NOTIFICATION),
        title = intent.getStringExtra(EXTRA_TITLE),
        nowMs = nowMs,
    )
}

/** Lenient extras → [PushTap]: blank / malformed optional values become null. */
fun pushTapOf(
    type: String,
    dedupeKey: String?,
    conversationId: String?,
    messageId: String?,
    link: String?,
    taskId: String?,
    notificationId: String?,
    title: String?,
    nowMs: Long,
): PushTap = PushTap(
    type = type,
    dedupeKey = dedupeKey,
    conversationId = conversationId?.toLongOrNull()?.takeIf { it > 0 },
    messageId = messageId,
    tappedAtMs = nowMs,
    link = link?.trim()?.takeIf { it.startsWith("/") && !it.startsWith("//") },
    taskId = taskId?.trim()?.toLongOrNull()?.takeIf { it > 0 },
    notificationId = notificationId?.trim()?.toLongOrNull()?.takeIf { it > 0 },
    title = title?.takeIf(String::isNotBlank),
)

/** The one pending tap; MainActivity sets it, the authenticated shell consumes it. */
object PendingPushTap {
    private val _tap = MutableStateFlow<PushTap?>(null)
    val tap: StateFlow<PushTap?> = _tap.asStateFlow()
    fun set(tap: PushTap) { _tap.value = tap }
    fun consume(): PushTap? = _tap.value.also { _tap.value = null }
}

/** `POST /api/notifications/metrics/events` item (server `normalizeMetricEvent`). */
@Serializable
data class NotificationMetricEvent(
    val clientEventId: String,
    val event: String,
    val timestamp: Long,
    val level: String = "INFO",
    val state: String? = null,
    val dedupeKey: String? = null,
    val conversationId: String? = null,
    val messageId: String? = null,
    val notificationType: String? = null,
    val durationMs: Long? = null,
    val source: String = "android",
)

@Serializable
data class NotificationMetricBatch(val events: List<NotificationMetricEvent>)

/** Telemetry for the Analytics "Notification Routing" card: the tap, then the consumed route. */
fun routingEvents(tap: PushTap, routed: Boolean, nowMs: Long, id: () -> String = { UUID.randomUUID().toString() }): List<NotificationMetricEvent> {
    fun event(name: String, state: String, timestamp: Long, level: String = "INFO", duration: Long? = null) = NotificationMetricEvent(
        clientEventId = id(), event = name, timestamp = timestamp, level = level, state = state,
        dedupeKey = tap.dedupeKey, conversationId = tap.conversationId?.toString(), messageId = tap.messageId,
        notificationType = tap.type, durationMs = duration,
    )
    val tapped = event("notification_tapped", "tapped", tap.tappedAtMs)
    val outcome = if (routed) event("notification_routed", "route_consumed", nowMs, duration = (nowMs - tap.tappedAtMs).coerceAtLeast(0))
    else event("notification_route_failed", "route_failed", nowMs, level = "ERROR")
    return listOf(tapped, outcome)
}

/** Best-effort; telemetry must never affect navigation. */
fun reportNotificationMetrics(api: ApiClient, events: List<NotificationMetricEvent>, json: Json = Json { encodeDefaults = true }) {
    runCatching {
        api.execute(ApiRequest("POST", "notifications/metrics/events", body = json.encodeToString(NotificationMetricBatch(events)).toByteArray()))
    }
}
