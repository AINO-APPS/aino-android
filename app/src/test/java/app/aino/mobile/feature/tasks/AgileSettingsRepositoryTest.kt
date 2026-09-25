package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgileSettingsRepositoryTest {
    private fun client(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) = ApiClient { request ->
        captured += request
        ApiResponse(200, emptyMap(), body(request).toByteArray())
    }

    private fun body(request: ApiRequest) = request.body!!.toString(Charsets.UTF_8)

    @Test
    fun settingsRowDecodesMixedScalesAndNullFlags() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AgileSettingsRepository(
            client(captured) {
                """
                {"org_id":1,"estimation_type":"tshirt","estimation_values":["XS","S",3,0.5],"estimation_unit_label":"SP",
                "priority_scheme":[{"key":"high","label":"High","color":"#ef4444"}],"enable_story_points":true,
                "enable_epics":null,"enable_wip_limits":true,"default_dod":null,"updated_at":"2026-09-20T10:00:00.000Z"}
                """.trimIndent()
            },
        )

        val settings = repository.loadSettings()

        assertEquals("agile/settings", captured.single().path)
        assertEquals("tshirt", settings.estimationType)
        assertEquals("XS, S, 3, 0.5", settings.valuesText())
        assertTrue(settings.flag("enable_story_points"))
        assertFalse(settings.flag("enable_epics"))
        assertTrue(settings.flag("enable_wip_limits"))
        assertNull(settings.defaultDod)
    }

    @Test
    fun saveSettingsPutsTheEditedFields() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AgileSettingsRepository(client(captured) { """{"estimation_type":"linear","estimation_values":[1,2]}""" })
        val draft = AgileSettingsRecord(estimationType = "custom", estimationValues = parseEstimationValues("1, 2.5, XL"), defaultDod = "- Tests pass")
            .withFlag("enable_blockers", true)

        repository.saveSettings(draft)

        val request = captured.single()
        assertEquals("PUT", request.method)
        assertEquals("agile/settings", request.path)
        val sent = body(request)
        assertTrue(sent.contains(""""estimation_type":"custom""""))
        assertTrue(sent.contains(""""estimation_values":[1,2.5,"XL"]"""))
        assertTrue(sent.contains(""""enable_blockers":true"""))
        assertTrue(sent.contains(""""default_dod":"- Tests pass""""))
    }

    @Test
    fun estimationPresetsAndParsing() {
        val base = AgileSettingsRecord(estimationValues = listOf(JsonPrimitive(7)))
        assertEquals("0.5, 1, 2, 3, 5, 8, 13, 21, 34", base.withEstimationType("fibonacci").valuesText())
        assertEquals("XS, S, M, L, XL, XXL", base.withEstimationType("tshirt").valuesText())
        assertEquals("", base.withEstimationType("none").valuesText())
        assertEquals("7", base.withEstimationType("custom").valuesText())
        assertEquals(listOf(JsonPrimitive(1L), JsonPrimitive(2.5), JsonPrimitive("M")), parseEstimationValues(" 1 , 2.5,, M ,"))
        assertEquals(null, parseWip(""))
        assertEquals(4, parseWip("4"))
        assertEquals(12, parseWip("12abc"))
        assertTrue(isHexColor6("#A1b2C3"))
        assertFalse(isHexColor6("#abc"))
    }

    @Test
    fun workItemTypeCrudPaths() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AgileSettingsRepository(
            client(captured) { r ->
                when {
                    r.method == "GET" -> """[{"id":1,"key":"story","name":"Story","color":"#10b981","is_default":true,"is_epic":false,"is_active":true,"sort_order":"1"},
                        {"id":2,"key":"epic","name":"Epic","icon":null,"is_epic":true,"is_active":false,"sort_order":2}]"""
                    r.method == "DELETE" || r.path.endsWith("reorder") -> """{"message":"ok"}"""
                    else -> """{"id":3,"name":"Spike"}"""
                }
            },
        )

        val types = repository.loadWorkItemTypes()
        repository.createWorkItemType(NewWorkItemType(name = "Spike", color = "#123456", isEpic = true))
        repository.updateWorkItemType(2, patchOf("is_active", true))
        repository.deleteWorkItemType(2)
        repository.reorderWorkItemTypes(listOf(2, 1))

        assertEquals(2, types.size)
        assertEquals(1, types[0].sortOrder)
        assertFalse(types[1].isActive)
        assertEquals(
            listOf("GET agile/work-item-types", "POST agile/work-item-types", "PUT agile/work-item-types/2", "DELETE agile/work-item-types/2", "PUT agile/work-item-types/reorder"),
            captured.map { "${it.method} ${it.path}" },
        )
        assertEquals("""{"name":"Spike","color":"#123456","icon":"","is_epic":true,"is_default":false,"description":""}""", body(captured[1]))
        assertEquals("""{"is_active":true}""", body(captured[2]))
        assertNull(captured[3].body)
        assertEquals("""{"order":[2,1]}""", body(captured[4]))
    }

    @Test
    fun workflowStateCrudPathsAndWipParsing() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AgileSettingsRepository(
            client(captured) { r ->
                when {
                    r.method == "GET" -> """[{"id":5,"key":"todo","name":"To Do","category":"open","wip_limit":null,"is_initial":true,"is_active":true},
                        {"id":6,"key":"doing","name":"Doing","category":"in_progress","wip_limit":"3","is_terminal":false}]"""
                    r.method == "DELETE" || r.path.endsWith("reorder") -> """{"message":"ok"}"""
                    else -> """{"id":7,"name":"Triage","category":"open"}"""
                }
            },
        )

        val states = repository.loadWorkflowStates()
        repository.createWorkflowState(NewWorkflowState(name = "Triage", wipLimit = "4", isInitial = true))
        repository.createWorkflowState(NewWorkflowState(name = "Later"))
        repository.updateWorkflowState(6, patchOf("wip_limit", null))
        repository.deleteWorkflowState(6)
        repository.reorderWorkflowStates(listOf(6, 5))

        assertEquals("", states[0].wipText())
        assertEquals("3", states[1].wipText())
        assertEquals(listOf("in_progress"), groupStatesByCategory(states)["in_progress"]!!.map { it.category })
        assertTrue(groupStatesByCategory(states)["done"]!!.isEmpty())
        assertEquals(
            """{"name":"Triage","category":"open","color":"#6b7280","wip_limit":4,"is_initial":true,"is_terminal":false}""",
            body(captured[1]),
        )
        assertTrue(body(captured[2]).contains(""""wip_limit":null"""))
        assertEquals("PUT agile/workflow-states/6", "${captured[3].method} ${captured[3].path}")
        assertEquals("""{"wip_limit":null}""", body(captured[3]))
        assertEquals("DELETE agile/workflow-states/6", "${captured[4].method} ${captured[4].path}")
        assertEquals("PUT agile/workflow-states/reorder", "${captured[5].method} ${captured[5].path}")
    }

    @Test
    fun permissionsAndAccessWorkflow() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AgileSettingsRepository(
            client(captured) { r ->
                when (r.path) {
                    "agile/permissions/me" -> """{"canEdit":true,"isSuperAdmin":false,"isReviewer":true,"requestStatus":"none","role":"team_lead"}"""
                    "agile/permissions/requests" -> """[{"id":1,"org_id":1,"user_id":4,"status":"pending","reason":"PO","username":"po","full_name":"Product Owner"}]"""
                    "agile/permissions/grants" -> """[{"id":2,"user_id":4,"granted_at":"2026-09-20","username":"po","granted_by_name":"Admin"}]"""
                    "agile/permissions/cancel-request" -> """{"cancelled":1}"""
                    "agile/permissions/grants/2" -> """{"message":"Grant revoked"}"""
                    else -> """{"id":1,"status":"approved"}"""
                }
            },
        )

        val perms = repository.loadPermissions()
        assertEquals("Editor (team lead)", perms.editorLabel())
        assertEquals("Super Admin", perms.copy(isSuperAdmin = true).editorLabel())
        assertEquals("Editor", AgilePermissions(canEdit = true).editorLabel())
        repository.requestEditAccess("Need to tune the board")
        repository.cancelAccessRequest()
        assertEquals("Product Owner", repository.loadAccessRequests().single().fullName)
        repository.reviewAccessRequest(1, "reject", "Not now")
        assertEquals("Admin", repository.loadGrants().single().grantedByName)
        repository.revokeGrant(2)

        assertEquals(
            listOf(
                "GET agile/permissions/me", "POST agile/permissions/request", "POST agile/permissions/cancel-request",
                "GET agile/permissions/requests", "PUT agile/permissions/requests/1", "GET agile/permissions/grants",
                "DELETE agile/permissions/grants/2",
            ),
            captured.map { "${it.method} ${it.path}" },
        )
        assertEquals("""{"reason":"Need to tune the board"}""", body(captured[1]))
        assertNull(captured[2].body)
        assertEquals("""{"action":"reject","reject_reason":"Not now"}""", body(captured[4]))
    }

    @Test
    fun labelManagementUsesTheTaskLabelRoutes() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AgileSettingsRepository(
            client(captured) { r ->
                when (r.method) {
                    "GET" -> """[{"id":1,"name":"frontend","color":"#0ea5e9","created_by_username":"vv"},{"id":2,"name":"tech-debt","color":"#ef4444","created_by_username":null}]"""
                    "DELETE" -> """{"message":"Label deleted"}"""
                    else -> """{"id":3,"name":"Q4-OKR","color":"#10b981"}"""
                }
            },
        )

        val labels = repository.loadManagedLabels()
        repository.createLabel("Q4-OKR", "#10b981")
        repository.updateLabel(3, "Q4 OKR", "#3b82f6")
        repository.deleteLabel(3)

        assertEquals("vv", labels[0].createdByUsername)
        assertNull(labels[1].createdByUsername)
        assertEquals(
            listOf("GET tasks/labels/manage", "POST tasks/labels", "PUT tasks/labels/3", "DELETE tasks/labels/3"),
            captured.map { "${it.method} ${it.path}" },
        )
        assertEquals("""{"name":"Q4-OKR","color":"#10b981"}""", body(captured[1]))
        assertEquals("""{"name":"Q4 OKR","color":"#3b82f6"}""", body(captured[2]))
    }

    @Test
    fun failuresSurfaceServerErrorsOrTheWebFallback() {
        val repository = AgileSettingsRepository(
            ApiClient { r ->
                if (r.path.startsWith("agile/workflow-states")) {
                    throw ApiError.Http(409, """{"error":"Cannot delete: 2 task(s) are in this state. Move them to another state first.","taskCount":2}""", r.method, r.path)
                }
                throw ApiError.Http(500, "<html>", r.method, r.path)
            },
        )
        try {
            repository.deleteWorkflowState(6)
            fail("expected TaskFailure")
        } catch (e: TaskFailure) {
            assertEquals("Cannot delete: 2 task(s) are in this state. Move them to another state first.", e.message)
            assertEquals(409, e.statusCode)
        }
        try {
            repository.saveSettings(AgileSettingsRecord())
            fail("expected TaskFailure")
        } catch (e: TaskFailure) {
            assertEquals("Failed to save", e.message)
        }
        try {
            repository.createLabel("x", "#000000")
            fail("expected TaskFailure")
        } catch (e: TaskFailure) {
            assertEquals("Failed to create label", e.message)
        }
    }

    @Test
    fun optimisticPatchesMirrorTheSentKeys() {
        val type = AdminWorkItemType(id = 1, name = "Story")
        assertEquals("Bug", type.patched(patchOf("name", "Bug")).name)
        assertFalse(type.patched(patchOf("is_active", false)).isActive)
        val state = AdminWorkflowState(id = 1, wipLimit = 3.0)
        assertNull(state.patched(patchOf("wip_limit", null)).wipLimit)
        assertEquals(5.0, state.patched(patchOf("wip_limit", 5)).wipLimit!!, 0.0)
        assertTrue(state.patched(patchOf("is_terminal", true)).isTerminal)
    }
}
