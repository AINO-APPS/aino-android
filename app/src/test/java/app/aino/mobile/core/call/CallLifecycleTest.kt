package app.aino.mobile.core.call

import app.aino.mobile.core.realtime.RealtimeEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLifecycleTest {
    private val route = IncomingCallRoute(41, 7, 9, "Priya", null, "video")

    @Test
    fun callingBecomesRingingWhenTheCalleeAcksBeforeTheCallId() {
        val controller = CallSessionController()
        controller.outgoing(7)
        assertFalse(controller.state.value.remoteRinging)

        // Another conversation's ack is not ours.
        assertFalse(controller.handle(CallRealtimeEvent.Ringing(41, 8, 9)))
        assertFalse(controller.state.value.remoteRinging)

        // No call id yet: matched by conversation.
        assertTrue(controller.handle(CallRealtimeEvent.Ringing(41, 7, 9)))
        assertTrue(controller.state.value.remoteRinging)
        assertEquals(CallPhase.Connecting, controller.state.value.phase)
        // Duplicate acks (several callee devices) change nothing.
        assertFalse(controller.handle(CallRealtimeEvent.Ringing(41, 7, 9)))
    }

    @Test
    fun ringingAckMatchesTheServerCallIdOnceKnown() {
        val controller = CallSessionController()
        controller.outgoing(7)
        controller.handle(CallRealtimeEvent.Started(41, 7))
        assertFalse(controller.handle(CallRealtimeEvent.Ringing(42, 7, 9)))
        assertFalse(controller.state.value.remoteRinging)
        assertTrue(controller.handle(CallRealtimeEvent.Ringing(41, 7, 9)))
        assertTrue(controller.state.value.remoteRinging)
    }

    @Test
    fun ringingAckIsIgnoredForIncomingOrAnsweredCalls() {
        val incoming = CallSessionController()
        incoming.incoming(route)
        assertFalse(incoming.handle(CallRealtimeEvent.Ringing(41, 7, 9)))

        val answered = CallSessionController()
        answered.outgoing(7)
        answered.handle(CallRealtimeEvent.Started(41, 7))
        answered.handle(CallRealtimeEvent.Accepted(41, 7, 9))
        answered.mediaConnected()
        assertFalse(answered.handle(CallRealtimeEvent.Ringing(41, 7, 9)))
        assertFalse(answered.state.value.remoteRinging)
    }

    @Test
    fun ringTimeoutExpiresReleasesAndReportsAMissedCall() {
        var releases = 0
        val missed = mutableListOf<Pair<Long, String>>()
        val controller = CallSessionController(
            CallResourceOwner { releases++ },
            onMissed = { r, reason -> missed += r.callId to reason },
        )
        controller.incoming(route)
        controller.expire()

        assertEquals(CallPhase.Expired, controller.state.value.phase)
        assertEquals(1, releases)
        assertEquals(listOf(41L to "expired"), missed)
        assertTrue(controller.reset())
        assertEquals(CallPhase.Idle, controller.state.value.phase)
    }

    @Test
    fun onlyAnUnansweredRingHereIsMissed() {
        fun missedAfter(block: CallSessionController.() -> Unit): List<String> {
            val reasons = mutableListOf<String>()
            CallSessionController(onMissed = { _, reason -> reasons += reason }).apply {
                incoming(route)
                block()
            }
            return reasons
        }
        // The caller hung up / the server's no-answer timeout while ringing here.
        assertEquals(listOf("cancelled"), missedAfter { handle(CallRealtimeEvent.Ended(41, 7, "cancelled")) })
        assertEquals(listOf("no_answer"), missedAfter { handle(CallRealtimeEvent.Ended(41, 7, "no_answer")) })
        // Taken on another of my devices.
        assertEquals(emptyList<String>(), missedAfter { handle(CallRealtimeEvent.HandledElsewhere(41, 7, "accepted")) })
        assertEquals(emptyList<String>(), missedAfter { handle(CallRealtimeEvent.HandledElsewhere(41, 7, "rejected")) })
        // Declined here.
        assertEquals(emptyList<String>(), missedAfter { localReject() })
        // Answered here, then the call ended normally.
        assertEquals(emptyList<String>(), missedAfter { accepting(); handle(CallRealtimeEvent.Ended(41, 7, "ended")) })
        // The caller's own session never produces a missed call.
        val reasons = mutableListOf<String>()
        CallSessionController(onMissed = { _, reason -> reasons += reason }).apply {
            outgoing(7)
            handle(CallRealtimeEvent.Started(41, 7))
            handle(CallRealtimeEvent.Ended(41, 7, "no_answer"))
        }
        assertEquals(emptyList<String>(), reasons)
    }

    @Test
    fun missedCallDecision() {
        assertTrue(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = "cancelled"))
        assertTrue(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = "ended"))
        assertTrue(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = "expired"))
        assertTrue(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = null))
        assertFalse(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = "accepted"))
        assertFalse(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = "rejected"))
        assertFalse(shouldNotifyMissedCall(ringingHere = true, handledHere = false, reason = "handled_elsewhere"))
        assertFalse(shouldNotifyMissedCall(ringingHere = true, handledHere = true, reason = "cancelled"))
        assertFalse(shouldNotifyMissedCall(ringingHere = false, handledHere = false, reason = "cancelled"))
    }

    @Test
    fun serverStatusMapsToLocalTerminalEvents() {
        assertEquals(CallReconcileAction(CallEvent.RemoteRejected, "declined"), reconcileServerCallStatus("declined", CallPhase.Connecting, incoming = false))
        assertEquals(CallReconcileAction(CallEvent.RemoteEnded, "missed"), reconcileServerCallStatus("missed", CallPhase.Ringing, incoming = true))
        assertEquals(CallReconcileAction(CallEvent.RemoteEnded, "ended"), reconcileServerCallStatus("ended", CallPhase.Connected, incoming = false))
        // Answered (and maybe ended) while this device still rang: picked up elsewhere, not missed.
        assertEquals(CallReconcileAction(CallEvent.RemoteEnded, "answered_elsewhere"), reconcileServerCallStatus("ended", CallPhase.Ringing, incoming = true))
        assertEquals(CallReconcileAction(CallEvent.RemoteEnded, "answered_elsewhere"), reconcileServerCallStatus("answered", CallPhase.Ringing, incoming = true))
        // Live on the server: keep the call.
        assertNull(reconcileServerCallStatus("answered", CallPhase.Connected, incoming = true))
        assertNull(reconcileServerCallStatus("ringing", CallPhase.Ringing, incoming = true))
        assertNull(reconcileServerCallStatus("ringing", CallPhase.Connecting, incoming = false))
        // Nothing to reconcile locally.
        assertNull(reconcileServerCallStatus("ended", CallPhase.Idle, incoming = false))
        assertNull(reconcileServerCallStatus("ended", CallPhase.Ended, incoming = false))
    }

    @Test
    fun reconcileEndsOnlyTheMatchingCall() {
        var releases = 0
        val missed = mutableListOf<String>()
        val controller = CallSessionController(CallResourceOwner { releases++ }, onMissed = { _, reason -> missed += reason })
        controller.incoming(route)
        assertFalse(controller.reconcile(99, "ended"))
        assertFalse(controller.reconcile(41, "ringing"))
        assertEquals(CallPhase.Ringing, controller.state.value.phase)
        assertTrue(controller.reconcile(41, "missed"))
        assertEquals(CallPhase.Ended, controller.state.value.phase)
        assertEquals("missed", controller.state.value.terminalReason)
        assertEquals(1, releases)
        assertEquals(listOf("missed"), missed)

        val declined = CallSessionController()
        declined.outgoing(7)
        declined.handle(CallRealtimeEvent.Started(41, 7))
        assertTrue(declined.reconcile(41, "declined"))
        assertEquals(CallPhase.Rejected, declined.state.value.phase)
    }

    @Test
    fun unroutedOutgoingIsAbandonedAfterTheRingTimeout() {
        var now = 1_000L
        val controller = CallSessionController(clock = { now })
        controller.outgoing(7)
        assertFalse(isAbandonedOutgoing(controller.state.value, now + OUTGOING_NO_ANSWER_MS))
        assertTrue(isAbandonedOutgoing(controller.state.value, now + OUTGOING_NO_ANSWER_MS + 1))
        // Once the server assigned an id, the status endpoint decides instead.
        controller.handle(CallRealtimeEvent.Started(41, 7))
        assertFalse(isAbandonedOutgoing(controller.state.value, now + 10 * OUTGOING_NO_ANSWER_MS))
        now = 5_000L
        val incoming = CallSessionController(clock = { now })
        incoming.incoming(route)
        assertEquals(5_000L, incoming.state.value.startedAtMs)
        assertFalse(isAbandonedOutgoing(incoming.state.value, now + 10 * OUTGOING_NO_ANSWER_MS))
    }

    @Test
    fun ringingAckIsSentOncePerCallPreferringTheSocket() {
        val frames = mutableListOf<RealtimeEnvelope>()
        val httpAcks = mutableListOf<Pair<Long, Long>>()
        var socketOpen = false
        val ack = CallRingingAcknowledger(
            socket = { frames += it; socketOpen },
            http = { callId, conversationId -> httpAcks += callId to conversationId },
            background = { it() },
        )
        // Push-woken device without a socket: HTTP.
        assertTrue(ack.acknowledge(41, 7))
        assertEquals(listOf(41L to 7L), httpAcks)
        // The same call ringing again over the socket is not re-acked.
        socketOpen = true
        assertFalse(ack.acknowledge(41, 7))
        assertEquals(1, httpAcks.size)
        // A new call over an open socket: WS only.
        assertTrue(ack.acknowledge(42, 7))
        assertEquals("call_ringing", frames.last().type)
        assertEquals(1, httpAcks.size)
        assertFalse(ack.acknowledge(0, 7))
    }

    @Test
    fun failedHttpAckCanBeRetried() {
        var attempts = 0
        val ack = CallRingingAcknowledger(
            socket = { false },
            http = { _, _ -> attempts++; if (attempts == 1) error("offline") },
            background = { it() },
        )
        assertTrue(ack.acknowledge(41, 7))
        assertTrue(ack.acknowledge(41, 7))
        assertFalse(ack.acknowledge(41, 7))
        assertEquals(2, attempts)
    }

    @Test
    fun recentCallIdsEvictTheOldest() {
        val ids = RecentCallIds(capacity = 2)
        assertTrue(ids.add(1))
        assertTrue(ids.add(2))
        assertFalse(ids.add(2))
        assertTrue(ids.add(3))
        assertFalse(ids.contains(1))
        assertTrue(ids.contains(3))
    }

    @Test
    fun callBackIsConsumedOnceByItsOwnThread() {
        PendingCallBack.set(7, "video", nowMs = 1_000)
        assertNull(PendingCallBack.consume(8, nowMs = 1_000))
        assertEquals("video", PendingCallBack.consume(7, nowMs = 2_000)?.callType)
        assertNull(PendingCallBack.consume(7, nowMs = 2_000))
        PendingCallBack.set(7, "voice", nowMs = 1_000)
        assertNull(PendingCallBack.consume(7, nowMs = 1_000 + 120_000))
    }

    @Test
    fun missedCallInfoDropsTheGenericTitleOfAPrivatePush() {
        val private = IncomingCallSpec("Incoming Voice Call", "Tap to answer", "41", "7", "9", "Incoming Voice Call", "", "voice")
        assertEquals("", MissedCallInfo.of(private)?.callerName)
        val named = private.copy(callerName = "Priya", callType = "video")
        assertEquals(MissedCallInfo(41, 7, 9, "Priya", "video"), MissedCallInfo.of(named))
        assertNull(MissedCallInfo.of(private.copy(callId = "")))
        assertEquals("Missed video call", missedCallText("video"))
        assertEquals("Missed voice call", missedCallText("voice"))
    }

    @Test
    fun callAlertIssuesInPriorityOrder() {
        assertEquals(CallAlertIssue.NotificationsOff, callAlertIssue(false, true, 34, false))
        assertNull(callAlertIssue(false, false, 34, true, includeNotificationsOff = false))
        assertEquals(CallAlertIssue.CallChannelBlocked, callAlertIssue(true, true, 34, false))
        assertEquals(CallAlertIssue.FullScreenIntentDenied, callAlertIssue(true, false, 34, false))
        assertNull(callAlertIssue(true, false, 33, false))
        assertNull(callAlertIssue(true, false, 35, true))
    }
}
