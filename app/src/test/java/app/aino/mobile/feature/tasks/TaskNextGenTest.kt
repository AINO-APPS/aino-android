package app.aino.mobile.feature.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android side of the next-generation Tasks surfaces: field deltas, the
 * shared preferences, completed visibility, density and time tracking.
 */
class TaskNextGenTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    private fun task(
        id: Long = 1,
        title: String = "Refund webhook",
        workflowStateKey: String = "pending",
        plannedDate: String? = null,
        sprintId: Long? = null,
        completedAt: String? = null,
        loggedMinutes: Int = 0,
        estimatedMinutes: Int? = null,
    ) = Task(
        id = id,
        title = title,
        workflowStateKey = workflowStateKey,
        plannedDate = plannedDate,
        sprintId = sprintId,
        completedAt = completedAt,
        loggedMinutes = loggedMinutes,
        estimatedMinutes = estimatedMinutes,
    )

    // ── Field deltas ─────────────────────────────────────────────────────

    @Test
    fun parsesAFieldDelta() {
        val delta = parseTaskDelta(
            json("""{"taskId":7,"action":"updated","fields":{"title":"Renamed"},"actor":{"id":3,"name":"Ada"},"at":"2026-10-25T10:00:00.000Z"}"""),
        )
        assertEquals(7L, delta?.taskId)
        assertEquals(setOf("title"), delta?.fields?.keys)
        assertEquals("Ada", delta?.actorName)
        assertEquals("2026-10-25T10:00:00.000Z", delta?.at)
    }

    @Test
    fun aFrameWithoutFieldsIsNotADelta() {
        // Delete and comment frames carry no fields; the caller refetches instead.
        assertNull(parseTaskDelta(json("""{"taskId":7,"action":"deleted"}""")))
        assertNull(parseTaskDelta(json("""{"taskId":7,"action":"comment"}""")))
        assertNull(parseTaskDelta(null))
        assertNull(parseTaskDelta(json("""{"fields":{"title":"x"}}""")))
    }

    @Test
    fun aDeltaPatchesOnlyTheFieldsItCarries() {
        val before = task(title = "Original", workflowStateKey = "pending")
        val delta = parseTaskDelta(json("""{"taskId":1,"fields":{"title":"Renamed"}}"""))!!
        val after = applyTaskDelta(before, delta)
        assertEquals("Renamed", after.title)
        assertEquals("pending", after.workflowStateKey)
    }

    @Test
    fun twoSimultaneousEditsToDifferentFieldsBothSurvive() {
        val base = task(title = "Original")
        val rename = parseTaskDelta(json("""{"taskId":1,"fields":{"title":"Renamed"}}"""))!!
        val reassign = parseTaskDelta(json("""{"taskId":1,"fields":{"assigned_to":9}}"""))!!
        val after = applyTaskDelta(applyTaskDelta(base, rename), reassign)
        assertEquals("Renamed", after.title)
        assertEquals(9L, after.assignedTo)
    }

    @Test
    fun theSameFieldEditedTwiceResolvesToTheLaterWrite() {
        val base = task(title = "Original")
        val first = parseTaskDelta(
            json("""{"taskId":1,"fields":{"title":"First"},"at":"2026-10-25T10:00:00.000Z"}"""),
        )!!
        val second = parseTaskDelta(
            json("""{"taskId":1,"fields":{"title":"Second"},"at":"2026-10-25T10:00:01.000Z"}"""),
        )!!
        val after = applyTaskDelta(applyTaskDelta(base, first), second, first.at)
        assertEquals("Second", after.title)
    }

    @Test
    fun anOutOfOrderFrameCannotRollAFieldBack() {
        val current = task(title = "Second")
        val stale = parseTaskDelta(
            json("""{"taskId":1,"fields":{"title":"First"},"at":"2026-10-25T09:59:00.000Z"}"""),
        )!!
        assertEquals("Second", applyTaskDelta(current, stale, "2026-10-25T10:00:01.000Z").title)
    }

    @Test
    fun fieldsOutsideTheAllowListAreDropped() {
        val delta = parseTaskDelta(json("""{"taskId":1,"fields":{"org_id":99,"secret":"leak"}}"""))!!
        val after = applyTaskDelta(task(), delta)
        assertEquals(task(), after)
    }

    @Test
    fun theDeltaAllowListMatchesTheServer() {
        // Mirrors server/modules/tasks/tasks.deltas.ts and the web mirror.
        assertEquals(18, DELTA_FIELDS.size)
        assertTrue("planned_date" in DELTA_FIELDS)
        assertTrue("logged_minutes" in DELTA_FIELDS)
        assertFalse("org_id" in DELTA_FIELDS)
    }

    // ── Completed visibility ─────────────────────────────────────────────

    @Test
    fun completedIsDerivedFromCompletedAt() {
        assertTrue(task(completedAt = "2026-10-25T10:00:00Z").isCompleted)
        assertFalse(task(completedAt = null).isCompleted)
    }

    @Test
    fun aPlannedDateDoesNotRemoveATaskFromTheBacklog() {
        // D2: planned_date is metadata, never a container.
        assertTrue(task(plannedDate = "2026-10-25").isBacklogItem)
        assertFalse(task(sprintId = 4).isBacklogItem)
    }

    // ── Preferences ──────────────────────────────────────────────────────

    @Test
    fun preferencesDefaultToHiddenCompletedAndComfortableCards() {
        val defaults = TaskPreferences()
        assertFalse(defaults.showCompleted)
        assertEquals("comfortable", defaults.cardDensity)
        assertFalse(defaults.isCompact)
    }

    @Test
    fun compactIsTheOnlyDensityThatShrinksCards() {
        assertTrue(TaskPreferences(cardDensity = "compact").isCompact)
        assertFalse(TaskPreferences(cardDensity = "comfortable").isCompact)
    }

    // ── Time tracking ────────────────────────────────────────────────────

    @Test
    fun aTimerStateCarriesTheTimeSummary() {
        val state = Json { ignoreUnknownKeys = true }.decodeFromString<TaskTimerState>(
            """{"taskId":7,"running":true,"startedAt":"2026-10-25T09:00:00Z","time":{"estimated":120,"logged":45,"remaining":75,"percent":38,"over":false}}""",
        )
        assertTrue(state.running)
        assertEquals(45, state.time.logged)
        assertEquals(120, state.time.estimated)
        assertFalse(state.time.over)
    }

    @Test
    fun stoppingATimerReportsTheMinutesAdded() {
        val stop = Json { ignoreUnknownKeys = true }.decodeFromString<TaskTimerStop>(
            """{"taskId":7,"running":false,"minutes":15,"time":{"estimated":120,"logged":60,"remaining":60,"percent":50,"over":false}}""",
        )
        assertEquals(15, stop.minutes)
        assertEquals(60, stop.time.logged)
    }

    // ── Bulk and rank payloads ───────────────────────────────────────────

    @Test
    fun bulkAndRankPayloadsUseTheServerFieldNames() {
        val bulk = Json.encodeToString(BulkPayload(listOf(1, 2, 3), "status", "done"))
        assertTrue(bulk.contains("\"task_ids\""))
        assertTrue(bulk.contains("\"action\""))

        val rank = Json.encodeToString(RankPayload(beforeId = 4, afterId = 9))
        assertTrue(rank.contains("\"beforeId\""))
        assertTrue(rank.contains("\"afterId\""))
    }

    @Test
    fun aCompletionPreviewSummarisesWhatMoved() {
        val preview = Json { ignoreUnknownKeys = true }.decodeFromString<CompletionPreview>(
            """{"sprint_id":4,"done_tasks":8,"total_tasks":10,"incomplete_tasks":2,"incomplete_points":5.0,"velocity_points":21.0,"carryover":[{"id":9,"title":"Ship it","issue_key":"WEB-9","story_points":3.0}]}""",
        )
        assertEquals(8, preview.doneTasks)
        assertEquals(2, preview.incompleteTasks)
        assertEquals(1, preview.carryover.size)
        assertEquals("WEB-9", preview.carryover[0].issueKey)
    }

    @Test
    fun aBaselineReportsTheScopeDelta() {
        val baseline = Json { ignoreUnknownKeys = true }.decodeFromString<SprintBaseline>(
            """{"committed_points":21.0,"committed_task_ids":[1,2],"added_points":8.0,"removed_points":3.0}""",
        )
        assertEquals(21.0, baseline.committedPoints, 0.001)
        assertEquals(8.0, baseline.addedPoints, 0.001)
        assertEquals(3.0, baseline.removedPoints, 0.001)
    }

    @Test
    fun anEpicCarriesItsRollup() {
        val epic = Json { ignoreUnknownKeys = true }.decodeFromString<EpicSummary>(
            """{"id":3,"title":"Payments","issue_key":"PAY-1","total_children":4,"done_children":3,"total_points":13.0,"done_points":8.0,"percent_by_points":62}""",
        )
        assertEquals(4, epic.totalChildren)
        assertEquals(62, epic.percentByPoints)
    }

    @Test
    fun aScheduleOverlayIsReadOnlyContext() {
        val overlay = Json { ignoreUnknownKeys = true }.decodeFromString<ScheduleOverlay>(
            """{"id":"leave-1","type":"leave","label":"Ada on leave","date":"2026-10-25","end_date":"2026-10-27"}""",
        )
        assertEquals("leave", overlay.type)
        assertEquals("2026-10-27", overlay.endDate)
    }

    @Test
    fun taskTimeGroupsByDay() {
        val days = Json { ignoreUnknownKeys = true }.decodeFromString<List<TaskTimeDay>>(
            """[{"date":"2026-10-25","minutes":105,"entries":[{"task_id":7,"title":"Refund webhook","issue_key":"PAY-142","date":"2026-10-25","minutes":45}]}]""",
        )
        assertEquals(105, days[0].minutes)
        assertEquals("PAY-142", days[0].entries[0].issueKey)
    }

    @Test
    fun aTaskDecodesTheNewFields() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<Task>(
            """{"id":7,"title":"Refund webhook","planned_date":"2026-10-25","rank":"0|hzzzzz:","team_id":2,"estimated_minutes":120,"logged_minutes":45}""",
        )
        assertEquals("2026-10-25", decoded.plannedDate)
        assertEquals("0|hzzzzz:", decoded.rank)
        assertEquals(2L, decoded.teamId)
        assertEquals(120, decoded.estimatedMinutes)
        assertEquals(45, decoded.loggedMinutes)
    }

    // ── The Compose layer's own rules ────────────────────────────────────

    @Test
    fun manualOrderSortsByTheServerRank() {
        val tasks = listOf(
            task(id = 1).copy(rank = "0|hzzzzz:"),
            task(id = 2).copy(rank = "0|i00000:"),
            task(id = 3).copy(rank = "0|hzzzz0:"),
        )
        val ordered = sortBacklog(tasks, BacklogSort.Rank).map { it.id }
        // LexoRank is a string order, not a numeric one.
        assertEquals(listOf(3L, 1L, 2L), ordered)
    }

    @Test
    fun manualOrderIsNotGrouped() {
        // Grouping is a property of the priority and due-date sorts only; a
        // ranked list must stay flat so a drag maps to one position.
        assertNull(groupBacklog(listOf(task()), BacklogSort.Rank))
    }

    @Test
    fun minutesReadAsHoursAndMinutes() {
        assertEquals("0m", formatMinutes(0))
        assertEquals("25m", formatMinutes(25))
        assertEquals("1h", formatMinutes(60))
        assertEquals("1h 25m", formatMinutes(85))
    }

    @Test
    fun theSprintTabHasBothSurfaces() {
        assertEquals(SprintView.Board, SprintView.fromKey("board"))
        assertEquals(SprintView.Plan, SprintView.fromKey("plan"))
        assertNull(SprintView.fromKey("nonsense"))
    }

    @Test
    fun anUnknownDensityDoesNotSilentlyCompact() {
        assertTrue(TaskPreferences(cardDensity = "compact").isCompact)
        assertFalse(TaskPreferences(cardDensity = "comfortable").isCompact)
        // An unknown value from a newer server must not silently compact.
        assertFalse(TaskPreferences(cardDensity = "spacious").isCompact)
    }
}
