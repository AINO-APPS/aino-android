package app.aino.mobile.feature.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRealtimePatchTest {
    private val message = ChatMessage(id = 10, senderId = 1, createdAt = "2026-10-07T10:00:00Z", mediaJobId = 3, mediaState = "queued", mediaProgress = 0)

    @Test fun `media job progress patches only the matching message`() {
        val other = message.copy(id = 11)
        val patched = applyRealtimeMediaJob(
            listOf(message, other),
            ChatMediaJobEvent(messageId = 10, conversationId = 5, status = "processing", stage = "transcode", progress = 40),
        )
        assertEquals("processing", patched[0].mediaState)
        assertEquals("transcode", patched[0].mediaStage)
        assertEquals(40, patched[0].mediaProgress)
        assertEquals(3L, patched[0].mediaJobId)
        assertEquals(other, patched[1])
    }

    @Test fun `media job failure records the reason and a later success clears it`() {
        val failed = applyRealtimeMediaJob(listOf(message), ChatMediaJobEvent(10, 5, status = "failed", failureReason = "too large"))
        assertEquals("too large", failed[0].mediaFailureReason)
        val ready = applyRealtimeMediaJob(failed, ChatMediaJobEvent(10, 5, status = "ready", progress = 100))
        assertNull(ready[0].mediaFailureReason)
        assertEquals("ready", ready[0].mediaState)
    }

    @Test fun `poll id is read from message metadata`() {
        val poll = message.copy(formatType = "poll", metadata = buildJsonObject { put("pollId", 42) })
        assertEquals(42L, poll.pollId())
        assertNull(message.pollId())
        assertEquals(7L, message.copy(metadata = buildJsonObject { put("pollId", JsonPrimitive("7")) }).pollId())
    }

    @Test fun `poll tally counts votes, rounds shares and marks the user's picks`() {
        val poll = ChatPoll(
            id = 1, conversationId = 5, creatorId = 1, question = "Lunch?", options = listOf("A", "B", "C"),
            votes = mapOf(0 to listOf(PollVoter(1, "Me"), PollVoter(2, "Ravi")), 1 to listOf(PollVoter(3, "Asha"))),
        )
        val tally = pollTally(poll, 3, currentUserId = 1)
        assertEquals(3, tally.total)
        assertEquals(PollOptionTally(2, 67, mine = true), tally.options[0])
        assertEquals(PollOptionTally(1, 33, mine = false), tally.options[1])
        assertEquals(PollOptionTally(0, 0, mine = false), tally.options[2])
    }

    @Test fun `poll tally before load shows empty options`() {
        val tally = pollTally(null, 2, currentUserId = 1)
        assertEquals(0, tally.total)
        assertTrue(tally.options.all { it.count == 0 && !it.mine })
        assertFalse(tally.options.isEmpty())
    }
}
