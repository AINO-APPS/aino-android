package app.aino.mobile.feature.notes

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// ── Lenient JSON access ─────────────────────────────────────────────────────

fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.str(key: String): String? = this[key].str()
fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() ?: it.content.toLongOrNull() }
fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** JavaScript truthiness for the flags `migratePageModel` reads with `!!`. */
fun JsonObject.truthy(key: String): Boolean = when (val v = this[key]) {
    null, JsonNull -> false
    is JsonPrimitive -> v.booleanOrNull ?: if (v.isString) v.content.isNotEmpty() else (v.doubleOrNull ?: 0.0) != 0.0
    else -> true
}

fun JsonObject.with(vararg entries: Pair<String, JsonElement>): JsonObject = JsonObject(this + entries)

fun jsonOf(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}

// ── Notebook entities (raw JSON wrappers; unknown keys survive untouched) ──

data class NotePage(val raw: JsonObject) {
    val id: String = raw.str("id").orEmpty()
    val rawTitle: String get() = raw.str("title").orEmpty()
    /** `migratePageModel`: `title || "Untitled"`. */
    val title: String get() = rawTitle.ifEmpty { "Untitled" }
    val content: String get() = raw.str("content").orEmpty()
    val createdAt: String? get() = raw.str("createdAt") ?: raw.str("updatedAt")
    val updatedAt: String? get() = raw.str("updatedAt")
    val pinned: Boolean get() = raw.truthy("pinned")
    val archived: Boolean get() = raw.truthy("archived")
    val readOnly: Boolean get() = raw.truthy("readOnly")
    val folderId: String? get() = raw.str("folderId")?.ifEmpty { null }
    val parentPageId: String? get() = raw.str("parentPageId")?.ifEmpty { null }
    val sortOrder: Double get() = raw.double("sortOrder") ?: 0.0
    val icon: String get() = raw.str("icon").orEmpty()
    val coverColor: String get() = raw.str("coverColor").orEmpty()
    val createdBy: Long? get() = raw.long("createdBy")
    val lastEditedBy: Long? get() = raw.long("lastEditedBy")
    val tags: List<String> get() = raw.arr("tags")?.mapNotNull { it.str() }.orEmpty()
    val reactions: Map<String, List<String>>
        get() = raw.obj("reactions")?.mapValues { (_, v) -> (v as? JsonArray)?.mapNotNull { it.str() }.orEmpty() }.orEmpty()
    val updatedMillis: Long get() = parseIsoMillis(updatedAt) ?: 0L
}

data class NoteFolder(val raw: JsonObject) {
    val id: String = raw.str("id").orEmpty()
    val name: String get() = raw.str("name").orEmpty()
    val parentId: String? get() = raw.str("parentId")?.ifEmpty { null }
    val sortOrder: Double get() = raw.double("sortOrder") ?: 0.0
}

fun JsonObject.pages(): List<NotePage> =
    arr("pages")?.mapNotNull { (it as? JsonObject)?.let(::NotePage) }?.filter { it.id.isNotEmpty() }.orEmpty()

fun JsonObject.folders(): List<NoteFolder> =
    arr("folders")?.mapNotNull { (it as? JsonObject)?.let(::NoteFolder) }?.filter { it.id.isNotEmpty() }.orEmpty()

// ── Factories (notesUtils.newPage / newFolder) ─────────────────────────────

private val ISO_MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** JavaScript `new Date().toISOString()`. */
fun isoNow(nowMillis: Long = System.currentTimeMillis()): String = ISO_MILLIS.format(Instant.ofEpochMilli(nowMillis))

fun parseIsoMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDate.parse(iso.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}

fun newPageJson(
    title: String = "Untitled",
    folderId: String? = null,
    parentPageId: String? = null,
    userId: Long? = null,
    content: String = "",
    nowMillis: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): JsonObject {
    val now = isoNow(nowMillis)
    return JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(id),
            "title" to JsonPrimitive(title),
            "content" to JsonPrimitive(content),
            "createdAt" to JsonPrimitive(now),
            "updatedAt" to JsonPrimitive(now),
            "createdBy" to jsonOf(userId),
            "lastEditedBy" to jsonOf(userId),
            "pinned" to JsonPrimitive(false),
            "tags" to JsonArray(emptyList()),
            "folderId" to jsonOf(folderId),
            "parentPageId" to jsonOf(parentPageId),
            "archived" to JsonPrimitive(false),
            "sortOrder" to JsonPrimitive(nowMillis),
            "icon" to JsonPrimitive(""),
            "coverColor" to JsonPrimitive(""),
            "readOnly" to JsonPrimitive(false),
            "properties" to JsonObject(emptyMap()),
            "reactions" to JsonObject(emptyMap()),
        ),
    )
}

