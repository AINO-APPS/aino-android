package app.aino.mobile.feature.profile

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProfileRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> String) =
        ProfileRepository(
            ApiClient { request ->
                captured += request
                ApiResponse(200, emptyMap(), body(request).toByteArray())
            },
        )

    @Test
    fun decodesTheTenantProfileIncludingDerivedFields() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"id":3,"username":"vvronline","full_name":"Vishnu V R","email":"v@aino.org.in",
               "role":"super_admin","org_id":1,"team_id":2,"team_name":"Platform","tenant_id":7,
               "has_reports":true,"must_change_password":false,"tenant_plan":"standard",
               "tenant_features":{"agile":true,"webhooks":false}}"""
        }

        val user = repository.load()

        assertEquals("profile", captured.single().path)
        assertEquals("Vishnu V R", user.display())
        assertEquals("Platform", user.teamName)
        assertEquals(true, user.hasReports)
        assertEquals(true, user.tenantFeatures["agile"])
        assertEquals(false, user.tenantFeatures["webhooks"])
    }

    @Test
    fun profileAndEmailUseSeparatePutRoutes() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { """{"id":3,"username":"vvronline"}""" }

        repository.updateProfile("  Vishnu V R  ", "  vvronline  ")
        repository.updateEmail("  v@aino.org.in ")

        assertEquals("PUT", captured[0].method)
        assertEquals("profile", captured[0].path)
        // Whitespace is trimmed before it reaches the uniqueness check.
        assertEquals(
            """{"full_name":"Vishnu V R","username":"vvronline"}""",
            captured[0].body!!.toString(Charsets.UTF_8),
        )
        assertEquals("PUT", captured[1].method)
        assertEquals("profile/email", captured[1].path)
        assertEquals("""{"email":"v@aino.org.in"}""", captured[1].body!!.toString(Charsets.UTF_8))
    }

    @Test
    fun searchShortCircuitsBelowTwoCharactersWithoutARequest() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) { "{}" }

        val results = repository.search("a")

        assertTrue("no request should be issued", captured.isEmpty())
        assertTrue(results.isEmpty)
    }

    @Test
    fun searchEncodesTheTermAndDecodesEveryBucket() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured) {
            """{"tasks":[{"id":1,"title":"Ship release","snippet":"Ship the <b>release</b>"}],
               "notes":[{"id":"p1","title":"Standup","snippet":"release notes"}],
               "users":[{"id":4,"username":"asha","full_name":"Asha K","role":"manager"}],
               "events":[{"id":5,"title":"Release review","start_time":"2026-09-20T09:00:00Z"}],
               "leaves":[{"id":6,"date":"2026-09-21","leave_type":"casual","status":"approved"}],
               "sprints":[{"id":7,"name":"Sprint 4","status":"active"}],
               "logs":[{"id":8,"action":"update","entity_type":"task","actor_name":"Asha K"}]}"""
        }

        val results = repository.search("release plan")

        assertEquals("search?q=release+plan", captured.single().path)
        assertEquals(7, results.total)
        assertEquals("Ship the release", plainSnippet(results.tasks.single().snippet))
        assertEquals("Asha K", results.users.single().display())
        assertEquals("Sprint 4", results.sprints.single().name)
    }

    @Test
    fun surfacesTheServerUniquenessMessage() {
        val repository = ProfileRepository(
            ApiClient {
                throw ApiError.Http(
                    400,
                    """{"error":"Username already taken"}""",
                    "PUT",
                    "https://next.aino.org.in/api/profile",
                )
            },
        )

        try {
            repository.updateProfile("Vishnu", "taken")
            fail("expected a ProfileFailure")
        } catch (failure: ProfileFailure) {
            assertEquals(400, failure.statusCode)
            assertEquals("Username already taken", failure.message)
        }
    }
}
