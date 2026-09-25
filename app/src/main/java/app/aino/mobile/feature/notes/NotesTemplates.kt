package app.aino.mobile.feature.notes

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Date/time source for templates, injectable for tests. */
data class NotesClock(
    val now: ZonedDateTime = ZonedDateTime.now(),
    val locale: Locale = Locale.getDefault(),
) {
    val zone: ZoneId get() = now.zone

    /** `toLocaleDateString(undefined, { month: "long", day: "numeric", year: "numeric" })`. */
    fun todayLabel(): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(now)

    /** `toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })`. */
    fun todayShort(): String = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(now)

    fun todayIso(): String = now.toLocalDate().toString()

    /** `toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" })`. */
    fun time(iso: String?): String {
        val millis = parseIsoMillis(iso) ?: return "Invalid Date"
        return DateTimeFormatter.ofPattern("hh:mm a", locale).format(Instant.ofEpochMilli(millis).atZone(zone))
    }

    /** Slash `/timestamp`: `{ year, month: "short", day, hour: "2-digit", minute: "2-digit" }`. */
    fun timestamp(): String = DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a", locale).format(now)

    /** Quick capture header: `{ month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }`. */
    fun captureStamp(): String = DateTimeFormatter.ofPattern("MMM d, hh:mm a", locale).format(now)
}

data class NoteTemplate(
    val id: String,
    val name: String,
    val description: String,
    val folderName: String? = null,
    val title: (NotesClock) -> String,
    val html: (NotesClock) -> String,
)

private fun block(vararg lines: String): String = lines.joinToString("") { "\n      $it" } + "\n    "

val NOTE_TEMPLATES: List<NoteTemplate> = listOf(
    NoteTemplate("blank", "Blank page", "Start from scratch.", title = { "Untitled" }, html = { "" }),
    NoteTemplate(
        "journal", "Today's journal", "A daily journal entry for today.", folderName = "Journal",
        title = { "Journal — ${it.todayIso()}" },
        html = {
            block(
                "<h2>${it.todayLabel()}</h2>",
                "<h3>How I'm feeling</h3>",
                "<p><br></p>",
                "<h3>What I worked on</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Wins</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Tomorrow</h3>",
                "<ul><li data-list=\"unchecked\"><br></li></ul>",
            )
        },
    ),
    NoteTemplate(
        "meeting", "Meeting notes", "Agenda, discussion, action items.",
        title = { "Meeting notes — ${it.todayShort()}" },
        html = {
            block(
                "<h2>Meeting notes — ${it.todayShort()}</h2>",
                "<p><strong>Attendees:</strong> </p>",
                "<p><strong>Date / time:</strong> ${it.todayLabel()}</p>",
                "<h3>Agenda</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Discussion</h3>",
                "<p><br></p>",
                "<h3>Decisions</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Action items</h3>",
                "<ul><li data-list=\"unchecked\"><br></li></ul>",
            )
        },
    ),
    NoteTemplate(
        "decision", "Decision log", "Capture a decision and its rationale.",
        title = { "Decision — ${it.todayShort()}" },
        html = {
            block(
                "<h2>Decision — ${it.todayShort()}</h2>",
                "<p><strong>Status:</strong> Proposed</p>",
                "<p><strong>Owner:</strong> </p>",
                "<p><strong>Stakeholders:</strong> </p>",
                "<h3>Context</h3>",
                "<p><br></p>",
                "<h3>Options considered</h3>",
                "<ol><li><br></li></ol>",
                "<h3>Decision</h3>",
                "<p><br></p>",
                "<h3>Consequences</h3>",
                "<ul><li><br></li></ul>",
            )
        },
    ),
    NoteTemplate(
        "weekly", "Weekly review", "Reflect on the week and plan ahead.",
        title = { "Weekly review — ${it.todayShort()}" },
        html = {
            block(
                "<h2>Weekly review — ${it.todayShort()}</h2>",
                "<h3>Wins of the week</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Challenges</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Lessons learned</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Priorities for next week</h3>",
                "<ul><li data-list=\"unchecked\"><br></li></ul>",
            )
        },
    ),
    NoteTemplate(
        "oneonone", "1-on-1", "Talking points for a 1:1 conversation.",
        title = { "1-on-1 — ${it.todayShort()}" },
        html = {
            block(
                "<h2>1-on-1 — ${it.todayShort()}</h2>",
                "<p><strong>With:</strong> </p>",
                "<h3>Wins / highlights</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Blockers</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Feedback</h3>",
                "<p><br></p>",
                "<h3>Career / growth</h3>",
                "<p><br></p>",
                "<h3>Action items</h3>",
                "<ul><li data-list=\"unchecked\"><br></li></ul>",
            )
        },
    ),
    NoteTemplate(
        "retro", "Retrospective", "What went well, what didn't, action items.",
        title = { "Retrospective — ${it.todayShort()}" },
        html = {
            block(
                "<h2>Retrospective — ${it.todayShort()}</h2>",
                "<h3>What went well 🟢</h3>",
                "<ul><li><br></li></ul>",
                "<h3>What didn't go well 🔴</h3>",
                "<ul><li><br></li></ul>",
                "<h3>What we learned 💡</h3>",
                "<ul><li><br></li></ul>",
                "<h3>Action items 🎯</h3>",
                "<ul><li data-list=\"unchecked\"><br></li></ul>",
            )
        },
    ),
)

