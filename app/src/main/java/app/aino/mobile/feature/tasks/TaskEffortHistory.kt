package app.aino.mobile.feature.tasks

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private fun effortValue(value: String?, estimates: Boolean): String {
    val row = runCatching { Json.parseToJsonElement(value ?: "null") as? JsonObject }.getOrNull()
        ?: return value?.takeIf { it.isNotEmpty() } ?: "—"
    fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull
    fun duration(key: String) = text(key)?.toIntOrNull()?.let(::formatMinutes) ?: "—"
    fun person(key: String): String? {
        val person = row[key] as? JsonObject ?: return null
        return (person["full_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: (person["username"] as? JsonPrimitive)?.contentOrNull
    }
    if (estimates) return "Original ${duration("original_estimate_minutes")}, remaining ${duration("remaining_estimate_minutes")}"
    return "#${text("id") ?: "?"} · ${person("author") ?: "author #${text("author_id") ?: "?"}"} · " +
        "logged by ${person("logged_by") ?: "#${text("logged_by_id") ?: "?"}"} · ${text("day_worked") ?: "—"} · ${duration("minutes")}" +
        (text("start_time")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "") +
        (text("comment")?.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
}

internal fun effortHistoryText(h: TaskHistoryEntry): List<Pair<HistoryPart, String>> {
    val label = when (h.action) {
        "worklog_added" -> "added worklog"
        "worklog_updated" -> "updated worklog"
        "worklog_deleted" -> "deleted worklog"
        else -> "changed estimates"
    }
    if (h.action == "worklog_added") return listOf(HistoryPart.Plain to "$label: ", HistoryPart.New to effortValue(h.newValue, false))
    return buildList {
        add(HistoryPart.Plain to "$label: ")
        add(HistoryPart.Old to effortValue(h.oldValue, h.action == "estimate_changed"))
        if (h.newValue != null) {
            add(HistoryPart.Plain to " → ")
            add(HistoryPart.New to effortValue(h.newValue, h.action == "estimate_changed"))
        }
    }
}
