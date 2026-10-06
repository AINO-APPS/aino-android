package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatThreadCacheTest {
    private fun msg(id: Long, content: String = "m$id") =
        ChatMessage(id = id, senderId = 1, content = content, createdAt = "2026-09-30T10:00:${(id % 60).toString().padStart(2, '0')}Z")

    private fun page(range: LongRange) = range.map { msg(it) }

    @Test fun `lru evicts the least recently opened thread and trims long threads`() {
        val cache = ThreadMessageCache(maxThreads = 2, maxMessages = 3)
        cache.put(1, page(1L..5L))
        cache.put(2, page(10L..11L))
        cache.get(1)
        cache.put(3, page(20L..20L))
        assertNull(cache.get(2))
        assertEquals(listOf(3L, 4L, 5L), cache.get(1)!!.messages.map(ChatMessage::id))
        assertEquals(listOf(20L), cache.get(3)!!.messages.map(ChatMessage::id))
    }

    @Test fun `lru keeps receipts until replaced and forgets emptied threads`() {
        val cache = ThreadMessageCache()
        val receipt = ReadReceipt(2, "2026-09-30T10:00:00Z", "B")
        cache.put(1, page(1L..2L), listOf(receipt))
        cache.put(1, page(1L..3L))
        assertEquals(listOf(receipt), cache.get(1)!!.receipts)
        cache.put(1, emptyList())
        assertNull(cache.get(1))
    }

    @Test fun `unchanged network rows keep their instances`() {
        val current = page(1L..3L)
        val same = reuseUnchanged(current, page(1L..3L))
        assertSame(current, same)
        val edited = reuseUnchanged(current, listOf(msg(1), msg(2, "edited"), msg(3)))
        assertSame(current[0], edited[0])
        assertEquals("edited", edited[1].content)
        assertSame(current[2], edited[2])
    }

    @Test fun `latest page keeps contiguous older rows`() {
        val current = page(1L..60L)
        val merged = mergeLatestPage(current, page(11L..61L).let { it.subList(it.size - 50, it.size) }, pageSize = 50)
        assertEquals((1L..61L).toList(), merged.map(ChatMessage::id))
        assertSame(current[0], merged[0])
        assertSame(current[59], merged[59])
    }

    @Test fun `latest page with a gap restarts the thread`() {
        val merged = mergeLatestPage(page(1L..10L), page(100L..149L), pageSize = 50)
        assertEquals((100L..149L).toList(), merged.map(ChatMessage::id))
    }

    @Test fun `a live row stored past a gap does not glue the latest page to stale rows`() {
        // Stored rows 1-10, then a live row 140; the server page 100-149 must not keep 1-10 above it.
        val merged = mergeLatestPage(page(1L..10L) + msg(140), page(100L..149L), pageSize = 50)
        assertEquals((100L..149L).toList(), merged.map(ChatMessage::id))
    }

    @Test fun `stored rows only add what the thread lacks`() {
        val shown = listOf(msg(1), msg(2, "live edit"))
        val merged = mergeStoredRows(shown, listOf(msg(1), msg(2, "stale"), msg(3)))
        assertEquals(listOf(1L, 2L, 3L), merged.map(ChatMessage::id))
        assertEquals("live edit", merged[1].content)
        assertSame(shown, mergeStoredRows(shown, listOf(msg(2))))
    }

    @Test fun `short latest page is the whole history`() {
        val merged = mergeLatestPage(page(1L..10L), page(5L..10L), pageSize = 50)
        assertEquals((5L..10L).toList(), merged.map(ChatMessage::id))
    }

    @Test fun `older page replaces cached rows in its window`() {
        val shown = page(48L..60L) + page(100L..110L)
        val merged = mergeOlderPage(shown, page(50L..99L), before = 100L, pageSize = 50)
        assertEquals((48L..110L).toList(), merged.map(ChatMessage::id))
        assertSame(shown.last(), merged.last())
    }

    @Test fun `short older page drops stale cached rows beyond history start`() {
        val shown = page(1L..3L) + page(10L..12L)
        val merged = mergeOlderPage(shown, page(2L..3L), before = 10L, pageSize = 50)
        assertEquals(listOf(2L, 3L, 10L, 11L, 12L), merged.map(ChatMessage::id))
    }

    @Test fun `older page is dropped once a gap reset removed its anchor`() {
        val shown = page(251L..300L)
        assertTrue(canMergeOlderPage(shown, before = 251L))
        // refreshThread restarted the thread on a newer page while the older request was in flight.
        val reset = mergeLatestPage(shown, page(400L..449L), pageSize = 50)
        assertFalse(canMergeOlderPage(reset, before = 251L))
        assertFalse(canMergeOlderPage(emptyList(), before = 251L))
    }

    @Test fun `only rows arriving at the newest end animate`() {
        val arrivals = ThreadArrivals(burstLimit = 3)
        assertTrue(arrivals.update(listOf("s3", "s2", "s1")).isEmpty())
        // Older page appended at the far end (top of a reversed list): no animation.
        assertTrue(arrivals.update(listOf("s3", "s2", "s1", "s0", "d1")).isEmpty())
        assertEquals(setOf("s4"), arrivals.update(listOf("s4", "s3", "s2", "s1", "s0", "d1")))
        // Pending bubble swapped for the server row: the new key animates, the removed one is forgotten.
        assertEquals(setOf("s4", "q1"), arrivals.update(listOf("q1", "s4", "s3", "s2", "s1", "s0", "d1")))
        assertEquals(setOf("s4", "s5"), arrivals.update(listOf("s5", "s4", "s3", "s2", "s1", "s0", "d1")))
    }

    @Test fun `catch up bursts and the first page never animate`() {
        val arrivals = ThreadArrivals(burstLimit = 2)
        assertTrue(arrivals.update(emptyList()).isEmpty())
        assertTrue(arrivals.update(listOf("s2", "s1")).isEmpty())
        assertTrue(arrivals.update(listOf("s5", "s4", "s3", "s2", "s1")).isEmpty())
    }

    @Test fun `content types separate row shapes`() {
        val mine = ThreadItem.Message(msg(1), true, true)
        val theirs = ThreadItem.Message(msg(2).copy(senderId = 9), true, true)
        val media = ThreadItem.Message(msg(3).copy(fileUrl = "/f.jpg"), true, true)
        val types = listOf(mine, theirs, media).map { it.contentType(currentUserId = 1) }
        assertEquals(3, types.toSet().size)
        assertEquals(mine.contentType(1), ThreadItem.Message(msg(4), false, false).contentType(1))
    }

    @Test fun `room cache round trips full messages and pages before an id`() = kotlinx.coroutines.runBlocking {
        val dao = InMemoryMessagesDao()
        val scope = app.aino.mobile.core.db.CacheScope(1, 1)
        val cache = ChatCache(scope, app.aino.mobile.core.db.ScopedCache(scope, dao))
        val rows = page(1L..5L).map { it.copy(conversationId = 7, fileUrl = "/f${it.id}.jpg", fileType = "image/jpeg") }
        cache.replaceMessages(7, rows)
        assertEquals(rows, cache.fullMessages(7))
        assertEquals(listOf(2L, 3L), cache.fullMessages(7, before = 4, limit = 2).map(ChatMessage::id))
        // Text-only legacy rows (no payload) are never offered as a full thread.
        dao.rows.replaceAll { it.copy(payloadJson = null) }
        assertTrue(cache.fullMessages(7).isEmpty())
        assertEquals(5, cache.messageSnapshot(7).size)
    }

    internal class InMemoryMessagesDao : app.aino.mobile.core.db.AinoDao {
        val rows = java.util.concurrent.CopyOnWriteArrayList<app.aino.mobile.core.db.MessageEntity>()
        override fun observeConversations(tenantId: Long, userId: Long) = kotlinx.coroutines.flow.flowOf(emptyList<app.aino.mobile.core.db.ConversationEntity>())
        override suspend fun getConversations(tenantId: Long, userId: Long) = emptyList<app.aino.mobile.core.db.ConversationEntity>()
        override suspend fun upsertConversations(values: List<app.aino.mobile.core.db.ConversationEntity>) = Unit
        override fun observeMessages(tenantId: Long, userId: Long, conversationId: Long) = kotlinx.coroutines.flow.flowOf(rows.toList())
        override suspend fun getMessages(tenantId: Long, userId: Long, conversationId: Long) =
            rows.filter { it.conversationId == conversationId }.sortedBy { it.createdAtEpochMs }
        override suspend fun upsertMessages(values: List<app.aino.mobile.core.db.MessageEntity>) {
            rows.removeIf { row -> values.any { it.messageId == row.messageId } }
            rows += values
        }
        override suspend fun clearConversationMessages(tenantId: Long, userId: Long, conversationId: Long) { rows.removeIf { it.conversationId == conversationId } }
        override suspend fun putOutbox(value: app.aino.mobile.core.db.OutboxEntity) = Unit
        override suspend fun pendingOutbox(tenantId: Long, userId: Long, now: Long, limit: Int) = emptyList<app.aino.mobile.core.db.OutboxEntity>()
        override suspend fun markOutboxRetry(tenantId: Long, userId: Long, clientMessageId: String, nextAttemptAt: Long) = Unit
        override suspend fun markOutboxFailed(tenantId: Long, userId: Long, clientMessageId: String) = Unit
        override suspend fun deleteOutbox(tenantId: Long, userId: Long, clientMessageId: String) = Unit
        override suspend fun clearConversations(tenantId: Long, userId: Long) = Unit
        override suspend fun deleteConversation(tenantId: Long, userId: Long, conversationId: Long) = Unit
        override suspend fun clearMessages(tenantId: Long, userId: Long) = Unit
        override suspend fun clearOutbox(tenantId: Long, userId: Long) = Unit
    }
}
