package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientIntSerializer
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** `service_desk_tickets` row + `tenant_name` (`GET /service-desk/tickets`). */
@Serializable
data class ServiceTicket(
    val id: Long,
    val title: String = "",
    val description: String? = null,
    @SerialName("ticket_type") val ticketType: String = "other",
    val priority: String = "medium",
    val status: String = "open",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
    @SerialName("submitted_by_user_id") val submittedByUserId: Long? = null,
    @SerialName("submitted_by_name") val submittedByName: String? = null,
    @SerialName("tenant_name") val tenantName: String? = null,
    @SerialName("assigned_to") val assignedToRaw: JsonElement? = null,
    @SerialName("admin_notes") val adminNotes: String? = null,
) {
    val assignedTo: String? get() = (assignedToRaw as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}

@Serializable
data class ServiceTicketsResponse(val tickets: List<ServiceTicket> = emptyList())

/** `COUNT(*) … FILTER` columns arrive as quoted numbers. */
@Serializable
data class ServiceDeskStats(
    @Serializable(with = LenientIntSerializer::class) val total: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val open: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val acknowledged: Int = 0,
    @Serializable(with = LenientIntSerializer::class) @SerialName("in_progress") val inProgress: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val resolved: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val closed: Int = 0,
) {
    fun count(status: String): Int = when (status) {
        "open" -> open
        "acknowledged" -> acknowledged
        "in_progress" -> inProgress
        "resolved" -> resolved
        "closed" -> closed
        else -> 0
    }
}

@Serializable
data class CreateTicketPayload(
    val title: String,
    val description: String,
    @SerialName("ticket_type") val ticketType: String,
    val priority: String,
)

data class DeskOption(val value: String, val label: String, val color: Long)

/** ServiceDeskTab `TICKET_TYPES` / `PRIORITIES` / `STATUSES`. */
val TICKET_TYPES = listOf(
    DeskOption("bug", "Bug Report", 0xFFEF4444),
    DeskOption("feature_request", "Feature Request", 0xFF8B5CF6),
    DeskOption("access_issue", "Access Issue", 0xFFF59E0B),
    DeskOption("other", "Other", 0xFF6B7280),
)
val TICKET_PRIORITIES = listOf(
    DeskOption("low", "Low", 0xFF22C55E),
    DeskOption("medium", "Medium", 0xFFF59E0B),
    DeskOption("high", "High", 0xFFEF4444),
    DeskOption("critical", "Critical", 0xFFDC2626),
)
val TICKET_STATUSES = listOf(
    DeskOption("open", "Open", 0xFF3B82F6),
    DeskOption("acknowledged", "Acknowledged", 0xFF8B5CF6),
    DeskOption("in_progress", "In Progress", 0xFFF59E0B),
    DeskOption("resolved", "Resolved", 0xFF22C55E),
    DeskOption("closed", "Closed", 0xFF6B7280),
)

fun ticketType(value: String) = TICKET_TYPES.firstOrNull { it.value == value } ?: TICKET_TYPES[3]
fun ticketPriority(value: String) = TICKET_PRIORITIES.firstOrNull { it.value == value } ?: TICKET_PRIORITIES[1]
fun ticketStatus(value: String) = TICKET_STATUSES.firstOrNull { it.value == value } ?: TICKET_STATUSES[0]

/** Stats chips: Total, then every status with tickets (Open always shows). */
fun visibleStatusChips(stats: ServiceDeskStats): List<DeskOption> =
    TICKET_STATUSES.filter { stats.count(it.value) > 0 || it.value == "open" }

/** Owners may cancel their own ticket only while it is still open. */
fun canDeleteTicket(ticket: ServiceTicket, userId: Long?): Boolean =
    userId != null && ticket.submittedByUserId == userId && ticket.status == "open"

fun deleteTicketMessage(ticket: ServiceTicket, userId: Long?): String =
    if (ticket.submittedByUserId == userId && ticket.status == "open") {
        "Cancel ticket \"${ticket.title}\"? This will also remove it from the platform team's backlog."
    } else "Delete ticket \"${ticket.title}\"? This cannot be undone."

fun ticketsQuery(status: String, type: String): String {
    val params = buildList {
        if (status.isNotEmpty()) add("status" to status)
        if (type.isNotEmpty()) add("ticket_type" to type)
    }
    return "service-desk/tickets" + if (params.isEmpty()) "" else "?" + params.joinToString("&") { "${it.first}=" + URLEncoder.encode(it.second, "UTF-8") }
}

class ServiceDeskRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    // @api GET service-desk/tickets
    fun tickets(status: String, type: String): List<ServiceTicket> =
        json.decodeFromString<ServiceTicketsResponse>(api.execute(ApiRequest(path = ticketsQuery(status, type))).bodyAsString()).tickets

    fun stats(): ServiceDeskStats = json.decodeFromString(api.execute(ApiRequest(path = "service-desk/stats")).bodyAsString())

    // @api POST service-desk/tickets
    fun create(payload: CreateTicketPayload) = send("POST", "service-desk/tickets", json.encodeToString(payload), "Failed to submit ticket")

    // @api DELETE service-desk/tickets/:id
    fun delete(id: Long) = send("DELETE", "service-desk/tickets/$id", null, "Failed to delete ticket")

    private fun send(method: String, path: String, body: String?, fallback: String) {
        try {
            api.execute(ApiRequest(method, path, body = body?.toByteArray()))
        } catch (error: ApiError.Http) {
            val message = runCatching { json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
            throw TaskFailure(message ?: fallback, error.statusCode, error)
        }
    }
}
