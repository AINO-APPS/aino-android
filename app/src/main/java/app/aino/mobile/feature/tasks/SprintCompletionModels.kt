package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

/** Counts and lists come from the server; the client never classifies carryover. */
@Serializable
data class CompletionPreview(
    val sprint: CompletionPreviewSprint? = null,
    @Serializable(with = CompletionTargetSerializer::class)
    val defaultTarget: String? = null,
    val moving: CompletionTickets = CompletionTickets(),
    val staying: CompletionTickets = CompletionTickets(),
    val movingPoints: Double = 0.0,
    /** Missing on legacy servers: never send new-target requests without this opt-in. */
    val supportsNewSprint: Boolean? = null,
    val defaultTargetType: String? = null,
    val selectedTarget: String? = null,
    val nextPlannedSprint: CompletionPreviewSprint? = null,
    val hasNext: Boolean? = null,
    val movingParents: List<CompletionTicket>? = null,
    val movingSubtasks: Int? = null,
    val newSprint: CompletionNewSprint? = null,
) {
    val doneTasks: Int get() = staying.count
    val totalTasks: Int get() = moving.count + staying.count
    val incompleteTasks: Int get() = moving.count
}

@Serializable
data class CompletionPreviewSprint(
    val id: Long = 0,
    val name: String = "",
    val status: String = "",
)

@Serializable
data class CompletionNewSprint(
    val name: String = "",
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
)

@Serializable
data class CompletionTicket(
    val id: Long,
    val title: String = "",
    @SerialName("issue_key") val issueKey: String? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class)
    @SerialName("story_points") val storyPoints: Double? = null,
    @SerialName("parent_task_id") val parentTaskId: Long? = null,
    val completed: Boolean? = null,
)

/** Older preview responses may contain a count instead of the ticket array. */
@Serializable(with = CompletionTicketsSerializer::class)
data class CompletionTickets(val tickets: List<CompletionTicket> = emptyList(), val count: Int = tickets.size)

object CompletionTicketsSerializer : KSerializer<CompletionTickets> {
    private val list = ListSerializer(CompletionTicket.serializer())
    override val descriptor: SerialDescriptor = list.descriptor
    override fun deserialize(decoder: Decoder): CompletionTickets {
        val json = decoder as JsonDecoder
        return when (val element = json.decodeJsonElement()) {
            is JsonArray -> CompletionTickets(json.json.decodeFromJsonElement(list, element))
            is JsonPrimitive -> CompletionTickets(count = element.intOrNull ?: 0)
            else -> CompletionTickets()
        }
    }
    override fun serialize(encoder: Encoder, value: CompletionTickets) =
        encoder.encodeSerializableValue(list, value.tickets)
}

object CompletionTargetSerializer : KSerializer<String?> {
    private val string = String.serializer().nullable
    override val descriptor: SerialDescriptor = string.descriptor
    override fun deserialize(decoder: Decoder): String? {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
    }
    override fun serialize(encoder: Encoder, value: String?) = encoder.encodeSerializableValue(string, value)
}
