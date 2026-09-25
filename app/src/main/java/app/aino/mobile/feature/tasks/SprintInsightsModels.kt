package app.aino.mobile.feature.tasks

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientDoubleSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Sprint Insights payloads (`server/routes/sprints.ts`, Phase 3 endpoints).

@Serializable
data class SprintRange(
    val id: Long = 0,
    val name: String? = null,
    @SerialName("start_date") val startDate: String = "",
    @SerialName("end_date") val endDate: String = "",
    val status: String? = null,
)

@Serializable
data class BurndownSnapshot(
    @SerialName("snapshot_date") val snapshotDate: String = "",
    @Serializable(with = LenientDoubleSerializer::class) @SerialName("remaining_points") val remainingPoints: Double = 0.0,
)

@Serializable
data class IdealPoint(val date: String = "", @Serializable(with = LenientDoubleSerializer::class) val remaining: Double = 0.0)

@Serializable
data class BurndownResponse(
    val sprint: SprintRange = SprintRange(),
    val snapshots: List<BurndownSnapshot> = emptyList(),
    val ideal: List<IdealPoint> = emptyList(),
    @Serializable(with = LenientDoubleSerializer::class) val startScope: Double = 0.0,
)

@Serializable
data class VelocitySprint(
    val id: Long,
    val name: String = "",
    @SerialName("completed_at") val completedAt: String? = null,
    @Serializable(with = LenientDoubleSerializer::class) @SerialName("velocity_points") val velocityPoints: Double = 0.0,
)

@Serializable
data class VelocityResponse(
    val sprints: List<VelocitySprint> = emptyList(),
    @Serializable(with = LenientDoubleSerializer::class) val average: Double = 0.0,
)

@Serializable
data class CfdDay(
    val date: String = "",
    @Serializable(with = LenientIntSerializer::class) val open: Int = 0,
    @Serializable(with = LenientIntSerializer::class) @SerialName("in_progress") val inProgress: Int = 0,
    @Serializable(with = LenientIntSerializer::class) @SerialName("in_review") val inReview: Int = 0,
    @Serializable(with = LenientIntSerializer::class) val done: Int = 0,
) {
    fun value(category: String): Int = when (category) {
        "open" -> open
        "in_progress" -> inProgress
        "in_review" -> inReview
        else -> done
    }
}

@Serializable
data class CfdResponse(val series: List<CfdDay> = emptyList())

@Serializable
data class CycleStats(
    @Serializable(with = LenientDoubleNullableSerializer::class) val avg: Double? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class) val median: Double? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class) val p90: Double? = null,
    val n: Int = 0,
)

@Serializable
data class CycleTask(
    val id: Long,
    val title: String = "",
    @Serializable(with = LenientDoubleNullableSerializer::class) @SerialName("story_points") val storyPoints: Double? = null,
    @SerialName("type_name") val typeName: String? = null,
    @SerialName("type_color") val typeColor: String? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class) @SerialName("cycle_days") val cycleDays: Double? = null,
    @Serializable(with = LenientDoubleNullableSerializer::class) @SerialName("lead_days") val leadDays: Double? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class CycleResponse(
    val tasks: List<CycleTask> = emptyList(),
    val cycle: CycleStats = CycleStats(),
    val lead: CycleStats = CycleStats(),
)

@Serializable
data class ActionItem(
    val id: Long = 0,
    val text: String = "",
    val done: Boolean = false,
    val owner: Long? = null,
    @SerialName("due_date") val dueDate: String? = null,
)

@Serializable
data class Retrospective(
    val id: Long? = null,
    @SerialName("went_well") val wentWell: String? = null,
    @SerialName("to_improve") val toImprove: String? = null,
    val summary: String? = null,
    @SerialName("team_mood") val teamMood: Int? = null,
    @SerialName("action_items") val actionItems: List<ActionItem> = emptyList(),
)

@Serializable
data class RetrospectiveResponse(val retrospective: Retrospective? = null)

@Serializable
data class RetrospectivePayload(
    @SerialName("went_well") val wentWell: String,
    @SerialName("to_improve") val toImprove: String,
    val summary: String,
    @SerialName("team_mood") val teamMood: Int?,
    @SerialName("action_items") val actionItems: List<ActionItem>,
)

@Serializable
data class SprintTasksResponse(val tasks: List<Task> = emptyList())

/** Everything the Insights page fetches in parallel for one sprint (each part may fail alone). */
data class SprintInsightsData(
    val stats: SprintStats?,
    val cfd: CfdResponse?,
    val cycle: CycleResponse?,
    val retro: Retrospective?,
    val tasks: List<Task>,
)

/** CFD band order bottom → top, with the chart's fixed colours. */
val CFD_BANDS = listOf(
    Triple("open", "Open", 0xFF9CA3AFL),
    Triple("in_progress", "In Progress", 0xFFF59E0BL),
    Triple("in_review", "In Review", 0xFF3B82F6L),
    Triple("done", "Done", 0xFF10B981L),
)

/** Burndown actual point x-index: days from sprint start, clamped to the ideal span. */
fun burndownDayIndex(snapshotDate: String, startDate: String, totalDays: Int): Int {
    val start = localDateOf(startDate) ?: return 0
    val day = localDateOf(snapshotDate) ?: return 0
    val idx = java.time.temporal.ChronoUnit.DAYS.between(start, day).toInt()
    return idx.coerceIn(0, maxOf(1, totalDays))
}

/** Sprint select groups: Active, Planned, Completed (empty groups dropped). */
fun sprintGroups(sprints: List<AvailableSprint>): List<Pair<String, List<AvailableSprint>>> =
    listOf("active", "planned", "completed").mapNotNull { status ->
        sprints.filter { it.status == status }.takeIf { it.isNotEmpty() }?.let { status.replaceFirstChar(Char::uppercase) to it }
    }

/** Initial pick: a valid `?sprint_id=`, else active, else the first. */
fun initialInsightsSprint(sprints: List<AvailableSprint>, requested: Long?): Long? =
    requested?.takeIf { id -> sprints.any { it.id == id } }
        ?: sprints.firstOrNull { it.status == "active" }?.id
        ?: sprints.firstOrNull()?.id

val MOODS = listOf("😡" to "Very low", "😟" to "Low", "😐" to "OK", "🙂" to "Good", "😄" to "Great")

val INSIGHT_STATUS_LABELS = mapOf("pending" to "To Do", "in_progress" to "In Progress", "in_review" to "In Review", "done" to "Done")
