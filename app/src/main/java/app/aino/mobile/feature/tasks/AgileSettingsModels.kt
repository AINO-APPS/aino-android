package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** `org_agile_settings` row (`GET/PUT /agile/settings`), the fields the General tab edits. */
@Serializable
data class AgileSettingsRecord(
    @SerialName("estimation_type") val estimationType: String = "fibonacci",
    @SerialName("estimation_values") val estimationValues: List<JsonPrimitive> = emptyList(),
    @SerialName("estimation_unit_label") val estimationUnitLabel: String? = null,
    @SerialName("enable_story_points") val enableStoryPoints: Boolean = false,
    @SerialName("enable_epics") val enableEpics: Boolean = false,
    @SerialName("enable_dependencies") val enableDependencies: Boolean = false,
    @SerialName("enable_acceptance_criteria") val enableAcceptanceCriteria: Boolean = false,
    @SerialName("enable_blockers") val enableBlockers: Boolean = false,
    @SerialName("enable_wip_limits") val enableWipLimits: Boolean = false,
    @SerialName("enable_retrospectives") val enableRetrospectives: Boolean = false,
    @SerialName("require_estimate_for_sprint") val requireEstimateForSprint: Boolean = false,
    @SerialName("default_dod") val defaultDod: String? = null,
) {
    fun flag(key: String): Boolean = when (key) {
        "enable_story_points" -> enableStoryPoints
        "enable_epics" -> enableEpics
        "enable_dependencies" -> enableDependencies
        "enable_acceptance_criteria" -> enableAcceptanceCriteria
        "enable_blockers" -> enableBlockers
        "enable_wip_limits" -> enableWipLimits
        "enable_retrospectives" -> enableRetrospectives
        "require_estimate_for_sprint" -> requireEstimateForSprint
        else -> false
    }

    fun withFlag(key: String, on: Boolean): AgileSettingsRecord = when (key) {
        "enable_story_points" -> copy(enableStoryPoints = on)
        "enable_epics" -> copy(enableEpics = on)
        "enable_dependencies" -> copy(enableDependencies = on)
        "enable_acceptance_criteria" -> copy(enableAcceptanceCriteria = on)
        "enable_blockers" -> copy(enableBlockers = on)
        "enable_wip_limits" -> copy(enableWipLimits = on)
        "enable_retrospectives" -> copy(enableRetrospectives = on)
        "require_estimate_for_sprint" -> copy(requireEstimateForSprint = on)
        else -> this
    }

    /** `handleEstimationType`: presets replace the values; `custom` keeps them. */
    fun withEstimationType(type: String): AgileSettingsRecord =
        copy(estimationType = type, estimationValues = ESTIMATION_PRESETS[type]?.takeIf { type != "custom" } ?: estimationValues)

    /** `estimation_values.join(", ")`. */
    fun valuesText(): String = estimationValues.joinToString(", ") { it.content }
}

/** Admin `GET /agile/work-item-types` row (includes inactive types). */
@Serializable
data class AdminWorkItemType(
    val id: Long,
    val key: String = "",
    val name: String = "",
    val icon: String? = null,
    val color: String = "#6366f1",
    val description: String? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("is_epic") val isEpic: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("sort_order") @Serializable(with = LenientIntSerializer::class) val sortOrder: Int = 0,
)

/** Admin `GET /agile/workflow-states` row (includes inactive states). */
@Serializable
data class AdminWorkflowState(
    val id: Long,
    val key: String = "",
    val name: String = "",
    val category: String = "open",
    val color: String = "#6b7280",
    val icon: String? = null,
    @SerialName("wip_limit") @Serializable(with = LenientDoubleNullableSerializer::class) val wipLimit: Double? = null,
    @SerialName("is_initial") val isInitial: Boolean = false,
    @SerialName("is_terminal") val isTerminal: Boolean = false,
    @SerialName("is_active") val isActive: Boolean = true,
    @SerialName("sort_order") @Serializable(with = LenientIntSerializer::class) val sortOrder: Int = 0,
) {
    fun wipText(): String = wipLimit?.toLong()?.toString().orEmpty()
}

/** `GET /agile/permissions/me`. */
@Serializable
data class AgilePermissions(
    val canEdit: Boolean = false,
    val isSuperAdmin: Boolean = false,
    val isReviewer: Boolean = false,
    val requestStatus: String = "none",
    val role: String = "",
) {
    /** The header badge text when [canEdit]. */
    fun editorLabel(): String = when {
        isSuperAdmin -> "Super Admin"
        role.isNotEmpty() -> "Editor (${role.replace("_", " ")})"
        else -> "Editor"
    }
}

/** `agile_editor_requests` row joined with the requester (`GET /agile/permissions/requests`). */
@Serializable
data class AgileAccessRequest(
    val id: Long,
    @SerialName("user_id") val userId: Long? = null,
    val status: String = "pending",
    val reason: String? = null,
    @SerialName("reject_reason") val rejectReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
    val email: String? = null,
)

/** Active `agile_editor_grants` row (`GET /agile/permissions/grants`). */
@Serializable
data class AgileEditorGrant(
    val id: Long,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("granted_at") val grantedAt: String? = null,
    val username: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val avatar: String? = null,
    val email: String? = null,
    @SerialName("granted_by_username") val grantedByUsername: String? = null,
    @SerialName("granted_by_name") val grantedByName: String? = null,
)

