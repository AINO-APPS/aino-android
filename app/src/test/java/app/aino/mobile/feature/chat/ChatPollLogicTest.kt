package app.aino.mobile.feature.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChatPollLogicTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun poll(votes: Map<Int, List<PollVoter>> = emptyMap(), multi: Boolean = false) = ChatPoll(
        42, 5, 1, "Lunch?", listOf("Soup", "Salad", "Rice"), multiSelect = multi, votes = votes,
    )

    @Test fun `poll voter avatars decode when present null or omitted in old payloads`() {
        val decoded = json.decodeFromString<ChatPoll>("""{
            "id":42,"conversation_id":5,"creator_id":1,"question":"Lunch?",
            "options":["Soup","Salad","Rice"],"votes":{
                "0":[{"userId":1,"fullName":"Asha Mehta","avatar":"/uploads/asha.png"}],
                "1":[{"userId":2,"fullName":"Ben Shah","avatar":null}],
                "2":[{"userId":3,"fullName":"Cara Patel"}]
            }
        }""")
        assertEquals(PollVoter(1, "Asha Mehta", "/uploads/asha.png"), decoded.votes[0]!!.single())
        assertEquals(PollVoter(2, "Ben Shah"), decoded.votes[1]!!.single())
        assertEquals(PollVoter(3, "Cara Patel"), decoded.votes[2]!!.single())
        assertEquals(listOf("Soup", "Salad", "Rice"), decoded.options)
        assertFalse(decoded.multiSelect)
        assertNull(decoded.closedAt)
        assertEquals(decoded.votes[0], pollVoterGroups(decoded, 1)[0].voters)
    }

    @Test fun `creator validates all trust boundary limits and keeps current defaults`() {
        val draft = PollDraft()
        assertFalse(draft.multiSelect)
        assertEquals(2, draft.options.size)
        assertNotNull(draft.error)
        assertNull(pollCreationError("Q".repeat(500), listOf("A".repeat(200), "B")))
        assertNotNull(pollCreationError("Q".repeat(501), listOf("A", "B")))
        assertNotNull(pollCreationError("Q", listOf("A".repeat(201), "B")))
        assertNotNull(pollCreationError("Q", listOf("A")))
        assertNotNull(pollCreationError("Q", List(11) { "A" }))
        assertNotNull(pollCreationError("   ", listOf("A", "B")))
        assertNotNull(pollCreationError("Q", listOf("A", "  ")))
        assertNull(pollCreationError(" Q ", listOf("A", "B", "")))
    }

    @Test fun `option removal preserves identity and never reuses an id`() {
        val draft = PollDraft().updateOption(0, "Soup").updateOption(1, "Salad").addOption().updateOption(2, "Rice")
        val changed = draft.removeOption(1).addOption()
        assertEquals(listOf(0, 2, 3), changed.options.map { it.id })
        assertEquals(listOf("Soup", "Rice", ""), changed.options.map { it.text })
        assertEquals(PollDraft(), PollDraft().removeOption(0))
        val maximum = (1..20).fold(PollDraft()) { state, _ -> state.addOption() }
        assertEquals(10, maximum.options.size)
        assertEquals(maximum, maximum.addOption())
        assertEquals(200, draft.updateOption(0, "x".repeat(201)).options[0].text.length)
    }

    @Test fun `tallies use all selections and unique voters are labeled separately`() {
        val mine = PollVoter(1, "Asha")
        val poll = poll(mapOf(0 to listOf(mine, PollVoter(2, "Ben")), 1 to listOf(mine)), multi = true)
        val tally = pollTally(poll, 3, 1)
        assertEquals(3, tally.total)
        assertEquals(listOf(67, 33, 0), tally.options.map { it.percent })
        assertEquals(listOf(true, true, false), tally.options.map { it.mine })
        assertEquals(2, pollUniqueVoterCount(poll))
        assertEquals("1 vote", pollVoteLabel(1))
        assertEquals("0 votes", pollVoteLabel(0))
    }

    @Test fun `voters are grouped by option with You first ties and zero vote sections`() {
        val poll = poll(mapOf(
            0 to listOf(PollVoter(9, "Zoe"), PollVoter(1, "Asha")),
            1 to listOf(PollVoter(8, "Ben"), PollVoter(7, "Amy")),
        ))
        val groups = pollVoterGroups(poll, 1)
        assertEquals(listOf(0, 1, 2), groups.map { it.optionIndex })
        assertEquals("You", groups[0].voters.first().displayName(1))
        assertEquals(listOf("Amy", "Ben"), groups[1].voters.map { it.displayName(1) })
        assertTrue(groups.take(2).all { it.leading && it.tied })
        assertFalse(groups[2].leading)
        assertTrue(groups[2].voters.isEmpty())
        assertTrue(pollVoterGroups(poll(), 1).none { it.leading || it.tied })
        assertEquals("Member", PollVoter(10, "").displayName(1))
    }

    @Test fun `long voter lists expand completely and live changes recalculate leaders`() {
        val voters = (1L..12L).map { PollVoter(it, "Member $it") }
        val initial = poll(mapOf(0 to voters.take(2), 1 to voters))
        val groups = pollVoterGroups(initial, 1)
        assertEquals(5, groups[1].visibleVoters(false).size)
        assertEquals(12, groups[1].visibleVoters(true).size)
        assertTrue(groups[1].leading)
        val updated = pollVoterGroups(initial.copy(votes = mapOf(0 to voters)), 1)
        assertTrue(updated[0].leading)
        assertFalse(updated[1].leading)
    }

    @Test fun `votes require authoritative load and are disabled for pending closed or stale results`() {
        val state = PollUiState()
        assertFalse(state.canVote(null))
        assertTrue(state.canVote(poll()))
        assertFalse(state.copy(loading = true).canVote(poll()))
        assertFalse(state.copy(voting = true).canVote(poll()))
        assertFalse(state.copy(loadError = "Retry").canVote(poll()))
        assertFalse(state.canVote(poll().copy(closedAt = "2026-10-10")))
        assertTrue(state.copy(voteError = "Retry vote").canVote(poll()))
    }
}
