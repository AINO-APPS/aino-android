package app.aino.mobile.core.push

import java.time.Instant

enum class PushKind { ChatMessage, IncomingCall, CallHandledElsewhere, General }

data class ValidatedPush(
    val kind: PushKind,
    val data: Map<String, String>,
    val dedupeKey: String,
    val tenantId: Long?,
)

fun validatePushPayload(data: Map<String, String>, now: Instant = Instant.now()): Result<ValidatedPush> = runCatching {
    fun required(name: String): String = data[name]?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing push field: $name")
    fun positive(name: String): Long = required(name).toLongOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("Invalid push field: $name")
    fun nonNegative(name: String): Long = required(name).toLongOrNull()?.takeIf { it >= 0 }
        ?: throw IllegalArgumentException("Invalid push field: $name")
    fun tenant(): Long? = data["tenantId"]?.takeIf(String::isNotBlank)?.toLongOrNull()?.takeIf { it > 0 }
    fun sentAt() { Instant.parse(required("sentAt")) }
    fun exact(required: Set<String>, optional: Set<String> = emptySet()) {
        require(data.keys.containsAll(required)) { "Push payload is missing required fields" }
        require(data.keys.all { it in required || it in optional }) { "Push payload has unknown fields" }
    }

    val type = required("type")
    val kind = when (type) {
        "chat_message" -> {
            exact(setOf("type", "title", "body", "conversationId", "messageId", "senderId", "senderName", "isGroup", "groupName", "senderAvatar", "unreadCount", "badgeCount", "dedupeKey", "expiresAt", "tenantId", "sentAt"))
            positive("conversationId"); positive("messageId"); positive("senderId")
            nonNegative("unreadCount"); nonNegative("badgeCount"); required("title"); required("senderName")
            // Group bodies are "{sender}: {150-char preview}" (server pushNotifications.ts).
            require((data["body"] ?: "").length <= 400) { "Chat push body is too long" }
            require(data["isGroup"] in setOf("true", "false"))
            require(required("dedupeKey") == "msg:${data["messageId"]}")
            val expiry = required("expiresAt").toLongOrNull()?.let(Instant::ofEpochSecond)
                ?: throw IllegalArgumentException("Invalid push field: expiresAt")
            require(expiry.isAfter(now)) { "Push payload expired" }
            PushKind.ChatMessage
        }
        "incoming_call" -> {
            exact(
                setOf("type", "title", "body", "callId", "conversationId", "callerId", "callType", "isGroup", "groupName", "meetingCode", "expiresAt", "dedupeKey", "callCategory", "tenantId", "sentAt"),
                setOf("callerName", "callerAvatar"),
            )
            positive("callId"); positive("conversationId"); positive("callerId")
            require(data["title"] in setOf("Incoming Voice Call", "Incoming Video Call"))
            require(data["callType"] in setOf("voice", "video"))
            require(data["isGroup"] in setOf("true", "false"))
            require(required("callCategory") == "incoming-call")
            require(required("dedupeKey") == "call:${data["callId"]}")
            require(Instant.parse(required("expiresAt")).isAfter(now)) { "Push payload expired" }
            val privatePayload = !data.containsKey("callerName") && !data.containsKey("callerAvatar")
            if (privatePayload) require(required("body") == "Tap to answer")
            else { required("callerName"); require(data.containsKey("callerAvatar")); require(required("body").endsWith(" is calling...")) }
            PushKind.IncomingCall
        }
        "call_handled_elsewhere" -> {
            exact(setOf("type", "callId", "conversationId", "reason", "dedupeKey", "tenantId", "sentAt"))
            positive("callId"); positive("conversationId")
            require(data["reason"] in setOf("accepted", "cancelled", "rejected", "ended", "handled_elsewhere"))
            require(required("dedupeKey") == "call_cancel:${data["callId"]}")
            PushKind.CallHandledElsewhere
        }
        else -> {
            exact(setOf("notificationId", "type", "title", "body", "badgeCount", "dedupeKey", "actorAvatar", "actorName", "tenantId", "sentAt"))
            positive("notificationId"); required("title"); required("body"); positive("badgeCount")
            require(required("dedupeKey") == "notif:${data["notificationId"]}")
            require(type !in setOf("incoming_call", "call_handled_elsewhere", "chat_message"))
            PushKind.General
        }
    }
    sentAt()
    val tenantValue = data["tenantId"] ?: throw IllegalArgumentException("Missing push field: tenantId")
    require(tenantValue.isEmpty() || tenantValue.toLongOrNull()?.let { it > 0 } == true) { "Invalid push field: tenantId" }
    ValidatedPush(kind, data, required("dedupeKey"), tenant())
}

fun notificationId(push: ValidatedPush): Int = when (push.kind) {
    PushKind.ChatMessage -> push.data.getValue("conversationId").toLong().hashCode()
    PushKind.IncomingCall, PushKind.CallHandledElsewhere -> push.data.getValue("callId").toLong().hashCode()
    PushKind.General -> push.data.getValue("notificationId").toLong().hashCode()
}