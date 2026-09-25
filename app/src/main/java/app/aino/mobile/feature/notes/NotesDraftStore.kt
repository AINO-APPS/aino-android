package app.aino.mobile.feature.notes

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Unsynced edits that must survive process death (the web's localStorage
 * fallback). Holds only the pending change set plus the entities it touches.
 */
interface NotesDraftStore {
    fun read(userId: Long): Pair<PendingChanges, JsonObject>?
    fun write(userId: Long, pending: PendingChanges, subset: JsonObject)
    fun clear(userId: Long)
}

class FileNotesDraftStore(private val directory: File) : NotesDraftStore {
    private val json = Json { ignoreUnknownKeys = true }

    private fun file(userId: Long) = File(directory, "notes-pending-$userId.json")

    override fun read(userId: Long): Pair<PendingChanges, JsonObject>? = runCatching {
        val f = file(userId)
        if (!f.exists()) return null
        val root = json.parseToJsonElement(f.readText()).jsonObject
        val pending = PendingChanges.fromJson(root["pending"] as? JsonObject)
        if (pending.isEmpty) null else pending to (root["local"] as? JsonObject ?: JsonObject(emptyMap()))
    }.getOrNull()

    override fun write(userId: Long, pending: PendingChanges, subset: JsonObject) {
        runCatching {
            directory.mkdirs()
            val root = JsonObject(mapOf("pending" to pending.toJson(), "local" to subset))
            val target = file(userId)
            val tmp = File(directory, target.name + ".tmp")
            tmp.writeText(json.encodeToString(JsonElement.serializer(), root))
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        }
    }

    override fun clear(userId: Long) {
        runCatching { file(userId).delete() }
    }
}