fun newFolderJson(
    name: String,
    parentId: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
    id: String = UUID.randomUUID().toString(),
): JsonObject = JsonObject(
    linkedMapOf(
        "id" to JsonPrimitive(id),
        "name" to JsonPrimitive(name),
        "parentId" to jsonOf(parentId),
        "sortOrder" to JsonPrimitive(nowMillis),
    ),
)

// ── Tree helpers ───────────────────────────────────────────────────────────

fun descendantPageIds(pageId: String, pages: List<NotePage>): List<String> {
    val out = mutableListOf<String>()
    val seen = mutableSetOf(pageId)
    val queue = ArrayDeque(listOf(pageId))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        pages.filter { it.parentPageId == current && seen.add(it.id) }.forEach {
            out += it.id
            queue += it.id
        }
    }
    return out
}

fun descendantFolderIds(folderId: String, folders: List<NoteFolder>): List<String> {
    val out = mutableListOf<String>()
    val seen = mutableSetOf(folderId)
    val queue = ArrayDeque(listOf(folderId))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        folders.filter { it.parentId == current && seen.add(it.id) }.forEach {
            out += it.id
            queue += it.id
        }
    }
    return out
}

fun pageAncestors(pageId: String, pages: List<NotePage>): List<NotePage> {
    val byId = pages.associateBy { it.id }
    val result = mutableListOf<NotePage>()
    val seen = mutableSetOf(pageId)
    var current = byId[pageId]
    while (true) {
        val parentId = current?.parentPageId ?: break
        if (!seen.add(parentId)) break
        val parent = byId[parentId] ?: break
        result.add(0, parent)
        current = parent
    }
    return result
}

fun folderPath(folderId: String?, folders: List<NoteFolder>): String {
    if (folderId == null) return ""
    val byId = folders.associateBy { it.id }
    val parts = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    var current = byId[folderId]
    while (current != null && seen.add(current.id)) {
        parts.add(0, current.name)
        current = current.parentId?.let(byId::get)
    }
    return parts.joinToString(" / ")
}

private val PAGE_LINK_RE = Regex("data-page-id=\"([^\"]+)\"")

fun extractPageLinks(html: String?): Set<String> =
    if (html.isNullOrEmpty()) emptySet() else PAGE_LINK_RE.findAll(html).map { it.groupValues[1] }.toSet()

fun backlinks(pageId: String, pages: List<NotePage>): List<NotePage> =
    pages.filter { !it.archived && it.id != pageId && pageId in extractPageLinks(it.content) }

fun childPages(pageId: String, pages: List<NotePage>): List<NotePage> =
    pages.filter { it.parentPageId == pageId && !it.archived }.sortedBy { it.sortOrder }

// ── Formatting ─────────────────────────────────────────────────────────────

/** `formatDate`: Today / Yesterday / "Sep 25". */
fun formatNoteDate(iso: String?, nowMillis: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String {
    val millis = parseIsoMillis(iso) ?: return ""
    val diff = Math.floorDiv(nowMillis - millis, 86_400_000L)
    if (diff == 0L) return "Today"
    if (diff == 1L) return "Yesterday"
    return DateTimeFormatter.ofPattern("MMM d", locale).format(Instant.ofEpochMilli(millis).atZone(zone))
}

/** NotesHome `relativeFromNow`. */
fun relativeFromNow(iso: String?, nowMillis: Long = System.currentTimeMillis()): String {
    val then = parseIsoMillis(iso) ?: return ""
    val min = Math.round((nowMillis - then) / 60000.0)
    if (min < 1) return "just now"
    if (min < 60) return "$min min ago"
    val hr = Math.round(min / 60.0)
    if (hr < 24) return "$hr hr ago"
    val d = Math.round(hr / 24.0)
    if (d < 7) return "${d}d ago"
    return formatNoteDate(iso, nowMillis)
}

/** NotesHome `snippetOf`. */
fun snippetOf(html: String?, max: Int = 140): String {
    val text = htmlToPlainText(html).trim()
    if (text.isEmpty()) return ""
    return if (text.length > max) text.take(max).trim() + "…" else text
}

private val TAG_COLORS = listOf(0xFF0EA5E9, 0xFFEC4899, 0xFFF59E0B, 0xFF10B981, 0xFF3B82F6, 0xFF0EA5E9, 0xFFEF4444, 0xFF14B8A6)

/** `tagColor`: deterministic JS string hash into the palette. */
fun tagColorArgb(name: String): Long {
    var h = 0L
    for (c in name) h = c.code + ((h.toInt() shl 5).toLong() - h)
    return TAG_COLORS[(Math.abs(h) % TAG_COLORS.size).toInt()]
}
