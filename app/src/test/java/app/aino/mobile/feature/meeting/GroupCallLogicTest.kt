package app.aino.mobile.feature.meeting

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupCallLogicTest {
    private fun peer(id: Long, video: Boolean = false) = MeetingPeer(id, "P$id", null, videoOff = !video)

    @Test
    fun layoutFollowsTheNumberOfOtherPeople() {
        assertEquals(GroupCallLayout.Alone, groupCallLayout(0))
        assertEquals(GroupCallLayout.OneOnOne, groupCallLayout(1))
        assertEquals(GroupCallLayout.Grid, groupCallLayout(2))
        assertEquals(GroupCallLayout.Grid, groupCallLayout(4))
        assertEquals(GroupCallLayout.Speaker, groupCallLayout(5))
    }

    @Test
    fun speakerViewFocusesTheActiveSpeakerFirst() {
        val peers = listOf(peer(1), peer(2), peer(3))
        assertEquals(2L, speakerFocus(peers, activeSpeakerId = 2)?.userId)
        assertEquals(1L, speakerFocus(peers, activeSpeakerId = null)?.userId)
        assertEquals(null, speakerFocus(emptyList(), null))
    }

    @Test
    fun lobbyCopy() {
        assertEquals("Start call", lobbyPrimaryLabel(null))
        assertEquals("Start call", lobbyPrimaryLabel(LobbyActiveCall(1, "X")))
        val active = LobbyActiveCall(1, "X", participants = listOf(LobbyParticipant(7, "Ana Lee"), LobbyParticipant(8, "Ben"), LobbyParticipant(9, "Cy")))
        assertEquals("Join call", lobbyPrimaryLabel(active))
        assertEquals("No one else is here", lobbyPresenceText(null, 1))
        assertEquals("Ana and 2 others are in this call", lobbyPresenceText(active, 1))
        assertEquals("Ben and 1 other are in this call", lobbyPresenceText(active, selfId = 7))
        assertEquals("Ana is in this call", lobbyPresenceText(active.copy(participants = active.participants.take(1)), 1))
    }

    @Test
    fun reactionsExpireAndAreCapped() {
        val now = 100_000L
        val old = CallReactionBurst(1, 7, "Ana", "👍", now - CALL_REACTION_MS - 1)
        val fresh = (2L..8L).map { CallReactionBurst(it, 7, "Ana", "🎉", now) }
        val next = addReaction(listOf(old) + fresh.dropLast(1), fresh.last(), now)
        assertEquals(6, next.size)
        assertEquals(8L, next.last().id)
        assertEquals(false, next.any { it.id == 1L })
    }

    @Test
    fun elapsedTime() {
        assertEquals("0:05", callElapsed(0, 5_000))
        assertEquals("1:05", callElapsed(0, 65_000))
        assertEquals("1:02:09", callElapsed(0, 3_729_000))
        assertEquals("0:00", callElapsed(10_000, 0))
    }
}
