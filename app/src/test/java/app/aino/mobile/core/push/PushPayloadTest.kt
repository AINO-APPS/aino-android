package app.aino.mobile.core.push

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPayloadTest {
    private val fixtureTime = Instant.parse("2026-06-17T02:42:45Z")

    @Test
    fun validatesAllFivePlatformFixtures() {
        assertEquals(PushKind.ChatMessage, validatePushPayload(chat(), fixtureTime).getOrThrow().kind)
        assertEquals(PushKind.General, validatePushPayload(general(), fixtureTime).getOrThrow().kind)
        assertEquals(PushKind.CallHandledElsewhere, validatePushPayload(cancel(), fixtureTime).getOrThrow().kind)
        assertEquals(PushKind.IncomingCall, validatePushPayload(privateCall(), fixtureTime).getOrThrow().kind)
        assertEquals(PushKind.IncomingCall, validatePushPayload(visibleCall(), fixtureTime).getOrThrow().kind)
    }

    @Test
    fun rejectsExpiredMismatchedAndPrivacyLeakingPayloads() {
        assertTrue(validatePushPayload(chat() + ("dedupeKey" to "msg:999"), fixtureTime).isFailure)
        assertTrue(validatePushPayload(chat(), Instant.ofEpochSecond(1_781_667_766)).isFailure)
        assertTrue(validatePushPayload(privateCall() + ("callerName" to "Priya"), fixtureTime).isFailure)
        assertTrue(validatePushPayload(general() + ("unknown" to "leak"), fixtureTime).isFailure)
        assertTrue(validatePushPayload(chat() + ("body" to "x".repeat(401)), fixtureTime).isFailure)
        assertTrue(validatePushPayload(visibleCall() + ("title" to "Incoming Fax"), fixtureTime).isFailure)
    }

    @Test
    fun acceptsGroupMessagesWithAFullPreview() {
        // Server: body = "{sender}: {messagePreview.substring(0,150)}" for groups.
        val group = chat() + mapOf(
            "isGroup" to "true", "groupName" to "Design", "title" to "Design",
            "body" to "Alice Johnson: " + "x".repeat(150), "unreadCount" to "0", "badgeCount" to "0",
        )
        assertEquals(PushKind.ChatMessage, validatePushPayload(group, fixtureTime).getOrThrow().kind)
    }

    @Test
    fun usesStableEntityNotificationIds() {
        val chat = validatePushPayload(chat(), fixtureTime).getOrThrow()
        val call = validatePushPayload(visibleCall(), fixtureTime).getOrThrow()
        assertEquals(456L.hashCode(), notificationId(chat))
        assertEquals(199L.hashCode(), notificationId(call))
    }

    private fun chat() = mapOf(
        "type" to "chat_message", "title" to "Alice", "body" to "Hello from Alice", "conversationId" to "456",
        "messageId" to "123", "senderId" to "7", "senderName" to "Alice", "isGroup" to "false", "groupName" to "",
        "senderAvatar" to "", "unreadCount" to "5", "badgeCount" to "5", "dedupeKey" to "msg:123",
        "expiresAt" to "1781667765", "tenantId" to "1", "sentAt" to "2026-06-17T02:42:45.000Z",
    )

    private fun general() = mapOf(
        "notificationId" to "99", "type" to "leave", "title" to "Leave Approved",
        "body" to "Your leave on 2026-06-20 has been approved.", "badgeCount" to "3", "dedupeKey" to "notif:99",
        "actorAvatar" to "", "actorName" to "", "tenantId" to "1", "sentAt" to "2026-06-17T02:42:45.000Z",
    )

    private fun cancel() = mapOf(
        "type" to "call_handled_elsewhere", "callId" to "401", "conversationId" to "41", "reason" to "cancelled",
        "dedupeKey" to "call_cancel:401", "tenantId" to "1", "sentAt" to "2026-06-17T02:42:45.000Z",
    )

    private fun privateCall() = mapOf(
        "type" to "incoming_call", "title" to "Incoming Voice Call", "body" to "Tap to answer",
        "callId" to "301", "conversationId" to "31", "callerId" to "6", "callType" to "voice", "isGroup" to "false",
        "groupName" to "", "meetingCode" to "", "expiresAt" to "2026-06-17T02:43:15.000Z",
        "dedupeKey" to "call:301", "callCategory" to "incoming-call", "tenantId" to "1", "sentAt" to "2026-06-17T02:42:45.000Z",
    )

    private fun visibleCall() = privateCall() + mapOf(
        "title" to "Incoming Video Call", "body" to "Priya is calling...", "callId" to "199",
        "conversationId" to "19", "callerId" to "13", "callerName" to "Priya",
        "callerAvatar" to "https://cdn.example.test/priya.png", "callType" to "video", "dedupeKey" to "call:199",
    )
}