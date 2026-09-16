package app.aino.mobile.core.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.JsonElement

/**
 * Routes raw realtime envelopes onto typed domain buses (A-100).
 *
 * Every wire type in [RealtimeEvent] is dispatched. Events whose consuming
 * feature is not built yet still flow onto their bus, so adding that feature is
 * a matter of subscribing rather than re-plumbing transport — and
 * `check-realtime-parity.mjs` can prove nothing is dropped.
 *
 * Unknown types are counted and exposed rather than discarded silently: if the
 * platform ships an event before Android knows about it, that must be
 * observable at runtime and not merely at build time.
 */
class RealtimeDispatcher {
    private val _events = MutableSharedFlow<RoutedRealtimeEvent>(extraBufferCapacity = 128)
    private val _unknown = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** All recognised events, already typed. */
    val events: SharedFlow<RoutedRealtimeEvent> = _events.asSharedFlow()

    /** Wire types the server sent that this build does not know about. */
    val unknownTypes: SharedFlow<String> = _unknown.asSharedFlow()

    /** Subscribe to a single domain — the normal path for a feature ViewModel. */
    fun domain(domain: RealtimeDomain): Flow<RoutedRealtimeEvent> =
        events.filter { it.event.domain == domain }

    /** Subscribe to several domains at once. */
    fun domains(vararg domains: RealtimeDomain): Flow<RoutedRealtimeEvent> {
        val wanted = domains.toSet()
        return events.filter { it.event.domain in wanted }
    }

    /**
     * Dispatch one envelope. Returns the routed event, or null when the type is
     * unrecognised.
     */
    fun dispatch(envelope: RealtimeEnvelope): RoutedRealtimeEvent? {
        val event = RealtimeEvent.from(envelope.type)
        if (event == null) {
            // `pong` is handled by the transport's liveness tracking and is
            // deliberately not a domain event.
            if (envelope.type != "pong") _unknown.tryEmit(envelope.type)
            return null
        }
        val routed = RoutedRealtimeEvent(event, envelope.data)
        _events.tryEmit(routed)
        return routed
    }
}

/** A realtime envelope resolved against the registry. */
data class RoutedRealtimeEvent(
    val event: RealtimeEvent,
    val data: JsonElement?,
) {
    val type: String get() = event.type
    val domain: RealtimeDomain get() = event.domain
    val reaction: RealtimeReaction get() = event.reaction

    /**
     * True when this event should trigger a network reload. Ephemeral events
     * must never do so — a typing indicator that refetches the conversation
     * list is the defect this distinction exists to prevent.
     */
    val causesRefetch: Boolean get() = reaction == RealtimeReaction.Refetch
}
