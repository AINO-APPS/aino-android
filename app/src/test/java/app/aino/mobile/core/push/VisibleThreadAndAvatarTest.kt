package app.aino.mobile.core.push

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleThreadAndAvatarTest {
    @After fun tearDown() = VisibleThread.clear()

    @Test fun `only the resumed thread is visible`() {
        VisibleThread.set(7)
        assertTrue(VisibleThread.isVisible(7))
        assertFalse(VisibleThread.isVisible(8))
    }

    @Test fun `a late pause of an old thread does not clear the newer one`() {
        VisibleThread.set(7)
        VisibleThread.set(9)
        VisibleThread.clear(7)
        assertTrue(VisibleThread.isVisible(9))
        VisibleThread.clear(9)
        assertFalse(VisibleThread.isVisible(9))
    }

    @Test fun `initials follow Signal NameUtil abbreviation`() {
        assertEquals("AK", NotificationAvatars.initials("Asha Kumar"))
        assertEquals("AR", NotificationAvatars.initials("  asha  mary  ravi "))
        assertEquals("A", NotificationAvatars.initials("asha"))
        assertEquals("", NotificationAvatars.initials(""))
        assertEquals("", NotificationAvatars.initials("🙂 ✨"))
    }

    @Test fun `fallback colour is stable per key`() {
        assertEquals(NotificationAvatars.colorsFor("aino-user-8"), NotificationAvatars.colorsFor("aino-user-8"))
        val distinct = (1..40).map { NotificationAvatars.colorsFor("aino-user-$it") }.toSet()
        assertTrue(distinct.size > 1)
        val (bg, fg) = NotificationAvatars.colorsFor("x")
        assertNotEquals(bg, fg)
    }
}
