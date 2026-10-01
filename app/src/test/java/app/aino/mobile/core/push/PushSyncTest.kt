package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushSyncTest {
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
