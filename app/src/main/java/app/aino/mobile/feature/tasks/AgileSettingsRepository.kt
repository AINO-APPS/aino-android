package app.aino.mobile.feature.tasks

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Agile administration calls (`pages/AgileSettings.tsx`, `server/routes/agile.ts`)
 * plus the task-label management the web's Labels tab reuses (`TaskLabelsTab`).
 */
class AgileSettingsRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = true; encodeDefaults = true },
) {
    // ── Settings ─────────────────────────────────────────────────────────
    fun loadSettings(): AgileSettingsRecord = decode(api.execute(ApiRequest(path = "agile/settings")))

    fun saveSettings(settings: AgileSettingsRecord): AgileSettingsRecord =
        mutate("agile/settings", settings, "PUT", "Failed to save")

    // ── Work item types ──────────────────────────────────────────────────
    fun loadWorkItemTypes(): List<AdminWorkItemType> = decode(api.execute(ApiRequest(path = "agile/work-item-types")))

    fun createWorkItemType(form: NewWorkItemType): AdminWorkItemType =
        mutate("agile/work-item-types", form, "POST", "Failed to create")

    /** Partial update: only the keys in [patch] are sent (`{ name }`, `{ is_default }` …). */
    fun updateWorkItemType(id: Long, patch: JsonObject): AdminWorkItemType =
        mutate("agile/work-item-types/$id", patch, "PUT", "Failed to update")

    fun deleteWorkItemType(id: Long): JsonObject = mutate("agile/work-item-types/$id", Unit, "DELETE", "Failed to delete")

    /**
     * `reorderWorkItemTypes(order)`. The web page never calls it, and on the
     * current server the route is registered after `PUT /work-item-types/:id`,
     * so Express answers it with that handler's 400 "Invalid id"; no UI uses it.
     */
    fun reorderWorkItemTypes(order: List<Long>): JsonObject =
        mutate("agile/work-item-types/reorder", ReorderPayload(order), "PUT", "Failed to reorder")

    // ── Workflow states ──────────────────────────────────────────────────
    fun loadWorkflowStates(): List<AdminWorkflowState> = decode(api.execute(ApiRequest(path = "agile/workflow-states")))

    /** `{ ...form, wip_limit: form.wip_limit ? parseInt(form.wip_limit, 10) : null }`. */
    fun createWorkflowState(form: NewWorkflowState): AdminWorkflowState = mutate(
        "agile/workflow-states",
        WorkflowStatePayload(form.name, form.category, form.color, parseWip(form.wipLimit), form.isInitial, form.isTerminal),
        "POST",
        "Failed to create",
    )

    fun updateWorkflowState(id: Long, patch: JsonObject): AdminWorkflowState =
        mutate("agile/workflow-states/$id", patch, "PUT", "Failed to update")

    fun deleteWorkflowState(id: Long): JsonObject = mutate("agile/workflow-states/$id", Unit, "DELETE", "Failed to delete")

    /** Same route-shadowing caveat as [reorderWorkItemTypes]. */
    fun reorderWorkflowStates(order: List<Long>): JsonObject =
        mutate("agile/workflow-states/reorder", ReorderPayload(order), "PUT", "Failed to reorder")

    // ── Permissions ──────────────────────────────────────────────────────
    fun loadPermissions(): AgilePermissions = decode(api.execute(ApiRequest(path = "agile/permissions/me")))

    /*
     * The access-request / grant workflow below is still served, but the web
     * removed its UI ("access is purely role-based now") and there is no copy
     * to port, so no Android screen calls these yet.
     */
    fun requestEditAccess(reason: String?): AgileAccessRequest =
        mutate("agile/permissions/request", AccessRequestPayload(reason), "POST", "Failed to submit request")

    fun cancelAccessRequest(): JsonObject = mutate("agile/permissions/cancel-request", Unit, "POST", "Failed to cancel")

    fun loadAccessRequests(): List<AgileAccessRequest> = decode(api.execute(ApiRequest(path = "agile/permissions/requests")))

    /** [action] is `approve` or `reject`. */
    fun reviewAccessRequest(id: Long, action: String, rejectReason: String? = null): AgileAccessRequest =
        mutate("agile/permissions/requests/$id", ReviewPayload(action, rejectReason), "PUT", "Failed to review request")

    fun loadGrants(): List<AgileEditorGrant> = decode(api.execute(ApiRequest(path = "agile/permissions/grants")))

    fun revokeGrant(id: Long): JsonObject = mutate("agile/permissions/grants/$id", Unit, "DELETE", "Failed to revoke grant")

    // ── Labels tab (TaskLabelsTab) ───────────────────────────────────────
    fun loadManagedLabels(): List<ManagedLabel> = decode(api.execute(ApiRequest(path = "tasks/labels/manage")))

    fun createLabel(name: String, color: String): ManagedLabel =
        mutate("tasks/labels", LabelPayload(name, color), "POST", "Failed to create label")

    fun updateLabel(id: Long, name: String, color: String): ManagedLabel =
        mutate("tasks/labels/$id", LabelPayload(name, color), "PUT", "Failed to update label")

    fun deleteLabel(id: Long): JsonObject = mutate("tasks/labels/$id", Unit, "DELETE", "Failed to delete label")

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String, fallback: String): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw TaskFailure(serverError(json, error) ?: fallback, error.statusCode, error)
        }
    }

    private inline fun <reified T> decode(response: ApiResponse): T = json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}
