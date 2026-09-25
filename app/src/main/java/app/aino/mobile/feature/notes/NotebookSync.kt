package app.aino.mobile.feature.notes

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * A local change to one notebook entity (page or folder). Each flag/field
 * carries the edit version that set it, so a save only clears what it actually
 * sent — edits made while the request was in flight stay pending.
 */
data class EntityChange(
    val created: Long? = null,
    val deleted: Long? = null,
    val fields: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = created == null && deleted == null && fields.isEmpty()

    fun minus(saved: EntityChange): EntityChange = EntityChange(
        created = created?.takeIf { it != saved.created },
        deleted = deleted?.takeIf { it != saved.deleted },
        fields = fields.filter { (k, v) -> saved.fields[k] != v },
    )
}

/** Everything edited locally since the last successful sync. */
data class PendingChanges(
    val pages: Map<String, EntityChange> = emptyMap(),
    val folders: Map<String, EntityChange> = emptyMap(),
    /** Top-level notebook keys (`activePageId`, `sortBy`, …). */
    val top: Map<String, Long> = emptyMap(),
) {
    val isEmpty: Boolean get() = pages.isEmpty() && folders.isEmpty() && top.isEmpty()

    fun touchPage(id: String, version: Long, vararg fields: String) = copy(pages = pages.touch(id, version, fields))
    fun createPage(id: String, version: Long) = copy(pages = pages + (id to (pages[id] ?: EntityChange()).copy(created = version)))
    fun deletePage(id: String, version: Long) = copy(pages = pages + (id to (pages[id] ?: EntityChange()).copy(deleted = version)))
    fun touchFolder(id: String, version: Long, vararg fields: String) = copy(folders = folders.touch(id, version, fields))
    fun createFolder(id: String, version: Long) = copy(folders = folders + (id to (folders[id] ?: EntityChange()).copy(created = version)))
    fun deleteFolder(id: String, version: Long) = copy(folders = folders + (id to (folders[id] ?: EntityChange()).copy(deleted = version)))
    fun touchTop(key: String, version: Long) = copy(top = top + (key to version))

    fun minus(saved: PendingChanges): PendingChanges = PendingChanges(
        pages = pages.minusSaved(saved.pages),
        folders = folders.minusSaved(saved.folders),
        top = top.filter { (k, v) -> saved.top[k] != v },
    )

    private fun Map<String, EntityChange>.touch(id: String, version: Long, fields: Array<out String>): Map<String, EntityChange> {
        val current = this[id] ?: EntityChange()
        return this + (id to current.copy(fields = current.fields + fields.associateWith { version }))
    }

    private fun Map<String, EntityChange>.minusSaved(saved: Map<String, EntityChange>): Map<String, EntityChange> =
        mapNotNull { (id, change) ->
            val remaining = saved[id]?.let(change::minus) ?: change
            if (remaining.isEmpty) null else id to remaining
        }.toMap()

    fun toJson(): JsonObject = JsonObject(
        mapOf(
            "pages" to entitiesJson(pages),
            "folders" to entitiesJson(folders),
            "top" to JsonObject(top.mapValues { JsonPrimitive(it.value) }),
        ),
    )

    companion object {
        private fun entitiesJson(map: Map<String, EntityChange>) = JsonObject(
            map.mapValues { (_, c) ->
                JsonObject(
                    buildMap {
                        c.created?.let { put("created", JsonPrimitive(it)) }
                        c.deleted?.let { put("deleted", JsonPrimitive(it)) }
                        put("fields", JsonObject(c.fields.mapValues { JsonPrimitive(it.value) }))
                    },
                )
            },
        )

        private fun entities(json: JsonObject?): Map<String, EntityChange> = json?.mapValues { (_, v) ->
            val o = v as? JsonObject ?: JsonObject(emptyMap())
            EntityChange(
                created = o.long("created"),
                deleted = o.long("deleted"),
                fields = o.obj("fields")?.mapNotNull { (k, f) -> (f as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap().orEmpty(),
            )
        }.orEmpty()

        fun fromJson(json: JsonObject?): PendingChanges = PendingChanges(
            pages = entities(json?.obj("pages")),
            folders = entities(json?.obj("folders")),
            top = json?.obj("top")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.longOrNull?.let { k to it } }?.toMap().orEmpty(),
        )
    }
}

/**
 * Applies the locally [pending] edits from [local] onto the freshly fetched
 * server notebook. Only the changed entities/fields are taken from [local];
 * everything else (another device's edits to other pages, unknown keys,
 * `todos`, …) comes from [fresh]. With no server notebook the local one wins.
 */
fun mergeNotebook(fresh: JsonObject?, local: JsonObject, pending: PendingChanges): JsonObject {
    if (fresh == null) return local
    if (pending.isEmpty) return fresh
    val result = LinkedHashMap<String, JsonElement>(fresh)
    if (pending.pages.isNotEmpty()) result["pages"] = mergeEntities(fresh.arr("pages"), local.arr("pages"), pending.pages)
    if (pending.folders.isNotEmpty()) result["folders"] = mergeEntities(fresh.arr("folders"), local.arr("folders"), pending.folders)
    for (key in pending.top.keys) {
        val value = local[key]
        if (value == null) result.remove(key) else result[key] = value
    }
    return JsonObject(result)
}

private fun mergeEntities(freshArr: JsonArray?, localArr: JsonArray?, changes: Map<String, EntityChange>): JsonArray {
    val localById = localArr.orEmpty().mapNotNull { el -> (el as? JsonObject)?.let { o -> o.str("id")?.let { it to o } } }.toMap()
    val out = mutableListOf<JsonElement>()
    val seen = mutableSetOf<String>()
    for (el in freshArr.orEmpty()) {
        val obj = el as? JsonObject
        val id = obj?.str("id")
        if (obj == null || id == null) {
            out += el
            continue
        }
        seen += id
        val change = changes[id]
        val localObj = localById[id]
        when {
            change == null -> out += el
            change.deleted != null -> Unit
            localObj == null -> out += el
            change.created != null -> out += localObj
            else -> {
                val merged = LinkedHashMap<String, JsonElement>(obj)
                for (field in change.fields.keys) {
                    val value = localObj[field]
                    if (value == null) merged.remove(field) else merged[field] = value
                }
                out += JsonObject(merged)
            }
        }
    }
    // Pages created locally — or edited locally but deleted elsewhere meanwhile
    // (kept rather than silently dropping the user's edits).
    for (el in localArr.orEmpty()) {
        val obj = el as? JsonObject ?: continue
        val id = obj.str("id") ?: continue
        if (id in seen) continue
        val change = changes[id] ?: continue
        if (change.deleted != null) continue
        out += obj
    }
    return JsonArray(out)
}

/** The server rejects notebooks whose `JSON.stringify` exceeds 2 MB (UTF-16 length). */
const val NOTEBOOK_MAX_CHARS = 2 * 1024 * 1024
const val NOTEBOOK_TOO_LARGE = "Notebook data too large (max 2 MB)"

/** Subset of [local] needed to replay [pending] later (persisted draft). */
fun pendingSubset(local: JsonObject, pending: PendingChanges): JsonObject {
    val map = LinkedHashMap<String, JsonElement>()
    map["pages"] = JsonArray(local.arr("pages").orEmpty().filter { (it as? JsonObject)?.str("id") in pending.pages.keys })
    map["folders"] = JsonArray(local.arr("folders").orEmpty().filter { (it as? JsonObject)?.str("id") in pending.folders.keys })
    for (key in pending.top.keys) local[key]?.let { map[key] = it }
    return JsonObject(map)
}
