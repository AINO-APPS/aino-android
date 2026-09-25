package app.aino.mobile.feature.search

import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchModelsTest {
    @Test
    fun minLengthRules() {
        assertFalse(isSearchable(""))
        assertFalse(isSearchable(" a "))
        assertTrue(isSearchable("ab"))
        assertEquals("ab", searchTerm("  ab  "))
        assertEquals(100, searchTerm("y".repeat(120)).length)
        assertTrue(navResults("a", "super_admin").isEmpty())
    }

    @Test
    fun navIndexIsRoleGated() {
        val employee = visibleNav("employee").map { it.title }
        assertTrue("Dashboard" in employee)
        assertTrue("Organization" in employee)
        assertFalse("Manager Dashboard" in employee)
        assertFalse("Admin Panel" in employee)

        val lead = visibleNav("team_lead").map { it.title }
        assertTrue("Manager Dashboard" in lead)
        assertFalse("Admin Panel" in lead)

        val platform = visibleNav("platform_admin").map { it.title }
        assertTrue("Tenant Management" in platform)
        assertFalse("Organization" in platform)

        // Unknown role falls back to level 1.
        assertEquals(employee, visibleNav(null).map { it.title })
    }

    @Test
    fun navResultsMatchTitleSubOrKeywordsAndCapAtSix() {
        assertEquals(listOf("/calendar", "/attendance", "/attendance#leaves"), navResults("calendar", "employee").map { it.path })
        assertEquals(listOf("Leaves", "Leave Policy", "Holidays"), navResults("HOLIDAY", "employee").map { it.title })
        assertEquals(6, navResults("leave", "hr_admin").size)
    }

    @Test
    fun linksMirrorNavigateToItem() {
        assertEquals("/attendance#leaves", searchLink(SearchKind.Nav, "", "/attendance#leaves"))
        assertEquals("/tasks?taskId=5", searchLink(SearchKind.Task, "5"))
        assertEquals("/notes?pageId=pg_1", searchLink(SearchKind.Note, "pg_1"))
        assertEquals("/calendar", searchLink(SearchKind.Event, "3"))
        assertEquals("/attendance#leaves", searchLink(SearchKind.Leave, "4"))
        assertEquals("/manager", searchLink(SearchKind.Sprint, "6"))
        assertEquals("/admin?tab=users&userId=8", searchLink(SearchKind.User, "8"))
        assertEquals("/admin?tab=audit", searchLink(SearchKind.Log, "7"))
    }

    @Test
    fun sectionsFollowWebOrderLabelsAndCopy() {
        val results = SearchResults(
            tasks = listOf(SearchTask("5", "Fix login", "<b>Fix</b> login", "in_progress")),
            notes = listOf(SearchNote("pg_1", "Plan", "…the plan")),
            users = listOf(SearchUser("8", "ana", "Ana B", "a@x.io", "ana.png", "team_lead")),
            events = listOf(
                SearchEvent("3", "Review", "d".repeat(80), "2026-09-25T04:05:00.000Z", allDay = false),
                SearchEvent("9", "Offsite", null, "2026-09-26T00:00:00.000Z", allDay = true),
            ),
            leaves = listOf(SearchLeave("4", "2026-10-01", "sick", "half", "pending", "flu")),
            sprints = listOf(SearchSprint("6", "S12", null, "active", "2026-09-01", "2026-09-14")),
            logs = listOf(SearchLog("7", "user.update", "user", "Root", "2026-09-20T10:00:00.000Z")),
        )
        val sections = searchSections(navResults("tasks", "employee"), results, ZoneOffset.UTC, Locale.US)

        assertEquals(
            listOf("Pages & Features", "Tasks", "Notes", "Calendar Events", "Leave Requests", "Sprints", "People", "Audit Logs"),
            sections.map { it.title },
        )
        val nav = sections[0].rows.single()
        assertEquals("Tasks", nav.title)
        assertEquals("Go", nav.badge?.text)
        assertTrue(nav.badge!!.go)

        val task = sections[1].rows.single()
        assertEquals("in progress", task.badge?.text)
        assertEquals(0xFF93C5FD, task.badge?.fg)
        assertEquals(0xFF1E3A5F, task.badge?.bg)
        assertTrue(task.snippetHtml)

        val (timed, allDay) = sections[3].rows
        assertEquals("9/25/2026 04:05 AM · " + "d".repeat(60), timed.snippet?.replace('\u202F', ' '))
        assertEquals("9/26/2026", allDay.snippet)

        val leave = sections[4].rows.single()
        assertEquals("Sick leave — 2026-10-01", leave.title)
        assertEquals("half day · flu", leave.snippet)
        assertEquals("pending", leave.badge?.text)
        assertEquals(0xFFD97706, leave.badge?.fg)

        val sprint = sections[5].rows.single()
        assertEquals("2026-09-01 → 2026-09-14", sprint.snippet)
        assertEquals(0xFF16A34A, sprint.badge?.fg)

        val person = sections[6].rows.single()
        assertEquals("Team Lead", person.badge?.text)
        assertEquals("/uploads/avatars/ana.png", person.avatarUrl)
        assertEquals("a@x.io", person.snippet)

        val log = sections[7].rows.single()
        assertEquals("user.update — user", log.title)
        assertEquals("by Root · 9/20/2026", log.snippet)
    }

    @Test
    fun emptyBucketsAreOmittedAndUnknownRolesPassThrough() {
        val sections = searchSections(emptyList(), SearchResults(users = listOf(SearchUser("1", "x", null, null, null, "contractor"))))
        assertEquals(listOf("People"), sections.map { it.title })
        val row = sections.single().rows.single()
        assertEquals("x", row.title)
        assertEquals("contractor", row.badge?.text)
        assertNull(row.avatarUrl)
        assertTrue(searchSections(emptyList(), null).isEmpty())
        assertEquals("/uploads/t1/a.png", searchAvatarPath("/uploads/t1/a.png"))
    }

    @Test
    fun snippetHighlightsParseBoldRuns() {
        assertEquals(
            listOf(SnippetRun("Fix ", false), SnippetRun("login", true), SnippetRun(" & page", false)),
            parseSnippetHighlights("Fix <b>login</b> &amp; <i>page</i>"),
        )
        assertEquals(listOf(SnippetRun("plain", false)), parseSnippetHighlights("plain"))
    }

    @Test
    fun webLocaleFormats() {
        val instant = Instant.parse("2026-01-05T15:07:00Z")
        assertEquals("1/5/2026", webLocaleDate(instant, ZoneOffset.UTC, Locale.US))
        assertEquals("03:07 PM", webLocaleTime(instant, ZoneOffset.UTC, Locale.US).replace('\u202F', ' '))
    }
}
