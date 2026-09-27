package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardHeightTest {
    @Test fun `missing or too small heights use Signal's 260dp default`() {
        assertEquals(260f, resolveKeyboardHeight(0f, 800f, landscape = false))
        assertEquals(260f, resolveKeyboardHeight(180f, 800f, landscape = false))
    }

    @Test fun `a settled keyboard height is used as is`() {
        assertEquals(336f, resolveKeyboardHeight(336f, 800f, landscape = false))
    }

    @Test fun `portrait leaves 170dp above the drawer`() {
        assertEquals(430f, resolveKeyboardHeight(520f, 600f, landscape = false))
    }

    @Test fun `landscape is not clamped by the portrait margin`() {
        assertEquals(300f, resolveKeyboardHeight(300f, 360f, landscape = true))
    }

    @Test fun `only the visible thread's row is forced read`() {
        val list = listOf(ChatConversation(1, unreadCount = 4), ChatConversation(2, unreadCount = 3))
        assertEquals(listOf(0, 3), zeroUnread(list, 1).map { it.unreadCount })
        assertEquals(listOf(4, 3), zeroUnread(list, null).map { it.unreadCount })
    }
}
