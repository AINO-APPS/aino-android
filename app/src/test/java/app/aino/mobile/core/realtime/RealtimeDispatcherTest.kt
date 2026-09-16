package app.aino.mobile.core.realtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-100 dispatcher tests: routing, domain isolation and unknown-type visibility.
 *
 * Collectors run on `UnconfinedTestDispatcher` so they are subscribed before
 * any dispatch happens. `MutableSharedFlow.tryEmit` drops when there is no
 * active subscriber, so a lazily-started collector would miss events and the
 * test would hang instead of failing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeDispatcherTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun envelope(type: String, data: String = "{}"): RealtimeEnvelope =
        json.decodeFromString("""{"type":"$type","data":$data}""")

    @Test
    fun routesEveryRegisteredEventToItsDomain() {
        // dispatch() resolves synchronously, so exhaustive routing needs no flow.
        val dispatcher = RealtimeDispatcher()
        for (event in RealtimeEvent.entries) {
            val routed = dispatcher.dispatch(envelope(event.type))
            assertNotNull("${event.type} must route", routed)
            assertEquals(event, routed!!.event)
            assertEquals(event.domain, routed.domain)
            assertEquals(event.reaction, routed.reaction)
        }
    }

    @Test
    fun domainSubscriptionFiltersOtherDomains() = runTest {
        val dispatcher = RealtimeDispatcher()
        val received = mutableListOf<RealtimeEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            dispatcher.domain(RealtimeDomain.Leaves).collect { received += it.event }
        }

        dispatcher.dispatch(envelope("chat_message"))
        dispatcher.dispatch(envelope("meeting_started"))
        dispatcher.dispatch(envelope("leave_update"))

        assertEquals(listOf(RealtimeEvent.LeaveUpdate), received)
        job.cancel()
    }

    @Test
    fun multiDomainSubscriptionReceivesEachRequestedDomain() = runTest {
        val dispatcher = RealtimeDispatcher()
        val received = mutableListOf<RealtimeEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            dispatcher.domains(RealtimeDomain.Tasks, RealtimeDomain.Approvals)
                .collect { received += it.event }
        }

        dispatcher.dispatch(envelope("chat_message"))
        dispatcher.dispatch(envelope("task_assigned"))
        dispatcher.dispatch(envelope("approval_update"))

        assertEquals(listOf(RealtimeEvent.TaskAssigned, RealtimeEvent.ApprovalUpdate), received)
        job.cancel()
    }

    @Test
    fun reportsUnknownTypesInsteadOfDroppingThemSilently() = runTest {
        val dispatcher = RealtimeDispatcher()
        val unknown = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            dispatcher.unknownTypes.collect { unknown += it }
        }

        assertNull(dispatcher.dispatch(envelope("some_future_event")))
        // `pong` is transport liveness, not a domain event, and is not "unknown".
        assertNull(dispatcher.dispatch(envelope("pong")))

        assertEquals(listOf("some_future_event"), unknown)
        job.cancel()
    }

    @Test
    fun preservesEventPayload() {
        val dispatcher = RealtimeDispatcher()
        val routed = dispatcher.dispatch(envelope("chat_message", """{"conversationId":42}"""))
        assertTrue(routed!!.data.toString().contains("42"))
    }

    @Test
    fun ephemeralEventsDoNotRequestRefetch() {
        val dispatcher = RealtimeDispatcher()
        assertFalse(dispatcher.dispatch(envelope("chat_typing"))!!.causesRefetch)
        assertFalse(dispatcher.dispatch(envelope("meeting_audio_level"))!!.causesRefetch)
        assertTrue(dispatcher.dispatch(envelope("chat_pin"))!!.causesRefetch)
    }

    @Test
    fun deadListenersFromTheOldClientNoLongerResolve() {
        val dispatcher = RealtimeDispatcher()
        assertNull(dispatcher.dispatch(envelope("chat_group_updated")))
        assertNull(dispatcher.dispatch(envelope("task_updated")))
    }
}
