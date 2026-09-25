package app.aino.mobile.feature.notes

import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesTemplatesTest {
    private val clock = NotesClock(ZonedDateTime.parse("2026-09-25T10:00:00Z[UTC]"), Locale.US)

    @Test
    fun templateTitlesAndFolders() {
        assertEquals("Journal — 2026-09-25", noteTemplate("journal").title(clock))
        assertEquals("Journal", noteTemplate("journal").folderName)
        assertEquals("Meeting notes — Sep 25, 2026", noteTemplate("meeting").title(clock))
        assertEquals("Untitled", noteTemplate("nope").title(clock))
        assertEquals("", noteTemplate("blank").html(clock))
        assertEquals(listOf("blank", "journal", "meeting", "decision", "weekly", "oneonone", "retro"), NOTE_TEMPLATES.map { it.id })
        assertTrue(noteTemplate("journal").html(clock).startsWith("\n      <h2>September 25, 2026</h2>\n      <h3>How I'm feeling</h3>"))
    }

    @Test
    fun journalPrefillMatchesTheWebBuilder() {
        val prefill = JournalPrefill(
            tasks = listOf(PrefillTask("Ship <it>", "done"), PrefillTask("Review", "in_progress")),
            hoursWorked = 4.0,
            meetings = listOf(PrefillMeeting("Standup", "2026-09-25T09:30:00.000Z")),
            events = listOf(PrefillEvent("Ignored when meetings exist", true, null)),
        )
        assertEquals(
            listOf(
                "<h2>September 25, 2026</h2>",
                "<div class=\"ql-callout\" data-callout=\"info\"><p>⏱ <strong>4h</strong> tracked today</p></div>",
                "<h3>How I'm feeling</h3><p><br></p>",
                "<h3>What I worked on</h3>",
                "<ul>",
                "<li data-list=\"checked\">Ship &lt;it&gt;</li>",
                "<li data-list=\"unchecked\">Review <em>(in_progress)</em></li>",
                "</ul>",
                "<h3>Meetings</h3>",
                "<ul><li>📅 <strong>Standup</strong> at 09:30 AM</li></ul>",
                "<h3>Wins</h3><ul><li><br></li></ul>",
                "<h3>Tomorrow</h3><ul><li data-list=\"unchecked\"><br></li></ul>",
            ).joinToString("\n"),
            buildJournalPrefillHtml(prefill, clock),
        )
    }

    @Test
    fun journalPrefillWithoutDataAndWithEvents() {
        val html = buildJournalPrefillHtml(JournalPrefill(hoursWorked = 2.5, events = listOf(PrefillEvent("Offsite", true, null))), clock)
        assertTrue(html.contains("<strong>2.5h</strong>"))
        assertTrue(html.contains("<h3>What I worked on</h3>\n<ul><li><br></li></ul>"))
        assertTrue(html.contains("<h3>Events</h3>\n<ul><li>Offsite — All day</li></ul>"))
        // The prefilled page parses into editable blocks (callout stays read-only) and round-trips.
        val doc = parseNoteDoc(html)
        assertEquals(html, doc.toHtml())
        assertTrue(doc.blocks.any { it.opaqueKind == OpaqueKind.CALLOUT })
    }

    @Test
    fun oneOnOnePrefillMatchesTheWebBuilder() {
        val prefill = OneOnOnePrefill(
            reportName = "Ann",
            tasks = listOf(PrefillTask("A", "done"), PrefillTask("B", "in_progress"), PrefillTask("C", "pending")),
            leaves = listOf(PrefillLeave("2026-09-20", "sick", "full_day", "approved")),
            sprint = PrefillSprint("Sprint 9", listOf("done" to 3, "in_progress" to 1, "pending" to 0)),
            hoursThisWeek = 12.5,
        )
        assertEquals(
            listOf(
                "<h2>1-on-1 — Sep 25, 2026</h2>",
                "<p><strong>With:</strong> Ann</p>",
                "<div class=\"ql-callout\" data-callout=\"info\"><p>📊 Ann logged <strong>12.5h</strong> this week</p></div>",
                "<h3>Recent task activity</h3>",
                "<p><strong>Completed (1):</strong></p>", "<ul>", "<li data-list=\"checked\">A</li>", "</ul>",
                "<p><strong>In progress (1):</strong></p>", "<ul>", "<li>🔵 B</li>", "</ul>",
                "<p><strong>Pending (1):</strong></p>", "<ul>", "<li>⬜ C</li>", "</ul>",
                "<h3>Sprint: Sprint 9</h3>",
                "<p>Progress: <strong>3/4</strong> tasks done (75%)</p>",
                "<p>🔵 1 in progress</p>",
                "<h3>Recent leaves</h3>",
                "<ul><li>2026-09-20 — sick (full_day, approved)</li></ul>",
                "<h3>Wins / highlights</h3><ul><li><br></li></ul>",
                "<h3>Blockers</h3><ul><li><br></li></ul>",
                "<h3>Feedback</h3><p><br></p>",
                "<h3>Career / growth</h3><p><br></p>",
                "<h3>Action items</h3><ul><li data-list=\"unchecked\"><br></li></ul>",
            ).joinToString("\n"),
            buildOneOnOnePrefillHtml(prefill, clock),
        )
        assertEquals("1-on-1 with Ann — Sep 25, 2026", oneOnOneTitle("Ann", clock))
        assertEquals("1-on-1 with Team member — Sep 25, 2026", oneOnOneTitle(null, clock))
    }

    @Test
    fun emptyOneOnOnePrefill() {
        val html = buildOneOnOnePrefillHtml(OneOnOnePrefill(), clock)
        assertTrue(html.contains("<p><strong>With:</strong> Team member</p>"))
        assertTrue(html.contains("<p><em>No recent tasks</em></p>"))
        assertTrue(!html.contains("ql-callout"))
    }

    @Test
    fun helpers() {
        assertEquals("3", jsNumber(3.0))
        assertEquals("3.5", jsNumber(3.5))
        assertEquals("0", jsNumber(0.0))
        assertEquals("Sep 25, 2026, 10:00 AM", clock.timestamp())
        assertEquals("<p><strong>Sep 25, 10:00 AM</strong></p><p>a &lt;b&gt;<br>c</p><p><br></p>", inboxCaptureHtml(" a <b>\nc ", clock))
    }
}
