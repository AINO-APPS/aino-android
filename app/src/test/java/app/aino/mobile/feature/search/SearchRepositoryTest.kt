package app.aino.mobile.feature.search

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRepositoryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: String) = SearchRepository(
        ApiClient { request ->
            captured += request
            ApiResponse(200, emptyMap(), body.toByteArray())
        },
    )

    @Test
    fun searchSendsTheTrimmedEncodedTermAndDecodesAllBuckets() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(
            captured,
            """{"tasks":[{"id":5,"title":"Fix login","description":"x","status":"in_progress","priority":"high",
                         "date":"2026-09-01","due_date":null,"sprint_id":null,"snippet":"<b>Fix</b> login"}],
                "notes":[{"id":"pg_1","title":"Plan","snippet":"…the plan","tags":[],"pinned":false,"folderId":null,"updatedAt":null}],
                "users":[{"id":8,"username":"ana","full_name":"Ana B","email":"a@x.io","avatar":"/uploads/a.png","role":"team_lead"}],
                "events":[{"id":3,"title":"Review","description":null,"start_time":"2026-09-25T04:00:00.000Z","end_time":null,"all_day":false}],
                "leaves":[{"id":4,"date":"2026-10-01","leave_type":"sick","duration":"half","status":"pending","reason":null}],
                "sprints":[{"id":6,"name":"S12","goal":null,"status":"active","start_date":"2026-09-01","end_date":"2026-09-14"}],
                "logs":[{"id":7,"action":"user.update","entity_type":"user","entity_id":8,"details":"{}","created_at":"2026-09-20T10:00:00.000Z","actor_name":"Root"}]}""",
        )

        val results = repository.search("  fix login & more  ")

        val request = captured.single()
        assertEquals("GET", request.method)
        assertEquals("search?q=fix%20login%20%26%20more", request.path)
        assertEquals("5", results.tasks.single().id)
        assertEquals("in_progress", results.tasks.single().status)
        assertEquals("pg_1", results.notes.single().id)
        assertEquals("Ana B", results.users.single().fullName)
        assertFalse(results.events.single().allDay)
        assertEquals("half", results.leaves.single().duration)
        assertEquals("2026-09-14", results.sprints.single().endDate)
        assertEquals("Root", results.logs.single().actorName)
        assertFalse(results.isEmpty())
    }

    @Test
    fun shortTermsSkipTheRequestAndLongTermsAreCapped() {
        val captured = mutableListOf<ApiRequest>()
        val repository = repository(captured, "{}")

        assertTrue(repository.search(" a ").isEmpty())
        assertTrue(captured.isEmpty())

        repository.search("x".repeat(150))
        assertEquals("search?q=" + "x".repeat(100), captured.single().path)
    }

    @Test
    fun missingBucketsDecodeAsEmpty() {
        val results = repository(mutableListOf(), """{"tasks":[]}""").search("hello")
        assertTrue(results.isEmpty())
    }
}