fun noteTemplate(id: String): NoteTemplate = NOTE_TEMPLATES.firstOrNull { it.id == id } ?: NOTE_TEMPLATES.first()

// ── Prefill payloads (GET notes/daily-prefill, notes/oneonone-prefill/:userId) ──

data class PrefillTask(val title: String?, val status: String?)
data class PrefillMeeting(val title: String?, val scheduledStart: String?)
data class PrefillEvent(val title: String?, val allDay: Boolean, val startTime: String?)
data class PrefillLeave(val date: String?, val leaveType: String?, val duration: String?, val status: String?)
data class PrefillSprint(val name: String?, val breakdown: List<Pair<String, Int>>)

data class JournalPrefill(
    val tasks: List<PrefillTask> = emptyList(),
    val hoursWorked: Double? = null,
    val meetings: List<PrefillMeeting> = emptyList(),
    val events: List<PrefillEvent> = emptyList(),
) {
    companion object {
        fun from(json: JsonObject): JournalPrefill = JournalPrefill(
            tasks = json.objects("tasks").map { PrefillTask(it.str("title"), it.str("status")) },
            hoursWorked = json.double("hoursWorked"),
            meetings = json.objects("meetings").map { PrefillMeeting(it.str("title"), it.str("scheduled_start")) },
            events = json.objects("events").map {
                PrefillEvent(it.str("title"), (it["all_day"] as? JsonPrimitive)?.booleanOrNull ?: it.truthy("all_day"), it.str("start_time"))
            },
        )
    }
}

data class OneOnOnePrefill(
    val reportName: String? = null,
    val tasks: List<PrefillTask> = emptyList(),
    val leaves: List<PrefillLeave> = emptyList(),
    val sprint: PrefillSprint? = null,
    val hoursThisWeek: Double? = null,
) {
    companion object {
        fun from(json: JsonObject): OneOnOnePrefill = OneOnOnePrefill(
            reportName = json.obj("report")?.str("fullName"),
            tasks = json.objects("tasks").map { PrefillTask(it.str("title"), it.str("status")) },
            leaves = json.objects("leaves").map { PrefillLeave(it.str("date"), it.str("leave_type"), it.str("duration"), it.str("status")) },
            sprint = json.obj("sprint")?.let { sp ->
                PrefillSprint(sp.str("name"), sp.objects("taskBreakdown").mapNotNull { r -> r.str("status")?.let { it to (r.long("count") ?: 0L).toInt() } })
            },
            hoursThisWeek = json.double("hoursThisWeek"),
        )
    }
}

