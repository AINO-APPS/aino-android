package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientDoubleSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** `GET /tasks/:id/dependencies` row (`d.id AS link_id, d.type, t.*`). */
@Serializable
data class DependencyLink(
    @SerialName("link_id") val linkId: Long,
    val type: String = "blocks",
    val id: Long,
    val title: String = "",
    val status: String? = null,
    @SerialName("workflow_state_id") val workflowStateId: Long? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
)

@Serializable
data class DependenciesResponse(
    val blocks: List<DependencyLink> = emptyList(),
    val blockedBy: List<DependencyLink> = emptyList(),
)

@Serializable
data class DependencyPayload(@SerialName("depends_on_id") val dependsOnId: Long, val type: String)

/** `GET /tasks/lookup/quicksearch` row. */
@Serializable
data class QuickTask(
    val id: Long,
    val title: String = "",
    val status: String? = null,
    @SerialName("workflow_state_id") val workflowStateId: Long? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @Serializable(with = LenientDoubleNullableSerializer::class)
    @SerialName("story_points") val storyPoints: Double? = null,
    @SerialName("work_item_type_id") val workItemTypeId: Long? = null,
)

@Serializable
data class QuickSearchResponse(val tasks: List<QuickTask> = emptyList())

/** `GET /tasks/:id/children` child row (joined with its type and workflow state). */
@Serializable
data class ChildTask(
    val id: Long,
    val title: String = "",
    val status: String? = null,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("is_terminal") val isTerminal: Boolean = false,
    @SerialName("type_name") val typeName: String? = null,
    @SerialName("type_color") val typeColor: String? = null,
    @SerialName("state_name") val stateName: String? = null,
    @SerialName("state_color") val stateColor: String? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class)
    @SerialName("story_points") val storyPoints: Double? = null,
)

@Serializable
data class ChildRollup(
    @Serializable(with = LenientIntSerializer::class) val totalChildren: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val doneChildren: Int = 0,
    @Serializable(with = LenientDoubleSerializer::class) val totalPoints: Double = 0.0,
    @Serializable(with = LenientDoubleSerializer::class) val donePoints: Double = 0.0,
    @Serializable(with = LenientIntSerializer::class) val percentByPoints: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val percentByCount: Int = 0,
)

@Serializable
data class ChildrenResponse(val children: List<ChildTask> = emptyList(), val rollup: ChildRollup = ChildRollup())

@Serializable
data class ParentTask(
    val id: Long,
    val title: String = "",
    val status: String? = null,
    @SerialName("issue_key") val issueKey: String? = null,
    @SerialName("work_item_type_id") val workItemTypeId: Long? = null,
)

@Serializable
data class ParentResponse(val parent: ParentTask? = null)

@Serializable
data class ParentPayload(@SerialName("parent_task_id") val parentTaskId: Long?)

@Serializable
data class CriteriaResponse(val criteria: List<JsonElement> = emptyList())

@Serializable
data class CriterionPayload(val id: String? = null, val text: String, val done: Boolean)

@Serializable
data class CriteriaPayload(val criteria: List<CriterionPayload>)

@Serializable
data class BlockResponse(
    val id: Long = 0,
    @SerialName("is_blocked") val isBlocked: Boolean = false,
    @SerialName("blocked_reason") val blockedReason: String? = null,
)

@Serializable
data class CustomFieldOption(val value: JsonPrimitive, val label: String = "")

/** `GET /custom-fields` definition (CustomFieldsContext). */
@Serializable
data class CustomFieldDef(
    val id: Long,
    val label: String = "",
    @SerialName("field_type") val fieldType: String = "text",
    val description: String? = null,
    @SerialName("is_required") val isRequired: Boolean = false,
    @SerialName("show_on_card") val showOnCard: Boolean = false,
    val options: List<CustomFieldOption>? = null,
    @SerialName("applies_to_types") val appliesToTypes: List<Long>? = null,
)

@Serializable
data class CustomFieldValuesResponse(val values: JsonObject = JsonObject(emptyMap()))

@Serializable
data class CustomFieldValuesPayload(val values: JsonObject)
