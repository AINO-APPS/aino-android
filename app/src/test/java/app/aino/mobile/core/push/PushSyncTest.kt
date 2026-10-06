package app.aino.mobile.core.push

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.ConversationEntity
import app.aino.mobile.core.db.InMemoryAinoDao
import app.aino.mobile.core.db.MessageEntity
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushSyncTest {
    private val scope = CacheScope(1, 7)
    private val paths = CopyOnWriteArrayList<String>()

    private fun row(id: Long, conversationId: Long = 5) =
        """{"id":$id,"conversation_id":$conversationId,"sender_id":8,"content":"m$id","created_at":"2026-10-06T10:00:${(id % 60).toString().padStart(2, '0')}Z","sender_name":"Asha"}"""

    private fun api(respond: (String) -> String) = ApiClient { request ->
        paths += request.path
        ApiResponse(200, mapOf("Content-Type" to listOf("application/json")), respond(request.path).toByteArray())
    }

    private fun stored(dao: InMemoryAinoDao, vararg ids: Long) = runBlocking {
        dao.upsertMessages(ids.map { MessageEntity(1, 7, it, 5, 8, "m$it", it * 1000, "sent", row(it)) })
    }

    @Test
    fun anEmptyThreadStoresTheLatestPageWithFullPayloads() = runBlocking {
        val dao = InMemoryAinoDao()
        val written = syncChatThread(api { "[${row(1)},${row(2)}]" }, ScopedCache(scope, dao), scope, 5)

        assertEquals(listOf("chat/conversations/5/messages?limit=50"), paths)
        assertEquals(listOf(1L, 2L), written.map(MessageEntity::messageId))
        val rows = dao.getMessages(1, 7, 5)
        assertEquals(listOf(1L, 2L), rows.map(MessageEntity::messageId))
        assertTrue(rows.all { it.payloadJson?.contains("\"sender_name\":\"Asha\"") == true && it.userId == 7L && it.tenantId == 1L })
    }

    @Test
    fun storedThreadsFetchOnlyTheDeltaAndKeepOlderRows() = runBlocking {
        val dao = InMemoryAinoDao()
        stored(dao, 1, 2)
        syncChatThread(api { "[${row(3)}]" }, ScopedCache(scope, dao), scope, 5)

        assertEquals(listOf("chat/conversations/5/messages?limit=100&after=2"), paths)
        assertEquals(listOf(1L, 2L, 3L), dao.getMessages(1, 7, 5).map(MessageEntity::messageId))
    }

    @Test
    fun aDeltaAsLargeAsAPageRestartsTheStoredThread() = runBlocking {
        val dao = InMemoryAinoDao()
        stored(dao, 1, 2)
        val delta = (100L..149L).joinToString(",", "[", "]") { row(it) }
        syncChatThread(api { delta }, ScopedCache(scope, dao), scope, 5)

        assertEquals((100L..149L).toList(), dao.getMessages(1, 7, 5).map(MessageEntity::messageId).sorted())
    }

    @Test
    fun aTruncatedBacklogStoresTheNewestPageInsteadOfTheOldestWindow() = runBlocking {
        val dao = InMemoryAinoDao()
        stored(dao, 1, 2)
        val backlog = (3L..102L).joinToString(",", "[", "]") { row(it) }
        val latest = (251L..300L).joinToString(",", "[", "]") { row(it) }
        syncChatThread(api { if ("after=" in it) backlog else latest }, ScopedCache(scope, dao), scope, 5)

        assertEquals(listOf("chat/conversations/5/messages?limit=100&after=2", "chat/conversations/5/messages?limit=50"), paths)
        assertEquals((251L..300L).toList(), dao.getMessages(1, 7, 5).map(MessageEntity::messageId).sorted())
    }

    @Test
    fun rowsOfOtherConversationsOrMalformedRowsAreNotStored() = runBlocking {
        val dao = InMemoryAinoDao()
        syncChatThread(api { """[${row(1)},${row(2, conversationId = 6)},{"id":3},"x"]""" }, ScopedCache(scope, dao), scope, 5)

        assertEquals(listOf(1L), dao.getMessages(1, 7, 5).map(MessageEntity::messageId))
        assertTrue(dao.getMessages(1, 7, 6).isEmpty())
    }

    @Test
    fun theStoredConversationRowTakesTheServerUnreadCount() = runBlocking {
        val dao = InMemoryAinoDao()
        dao.upsertConversations(listOf(ConversationEntity(1, 7, 5, "Asha", null, 0, 0)))
        val list = """[{"id":5,"unread_count":3,"last_message_at":"2026-10-06T10:00:00Z"},{"id":6,"unread_count":9}]"""
        syncConversationRow(api { list }, ScopedCache(scope, dao), 5)

        val row = dao.getConversations(1, 7).single()
        assertEquals(3, row.unreadCount)
        assertEquals(java.time.Instant.parse("2026-10-06T10:00:00Z").toEpochMilli(), row.updatedAtEpochMs)
    }

    @Test
    fun deltaAppendsNewMessagesDedupesAndTrims() {
        val cached = """[{"id":1,"content":"a"},{"id":2,"content":"b"}]"""
        val delta = """[{"id":2,"content":"b-edited"},{"id":3,"content":"c"}]"""

        val merged = mergeMessagesJson(cached, delta, keep = 2)

        assertEquals("""[{"id":2,"content":"b-edited"},{"id":3,"content":"c"}]""", merged)
    }

    @Test
    fun lastIdReadsTheNewestCachedMessage() {
        assertEquals(9L, lastMessageId("""[{"id":4},{"id":9},{"id":7}]"""))
        assertNull(lastMessageId("[]"))
        assertNull(lastMessageId("not json"))
    }

    @Test
    fun aServerWithoutAfterSupportStillMergesCorrectly() {
        // An older server ignores `after` and returns the latest page: ids overlap, nothing duplicates.
        val merged = mergeMessagesJson("""[{"id":1},{"id":2}]""", """[{"id":1},{"id":2},{"id":3}]""")
        assertEquals("""[{"id":1},{"id":2},{"id":3}]""", merged)
    }
}
