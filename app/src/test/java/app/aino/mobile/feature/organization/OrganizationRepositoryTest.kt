package app.aino.mobile.feature.organization

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrganizationRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        OrganizationRepository(
            ApiClient { request ->
                captured += request
                ApiResponse(200, emptyMap(), body(request).toByteArray())
            },
        )

    private val ApiRequest.text get() = body?.toString(Charsets.UTF_8)

    @Test
    fun currentOrgDecodesRowAndNullMeansNoOrg() {
        val captured = mutableListOf<ApiRequest>()
        val org = repository(captured) { """{"id":4,"name":"Acme","slug":"acme","memberCount":12,"settings":{}}""" }.currentOrg()
        assertEquals("GET", captured.single().method)
        assertEquals("org/current", captured.single().path)
        assertEquals(4L, org?.id)
        assertEquals("Acme", org?.name)
        assertEquals(12, org?.memberCount)

        assertNull(repository(mutableListOf()) { "null" }.currentOrg())
    }

    @Test
    fun createOrgPostsNameAndReturnsId() {
        val captured = mutableListOf<ApiRequest>()
        val res = repository(captured) { """{"id":9,"name":"Acme","slug":"acme","message":"Organization created successfully"}""" }
            .createOrg("Acme")
        assertEquals("POST", captured.single().method)
        assertEquals("org", captured.single().path)
        assertEquals("""{"name":"Acme"}""", captured.single().text)
        assertEquals(9L, res.id)
    }

    @Test
    fun departmentsCrudShapes() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { request ->
            if (request.method == "GET") {
                """[{"id":1,"org_id":4,"name":"Eng","head_id":7,"head_name":"Ann","member_count":5},{"id":2,"name":"Ops","head_name":null}]"""
            } else {
                """{"message":"ok"}"""
            }
        }

        val list = repo.departments(4)
        assertEquals("org/departments?org_id=4", captured[0].path)
        assertEquals(Department(1, "Eng", 7, "Ann", 5), list[0])
        assertEquals(Department(2, "Ops"), list[1])

        repo.departments(null)
        assertEquals("org/departments", captured[1].path)

        repo.createDepartment("Eng", null, 4)
        assertEquals("POST", captured[2].method)
        assertEquals("org/departments", captured[2].path)
        assertEquals("""{"name":"Eng","head_id":null,"org_id":4}""", captured[2].text)

        repo.createDepartment("Eng", 7, null)
        assertEquals("""{"name":"Eng","head_id":7}""", captured[3].text)

        repo.updateDepartment(1, "Engineering", null)
        assertEquals("PUT", captured[4].method)
        assertEquals("org/departments/1", captured[4].path)
        assertEquals("""{"name":"Engineering","head_id":null}""", captured[4].text)

        repo.deleteDepartment(1)
        assertEquals("DELETE", captured[5].method)
        assertEquals("org/departments/1", captured[5].path)
        assertNull(captured[5].body)
    }

    @Test
    fun teamsCrudAndSprintConfigShapes() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { request ->
            when {
                request.path.endsWith("sprint-config") && request.method == "GET" ->
                    """{"teamId":3,"teamName":"Core","sprintDurationWeeks":3,"sprintStartDate":"2026-01-05","sprintMode":"auto","sprintPaused":true,"currentSprint":{"number":2}}"""
                request.method == "GET" ->
                    """[{"id":3,"org_id":4,"name":"Core","department_id":1,"department_name":"Eng","lead_id":7,"lead_name":"Ann","member_count":4,"sprint_duration_weeks":2,"sprint_start_date":"2026-01-05","sprint_mode":"manual"}]"""
                else -> """{"message":"ok"}"""
            }
        }

        val team = repo.teams(null).single()
        assertEquals("org/teams", captured[0].path)
        assertEquals(Team(3, "Core", 1, "Eng", 7, "Ann", 4, 2, "2026-01-05"), team)

        repo.createTeam("Core", null, 7, 4)
        assertEquals("POST", captured[1].method)
        assertEquals("org/teams", captured[1].path)
        assertEquals("""{"name":"Core","department_id":null,"lead_id":7,"org_id":4}""", captured[1].text)

        repo.updateTeam(3, "Core", 1, null)
        assertEquals("PUT", captured[2].method)
        assertEquals("org/teams/3", captured[2].path)
        assertEquals("""{"name":"Core","department_id":1,"lead_id":null}""", captured[2].text)

        repo.deleteTeam(3)
        assertEquals("DELETE", captured[3].method)
        assertEquals("org/teams/3", captured[3].path)

        val config = repo.teamSprintConfig(3)
        assertEquals("org/teams/3/sprint-config", captured[4].path)
        assertEquals(SprintConfig(3, "Core", 3, "2026-01-05", "auto", true), config)

        repo.updateTeamSprintConfig(3, 2, null, "manual")
        assertEquals("PUT", captured[5].method)
        assertEquals("org/teams/3/sprint-config", captured[5].path)
        assertEquals("""{"sprint_duration_weeks":2,"sprint_start_date":null,"sprint_mode":"manual"}""", captured[5].text)

        repo.updateTeamSprintConfig(3, 4, "2026-02-02", "auto")
        assertEquals("""{"sprint_duration_weeks":4,"sprint_start_date":"2026-02-02","sprint_mode":"auto"}""", captured[6].text)
    }

    @Test
    fun sprintActivePauseResume() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { request ->
            when (request.path) {
                "sprints/active" -> """{"sprint":{"id":11,"name":"Sprint 4","status":"active","team_id":3}}"""
                else -> """{"sprint":{"id":11,"name":"Sprint 4","status":"paused"}}"""
            }
        }
        assertEquals(Sprint(11, "Sprint 4", "active"), repo.activeSprint())
        assertEquals("GET", captured[0].method)

        assertEquals("paused", repo.pauseSprint(11)?.status)
        assertEquals("POST", captured[1].method)
        assertEquals("sprints/11/pause", captured[1].path)
        // OkHttp needs a (possibly empty) body for POST.
        assertEquals(0, captured[1].body?.size)

        repo.resumeSprint(11)
        assertEquals("sprints/11/resume", captured[2].path)

        assertNull(repository(mutableListOf()) { """{"sprint":null}""" }.activeSprint())
    }

    @Test
    fun membersPagesUntilTotal() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { request ->
            if (request.path.endsWith("&page=1")) {
                """{"data":[{"id":1,"username":"ann","full_name":"Ann"},{"id":2,"username":"bob","full_name":null}],"total":3,"page":1,"perPage":2}"""
            } else {
                """{"data":[{"id":3,"username":"cy","full_name":""}],"total":3,"page":2,"perPage":2}"""
            }
        }
        val members = repo.activeMembers(4)
        assertEquals(listOf("Ann", "bob", "cy"), members.map { it.label })
        assertEquals("org/members?is_active=true&org_id=4&per_page=100&page=1", captured[0].path)
        assertEquals("org/members?is_active=true&org_id=4&per_page=100&page=2", captured[1].path)
        assertEquals(2, captured.size)
    }

    @Test
    fun orgChartDecodes() {
        val captured = mutableListOf<ApiRequest>()
        val chart = repository(captured) {
            """{"departments":[{"id":1,"name":"Eng","head_id":7,"head_name":"Ann","head_avatar":null}],
               "teams":[{"id":3,"name":"Core","department_id":1,"lead_id":7,"lead_name":"Ann","lead_avatar":"/u/a.png"}],
               "members":[{"id":7,"full_name":"Ann","email":"a@x.io","avatar":null,"role":"manager","department_id":1,"team_id":3,
                           "manager_id":null,"manager_name":null,"department_name":"Eng","team_name":"Core"}]}"""
        }.orgChart(null)
        assertEquals("org/chart", captured.single().path)
        assertEquals("Eng", chart.departments.single().name)
        assertEquals("/u/a.png", chart.teams.single().leadAvatar)
        assertEquals(ChartMember(7, "Ann", "a@x.io", null, "manager", 1, 3, null, null, "Eng", "Core"), chart.members.single())
    }

    @Test
    fun taskLabelsCrudShapes() {
        val captured = mutableListOf<ApiRequest>()
        val repo = repository(captured) { request ->
            when (request.method) {
                "GET" -> """[{"id":5,"org_id":4,"name":"Bug","color":"#ef4444","created_by":7,"created_by_username":"ann"}]"""
                "DELETE" -> """{"message":"Label deleted"}"""
                else -> """{"id":5,"org_id":4,"name":"Bug","color":"#ef4444"}"""
            }
        }
        assertEquals(listOf(TaskLabel(5, "Bug", "#ef4444", "ann")), repo.taskLabels())
        assertEquals("tasks/labels/manage", captured[0].path)

        repo.createTaskLabel("Bug", "#ef4444")
        assertEquals("POST", captured[1].method)
        assertEquals("tasks/labels", captured[1].path)
        assertEquals("""{"name":"Bug","color":"#ef4444"}""", captured[1].text)

        repo.updateTaskLabel(5, "Defect", "#0ea5e9")
        assertEquals("PUT", captured[2].method)
        assertEquals("tasks/labels/5", captured[2].path)
        assertEquals("""{"name":"Defect","color":"#0ea5e9"}""", captured[2].text)

        repo.deleteTaskLabel(5)
        assertEquals("DELETE", captured[3].method)
        assertEquals("tasks/labels/5", captured[3].path)
    }

    @Test
    fun serverErrorMessageIsSurfaced() {
        val repo = OrganizationRepository(
            ApiClient { request ->
                throw ApiError.Http(400, """{"error":"Department name already exists"}""", request.method, request.path)
            },
        )
        try {
            repo.createDepartment("Eng", null, null)
            fail("expected failure")
        } catch (error: OrganizationFailure) {
            assertEquals("Department name already exists", error.serverMessage)
            assertEquals(400, error.statusCode)
            assertEquals("Department name already exists", error.orgMessage("Failed"))
        }

        val silent = OrganizationRepository(ApiClient { request -> throw ApiError.Http(500, "<html>", request.method, request.path) })
        try {
            silent.departments(null)
            fail("expected failure")
        } catch (error: OrganizationFailure) {
            assertNull(error.serverMessage)
            assertEquals("Failed", error.orgMessage("Failed"))
        }
        assertTrue(IllegalStateException().orgMessage("Failed to delete label") == "Failed to delete label")
    }
}