private fun JsonObject.objects(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun esc(value: String?): String = escapeHtmlText(value.orEmpty())

/** JavaScript number → string (`3` not `3.0`). */
fun jsNumber(value: Double): String =
    if (value == Math.floor(value) && !value.isInfinite() && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** `buildJournalPrefillHtml`. */
fun buildJournalPrefillHtml(prefill: JournalPrefill, clock: NotesClock = NotesClock()): String {
    val parts = mutableListOf<String>()
    parts += "<h2>${clock.todayLabel()}</h2>"
    prefill.hoursWorked?.let {
        parts += "<div class=\"ql-callout\" data-callout=\"info\"><p>⏱ <strong>${jsNumber(it)}h</strong> tracked today</p></div>"
    }
    parts += "<h3>How I'm feeling</h3><p><br></p>"
    parts += "<h3>What I worked on</h3>"
    if (prefill.tasks.isNotEmpty()) {
        val done = prefill.tasks.filter { it.status == "done" }
        val other = prefill.tasks.filter { it.status != "done" }
        parts += "<ul>"
        done.forEach { parts += "<li data-list=\"checked\">${esc(it.title)}</li>" }
        other.forEach { parts += "<li data-list=\"unchecked\">${esc(it.title)} <em>(${esc(it.status)})</em></li>" }
        parts += "</ul>"
    } else {
        parts += "<ul><li><br></li></ul>"
    }
    if (prefill.meetings.isNotEmpty()) {
        parts += "<h3>Meetings</h3>"
        prefill.meetings.forEach { parts += "<ul><li>📅 <strong>${esc(it.title)}</strong> at ${clock.time(it.scheduledStart)}</li></ul>" }
    }
    if (prefill.events.isNotEmpty() && prefill.meetings.isEmpty()) {
        parts += "<h3>Events</h3>"
        prefill.events.forEach {
            val time = if (it.allDay) "All day" else clock.time(it.startTime)
            parts += "<ul><li>${esc(it.title)} — $time</li></ul>"
        }
    }
    parts += "<h3>Wins</h3><ul><li><br></li></ul>"
    parts += "<h3>Tomorrow</h3><ul><li data-list=\"unchecked\"><br></li></ul>"
    return parts.joinToString("\n")
}

/** `buildOneOnOnePrefillHtml`. */
fun buildOneOnOnePrefillHtml(prefill: OneOnOnePrefill, clock: NotesClock = NotesClock()): String {
    val parts = mutableListOf<String>()
    val reportName = prefill.reportName?.ifEmpty { null } ?: "Team member"
    parts += "<h2>1-on-1 — ${clock.todayShort()}</h2>"
    parts += "<p><strong>With:</strong> ${esc(reportName)}</p>"
    prefill.hoursThisWeek?.let {
        parts += "<div class=\"ql-callout\" data-callout=\"info\"><p>📊 ${esc(reportName)} logged <strong>${jsNumber(it)}h</strong> this week</p></div>"
    }
    parts += "<h3>Recent task activity</h3>"
    if (prefill.tasks.isNotEmpty()) {
        val done = prefill.tasks.filter { it.status == "done" }
        val inProgress = prefill.tasks.filter { it.status == "in_progress" }
        val pending = prefill.tasks.filter { it.status == "pending" }
        if (done.isNotEmpty()) {
            parts += "<p><strong>Completed (${done.size}):</strong></p>"
            parts += "<ul>"
            done.take(8).forEach { parts += "<li data-list=\"checked\">${esc(it.title)}</li>" }
            parts += "</ul>"
        }
        if (inProgress.isNotEmpty()) {
            parts += "<p><strong>In progress (${inProgress.size}):</strong></p>"
            parts += "<ul>"
            inProgress.take(5).forEach { parts += "<li>🔵 ${esc(it.title)}</li>" }
            parts += "</ul>"
        }
        if (pending.isNotEmpty()) {
            parts += "<p><strong>Pending (${pending.size}):</strong></p>"
            parts += "<ul>"
            pending.take(5).forEach { parts += "<li>⬜ ${esc(it.title)}</li>" }
            parts += "</ul>"
        }
    } else {
        parts += "<p><em>No recent tasks</em></p>"
    }
    prefill.sprint?.let { sp ->
        parts += "<h3>Sprint: ${esc(sp.name)}</h3>"
        if (sp.breakdown.isNotEmpty()) {
            val bd = LinkedHashMap<String, Int>()
            sp.breakdown.forEach { (status, count) -> bd[status] = count }
            val total = bd.values.sum()
            val done = bd["done"] ?: 0
            val pct = if (total > 0) Math.round(done * 100.0 / total) else 0
            parts += "<p>Progress: <strong>$done/$total</strong> tasks done ($pct%)</p>"
            bd["in_progress"]?.takeIf { it != 0 }?.let { parts += "<p>🔵 $it in progress</p>" }
            bd["pending"]?.takeIf { it != 0 }?.let { parts += "<p>⬜ $it pending</p>" }
        }
    }
    if (prefill.leaves.isNotEmpty()) {
        parts += "<h3>Recent leaves</h3>"
        prefill.leaves.forEach {
            parts += "<ul><li>${esc(it.date)} — ${esc(it.leaveType)} (${it.duration ?: "null"}, ${it.status ?: "null"})</li></ul>"
        }
    }
    parts += "<h3>Wins / highlights</h3><ul><li><br></li></ul>"
    parts += "<h3>Blockers</h3><ul><li><br></li></ul>"
    parts += "<h3>Feedback</h3><p><br></p>"
    parts += "<h3>Career / growth</h3><p><br></p>"
    parts += "<h3>Action items</h3><ul><li data-list=\"unchecked\"><br></li></ul>"
    return parts.joinToString("\n")
}

/** `handleNewOneOnOneWithPrefill` title. */
fun oneOnOneTitle(reportName: String?, clock: NotesClock = NotesClock()): String =
    "1-on-1 with ${reportName?.ifEmpty { null } ?: "Team member"} — ${clock.todayShort()}"

/** `appendToInbox` block (quick capture). */
fun inboxCaptureHtml(text: String, clock: NotesClock = NotesClock()): String {
    val safe = text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    return "<p><strong>${clock.captureStamp()}</strong></p><p>$safe</p><p><br></p>"
}
