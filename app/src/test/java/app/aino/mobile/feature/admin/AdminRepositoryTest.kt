package app.aino.mobile.feature.admin

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

internal fun capturingClient(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) = ApiClient { request ->
    captured += request
    ApiResponse(200, emptyMap(), body(request).toByteArray())
}

internal fun ApiRequest.text() = body!!.toString(Charsets.UTF_8)

internal fun List<ApiRequest>.calls() = map { "${it.method} ${it.path}" }

class AdminRepositoryTest {
    @Test
    fun usersQueryEncodesFiltersAndDecodesPage() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(
            capturingClient(captured) {
                """{"data":[{"id":4,"username":"ann","full_name":"Ann Lee","role":"manager","is_active":false,"team_id":null}],"total":"61","page":2}"""
            },
        )

        val page = repository.users(UserFilters(search = " ann lee ", role = "manager", status = "false"), page = 2)

        assertEquals("admin/users?search=ann+lee&role=manager&is_active=false&page=2&per_page=50", captured.single().path)
        assertEquals(61, page.total)
        val user = page.data.single()
        assertEquals("Ann Lee", user.fullName)
        assertFalse(user.isActive)
        assertNull(user.teamId)
    }

    @Test
    fun auditLogsSkipBlankFilters() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(
            capturingClient(captured) { """{"total":3,"logs":[{"id":1,"action":"login","entity_id":42}]}""" },
        )

        val page = repository.auditLogs(AuditFilters(action = "login", from = "2026-09-01"), limit = 50, offset = 50)

        assertEquals("admin/audit-logs?action=login&from=2026-09-01&limit=50&offset=50", captured.single().path)
        assertEquals(3, page.total)
        assertEquals("42", page.logs.single().entityIdText)
    }

    @Test
    fun userMutationsHitWebRoutesWithWebBodies() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(capturingClient(captured) { """{"message":"ok"}""" })

        repository.createUser(NewUserDraft(fullName = " Ann ", username = "ann", email = "a@x.io", role = "employee", orgId = 3))
        repository.changeRole(4, "manager", "  ")
        repository.updateAssignment(4, 3, null, 9, null)
        repository.toggleActive(4)
        repository.resetPassword(4, "Secret123!")
        repository.resetFaceEnrollment(4)
        repository.deleteUser(4)

        assertEquals(
            listOf(
                "POST admin/users", "PUT admin/users/4/role", "PUT admin/users/4/assignment",
                "PUT admin/users/4/deactivate", "POST admin/users/4/reset-password",
                "DELETE admin/users/4/face-enroll", "DELETE admin/users/4",
            ),
            captured.calls(),
        )
        val create = captured[0].text()
        assertTrue(create.contains("\"full_name\":\"Ann\""))
        assertFalse("blank password is omitted", create.contains("password"))
        assertTrue(create.contains("\"department_id\":null"))
        assertTrue(captured[1].text().contains("\"reason\":null"))
        assertTrue(captured[2].text().contains("\"team_id\":9"))
        assertTrue(captured[4].text().contains("\"new_password\":\"Secret123!\""))
        assertNull("DELETE is sent without a body", captured[6].body)
    }

    @Test
    fun homeSummaryCountsIndependentlyAndTreatsFailuresAsEmpty() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(
            ApiClient { request ->
                captured += request
                when (request.path) {
                    "admin/role-requests?status=pending" -> ApiResponse(200, emptyMap(), "[{},{}]".toByteArray())
                    "org/departments" -> ApiResponse(200, emptyMap(), """[{"id":1}]""".toByteArray())
                    "org/teams" -> ApiResponse(200, emptyMap(), """{"data":[]}""".toByteArray())
                    else -> throw ApiError.Http(500, "{}", request.method, request.path)
                }
            },
        )

        val summary = repository.homeSummary(hasOrg = true)

        assertEquals(2, summary.pendingRoleRequests)
        assertTrue(summary.hasDept)
        assertFalse(summary.hasTeam)
        assertFalse(summary.hasPolicy)
        assertFalse(summary.tzSet)
        assertTrue(captured.any { it.path == "leave-policy/policies" })

        captured.clear()
        assertEquals(2, repository.homeSummary(hasOrg = false).pendingRoleRequests)
        assertEquals(1, captured.size)
    }

    @Test
    fun serverErrorMessageIsSurfaced() {
        val repository = AdminRepository(
            ApiClient { request -> throw ApiError.Http(403, """{"error":"Only super admins"}""", request.method, request.path) },
        )
        try {
            repository.deleteUser(1)
            fail("expected AdminFailure")
        } catch (e: AdminFailure) {
            assertEquals(403, e.statusCode)
            assertEquals("Only super admins", e.adminMessage("fallback"))
        }
        assertEquals("fallback", IllegalStateException().adminMessage("fallback"))
    }
}
