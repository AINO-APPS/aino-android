package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatNotificationHistoryTest {
    private fun line(i: Int) = ChatNotificationLine("m$i", i.toLong(), "aino-user-1", "Asha")

    @Test
    fun appendKeepsNewestLinesOldestFirst() {
        var lines = emptyList<ChatNotificationLine>()
        repeat(12) { lines = appendLine(lines, line(it)) }
        assertEquals(ChatNotificationHistory.MAX_LINES, lines.size)
        assertEquals("m4", lines.first().text)
        assertEquals("m11", lines.last().text)
    }

    @Test
    fun historyIsPerConversationAndClearable() {
        ChatNotificationHistory.clearAll()
        ChatNotificationHistory.append(1, line(1))
        ChatNotificationHistory.append(1, line(2))
        ChatNotificationHistory.append(2, line(3))
        assertEquals(2, ChatNotificationHistory.get(1).size)
        ChatNotificationHistory.clear(1)
        assertEquals(0, ChatNotificationHistory.get(1).size)
        assertEquals(1, ChatNotificationHistory.get(2).size)
        ChatNotificationHistory.clearAll()
    }

    @Test
    fun groupBodiesDropTheSenderPrefix() {
        assertEquals("hello", stripSenderPrefix("Asha: hello", "Asha", isGroup = true))
        assertEquals("Asha: hello", stripSenderPrefix("Asha: hello", "Asha", isGroup = false))
        assertEquals("Ben: hello", stripSenderPrefix("Ben: hello", "Asha", isGroup = true))
    }

    @Test
    fun parsesOrgAccentColours() {
        assertEquals(0xFF2383E2.toInt(), parseHexColor("#2383E2"))
        assertEquals(0xFFFF0000.toInt(), parseHexColor("#f00"))
        assertEquals(0x80112233.toInt(), parseHexColor("80112233"))
        assertNull(parseHexColor("blue"))
        assertNull(parseHexColor(null))
    }
}
