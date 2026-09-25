package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageGesturesTest {
    @Test fun `LTR only travels toward end and caps`() {
        assertEquals(0f, replyDragOffset(0f, -20f, 1f, 76f))
        assertEquals(76f, replyDragOffset(50f, 50f, 1f, 76f))
    }

    @Test fun `RTL end is negative`() {
        assertEquals(0f, replyDragOffset(0f, 20f, -1f, 76f))
        assertEquals(-76f, replyDragOffset(-50f, -50f, -1f, 76f))
    }

    @Test fun `progress clamps around trigger`() {
        assertEquals(0f, replyProgress(-2f, 1f, 56f))
        assertEquals(.5f, replyProgress(28f, 1f, 56f))
        assertEquals(1f, replyProgress(-70f, -1f, 56f))
    }
}