/** `GET /tasks/labels/manage` row. */
@Serializable
data class ManagedLabel(
    val id: Long,
    val name: String = "",
    val color: String = DEFAULT_MANAGED_LABEL_COLOR,
    @SerialName("created_by_username") val createdByUsername: String? = null,
)

@Serializable
data class NewWorkItemType(
    val name: String = "",
    val color: String = "#6366f1",
    val icon: String = "",
    @SerialName("is_epic") val isEpic: Boolean = false,
    @SerialName("is_default") val isDefault: Boolean = false,
    val description: String = "",
)

/** Add State form; `wip_limit` is the raw input, parsed on submit. */
data class NewWorkflowState(
    val name: String = "",
    val category: String = "open",
    val color: String = "#6b7280",
    val wipLimit: String = "",
    val isInitial: Boolean = false,
    val isTerminal: Boolean = false,
)

@Serializable
internal data class WorkflowStatePayload(
    val name: String,
    val category: String,
    val color: String,
    @SerialName("wip_limit") val wipLimit: Int?,
    @SerialName("is_initial") val isInitial: Boolean,
    @SerialName("is_terminal") val isTerminal: Boolean,
)

@Serializable
internal data class ReorderPayload(val order: List<Long>)

@Serializable
internal data class AccessRequestPayload(val reason: String?)

@Serializable
internal data class ReviewPayload(val action: String, @SerialName("reject_reason") val rejectReason: String? = null)

@Serializable
internal data class LabelPayload(val name: String, val color: String)

// ── Web constants (AgileSettings.tsx, TaskLabelsTab.tsx) ───────────────────

data class WorkflowCategory(val key: String, val label: String, val color: String)

val WORKFLOW_CATEGORIES = listOf(
    WorkflowCategory("open", "Open / To Do", "#6b7280"),
    WorkflowCategory("in_progress", "In Progress", "#f59e0b"),
    WorkflowCategory("in_review", "In Review", "#3b82f6"),
    WorkflowCategory("done", "Done", "#10b981"),
)

private fun nums(vararg values: Number) = values.map { JsonPrimitive(it) }

val ESTIMATION_PRESETS: Map<String, List<JsonPrimitive>> = mapOf(
    "fibonacci" to nums(0.5, 1, 2, 3, 5, 8, 13, 21, 34),
    "linear" to nums(1, 2, 3, 4, 5, 6, 7, 8, 9, 10),
    "tshirt" to listOf("XS", "S", "M", "L", "XL", "XXL").map { JsonPrimitive(it) },
    "hours" to nums(1, 2, 4, 8, 16, 24, 40),
    "none" to emptyList(),
    "custom" to emptyList(),
)

val ESTIMATION_OPTIONS = listOf(
    "fibonacci" to "Fibonacci (0.5, 1, 2, 3, 5, 8, 13, 21, 34)",
    "linear" to "Linear (1–10)",
    "tshirt" to "T-shirt (XS / S / M / L / XL / XXL)",
    "hours" to "Hours (1, 2, 4, 8, 16, 24, 40)",
    "none" to "None — disable estimation",
    "custom" to "Custom",
)

val AGILE_FEATURE_FLAGS = listOf(
    "enable_story_points" to "Story Points",
    "enable_epics" to "Epics & parent links",
    "enable_dependencies" to "Dependencies / blocked-by graph",
    "enable_acceptance_criteria" to "Acceptance Criteria",
    "enable_blockers" to "Blocker badges",
    "enable_wip_limits" to "WIP limits per column",
    "enable_retrospectives" to "Sprint Retrospectives",
    "require_estimate_for_sprint" to "Require estimate before adding to sprint",
)

const val DEFAULT_MANAGED_LABEL_COLOR = "#0ea5e9"

val MANAGED_LABEL_COLORS = listOf(
    "#0ea5e9", "#ef4444", "#f59e0b", "#10b981", "#3b82f6",
    "#8b5cf6", "#ec4899", "#14b8a6", "#f97316", "#64748b",
)

/**
 * The scale-values input: split on commas, trim, drop blanks, and keep numeric
 * entries as numbers (`isNaN(Number(v)) ? v : Number(v)`).
 */
fun parseEstimationValues(text: String): List<JsonPrimitive> =
    text.split(',').map(String::trim).filter(String::isNotEmpty).map { v ->
        val number = v.toDoubleOrNull()?.takeIf { it.isFinite() }
        when {
            number == null -> JsonPrimitive(v)
            number == Math.floor(number) && kotlin.math.abs(number) < 1e15 -> JsonPrimitive(number.toLong())
            else -> JsonPrimitive(number)
        }
    }

/** `parseInt(value, 10)` for the WIP inputs; blank → null. */
fun parseWip(text: String): Int? = Regex("^\\s*-?\\d+").find(text)?.value?.trim()?.toIntOrNull()

/** Web `/^#[0-9a-fA-F]{6}$/` (server `validateColor`). */
fun isHexColor6(value: String): Boolean = Regex("^#[0-9a-fA-F]{6}$").matches(value)

/** `grouped` — every state (active or not) bucketed by its category. */
fun groupStatesByCategory(states: List<AdminWorkflowState>): Map<String, List<AdminWorkflowState>> =
    WORKFLOW_CATEGORIES.associate { cat -> cat.key to states.filter { it.category == cat.key } }
