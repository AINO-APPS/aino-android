package app.aino.mobile.feature.tasks

import java.time.LocalDate

data class ScheduledUiState(
    val planScope: PlanScope = PlanScope(),
    val tickets: Map<Long, Task> = emptyMap(),
    val scheduledTicketIds: List<Long> = emptyList(),
    val scheduledLoading: Boolean = false,
    val scheduledLoaded: Boolean = false,
    val scheduledFocus: String? = null,
    val window: Pair<LocalDate, LocalDate> = scheduledWindow(),
    val recentlyUpdated: Set<Long> = emptySet(),
    val error: String? = null,
    val confirm: ConfirmRequest? = null,
    val overlays: ScheduleOverlays = ScheduleOverlays(),
    val overlayTypes: Set<String> = setOf("leave", "holiday", "meeting"),
) {
    val userId get() = planScope.userId
    val teamScope get() = planScope.teamScope
    val filters get() = planScope.filters

    internal fun containsScheduledTicket(ticket: Task): Boolean {
        val day = localDateOf(ticket.plannedDate) ?: return false
        return planScope.contains(ticket) && ticket.container == "scheduled" && ticket.workflowStateKey != "cancelled" &&
            !day.isBefore(window.first) && !day.isAfter(window.second) &&
            matchesFilters(ticket, filters, userId, includeStatus = true)
    }

    val scheduled get() = scheduledTicketIds.mapNotNull(tickets::get).filter(::containsScheduledTicket)
}
