package app.aino.mobile.feature.notes

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotesRepositoryTest {
    private val captured = mutableListOf<ApiRequest>()
    private fun repository(body: (ApiRequest) -> String = { "{}" }) = NotesRepository(
        ApiClient { request ->
            captured += request
            ApiResponse(200, emptyMap(), body(request).toByteArray())
        },
    )

    private val ApiRequest.text get() = body?.toString(Charsets.UTF_8)

    @Test
    fun loadAndSaveUseTheWholeNotebookEnvelope() {
        val repo = repository { if (it.method == "GET") """{"data":{"pages":[{"id":"a"}],"x":1},"updatedAt":"t"}""" else """{"ok":true}""" }
        val nb = repo.load()!!
        assertEquals("notes", captured[0].path)
        assertEquals("GET", captured[0].method)
        assertEquals(listOf("a"), nb.pages().map { it.id })
        repo.save(nb)
        assertEquals("PUT", captured[1].method)
        assertEquals("notes", captured[1].path)
        assertEquals("""{"data":{"pages":[{"id":"a"}],"x":1}}""", captured[1].text)
    }

    @Test
    fun loadReturnsNullForAFreshUser() {
        assertNull(repository { """{"data":null}""" }.load())
    }

    @Test
    fun historyAndSnapshots() {
        val repo = repository {
            when {
                it.path.startsWith("notes/history/snapshot/") -> """{"snapshot":{"id":9,"page_id":"p 1","page_title":"T","content":"<p>x</p>","saved_at":"s"}}"""
                else -> """{"history":[{"id":9,"page_title":"T","saved_at":"s"}]}"""
            }
        }
        assertEquals(listOf(HistoryRow(9, "T", "s")), repo.history("p 1"))
        assertEquals("notes/history/p%201", captured[0].path)
        assertEquals("<p>x</p>", repo.snapshot(9)!!.content)
        assertEquals("notes/history/snapshot/9", captured[1].path)
    }

    @Test
    fun mentionsAndUsers() {
        val repo = repository { """{"users":[{"id":3,"full_name":"Ann","avatar":null,"username":"ann"}],"reports":[{"id":4,"full_name":"Bo","username":"bo"}]}""" }
        assertEquals(listOf(MentionUser(3, "Ann", "ann", null)), repo.mentionableUsers())
        assertEquals("notes/mentionable-users", captured[0].path)
        repo.sendMention(3, "p", "Page")
        assertEquals("POST", captured[1].method)
        assertEquals("notes/mention", captured[1].path)
        assertEquals("""{"mentionedUserId":3,"pageId":"p","pageTitle":"Page"}""", captured[1].text)
        assertEquals(4L, repo.directReports().single().id)
        assertEquals("notes/direct-reports", captured[2].path)
    }

    @Test
    fun linksCrud() {
        val repo = repository { """{"links":[{"id":1,"entity_type":"task","entity_id":12,"created_at":"c","detail":{"id":12,"title":"Fix"}},{"id":2,"entity_type":"meeting","entity_id":5,"detail":null}]}""" }
        val links = repo.links("pg")
        assertEquals("notes/links/pg", captured[0].path)
        assertEquals(12L, links[0].entityId)
        assertEquals("Fix", links[0].detail!!.str("title"))
        assertNull(links[1].detail)
        repo.addLink("pg", "calendar_event", 8)
        assertEquals("POST", captured[1].method)
        assertEquals("notes/links", captured[1].path)
        assertEquals("""{"pageId":"pg","entityType":"calendar_event","entityId":8}""", captured[1].text)
        repo.removeLink("pg", "task", 12)
        assertEquals("DELETE", captured[2].method)
        assertEquals("notes/links", captured[2].path)
        assertEquals("""{"pageId":"pg","entityType":"task","entityId":12}""", captured[2].text)
    }

    @Test
    fun searchEndpointsEncodeTheQuery() {
        val repo = repository { """{"tasks":[{"id":1,"title":"T"}],"meetings":[{"id":2,"title":"M","meeting_code":"abc"}],"events":[{"id":3,"title":"E"}]}""" }
        assertEquals("T", repo.searchTasks("a b&c").single().title)
        assertEquals("notes/search-tasks?q=a%20b%26c", captured[0].path)
        assertEquals("abc", repo.searchMeetings("").single().raw.str("meeting_code"))
        assertEquals("notes/search-meetings?q=", captured[1].path)
        assertEquals(3L, repo.searchEvents("x").single().id)
        assertEquals("notes/search-events?q=x", captured[2].path)
    }

    @Test
    fun prefillsEmbedsAndConvertToTask() {
        val repo = repository {
            when (it.path) {
                "notes/daily-prefill" -> """{"tasks":[{"id":1,"title":"T","status":"done"}],"hoursWorked":3.5,"timeEntries":true,"meetings":[],"events":[{"title":"E","all_day":true}],"date":"2026-09-25"}"""
                "notes/oneonone-prefill/42" -> """{"report":{"id":42,"fullName":"Ann"},"tasks":[],"leaves":[],"sprint":{"name":"S1","taskBreakdown":[{"status":"done","count":2}]},"hoursThisWeek":10}"""
                "notes/time-summary" -> """{"hoursWorked":7.2,"breakHours":0.5,"firstClockIn":"2026-09-25T03:30:00.000Z","lastClockOut":null,"workMode":"remote","isActive":true}"""
                "notes/sprint-embed" -> """{"sprint":{"id":1,"name":"S"},"tasks":[{"id":1,"title":"t","status":"done"}],"stats":{"total":1,"done":1}}"""
                "notes/convert-to-task" -> """{"task":{"id":77,"title":"Do it"}}"""
                else -> "{}"
            }
        }
        val daily = repo.dailyPrefill()
        assertEquals(3.5, daily.hoursWorked!!, 0.0)
        assertTrue(daily.events.single().allDay)
        val one = repo.oneOnOnePrefill(42)
        assertEquals("Ann", one.reportName)
        assertEquals(listOf("done" to 2), one.sprint!!.breakdown)
        val time = repo.timeSummary()
        assertEquals(7.2, time.hoursWorked, 0.0)
        assertTrue(time.isActive)
        assertNull(time.lastClockOut)
        assertEquals("S", repo.sprintEmbed().sprint!!.str("name"))
        assertEquals(ConvertedTask(77, "Do it"), repo.convertToTask("Do it", "p", "Page"))
        val convert = captured.last()
        assertEquals("POST", convert.method)
        assertEquals("""{"title":"Do it","pageId":"p","pageTitle":"Page"}""", convert.text)
    }

    @Test
    fun shareLifecycle() {
        val repo = repository {
            when (it.method) {
                "GET" -> """{"token":null}"""
                "POST" -> """{"token":"tok","url":"https://app/n/tok"}"""
                else -> """{"ok":true}"""
            }
        }
        assertNull(repo.share("p").token)
        assertEquals("notes/share/p", captured[0].path)
        assertEquals(ShareState("tok", "https://app/n/tok"), repo.createShare("p"))
        assertEquals("POST", captured[1].method)
        assertEquals(0, captured[1].body!!.size)
        repo.revokeShare("p")
        assertEquals("DELETE", captured[2].method)
        assertEquals("notes/share/p", captured[2].path)
    }

    @Test
    fun serverErrorsAreSurfaced() {
        val repo = NotesRepository(ApiClient { throw ApiError.Http(400, """{"error":"Notebook data too large (max 2 MB)"}""", "PUT", "u") })
        try {
            repo.save(Json.parseToJsonElement("{}").jsonObject)
            fail("expected failure")
        } catch (e: NotesFailure) {
            assertEquals(NOTEBOOK_TOO_LARGE, e.message)
            assertEquals(400, e.statusCode)
        }
        assertEquals(JsonPrimitive(1), JsonPrimitive(1))
    }
}
