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
        teamId: Long? = null,
    ) = Task(
        id = id,
        title = title,
        workflowStateKey = workflowStateKey,
        plannedDate = plannedDate,
        sprintId = sprintId,
        completedAt = completedAt,
        loggedMinutes = loggedMinutes,
        estimatedMinutes = estimatedMinutes,
        teamId = teamId,
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
        assertEquals(19, DELTA_FIELDS.size)
        assertTrue("planned_date" in DELTA_FIELDS)
        assertTrue("logged_minutes" in DELTA_FIELDS)
        // A transfer moves the ticket's owning team.
        assertTrue("team_id" in DELTA_FIELDS)
        assertFalse("org_id" in DELTA_FIELDS)
    }

    @Test
    fun aTransferDeltaRepointsTheOwningTeam() {
        val delta = parseTaskDelta(json("""{"taskId":1,"fields":{"team_id":7}}"""))!!
        val after = applyTaskDelta(task(teamId = 3), delta)
        assertEquals(7L, after.teamId)
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
    fun preferencesDefaultToHiddenCompletedAndCompactCards() {
        val defaults = TaskPreferences()
        assertFalse(defaults.showCompleted)
        assertEquals("compact", defaults.cardDensity)
        assertTrue(defaults.isCompact)
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
        // The server reads `ids` (`routes/tasks/bulk.ts:74`). The client sent
        // `task_ids` for months, so every bulk action 400'd.
        val bulk = Json.encodeToString(BulkPayload(listOf(1, 2, 3), "status", "done"))
        assertTrue(bulk.contains("\"ids\""))
        assertTrue(bulk.contains("\"action\""))

        val rank = Json.encodeToString(RankPayload(beforeId = 4, afterId = 9))
        assertTrue(rank.contains("\"beforeId\""))
        assertTrue(rank.contains("\"afterId\""))
    }

    @Test
    fun aBulkResultReportsCountsAndPerTicketFailures() {
        // Captured from `routes/tasks/bulk.ts:221` — `failed` is a number, and
        // each result carries the server's own error text and machine code.
        val result = Json { ignoreUnknownKeys = true }.decodeFromString<BulkResult>(
            """{"action":"status","requested":3,"changed":1,"failed":2,"results":[
                 {"id":1,"ok":true},
                 {"id":2,"ok":false,"error":"Only the assignee can change this","code":"STATUS_FORBIDDEN"},
                 {"id":3,"ok":false,"error":"That move is not allowed from Done","code":"INVALID_TRANSITION"}]}""",
        )
        assertEquals(1, result.changed)
        assertEquals(2, result.failed)
        assertEquals(2, result.failures.size)
        assertEquals("INVALID_TRANSITION", result.failures[1].code)
    }

    @Test
    fun aCompletionPreviewSummarisesWhatMoved() {
        // Captured from `routes/sprintCompletion.ts:41`.
        val preview = Json { ignoreUnknownKeys = true }.decodeFromString<CompletionPreview>(
            """{"sprint":{"id":4,"name":"Sprint 12","status":"active"},"defaultTarget":"backlog","moving":2,"staying":8,"movingPoints":5.0}""",
        )
        assertEquals(8, preview.doneTasks)
        assertEquals(10, preview.totalTasks)
        assertEquals(2, preview.incompleteTasks)
        assertEquals(5.0, preview.movingPoints, 0.001)
        assertEquals("backlog", preview.defaultTarget)
    }

    @Test
    fun aBaselineReportsTheScopeDelta() {
        // Captured from `routes/sprintBaseline.ts:54` — the payload is wrapped
        // in `{baseline, delta}`, not flat.
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<BaselineResponse>(
            """{"baseline":{"committed_points":21.0,"committed_task_ids":[1,2],"captured_at":"2026-10-20T09:00:00Z"},
                "delta":{"addedPoints":8.0,"removedPoints":3.0,"addedCount":1,"removedCount":1}}""",
        )
        assertEquals(21.0, response.committedPoints, 0.001)
        assertEquals(8.0, response.addedPoints, 0.001)
        assertEquals(3.0, response.removedPoints, 0.001)
    }

    @Test
    fun anEpicCarriesItsNestedRollup() {
        // Captured from `routes/tasks/epics.ts:39` — the counts are nested under
        // `rollup` with camelCase keys, not flat snake_case on the epic.
        val response = Json { ignoreUnknownKeys = true }.decodeFromString<EpicListResponse>(
            """{"epics":[{"id":3,"title":"Payments","issue_key":"PAY-1",
                 "rollup":{"totalChildren":4,"doneChildren":3,"totalPoints":13.0,"donePoints":8.0,
                           "percentByPoints":62,"percentByCount":75}}]}""",
        )
        val epic = response.epics.single()
        assertEquals(4, epic.totalChildren)
        assertEquals(62, epic.percentByPoints)
        assertEquals(13.0, epic.totalPoints, 0.001)
    }

    @Test
    fun scheduleOverlaysKeepTheirThreeCollectionsApart() {
        // Captured from `modules/tasks/tasks.schedule.ts:57` — three arrays of
        // different row shapes, not one flat list.
        val overlays = Json { ignoreUnknownKeys = true }.decodeFromString<ScheduleOverlays>(
            """{"leave":[{"id":1,"user_id":9,"user_name":"Ada","date":"2026-10-25","duration":1.0,"leave_type":"annual","status":"approved"}],
                "holidays":[{"id":2,"name":"Diwali","date":"2026-10-27"}],
                "meetings":[{"id":3,"title":"Standup","start_time":"2026-10-26T09:30:00Z","end_time":"2026-10-26T09:45:00Z"}]}""",
        )
        assertEquals(1, overlays.leave.size)
        assertEquals(1, overlays.holidays.size)
        assertEquals(1, overlays.meetings.size)
        assertEquals(3, overlays.all.size)
        assertEquals("leave", overlays.all.first().kind)
        assertEquals("Ada", overlays.leave.single().displayLabel)
        assertEquals("Diwali", overlays.holidays.single().displayLabel)
        assertEquals("Standup", overlays.meetings.single().displayLabel)
        assertEquals("2026-10-26", overlays.meetings.single().day)
        assertFalse(overlays.isEmpty)
    }

    @Test
    fun anEmptyOverlayWindowIsEmpty() {
        val overlays = Json { ignoreUnknownKeys = true }.decodeFromString<ScheduleOverlays>("{}")
        assertTrue(overlays.isEmpty)
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
        // The Board/Plan switch is gone; the planner is a Backlog action now.
        assertEquals(TaskTab.Scheduled, TaskTab.fromKey("scheduled"))
        assertNull(TaskTab.fromKey("nonsense"))
    }

    @Test
    fun anUnknownDensityDoesNotSilentlyCompact() {
        assertTrue(TaskPreferences(cardDensity = "compact").isCompact)
        assertFalse(TaskPreferences(cardDensity = "comfortable").isCompact)
        // An unknown value from a newer server must not silently compact.
        assertFalse(TaskPreferences(cardDensity = "spacious").isCompact)
    }
}
