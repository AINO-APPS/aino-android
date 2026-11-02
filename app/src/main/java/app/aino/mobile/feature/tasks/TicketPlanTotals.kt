package app.aino.mobile.feature.tasks

/** Server aggregates and the loaded Tickets' contributions when that page arrived. */
data class TicketPlanBaseline(
    val total: Int?,
    val summary: BacklogSummary?,
    val completedCount: Int?,
    val contributions: Map<Long, TicketPlanContribution>,
)

data class TicketPlanContribution(
    val completed: Boolean,
    val workflowState: String,
    val priority: String,
)

internal fun TaskUiState.planContribution(ticket: Task): TicketPlanContribution? =
    ticket.takeIf { inScope(it) && containsPlanTicket(it) }?.let {
        TicketPlanContribution(it.isCompleted, it.workflowStateKey.orEmpty(), it.priority)
    }

internal fun TaskUiState.pageBaseline(page: BacklogResponse): TicketPlanBaseline =
    TicketPlanBaseline(
        total = page.incompleteTotal ?: page.summary?.total ?: page.pagination?.let {
            (it.total - (page.completedCount ?: completed.size)).coerceAtLeast(0)
        },
        summary = page.summary,
        completedCount = page.completedCount,
        contributions = tickets.mapNotNull { (id, ticket) -> planContribution(ticket)?.let { id to it } }.toMap(),
    )

internal data class TicketPlanTotals(val total: Int, val summary: BacklogSummary, val completed: Int)

internal fun TaskUiState.readPlanTotals(): TicketPlanTotals {
    val open = backlog
    val localSummary = BacklogSummary(
        open.size,
        open.groupingBy { it.workflowStateKey.orEmpty() }.eachCount(),
        open.groupingBy { it.priority }.eachCount(),
    )
    val baseline = planBaseline
    // The server baseline is unfiltered. Keep the existing local Plan filtering.
    if (baseline == null || planFiltersActive) return TicketPlanTotals(open.size, localSummary, completed.size)
    var openDelta = 0
    var completedDelta = 0
    val statuses = baseline.summary?.byStatus.orEmpty().toMutableMap()
    val priorities = baseline.summary?.byPriority.orEmpty().toMutableMap()
    fun bump(contribution: TicketPlanContribution?, delta: Int) {
        if (contribution == null) return
        if (contribution.completed) {
            completedDelta += delta
        } else {
            openDelta += delta
            statuses[contribution.workflowState] = (statuses[contribution.workflowState] ?: 0) + delta
            priorities[contribution.priority] = (priorities[contribution.priority] ?: 0) + delta
        }
    }
    (baseline.contributions.keys + tickets.keys).forEach { id ->
        val before = baseline.contributions[id]
        val after = tickets[id]?.let(::planContribution)
        if (before != after) {
            bump(before, -1)
            bump(after, 1)
        }
    }
    val total = baseline.total?.let { (it + openDelta).coerceAtLeast(0) } ?: open.size
    val summary = baseline.summary?.let {
        BacklogSummary(
            (it.total + openDelta).coerceAtLeast(0),
            statuses.mapValues { (_, count) -> count.coerceAtLeast(0) },
            priorities.mapValues { (_, count) -> count.coerceAtLeast(0) },
        )
    } ?: localSummary
    return TicketPlanTotals(total, summary, baseline.completedCount?.let { (it + completedDelta).coerceAtLeast(0) } ?: completed.size)
}
