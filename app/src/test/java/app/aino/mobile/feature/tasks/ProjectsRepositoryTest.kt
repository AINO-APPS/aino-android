package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectsRepositoryTest {
    private fun client(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) = ApiClient { request ->
        captured += request
        ApiResponse(200, emptyMap(), body(request).toByteArray())
    }

    private fun failing(status: Int, body: String) = ApiClient { request ->
        throw ApiError.Http(status, body, request.method, request.path)
    }

    private fun body(request: ApiRequest) = request.body!!.toString(Charsets.UTF_8)

    @Test
    fun paginatedListDecodesCountsAndLeadNames() {
        val captured = mutableListOf<ApiRequest>()
        val repository = ProjectsRepository(
            client(captured) {
                """
                {"projects":[{"id":3,"org_id":1,"key":"WEB","name":"Website","description":null,"color":"#10b981",
                "lead_user_id":9,"lead_name":null,"lead_username":"lead","task_count":"4","next_task_number":5,
                "is_archived":false,"created_by":1,"created_at":"2026-09-20T10:00:00.000Z"}],
                "pagination":{"limit":12,"offset":0,"total":30,"hasMore":true}}
                """.trimIndent()
            },
        )

        val page = repository.loadProjects(includeArchived = true, limit = 12, offset = 24)

        assertEquals("GET", captured.single().method)
        assertEquals("projects?include_archived=1&paginate=1&limit=12&offset=24", captured.single().path)
        assertEquals(30, page.total)
        val p = page.projects.single()
        assertEquals(4, p.taskCount)
        assertEquals(5, p.nextTaskNumber)
        assertEquals("lead", p.leadDisplay())
        assertEquals(9L, p.leadUserId)
    }

    @Test
    fun legacyArrayResponseIsAccepted() {
        val captured = mutableListOf<ApiRequest>()
        val repository = ProjectsRepository(client(captured) { """[{"id":1,"key":"AND","name":"Android"},{"id":2,"key":"IOS","name":"iOS","is_archived":true}]""" })

        val page = repository.loadProjects(includeArchived = false, limit = 6, offset = 0)

        assertEquals("projects?paginate=1&limit=6&offset=0", captured.single().path)
        assertEquals(2, page.total)
        assertTrue(page.projects[1].isArchived)
        assertEquals("—", page.projects[0].leadDisplay())
    }

    @Test
    fun createUpdateArchiveSendWebBodies() {
        val captured = mutableListOf<ApiRequest>()
        val repository = ProjectsRepository(client(captured) { """{"id":7,"key":"WEB","name":"Web"}""" })

        repository.createProject(ProjectPayload("Web", null, "#6366f1", 4, key = "WEB"))
        repository.updateProject(7, ProjectPayload("Web 2", "Docs", "#ef4444", null))
        repository.archiveProject(7, true)

        assertEquals(listOf("POST", "PUT", "PATCH"), captured.map { it.method })
        assertEquals(listOf("projects", "projects/7", "projects/7/archive"), captured.map { it.path })
        assertEquals("""{"name":"Web","description":null,"color":"#6366f1","lead_user_id":4,"key":"WEB"}""", body(captured[0]))
        assertEquals("""{"name":"Web 2","description":"Docs","color":"#ef4444","lead_user_id":null,"key":null}""", body(captured[1]))
        assertEquals("""{"is_archived":true}""", body(captured[2]))
    }

    @Test
    fun deleteSurfacesProjectNotEmptyAndForceDeletes() {
        val repository = ProjectsRepository(
            failing(409, """{"error":"Cannot delete project: 3 task(s) still belong to it.","task_count":3,"code":"PROJECT_NOT_EMPTY"}"""),
        )
        try {
            repository.deleteProject(5)
            fail("expected ProjectNotEmptyException")
        } catch (e: ProjectNotEmptyException) {
            assertEquals(3, e.taskCount)
        }

        val captured = mutableListOf<ApiRequest>()
        val ok = ProjectsRepository(client(captured) { """{"ok":true,"detached_tasks":3}""" }).deleteProject(5, force = true)
        assertEquals("DELETE", captured.single().method)
        assertEquals("projects/5?force=1", captured.single().path)
        assertNull(captured.single().body)
        assertEquals(3, ok.detachedTasks)
    }

    @Test
    fun otherFailuresCarryTheServerErrorVerbatim() {
        val repository = ProjectsRepository(failing(403, """{"error":"Insufficient permissions"}"""))
        try {
            repository.deleteProject(5)
            fail("expected TaskFailure")
        } catch (e: TaskFailure) {
            assertEquals("Insufficient permissions", e.message)
        }
        try {
            ProjectsRepository(failing(500, "")).createProject(ProjectPayload("x", null, "#6366f1", null, "AB"))
            fail("expected TaskFailure")
        } catch (e: TaskFailure) {
            assertEquals("Save failed", e.message)
        }
        try {
            ProjectsRepository(failing(409, """{"error":"A project with this key already exists"}""")).createProject(ProjectPayload("x", null, "#6366f1", null, "AB"))
            fail("expected TaskFailure")
        } catch (e: TaskFailure) {
            assertEquals("A project with this key already exists", e.message)
        }
    }

    @Test
    fun projectTasksPanelDecodesIssueKeysAndAssignees() {
        val captured = mutableListOf<ApiRequest>()
        val repository = ProjectsRepository(
            client(captured) {
                """
                {"tasks":[{"id":11,"title":"Login","status":"todo","issue_key":"WEB-1","assignee":{"username":"vv","full_name":"Vishnu"}},
                {"id":12,"title":"Logout","status":"done","issue_key":null,"assignee":null}],
                "pagination":{"limit":25,"offset":0,"total":"2","hasMore":false}}
                """.trimIndent()
            },
        )

        val page = repository.loadProjectTasks(3, 25, 0)

        assertEquals("projects/3/tasks?limit=25&offset=0", captured.single().path)
        assertEquals(2, page.total)
        assertEquals("WEB-1", page.tasks[0].keyDisplay())
        assertEquals("Vishnu", page.tasks[0].assigneeDisplay())
        assertEquals("#12", page.tasks[1].keyDisplay())
        assertEquals("—", page.tasks[1].assigneeDisplay())
    }

    @Test
    fun singleProjectAndLeadPickerPaths() {
        val captured = mutableListOf<ApiRequest>()
        val repository = ProjectsRepository(
            client(captured) { r -> if (r.path == "projects/3") """{"id":3,"key":"WEB","name":"Web"}""" else """[{"id":1,"username":"a","full_name":"A"}]""" },
        )
        assertEquals("WEB", repository.loadProject(3).key)
        assertEquals("A", repository.loadAssignableUsers().single().display())
        assertEquals(listOf("projects/3", "tasks/assignable-users"), captured.map { it.path })
    }

    @Test
    fun keyValidationMatchesTheDbConstraint() {
        assertTrue(isValidProjectKey("WEB"))
        assertTrue(isValidProjectKey("A1"))
        assertTrue(isValidProjectKey("PSS_PMT_10"))
        assertFalse(isValidProjectKey("A"))
        assertFalse(isValidProjectKey("1AB"))
        assertFalse(isValidProjectKey("_AB"))
        assertFalse(isValidProjectKey("ABCDEFGHIJK"))
        assertFalse(isValidProjectKey("web"))
        assertEquals("WEB_1", sanitizeProjectKey("we-b_1"))
        assertEquals("ABCDEFGHIJ", sanitizeProjectKey("abcdefghijklm"))
    }

    @Test
    fun roleGatesMatchTheWeb() {
        assertFalse(canEditProjects("employee"))
        assertFalse(canEditProjects("team_lead"))
        assertTrue(canEditProjects("manager"))
        assertTrue(canEditProjects("hr_admin"))
        assertFalse(canEditProjects(null))
        assertFalse(canDeleteProjects("hr_admin"))
        assertTrue(canDeleteProjects("super_admin"))
        assertTrue(canDeleteProjects("platform_admin"))
    }

    @Test
    fun formStateDefaultsAndValidity() {
        val create = ProjectFormState()
        assertEquals("#6366f1", create.color)
        assertFalse(create.keyValid)
        assertTrue(create.copy(key = "WEB").keyValid)
        val edit = ProjectFormState(editing = Project(id = 1, key = "WEB", name = "Web", color = "#10b981", leadUserId = 2))
        assertTrue(edit.isEdit)
        assertTrue(edit.keyValid)
        assertEquals("#10b981", edit.color)
        assertEquals(2L, edit.leadId)
    }

    @Test
    fun copyHelpers() {
        val p = Project(id = 1, key = "WEB", name = "Web")
        assertTrue(forceDeleteMessage(p, 3).startsWith("\"Web\" still contains 3 tasks.\n\n"))
        assertTrue(forceDeleteMessage(p, 1).startsWith("\"Web\" still contains 1 task.\n\n"))
        assertTrue(forceDeleteMessage(p, null).startsWith("\"Web\" still contains ? tasks."))
        assertTrue(forceDeleteMessage(p, 3).contains("they lose their WEB-N issue key"))
        assertEquals("13–24 of 30 projects", paginationSummary(30, 12, 12, "project"))
        assertEquals("1–1 of 1 task", paginationSummary(1, 25, 0, "task"))
        assertEquals(3, pageCount(30, 12))
        assertEquals(1, pageCount(0, 12))
    }

    @Test
    fun adminSectionsAreGatedByOrgAndAgileFeature() {
        val agile = mapOf("agile" to true)
        assertEquals(listOf("agile", "projects"), allowedAdminSections("hr_admin", 3, agile).map { it.key })
        assertTrue(allowedAdminSections("hr_admin", null, agile).isEmpty())
        assertTrue(allowedAdminSections("super_admin", 3, emptyMap()).isEmpty())
        assertTrue(allowedAdminSections("manager", 3, agile).isEmpty())
        assertEquals(2, allowedAdminSections("platform_admin", 3, emptyMap(), ungatedPlatformAdmin = true).size)
        val groups = groupAdminSections(allowedAdminSections("super_admin", 3, agile))
        assertEquals(listOf("Structure"), groups.map { it.first })
        assertEquals(listOf("Agile Config", "Projects"), groups.single().second.map { it.label })
        assertTrue(canOpenAdmin("hr_admin"))
        assertFalse(canOpenAdmin("manager"))
    }
}
