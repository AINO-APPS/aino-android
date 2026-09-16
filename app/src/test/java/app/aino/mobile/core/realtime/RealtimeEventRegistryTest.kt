package app.aino.mobile.core.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-100 registry tests.
 *
 * `scripts/check-realtime-parity.mjs` owns the server-vs-Android diff. These
 * tests pin the invariants that script cannot see: internal consistency,
 * classification correctness, and the boundary between WebSocket events and
 * in-band chat system-message subtypes.
 */
class RealtimeEventRegistryTest {
    @Test
    fun routesTheCompleteServerSurface() {
        // 73 = 66 sendToUser types + 6 tenant-wide broadcast types + task_assigned.
        assertEquals(73, RealtimeEvent.entries.size)
    }

    @Test
    fun wireTypesAreUnique() {
        val duplicates = RealtimeEvent.entries.groupBy(RealtimeEvent::type).filterValues { it.size > 1 }
        assertTrue("Duplicate wire types: ${duplicates.keys}", duplicates.isEmpty())
    }

    @Test
    fun resolvesKnownTypesAndRejectsUnknownOnes() {
        assertEquals(RealtimeEvent.ChatMessage, RealtimeEvent.from("chat_message"))
        assertEquals(RealtimeEvent.PlanChanged, RealtimeEvent.from("plan_changed"))
        assertNull(RealtimeEvent.from("definitely_not_an_event"))
        // Guards the exact drift this registry exists to prevent: these were
        // routed by the pre-A-100 client but the server never emits them.
        assertNull(RealtimeEvent.from("chat_group_updated"))
        assertNull(RealtimeEvent.from("task_updated"))
    }

    @Test
    fun systemMessageSubtypesAreNotRealtimeEvents() {
        // `group_renamed` and friends arrive as metadata inside a chat_message
        // envelope. Routing them as WebSocket events would be a contract error.
        for (subtype in listOf("group_renamed", "group_info_updated", "member_added", "role_changed")) {
            assertNull("$subtype must not be a RealtimeEvent", RealtimeEvent.from(subtype))
            assertNotNull("$subtype must be a system-message subtype", ChatSystemMessageType.from(subtype))
        }
    }

    @Test
    fun meetingEventsAreRoutedButMediaIsSeparable() {
        val meeting = RealtimeEvent.inDomain(RealtimeDomain.Meeting)
        assertEquals(22, meeting.size)
        // Media negotiation is distinguishable so it can stay inert until A-082
        // without suppressing meeting state updates.
        val media = meeting.filter { it.reaction == RealtimeReaction.MeetingMedia }
        assertEquals(
            listOf(
                "meeting_peer_ready",
                "meeting_signal",
                "meeting_screen_track_id",
                "meeting_request_quality",
            ).sorted(),
            media.map(RealtimeEvent::type).sorted(),
        )
    }

    @Test
    fun everyEventHasADomainAndReaction() {
        for (event in RealtimeEvent.entries) {
            assertTrue(event.type.isNotBlank())
            assertNotNull(event.domain)
            assertNotNull(event.reaction)
        }
    }

    @Test
    fun ephemeralEventsNeverTriggerRefetch() {
        // Typing and audio-level bursts must not cause network reloads; that
        // was the pre-A-100 defect where chat_typing forced a list refresh.
        val ephemeral = RealtimeEvent.entries.filter { it.reaction == RealtimeReaction.Ephemeral }
        assertEquals(
            listOf("call_reaction", "chat_typing", "meeting_audio_level"),
            ephemeral.map(RealtimeEvent::type).sorted(),
        )
    }

    @Test
    fun controlPlaneEventsRegateNavigation() {
        val regate = RealtimeEvent.entries
            .filter { it.reaction == RealtimeReaction.Regate }
            .map(RealtimeEvent::type)
        assertTrue(regate.containsAll(listOf("plan_changed", "tenant_features_changed", "branding_changed")))
    }

    @Test
    fun domainPartitionCoversEveryEvent() {
        val partitioned = RealtimeDomain.entries.sumOf { RealtimeEvent.inDomain(it).size }
        assertEquals(RealtimeEvent.entries.size, partitioned)
    }
}
