package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
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
}
