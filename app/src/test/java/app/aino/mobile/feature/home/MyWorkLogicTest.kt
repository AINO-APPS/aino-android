package app.aino.mobile.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

private const val ME = 7L

private fun task(
    id: Long,
    assignedTo: Long? = ME,
    plannedDate: String? = null,
    completedAt: String? = null,
    workflowStateKey: String? = null,
    priority: String? = null,
) = DashboardTask(
    id = id,
    title = "T$id",
    priority = priority,
    assignedTo = assignedTo,
    plannedDate = plannedDate,
    completedAt = completedAt,
    workflowStateKey = workflowStateKey,
)

/** The dashboard card's week window and its grouping (spec D2, D4, D10). */
class MyWorkLogicTest {
    @Test
    fun theWeekRunsMondayToSunday() {
        val wednesday = LocalDate.of(2026, 10, 28)
        val week = weekRange(wednesday)

        assertEquals(LocalDate.of(2026, 10, 26), week.start)
        assertEquals(LocalDate.of(2026, 11, 1), week.end)
    }

    @Test
    fun aSundayIsTheLastDayOfItsOwnWeek() {
        val sunday = LocalDate.of(2026, 11, 1)
        val week = weekRange(sunday)

        assertEquals(LocalDate.of(2026, 10, 26), week.start)
        assertEquals(sunday, week.end)
    }

    @Test
    fun theWeekStartsOnItsMonday() {
        val monday = LocalDate.of(2026, 10, 26)
        assertEquals(monday, weekRange(monday).start)
    }

    @Test
    fun theOverdueRangeEndsOnTheSundayBefore() {
        val monday = LocalDate.of(2026, 10, 26)
        assertEquals(LocalDate.of(2026, 10, 25), previousWeekEnd(monday))
    }

    @Test
    fun theWeekCrossesAMonthAndYearBoundary() {
        val week = weekRange(LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 12, 28), week.start)
        assertEquals(LocalDate.of(2027, 1, 3), week.end)
    }

    @Test
    fun onlyMyOpenUncancelledTicketsCountAsMyWork() {
        val tasks = listOf(
            task(1, assignedTo = ME),
            task(2, assignedTo = 999),
            // The server's `assignee=me` also returns the viewer's own unassigned
            // creations; the card must not inherit that.
            task(3, assignedTo = null),
            task(4, assignedTo = ME, completedAt = "2026-10-27T10:00:00Z"),
            task(5, assignedTo = ME, workflowStateKey = "cancelled"),
        )

        assertEquals(listOf(1L), myOpenTasks(tasks, ME).map { it.id })
        assertEquals(1, myDoneCount(tasks, ME))
        assertEquals(2, myWorkCount(tasks, ME))
    }

    @Test
    fun theWeekGroupsOverdueFirstThenEachDayThatHasWork() {
        val monday = LocalDate.of(2026, 10, 26)
        val overdue = listOf(task(1, assignedTo = ME, plannedDate = "2026-10-21"))
        val week = listOf(
            task(2, assignedTo = ME, plannedDate = "2026-10-28"),
            task(3, assignedTo = ME, plannedDate = "2026-10-26"),
            task(4, assignedTo = ME, plannedDate = "2026-10-28"),
        )

        val groups = weekGroups(overdue, week, monday)

        assertEquals(listOf("overdue", "2026-10-26", "2026-10-28"), groups.map { it.key })
        assertEquals(listOf("Overdue", "Monday", "Wednesday"), groups.map { it.label })
        // Nothing is invented for the empty days.
        assertEquals(listOf(2L, 4L), groups[2].tasks.map { it.id })
    }

    @Test
    fun sprintRowsSortByPriorityBecauseTheEndpointOrdersByCreation() {
        val groups = sprintGroups(
            listOf(
                task(1, assignedTo = ME, priority = "low"),
                task(2, assignedTo = ME, priority = "high"),
                task(3, assignedTo = ME, priority = "medium"),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), groups.single().tasks.map { it.id })
    }

    @Test
    fun onlyOverdueRowsCarryADay() {
        val slipped = task(1, assignedTo = ME, plannedDate = "2026-10-21")
        val planned = task(2, assignedTo = ME, plannedDate = "2026-10-26")

        assertEquals("Oct 21", rowDayLabel(slipped, "overdue"))
        // The group header already says "Monday"; the row must not repeat it.
        assertEquals(null, rowDayLabel(planned, "2026-10-26"))
    }

    @Test
    fun theCardStopsAtSixRowsAndCountsTheRest() {
        val rows = (1L..8L).map { task(it, assignedTo = ME, plannedDate = "2026-10-26") }
        val groups = listOf(MyWorkGroup("2026-10-26", "Monday", rows))

        val (kept, hidden) = capGroups(groups)

        assertEquals(MY_WORK_ROW_LIMIT, kept.single().tasks.size)
        assertEquals(2, hidden)
    }

    @Test
    fun aGroupTrimmedAwayEntirelyIsNotRendered() {
        val monday = List(6) { index -> task(index + 1L, assignedTo = ME, plannedDate = "2026-10-26") }
        val tuesday = listOf(task(99, assignedTo = ME, plannedDate = "2026-10-27"))

        val (kept, hidden) = capGroups(
            listOf(MyWorkGroup("mon", "Monday", monday), MyWorkGroup("tue", "Tuesday", tuesday)),
        )

        assertEquals(listOf("mon"), kept.map { it.key })
        assertEquals(1, hidden)
    }
}
