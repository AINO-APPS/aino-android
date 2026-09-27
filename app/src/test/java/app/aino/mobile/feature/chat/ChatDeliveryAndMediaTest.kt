package app.aino.mobile.feature.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

class ChatDeliveryAndMediaTest {
    private fun message(id: Long, sender: Long = 20L, deliveredTo: List<Long> = emptyList(), deletedAt: String? = null, metadata: JsonObject? = null) =
        ChatMessage(id = id, senderId = sender, createdAt = "2024-01-01T00:00:00Z", deliveredTo = deliveredTo, deletedAt = deletedAt, metadata = metadata)

    private fun file(id: Long) = SharedChatFile(id = id, fileUrl = "/uploads/$id.jpg", createdAt = "2024-01-01T00:00:00Z", senderId = 20L)

    @Before fun reset() = DeliveryReceipts.resetForTest()

    @Test fun `delivered event appends the recipient once`() {
        val event = ChatDeliveredEvent(messageId = 1, conversationId = 5, userId = 30)
        val once = applyDelivered(listOf(message(1, sender = 10), message(2, sender = 10)), event)
        assertEquals(listOf(30L), once[0].deliveredTo)
        assertEquals(emptyList<Long>(), once[1].deliveredTo)
        assertEquals(listOf(30L), applyDelivered(once, event)[0].deliveredTo)
    }

    @Test fun `receipts only for incoming, live, not yet delivered messages`() {
        val me = 10L
        val messages = listOf(
            message(1, sender = me),
            message(2),
            message(3, deliveredTo = listOf(me)),
            message(4, deletedAt = "2024-01-01T00:01:00Z"),
            message(5),
        )
        assertEquals(listOf(2L, 5L), DeliveryReceipts.pending(messages, me))
        assertEquals(emptyList<Long>(), DeliveryReceipts.pending(messages, null))
    }

    @Test fun `deleting or hiding a message prunes it from shared media`() {
        val content = InfoContent.Files(listOf(file(1), file(2), file(3)))
        val pruned = pruneSharedFiles(content, setOf(2L)) as InfoContent.Files
        assertEquals(listOf(1L, 3L), pruned.files.map { it.id })
        assertSame(content, pruneSharedFiles(content, setOf(99L)))
        assertNull(pruneSharedFiles(null, setOf(1L)))
    }

    @Test fun `media aspect comes from upload metadata`() {
        val meta = JsonObject(mapOf("width" to JsonPrimitive(1200), "height" to JsonPrimitive(1600)))
        assertEquals(0.75f, message(1, metadata = meta).mediaAspect()!!, 0.0001f)
        assertNull(message(2).mediaAspect())
    }

    @Test fun `sender cannot open own view-once media`() {
        val meta = JsonObject(mapOf("viewOnce" to JsonPrimitive(true)))
        assertEquals(ViewOnceState.SentUnviewed, viewOnceState(message(1, sender = 10, metadata = meta), currentUserId = 10))
    }
}
