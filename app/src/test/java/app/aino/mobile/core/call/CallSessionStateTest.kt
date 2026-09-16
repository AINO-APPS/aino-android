package app.aino.mobile.core.call

import org.junit.Assert.assertEquals
import org.junit.Test

class CallSessionStateTest {
    @Test
    fun incomingCallConnectsThroughExplicitPhases() {
        var phase = reduceCallPhase(CallPhase.Idle, CallEvent.Incoming)
        assertEquals(CallPhase.Ringing, phase)
        phase = reduceCallPhase(phase, CallEvent.Accept)
        assertEquals(CallPhase.Accepting, phase)
        phase = reduceCallPhase(phase, CallEvent.MediaConnected)
        assertEquals(CallPhase.Connected, phase)
    }

    @Test
    fun connectionLossAndRecoveryAreExplicit() {
        val reconnecting = reduceCallPhase(CallPhase.Connected, CallEvent.MediaDisconnected)
        assertEquals(CallPhase.Reconnecting, reconnecting)
        assertEquals(CallPhase.Connected, reduceCallPhase(reconnecting, CallEvent.MediaConnected))
    }

    @Test
    fun everyTerminalPhaseAbsorbsLateEvents() {
        val lateEvents = CallEvent.entries.filterNot { it == CallEvent.Reset }
        listOf(CallPhase.Ended, CallPhase.Rejected, CallPhase.Busy, CallPhase.Expired, CallPhase.Failed).forEach { terminal ->
            lateEvents.forEach { event -> assertEquals("$terminal must absorb $event", terminal, reduceCallPhase(terminal, event)) }
        }
    }

    @Test
    fun resetIsTheOnlyWayOutOfTerminalState() {
        assertEquals(CallPhase.Idle, reduceCallPhase(CallPhase.Ended, CallEvent.Reset))
    }
}