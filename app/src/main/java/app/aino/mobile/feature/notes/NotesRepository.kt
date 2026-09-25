package app.aino.mobile.feature.notes

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

data class MentionUser(val id: Long, val fullName: String?, val username: String?, val avatar: String?) {
    val display: String get() = fullName?.ifBlank { null } ?: username.orEmpty()
}

data class HistoryRow(val id: Long, val pageTitle: String?, val savedAt: String?)

data class HistorySnapshot(val id: Long, val pageId: String?, val pageTitle: String?, val content: String, val savedAt: String?)

/** `note_links` row enriched with the entity (`detail` is null when the entity was deleted). */
data class NoteLink(val id: Long, val entityType: String, val entityId: Long, val detail: JsonObject?)

/** Search result for the link picker (task / meeting / calendar event row). */
data class LinkCandidate(val id: Long, val raw: JsonObject) {
    val title: String get() = raw.str("title").orEmpty()
}

data class SprintEmbedData(val sprint: JsonObject?, val tasks: List<JsonObject>, val stats: JsonObject?)

data class TimeSummary(
    val hoursWorked: Double,
    val breakHours: Double,
    val firstClockIn: String?,
    val lastClockOut: String?,
    val workMode: String?,
    val isActive: Boolean,
)

data class ShareState(val token: String?, val url: String?)

data class ConvertedTask(val id: Long, val title: String?)

class NotesFailure(message: String, val statusCode: Int, cause: Throwable? = null) : Exception(message, cause)

class NotesRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /** `GET /notes` → `{ data: notebook | null, updatedAt }`. */
    fun load(): JsonObject? = get(ApiRequest(path = "notes"))["data"] as? JsonObject

    /** `PUT /notes` with `{ data }` (last write wins; the server snapshots changed pages). */
    fun save(notebook: JsonObject) {
        send(ApiRequest("PUT", "notes", body = body(JsonObject(mapOf("data" to notebook)))))
    }

    fun history(pageId: String): List<HistoryRow> {
        // @api GET notes/history/:pageId
        val res = get(ApiRequest(path = "notes/history/${enc(pageId)}"))
        return res.list("history").mapNotNull { o -> o.long("id")?.let { HistoryRow(it, o.str("page_title"), o.str("saved_at")) } }
    }

    fun snapshot(id: Long): HistorySnapshot? {
        // @api GET notes/history/snapshot/:id
        val o = get(ApiRequest(path = "notes/history/snapshot/$id")).obj("snapshot") ?: return null
        return HistorySnapshot(o.long("id") ?: id, o.str("page_id"), o.str("page_title"), o.str("content").orEmpty(), o.str("saved_at"))
    }

    fun mentionableUsers(): List<MentionUser> =
        get(ApiRequest(path = "notes/mentionable-users")).list("users").mapNotNull(::user)

    fun sendMention(mentionedUserId: Long, pageId: String, pageTitle: String) {
        send(ApiRequest("POST", "notes/mention", body = body(obj("mentionedUserId" to mentionedUserId, "pageId" to pageId, "pageTitle" to pageTitle))))
    }

    fun directReports(): List<MentionUser> =
        get(ApiRequest(path = "notes/direct-reports")).list("reports").mapNotNull(::user)

    fun links(pageId: String): List<NoteLink> {
        // @api GET notes/links/:pageId
        return get(ApiRequest(path = "notes/links/${enc(pageId)}")).list("links").mapNotNull { o ->
            val entityId = o.long("entity_id") ?: return@mapNotNull null
            NoteLink(o.long("id") ?: 0L, o.str("entity_type").orEmpty(), entityId, o.obj("detail"))
        }
    }

    fun addLink(pageId: String, entityType: String, entityId: Long) {
        send(ApiRequest("POST", "notes/links", body = body(obj("pageId" to pageId, "entityType" to entityType, "entityId" to entityId))))
    }

    /** axios `delete(url, { data })`: the body travels with the DELETE. */
    fun removeLink(pageId: String, entityType: String, entityId: Long) {
        send(ApiRequest("DELETE", "notes/links", body = body(obj("pageId" to pageId, "entityType" to entityType, "entityId" to entityId))))
    }

    fun dailyPrefill(): JournalPrefill = JournalPrefill.from(get(ApiRequest(path = "notes/daily-prefill")))

    fun oneOnOnePrefill(userId: Long): OneOnOnePrefill {
        // @api GET notes/oneonone-prefill/:userId
        return OneOnOnePrefill.from(get(ApiRequest(path = "notes/oneonone-prefill/$userId")))
    }

    fun timeSummary(): TimeSummary {
        val o = get(ApiRequest(path = "notes/time-summary"))
        return TimeSummary(
            hoursWorked = o.double("hoursWorked") ?: 0.0,
            breakHours = o.double("breakHours") ?: 0.0,
            firstClockIn = o.str("firstClockIn"),
            lastClockOut = o.str("lastClockOut"),
            workMode = o.str("workMode"),
            isActive = (o["isActive"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    fun convertToTask(title: String, pageId: String, pageTitle: String): ConvertedTask? {
        val res = send(ApiRequest("POST", "notes/convert-to-task", body = body(obj("title" to title, "pageId" to pageId, "pageTitle" to pageTitle))))
        val task = res.obj("task") ?: return null
        return task.long("id")?.let { ConvertedTask(it, task.str("title")) }
    }

    fun sprintEmbed(): SprintEmbedData {
        val o = get(ApiRequest(path = "notes/sprint-embed"))
        return SprintEmbedData(o.obj("sprint"), o.list("tasks"), o.obj("stats"))
    }

    fun searchTasks(query: String): List<LinkCandidate> {
        // @api GET notes/search-tasks
        return candidates(get(ApiRequest(path = "notes/search-tasks?q=${enc(query)}")), "tasks")
    }

    fun searchMeetings(query: String): List<LinkCandidate> {
        // @api GET notes/search-meetings
        return candidates(get(ApiRequest(path = "notes/search-meetings?q=${enc(query)}")), "meetings")
    }

    fun searchEvents(query: String): List<LinkCandidate> {
        // @api GET notes/search-events
        return candidates(get(ApiRequest(path = "notes/search-events?q=${enc(query)}")), "events")
    }

    fun share(pageId: String): ShareState {
        // @api GET notes/share/:pageId
        return shareState(get(ApiRequest(path = "notes/share/${enc(pageId)}")))
    }

    fun createShare(pageId: String): ShareState {
        // @api POST notes/share/:pageId
        return shareState(send(ApiRequest("POST", "notes/share/${enc(pageId)}", body = ByteArray(0))))
    }

    fun revokeShare(pageId: String) {
        // @api DELETE notes/share/:pageId
        send(ApiRequest("DELETE", "notes/share/${enc(pageId)}"))
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun shareState(o: JsonObject) = ShareState(o.str("token")?.ifBlank { null }, o.str("url")?.ifBlank { null })

    private fun candidates(o: JsonObject, key: String) = o.list(key).mapNotNull { r -> r.long("id")?.let { LinkCandidate(it, r) } }

    private fun user(o: JsonObject): MentionUser? =
        o.long("id")?.let { MentionUser(it, o.str("full_name"), o.str("username"), o.str("avatar")) }

    private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun obj(vararg pairs: Pair<String, Any?>) = JsonObject(pairs.associate { (k, v) -> k to jsonOf(v) })

    private fun body(element: JsonElement): ByteArray = json.encodeToString(JsonElement.serializer(), element).toByteArray()

    private fun get(request: ApiRequest): JsonObject = call(request)

    private fun send(request: ApiRequest): JsonObject = call(request)

    private fun call(request: ApiRequest): JsonObject {
        try {
            return decode(api.execute(request))
        } catch (error: ApiError.Http) {
            throw NotesFailure(serverMessage(error) ?: "Request failed (${error.statusCode})", error.statusCode, error)
        } catch (error: ApiError.Network) {
            throw NotesFailure("Network error. Check your connection.", 0, error)
        }
    }

    private fun decode(response: ApiResponse): JsonObject =
        runCatching { json.parseToJsonElement(response.bodyAsString().ifBlank { "{}" }).jsonObject }.getOrElse { JsonObject(emptyMap()) }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        (json.parseToJsonElement(error.responseBody).jsonObject["error"] as? JsonPrimitive)?.content
    }.getOrNull()

    private fun enc(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
