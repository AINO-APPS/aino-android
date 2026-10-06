package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushTapRoutingTest {
    private val tap = PushTap("chat_message", "msg:9", 42, "9", tappedAtMs = 1_000)

    @Test
    fun successfulRoutesEmitTappedThenConsumed() {
        var n = 0
        val events = routingEvents(tap, routed = true, nowMs = 1_250) { "e${n++}" }

        assertEquals(listOf("tapped", "route_consumed"), events.map { it.state })
        assertEquals(listOf("notification_tapped", "notification_routed"), events.map { it.event })
        assertEquals(250L, events[1].durationMs)
        assertEquals("42", events[0].conversationId)
        assertEquals("msg:9", events[1].dedupeKey)
        assertEquals("android", events[0].source)
        assertEquals(listOf("e0", "e1"), events.map { it.clientEventId })
    }

    @Test
    fun failedRoutesAreErrorLevel() {
        val events = routingEvents(tap, routed = false, nowMs = 900)
        assertEquals("ERROR", events[1].level)
        assertEquals("route_failed", events[1].state)
    }

    @Test
    fun tapExtrasAreParsedLeniently() {
        val full = pushTapOf(
            type = "approval", dedupeKey = "notif:9", conversationId = null, messageId = null,
            link = " /manager?tab=approvals&request=4 ", taskId = "", notificationId = "9", title = "New Leave Request", nowMs = 5,
        )
        assertEquals("/manager?tab=approvals&request=4", full.link)
        assertNull(full.taskId)
        assertEquals(9L, full.notificationId)
        assertEquals("New Leave Request", full.title)
        assertEquals(5L, full.tappedAtMs)

        // Older notifications (posted before the update) carry none of the new extras.
        val legacy = pushTapOf("leave", "notif:3", null, null, null, null, null, null, nowMs = 1)
        assertNull(legacy.link)
        assertNull(legacy.taskId)
        assertNull(legacy.notificationId)
        assertNull(legacy.title)

        val junk = pushTapOf("task", null, "0", null, "https://x.test", "-2", "abc", " ", nowMs = 1)
        assertNull(junk.conversationId)
        assertNull(junk.link)
        assertNull(junk.taskId)
        assertNull(junk.notificationId)
        assertNull(junk.title)
    }

    @Test
    fun chatTapsCarryAThreadHint() {
        val chat = pushTapOf(
            "chat_message", "msg:9", "42", "9", null, null, null, "Asha", nowMs = 1,
            isGroup = "false", avatar = "/uploads/a.png", unreadCount = "3",
        )
        assertEquals("Asha", chat.title)
        assertEquals(false, chat.isGroup)
        assertEquals("/uploads/a.png", chat.avatar)
        assertEquals(3, chat.unreadCount)

        val sparse = pushTapOf("chat_message", "msg:9", "42", "9", null, null, null, null, nowMs = 1, isGroup = "true", avatar = " ", unreadCount = "-4")
        assertEquals(true, sparse.isGroup)
        assertNull(sparse.avatar)
        assertEquals(0, sparse.unreadCount)
    }
}
