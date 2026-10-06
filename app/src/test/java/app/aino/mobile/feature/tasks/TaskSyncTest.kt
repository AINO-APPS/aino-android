package app.aino.mobile.feature.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSyncTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun parsesTheContractPayloads() {
        assertEquals(TaskEvent(42, TaskAction.Assigned), parseTaskEvent("task_assigned", json("""{"taskId":42,"title":"Ship"}""")))
        assertEquals(TaskEvent(42, TaskAction.Status), parseTaskEvent("task_updated", json("""{"taskId":42,"action":"status"}""")))
        assertEquals(TaskEvent(7, TaskAction.Deleted), parseTaskEvent("task_updated", json("""{"taskId":"7","action":"deleted"}""")))
        assertEquals(TaskEvent(7, TaskAction.Comment), parseTaskEvent("task_updated", json("""{"taskId":7,"action":"comment"}""")))
        assertEquals(TaskEvent(7, TaskAction.Updated), parseTaskEvent("task_updated", json("""{"taskId":7,"action":"updated"}""")))
        assertEquals(TaskEvent(7, TaskAction.Updated), parseTaskEvent("task_updated", json("""{"taskId":7}""")))
    }

    @Test
    fun rejectsUnusablePayloads() {
        assertNull(parseTaskEvent("task_updated", null))
        assertNull(parseTaskEvent("task_updated", JsonPrimitive(3)))
        assertNull(parseTaskEvent("task_updated", json("""{"action":"status"}""")))
        assertNull(parseTaskEvent("task_updated", json("""{"taskId":0}""")))
        assertNull(parseTaskEvent("notification", json("""{"taskId":5}""")))
    }

    @Test
    fun coalescesBurstsPerTaskAndDeleteWins() {
        val out = coalesceTaskEvents(
            listOf(
                TaskEvent(1, TaskAction.Status), TaskEvent(2, TaskAction.Comment), TaskEvent(1, TaskAction.Updated),
                TaskEvent(3, TaskAction.Updated), TaskEvent(3, TaskAction.Deleted),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), out.keys.toList())
        assertEquals(setOf(TaskAction.Status, TaskAction.Updated), out[1])
        assertEquals(setOf(TaskAction.Comment), out[2])
        assertEquals(setOf(TaskAction.Deleted), out[3])
    }

    private fun task(
        id: Long = 1,
        sprintId: Long? = null,
        date: String? = null,
        assignedTo: Long? = null,
        userId: Long? = 9,
        priority: String = "medium",
        status: String = "pending",
        title: String = "Fix login",
        labels: List<TaskLabel> = emptyList(),
    ) = Task(id = id, title = title, sprintId = sprintId, date = date, assignedTo = assignedTo, userId = userId, priority = priority, status = status, labels = labels)

    @Test
    fun filtersMirrorTheServerRules() {
        val me = 5L
        // "me" = assigned to me, or created by me and unassigned.
        assertTrue(matchesFilters(task(assignedTo = me), TaskFilters(assignee = "me"), me, true))
        assertTrue(matchesFilters(task(userId = me), TaskFilters(assignee = "me"), me, true))
        assertFalse(matchesFilters(task(userId = me, assignedTo = 8), TaskFilters(assignee = "me"), me, true))
        assertTrue(matchesFilters(task(assignedTo = 8), TaskFilters(assignee = "8"), me, true))
        assertFalse(matchesFilters(task(priority = "low"), TaskFilters(priority = "high"), me, true))
        assertTrue(matchesFilters(task(priority = "low"), TaskFilters(priority = "urgent"), me, true))
        assertFalse(matchesFilters(task(status = "done"), TaskFilters(status = "pending"), me, includeStatus = true))
        assertTrue(matchesFilters(task(status = "done"), TaskFilters(status = "pending"), me, includeStatus = false))
        assertTrue(matchesFilters(task(labels = listOf(TaskLabel(3, "ui"))), TaskFilters(label = "3"), me, true))
        assertFalse(matchesFilters(task(), TaskFilters(label = "3"), me, true))
        assertTrue(matchesFilters(task(title = "Fix LOGIN flow"), TaskFilters(search = "login"), me, true))
        assertFalse(matchesFilters(task(), TaskFilters(search = "payroll"), me, true))
    }

    @Test
    fun membershipFollowsSprintAndBacklogPredicates() {
        assertTrue(belongsToSprint(task(sprintId = 4), 4, TaskFilters(), null))
        assertFalse(belongsToSprint(task(sprintId = 5), 4, TaskFilters(), null))
        assertFalse(belongsToSprint(task(sprintId = 4), null, TaskFilters(), null))
        assertTrue(belongsToBacklog(task(), TaskFilters(), null))
        assertFalse(belongsToBacklog(task(sprintId = 4), TaskFilters(), null))
        assertFalse(belongsToBacklog(task(date = "2026-10-06"), TaskFilters(), null))
    }

    @Test
    fun upsertReplacesAppendsOrDrops() {
        val a = task(1)
        val b = task(2)
        val list = listOf(a, b)
        val renamed = b.copy(title = "Renamed")
        assertEquals(listOf(a, renamed), upsertTask(list, renamed, belongs = true))
        assertEquals(listOf(a, b, task(3)), upsertTask(list, task(3), belongs = true))
        assertEquals(listOf(a), upsertTask(list, b, belongs = false))
        assertSame(list, upsertTask(list, task(3), belongs = false))
    }

    @Test
    fun summaryTracksMembershipChanges() {
        val summary = BacklogSummary(total = 3, byStatus = mapOf("pending" to 3), byPriority = mapOf("high" to 1, "medium" to 2, "low" to 0))
        val added = adjustSummary(summary, null, task(priority = "low"))
        assertEquals(4, added.total)
        assertEquals(1, added.byPriority["low"])
        val moved = adjustSummary(summary, task(priority = "medium"), task(priority = "high"))
        assertEquals(3, moved.total)
        assertEquals(2, moved.byPriority["high"])
        assertEquals(1, moved.byPriority["medium"])
        val removed = adjustSummary(summary, task(priority = "high"), null)
        assertEquals(2, removed.total)
        assertEquals(0, removed.byPriority["high"])
        assertSame(summary, adjustSummary(summary, null, null))
    }

    @Test
    fun scheduledViewQueryGroupsAndMembership() {
        val today = java.time.LocalDate.of(2026, 10, 6)
        assertEquals(
            "tasks?start_date=2026-09-06&end_date=2026-11-05&priority=high",
            scheduledQuery(today.minusDays(30), today.plusDays(30), TaskFilters(priority = "high")),
        )
        val groups = groupScheduled(
            listOf(
                task(1, date = "2026-10-04"),                       // overdue
                task(2, date = "2026-10-03", status = "done"),      // past + done → hidden
                task(3, date = "2026-10-06", priority = "low"),
                task(4, date = "2026-10-06", priority = "high"),
                task(5, date = "2026-10-07"),
                task(6, date = "2026-10-12"),
                task(7),                                            // undated → ignored
            ),
            today,
        )
        assertEquals(listOf("Overdue", "Today", "Tomorrow", "Mon, Oct 12"), groups.map { it.label })
        assertEquals(listOf(1L), groups[0].tasks.map { it.id })
        assertEquals(listOf(4L, 3L), groups[1].tasks.map { it.id })
        assertTrue(belongsToScheduled(task(date = "2026-10-07"), today.minusDays(30), today.plusDays(30), TaskFilters(), null))
        assertFalse(belongsToScheduled(task(date = "2026-12-25"), today.minusDays(30), today.plusDays(30), TaskFilters(), null))
        assertFalse(belongsToScheduled(task(), today.minusDays(30), today.plusDays(30), TaskFilters(), null))
        assertEquals(1, filterCount(TaskFilters(status = "done"), TaskTab.Scheduled))
        assertEquals(TaskTab.Scheduled, TaskTab.fromKey("scheduled"))
    }

    @Test
    fun backlogWindowKeepsLoadedRows() {
        assertEquals(25, backlogWindow(0, 25))
        assertEquals(75, backlogWindow(75, 25))
        assertEquals(500, backlogWindow(900, 25))
    }
}
