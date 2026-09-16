package app.aino.mobile.core.call

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSessionControllerTest {
    private val route = IncomingCallRoute(41, 7, 9, "Priya", null, "video")

    @Test
    fun duplicateInviteMergesAndDifferentInviteCannotReplaceActiveCall() {
        val controller = CallSessionController()
        assertTrue(controller.incoming(route))
        assertTrue(controller.incoming(route.copy(callerName = "Priya P")))
        assertFalse(controller.incoming(route.copy(callId = 42)))
        assertEquals(41L, controller.state.value.route?.callId)
        assertEquals("Priya P", controller.state.value.route?.callerName)
    }

    @Test
    fun onlyMatchingTerminalEventEndsTheSession() {
        var releases = 0
        val controller = CallSessionController(CallResourceOwner { releases++ })
        controller.incoming(route)

        assertFalse(controller.handle(CallRealtimeEvent.Ended(99, 7, "wrong")))
        assertEquals(CallPhase.Ringing, controller.state.value.phase)
        assertTrue(controller.handle(CallRealtimeEvent.Ended(41, 7, "remote")))
        assertEquals(CallPhase.Ended, controller.state.value.phase)
        assertEquals(1, releases)
    }

    @Test
    fun terminalCleanupExecutesExactlyOnce() {
        var releases = 0
        var releasedId: Long? = null
        val controller = CallSessionController(CallResourceOwner { releases++; releasedId = it })
        controller.incoming(route)
        controller.localEnd()
        controller.handle(CallRealtimeEvent.Rejected(41, 7))
        controller.localEnd()

        assertEquals(1, releases)
        assertEquals(41L, releasedId)
        assertEquals(CallPhase.Ended, controller.state.value.phase)
    }

    @Test
    fun resetRequiresTerminalCleanup() {
        val controller = CallSessionController()
        controller.incoming(route)
        assertFalse(controller.reset())
        controller.localEnd()
        assertTrue(controller.reset())
        assertEquals(CallPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun signalsAreDeduplicatedAndQuarantinedWithoutMedia() {
        val controller = CallSessionController()
        controller.incoming(route)
        val signal = CallRealtimeEvent.Signal(
            conversationId = 7,
            fromUserId = 9,
            signalId = "signal-1",
            signal = buildJsonObject { put("type", "offer") },
        )

        assertTrue(controller.handle(signal))
        assertFalse(controller.handle(signal))
        assertEquals(1, controller.state.value.quarantinedSignalCount)
        assertEquals(CallPhase.Ringing, controller.state.value.phase)
    }

    @Test
    fun outgoingBusyWithoutCallIdTerminatesOnlyMatchingConversation() {
        val controller = CallSessionController()
        assertTrue(controller.outgoing(7))
        assertFalse(controller.handle(CallRealtimeEvent.Busy(8, "busy")))
        assertTrue(controller.handle(CallRealtimeEvent.Busy(7, "busy")))
        assertEquals(CallPhase.Busy, controller.state.value.phase)
    }

    @Test
    fun callStartedBindsServerIdToPendingOutgoingSession() {
        val controller = CallSessionController()
        controller.outgoing(7)
        assertTrue(controller.handle(CallRealtimeEvent.Started(41, 7)))
        assertEquals(41L, controller.state.value.route?.callId)
        assertFalse(controller.handle(CallRealtimeEvent.Started(42, 8)))
    }

    @Test
    fun terminalTombstoneRejectsDuplicateButAdmitsDifferentFutureCall() {
        val controller = CallSessionController()
        controller.incoming(route)
        controller.localEnd()

        assertFalse(controller.incoming(route))
        assertTrue(controller.incoming(route.copy(callId = 42)))
        assertEquals(42L, controller.state.value.route?.callId)
        assertEquals(CallPhase.Ringing, controller.state.value.phase)
    }
}