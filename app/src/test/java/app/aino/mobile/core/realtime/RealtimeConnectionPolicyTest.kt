package app.aino.mobile.core.realtime

import app.aino.mobile.core.network.NetworkStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtimeConnectionPolicyTest {
    private val signedIn = RealtimeConditions(authenticated = true, pushAvailable = true, network = NetworkStatus.Available(true))

    @Test fun `signed out always closes`() {
        assertEquals(RealtimeLinkDecision.Close, realtimeLinkDecision(signedIn.copy(authenticated = false), nowMs = 0))
    }

    @Test fun `no usable network pauses instead of retrying`() {
        assertEquals(RealtimeLinkDecision.Pause, realtimeLinkDecision(signedIn.copy(network = NetworkStatus.Unavailable), nowMs = 0))
    }

    @Test fun `an unknown or unvalidated network is assumed online`() {
        assertEquals(RealtimeLinkDecision.Open, realtimeLinkDecision(signedIn.copy(network = NetworkStatus.Unknown), nowMs = 0))
        assertEquals(RealtimeLinkDecision.Open, realtimeLinkDecision(signedIn.copy(network = NetworkStatus.Available(false)), nowMs = 0))
    }

    @Test fun `foreground stays open`() {
        assertEquals(RealtimeLinkDecision.Open, realtimeLinkDecision(signedIn, nowMs = 0))
    }

    @Test fun `backgrounded with push keeps a grace then pauses`() {
        val background = signedIn.copy(foreground = false, backgroundedAtMs = 1_000)
        assertEquals(RealtimeLinkDecision.OpenUntil(11_000), realtimeLinkDecision(background, nowMs = 5_000, graceMs = 10_000))
        assertEquals(RealtimeLinkDecision.Pause, realtimeLinkDecision(background, nowMs = 11_000, graceMs = 10_000))
    }

    @Test fun `without push or during a call the socket stays open in the background`() {
        val background = signedIn.copy(foreground = false, backgroundedAtMs = 0)
        assertEquals(RealtimeLinkDecision.Open, realtimeLinkDecision(background.copy(pushAvailable = false), nowMs = 999_999))
        assertEquals(RealtimeLinkDecision.Open, realtimeLinkDecision(background.copy(inCall = true), nowMs = 999_999))
    }

    @Test fun `indicator reflects the network first, then the socket`() {
        assertEquals(ConnectionIndicator.WaitingForNetwork, connectionIndicator(RealtimeState.Connected, NetworkStatus.Unavailable))
        assertEquals(ConnectionIndicator.Connected, connectionIndicator(RealtimeState.Connected, NetworkStatus.Unknown))
        assertEquals(ConnectionIndicator.Connecting, connectionIndicator(RealtimeState.Connecting(2), NetworkStatus.Available(true)))
        assertEquals(ConnectionIndicator.Connecting, connectionIndicator(RealtimeState.Disconnected, NetworkStatus.Available(false)))
        assertEquals(ConnectionIndicator.Connected, connectionIndicator(RealtimeState.Stopped(4001, "Unauthorized"), NetworkStatus.Available(true)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `brief reconnects never reach the indicator, lasting ones do`() = runTest {
        val source = MutableSharedFlow<ConnectionIndicator>()
        val seen = mutableListOf<ConnectionIndicator>()
        val job = launch { source.debounceProblems(delayMs = 3_000).toList(seen) }
        runCurrent()
        source.emit(ConnectionIndicator.Connected)
        source.emit(ConnectionIndicator.Connecting)
        advanceTimeBy(1_000)
        source.emit(ConnectionIndicator.Connected)
        runCurrent()
        assertEquals(listOf(ConnectionIndicator.Connected), seen)

        source.emit(ConnectionIndicator.WaitingForNetwork)
        advanceTimeBy(3_001)
        assertEquals(listOf(ConnectionIndicator.Connected, ConnectionIndicator.WaitingForNetwork), seen)
        source.emit(ConnectionIndicator.Connected)
        runCurrent()
        assertEquals(ConnectionIndicator.Connected, seen.last())
        job.cancel()
    }
}
