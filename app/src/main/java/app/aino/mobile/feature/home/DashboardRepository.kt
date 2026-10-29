package app.aino.mobile.feature.home

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.receivedAtEpochMs
import app.aino.mobile.core.common.TrackerStatus
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId
import java.net.URLEncoder

class DashboardRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private inline fun <reified T> get(path: String): T =
        json.decodeFromString(api.execute(ApiRequest(path = path)).bodyAsString())

    fun loadStatus(nowEpochMs: Long? = null): TrackerStatus {
        val response = api.execute(ApiRequest(path = "tracker/status"))
        return json.decodeFromString<TrackerStatus>(response.bodyAsString())
            .copy(receivedAtEpochMs = response.receivedAtEpochMs(nowEpochMs ?: System.currentTimeMillis()))
    }

    // @api GET tracker/task-summary
    fun loadTaskSummary(): TaskSummary = get("tracker/task-summary")

    // @api GET calendar
    fun loadEvents(from: LocalDate, to: LocalDate): List<DashboardEvent> {
        fun start(day: LocalDate): String = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()
        return get("calendar?from=${enc(start(from))}&to=${enc(start(to))}")
    }

    // @api GET notifications/announcements
    fun loadAnnouncements(): List<DashboardAnnouncement> =
        get<AnnouncementEnvelope>("notifications/announcements").data

    /**
     * The team's running sprint, or null when there is none.
     *
     * A `runCatching` here would make "the server answered with no sprint",
     * "the tenant lacks the `agile` feature" and "the response did not decode"
     * indistinguishable, and this card's whole mode switch hangs on it — a
     * decode failure would silently show the week to a team that has a sprint
     * running. Only the two *expected* absences are absorbed; anything else
     * propagates so [loadMyWork]'s caller sees it.
     */
    // @api GET sprints/active
    fun loadActiveSprint(): ActiveSprint? =
        try {
            get<ActiveSprintEnvelope>("sprints/active").sprint
        } catch (e: ApiError.Http) {
            if (e.statusCode == 403) null else throw e // 403 = the agile feature is off
        }

    // @api GET sprints/:id/tasks
    fun loadSprintTasks(sprintId: Long): List<DashboardTask> =
        runCatching { get<DashboardTaskList>("sprints/$sprintId/tasks").tasks }.getOrDefault(emptyList())

    /**
     * The planned-day range query. It is the ADR-015 Scheduled container
     * (`sprint_id IS NULL AND planned_date BETWEEN …`), so a ticket in a sprint
     * never leaks into the week, and completed rows come back in the same
     * response — that is what the card's "N of M done" counts.
     *
     * Null means the query failed. An empty list is a real answer ("nothing
     * planned") and the card says so; a failure must not borrow that sentence.
     */
    // @api GET tasks
    fun loadTasks(from: LocalDate, to: LocalDate): List<DashboardTask>? =
        runCatching {
            get<DashboardTaskList>("tasks?start_date=$from&end_date=$to&assignee=me").tasks
        }.getOrNull()

    fun loadApprovals(): List<Approval> =
        runCatching { get<List<Approval>>("manager/approvals?status=pending") }.getOrDefault(emptyList())

    fun approveRequest(id: Long) = runCatching {
        api.execute(ApiRequest(method = "POST", path = "manager/approvals/$id/approve", body = ByteArray(0)))
    }

    fun rejectRequest(id: Long) = runCatching {
        api.execute(ApiRequest(method = "POST", path = "manager/approvals/$id/reject", body = "{}".toByteArray()))
    }

    /**
     * P2.10 — Promise.allSettled semantics: every source fails independently so
     * a partial failure never blanks the page. The status is the only hard
     * requirement (it drives the work timer); everything else degrades to empty.
     */
    fun load(
        userId: Long,
        isManager: Boolean,
        nowEpochMs: Long? = null,
        today: LocalDate = LocalDate.now(),
    ): DashboardSnapshot {
        val status = loadStatus(nowEpochMs)
        val tasks = runCatching { loadTaskSummary() }.getOrNull()
        val todayEvents = runCatching { loadEvents(today, today.plusDays(1)) }.getOrDefault(emptyList())
        val tomorrowEvents = runCatching { loadEvents(today.plusDays(1), today.plusDays(2)) }.getOrDefault(emptyList())
        val announcements = runCatching { loadAnnouncements() }.getOrDefault(emptyList())
        val approvals = if (isManager) loadApprovals() else emptyList()
        return DashboardSnapshot(
            status = status,
            tasks = tasks,
            todayEvents = todayEvents,
            tomorrowEvents = tomorrowEvents,
            announcements = announcements,
            myWork = loadMyWork(userId, today),
            approvals = approvals,
        )
    }

    /**
     * Sprint mode when a sprint is running, the week otherwise (spec D1).
     *
     * The mode is the same rule the Tasks page uses (`planningTabs`): a
     * sprint-enabled team with no *active* sprint works from the dated surface,
     * so the card shows that surface too rather than a sprint that is not
     * running.
     */
    fun loadMyWork(userId: Long, today: LocalDate = LocalDate.now()): MyWork {
        val sprint = runCatching { loadActiveSprint() }.getOrElse {
            // A decode or transport failure is not "no sprint": the card says it
            // could not load rather than showing an empty week to someone whose
            // sprint simply failed to arrive.
            return MyWork(MyWorkMode.Week, "This Week", failed = true)
        }
        if (sprint != null) {
            // One fetch: the sprint's rows serve the list and both counts.
            val inSprint = loadSprintTasks(sprint.id)
            val (groups, hidden) = capGroups(sprintGroups(myOpenTasks(inSprint, userId)))
            return MyWork(
                mode = MyWorkMode.Sprint,
                title = sprint.name,
                sprintId = sprint.id,
                groups = groups,
                done = myDoneCount(inSprint, userId),
                total = myWorkCount(inSprint, userId),
                hidden = hidden,
            )
        }
        val week = weekRange(today)
        val thisWeek = loadTasks(week.start, week.end) ?: return MyWork(MyWorkMode.Week, "This Week", failed = true)
        val overdueFrom = previousWeekEnd(week.start).minusDays(OVERDUE_DAYS - 1)
        // A failed Overdue lookup costs the Overdue group only: this week's rows
        // are still the answer to the question the card asks.
        val overdue = loadTasks(overdueFrom, previousWeekEnd(week.start)).orEmpty()
        val (groups, hidden) = capGroups(
            weekGroups(myOpenTasks(overdue, userId), myOpenTasks(thisWeek, userId), week.start),
        )
        return MyWork(
            mode = MyWorkMode.Week,
            title = "This Week",
            groups = groups,
            done = myDoneCount(thisWeek, userId),
            total = myWorkCount(thisWeek, userId),
            hidden = hidden,
        )
    }
}