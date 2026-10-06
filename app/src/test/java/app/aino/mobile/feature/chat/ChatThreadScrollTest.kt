package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThreadScrollTest {
    @Test fun `bottom means the newest row with at most the slop of offset`() {
        assertTrue(isAtThreadBottom(0, 0, slopPx = 24))
        assertTrue(isAtThreadBottom(0, 24, slopPx = 24))
        assertFalse(isAtThreadBottom(0, 25, slopPx = 24))
        assertFalse(isAtThreadBottom(1, 0, slopPx = 24))
    }

    @Test fun `a burst at the bottom follows without animating, a single row animates`() {
        assertEquals(ThreadScroll.ToNewest(animate = false), scrollOnNewItems(wasAtBottom = true, newestIsMine = false, added = 4))
        assertEquals(ThreadScroll.ToNewest(animate = true), scrollOnNewItems(wasAtBottom = true, newestIsMine = false, added = 1))
    }

    @Test fun `a reader scrolled up keeps their place unless they sent the row`() {
        assertEquals(ThreadScroll.None, scrollOnNewItems(wasAtBottom = false, newestIsMine = false, added = 3))
        assertEquals(ThreadScroll.ToNewest(animate = true), scrollOnNewItems(wasAtBottom = false, newestIsMine = true, added = 1))
        assertEquals(ThreadScroll.None, scrollOnNewItems(wasAtBottom = true, newestIsMine = true, added = 0))
    }

    @Test fun `arrivals are counted from the previous newest row`() {
        assertEquals(3, addedAtNewest(listOf("s5", "s4", "s3", "s2"), previousNewestKey = "s2"))
        assertEquals(0, addedAtNewest(listOf("s2", "s1"), previousNewestKey = "s2"))
        // A pending bubble replaced by its server row.
        assertEquals(1, addedAtNewest(listOf("server-9", "s1"), previousNewestKey = "queued-a"))
        assertEquals(2, addedAtNewest(listOf("s2", "s1"), previousNewestKey = null))
    }

    @Test fun `a thread opens on its first unread row, otherwise at the bottom`() {
        val keys = listOf("s5", "s4", "s3", "d1", "s2")
        assertEquals(ThreadScroll.ToUnread(2), initialThreadScroll(keys, dividerKey = "s3", jumpPending = false, userScrolled = false))
        assertEquals(ThreadScroll.ToNewest(animate = false), initialThreadScroll(keys, dividerKey = null, jumpPending = false, userScrolled = false))
        // Only the newest row unread: the bottom already shows it.
        assertEquals(ThreadScroll.ToNewest(animate = false), initialThreadScroll(keys, dividerKey = "s5", jumpPending = false, userScrolled = false))
        assertEquals(ThreadScroll.ToNewest(animate = false), initialThreadScroll(keys, dividerKey = "gone", jumpPending = false, userScrolled = false))
    }

    @Test fun `a pending jump or a reader who scrolled is never moved by the open`() {
        val keys = listOf("s2", "s1")
        assertEquals(ThreadScroll.None, initialThreadScroll(keys, dividerKey = "s1", jumpPending = true, userScrolled = false))
        assertEquals(ThreadScroll.None, initialThreadScroll(keys, dividerKey = "s1", jumpPending = false, userScrolled = true))
        assertEquals(ThreadScroll.None, initialThreadScroll(emptyList(), dividerKey = null, jumpPending = false, userScrolled = false))
    }
}
