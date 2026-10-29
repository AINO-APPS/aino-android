package app.aino.mobile.feature.home

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The home card's two modes (spec D1) and the rules that shape its rows.
 *
 * These drive the repository through its only public load seam so the request
 * paths, the envelope decode and the filtering are pinned together.
 */
class DashboardMyWorkTest {
    private val me = 7L
    private val today = LocalDate.of(2026, 10, 28) // a Wednesday

    @Test
    fun theActiveSprintIsReadFromItsEnvelope() {
        val api = ApiClient { request ->
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> response("""{"sprint":{"id":12,"name":"Sprint 12"}}""")
                request.path == "sprints/12/tasks" -> response(
                    """{"tasks":[{"id":1,"title":"Mine","assigned_to":7,"completed_at":null}]}""",
                )
                else -> response("{}")
            }
        }

        val snapshot = DashboardRepository(api).load(userId = me, isManager = false, today = today)

        assertEquals(MyWorkMode.Sprint, snapshot.myWork.mode)
        assertEquals("Sprint 12", snapshot.myWork.title)
        assertEquals(12L, snapshot.myWork.sprintId)
        assertEquals(listOf("Mine"), snapshot.myWork.groups.single().tasks.map { it.title })
    }

    @Test
    fun sprintModeKeepsOnlyMyOpenTickets() {
        val api = ApiClient { request ->
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> response("""{"sprint":{"id":12,"name":"Sprint 12"}}""")
                request.path == "sprints/12/tasks" -> response(
                    """{"tasks":[
                        {"id":1,"title":"Mine","assigned_to":7},
                        {"id":2,"title":"Theirs","assigned_to":999},
                        {"id":3,"title":"Unassigned idea","user_id":7,"assigned_to":null},
                        {"id":4,"title":"Finished","assigned_to":7,"completed_at":"2026-10-27T10:00:00Z"},
                        {"id":5,"title":"Dropped","assigned_to":7,"workflow_state_key":"cancelled"}
                    ]}""",
                )
                else -> response("{}")
            }
        }

        val work = DashboardRepository(api).load(userId = me, isManager = false, today = today).myWork

        assertEquals(listOf("Mine"), work.groups.single().tasks.map { it.title })
        // The finished ticket is counted, the cancelled one is not counted at all.
        assertEquals(1, work.done)
        assertEquals(2, work.total)
    }

    @Test
    fun withNoActiveSprintTheCardShowsThisWeekAndNeverTheBacklog() {
        val requested = mutableListOf<String>()
        val api = ApiClient { request ->
            requested += request.path
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> response("""{"sprint":null}""")
                request.path.startsWith("tasks?") -> response("""{"tasks":[]}""")
                else -> response("{}")
            }
        }

        val work = DashboardRepository(api).load(userId = me, isManager = false, today = today).myWork

        assertEquals(MyWorkMode.Week, work.mode)
        assertEquals("This Week", work.title)
        assertEquals(0, work.groups.size)
        assertEquals(true, work.isEmpty)
        assertEquals(false, requested.any { it.contains("tasks/backlog") })
    }

    @Test
    fun theWeekQueryIsMondayToSundayPlusAPreviousWeekOverdueLookup() {
        val requested = mutableListOf<String>()
        val api = ApiClient { request ->
            requested += request.path
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> response("""{"sprint":null}""")
                else -> response("""{"tasks":[]}""")
            }
        }

        DashboardRepository(api).load(userId = me, isManager = false, today = today)

        assertEquals(
            listOf(
                "tasks?start_date=2026-10-26&end_date=2026-11-01&assignee=me",
                "tasks?start_date=2026-09-26&end_date=2026-10-25&assignee=me",
            ),
            requested.filter { it.startsWith("tasks?") },
        )
    }

    @Test
    fun weekModePutsSlippedWorkInOverdueAndGroupsTheRestByDay() {
        val api = ApiClient { request ->
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> response("""{"sprint":null}""")
                request.path == "tasks?start_date=2026-10-26&end_date=2026-11-01&assignee=me" -> response(
                    """{"tasks":[
                        {"id":2,"title":"Wednesday work","assigned_to":7,"planned_date":"2026-10-28"},
                        {"id":3,"title":"Monday work","assigned_to":7,"planned_date":"2026-10-26"},
                        {"id":4,"title":"Done on Friday","assigned_to":7,"planned_date":"2026-10-30","completed_at":"2026-10-30T09:00:00Z"}
                    ]}""",
                )
                request.path == "tasks?start_date=2026-09-26&end_date=2026-10-25&assignee=me" -> response(
                    """{"tasks":[{"id":1,"title":"Slipped","assigned_to":7,"planned_date":"2026-10-21"}]}""",
                )
                else -> response("{}")
            }
        }

        val work = DashboardRepository(api).load(userId = me, isManager = false, today = today).myWork

        assertEquals(listOf("Overdue", "Monday", "Wednesday"), work.groups.map { it.label })
        assertEquals(listOf("Slipped"), work.groups.first().tasks.map { it.title })
        assertEquals(listOf("Wednesday work"), work.groups.last().tasks.map { it.title })
        // The completed Friday ticket is counted, not listed.
        assertEquals(1, work.done)
        assertEquals(3, work.total)
    }

    @Test
    fun aFailedWeekRequestLeavesTheRestOfTheDashboardIntact() {
        val api = ApiClient { request ->
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> throw ApiError.Network("GET", request.path, java.io.IOException("offline"))
                else -> response("{}")
            }
        }

        val snapshot = DashboardRepository(api).load(userId = me, isManager = false, today = today)

        assertEquals("on_floor", snapshot.status.state)
        assertEquals(MyWorkMode.Week, snapshot.myWork.mode)
        // A failed lookup is reported, so the card cannot claim the week is empty.
        assertEquals(true, snapshot.myWork.failed)
    }

    @Test
    fun anUndecodableActiveSprintIsNotSilentlyTreatedAsNoSprint() {
        // The bug this card shipped with: a bare-sprint decode threw, runCatching
        // swallowed it, and a team with a running sprint quietly saw the week.
        val api = ApiClient { request ->
            when {
                request.path == "tracker/status" -> response("""{"state":"on_floor"}""")
                request.path.startsWith("calendar?") -> response("[]")
                request.path == "sprints/active" -> response("""{"sprint":""" + "{}" + """}""")
                request.path.startsWith("tasks?") -> response("""{"tasks":[]}""")
                else -> response("{}")
            }
        }

        val work = DashboardRepository(api).load(userId = me, isManager = false, today = today).myWork

        // A sprint that failed to decode must not read as "no sprint running".
        assertEquals(true, work.failed)
    }

    private fun response(json: String) = ApiResponse(200, emptyMap(), json.toByteArray())
}

