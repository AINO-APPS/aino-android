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
)

private const val EXTRA_TYPE = "push_type"
private const val EXTRA_CONVERSATION = "conversation_id"
private const val EXTRA_DEDUPE = "push_dedupe_key"
private const val EXTRA_MESSAGE = "push_message_id"

fun Intent.putPushTapExtras(push: ValidatedPush): Intent = apply {
    putExtra(EXTRA_TYPE, push.data["type"])
    putExtra(EXTRA_CONVERSATION, push.data["conversationId"])
    putExtra(EXTRA_DEDUPE, push.dedupeKey)
    putExtra(EXTRA_MESSAGE, push.data["messageId"])
}

/** Null for launches that did not come from one of our notifications. Calls are routed by the call stack. */
fun parsePushTap(intent: Intent?, nowMs: Long = System.currentTimeMillis()): PushTap? {
    val type = intent?.getStringExtra(EXTRA_TYPE)?.takeIf(String::isNotBlank) ?: return null
    if (type == "incoming_call" || type == "call_handled_elsewhere") return null
    return PushTap(
        type = type,
        dedupeKey = intent.getStringExtra(EXTRA_DEDUPE),
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION)?.toLongOrNull()?.takeIf { it > 0 },
        messageId = intent.getStringExtra(EXTRA_MESSAGE),
        tappedAtMs = nowMs,
    )
}

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
