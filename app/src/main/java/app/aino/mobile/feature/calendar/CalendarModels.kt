package app.aino.mobile.feature.calendar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET /api/calendar?from&to` row (`calendar_events` + task/meeting joins). */
@Serializable
data class CalendarEvent(
    val id: Long,
    val title: String,
    val description: String? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val color: String? = null,
    @SerialName("task_id") val taskId: Long? = null,
    @SerialName("task_title") val taskTitle: String? = null,
    @SerialName("meeting_id") val meetingId: Long? = null,
    @SerialName("meeting_code") val meetingCode: String? = null,
    @SerialName("meeting_status") val meetingStatus: String? = null,
    @SerialName("meeting_created_by") val meetingCreatedBy: Long? = null,
)

/** `POST /api/calendar` and `PUT /api/calendar/:id` body (`makePayload`). */
@Serializable
data class CalendarEventPayload(
    val title: String,
    val description: String,
    @SerialName("all_day") val allDay: Boolean,
    val color: String,
    @SerialName("task_id") val taskId: Long?,
    @SerialName("meeting_id") val meetingId: Long?,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

/** `GET /api/tasks?date&scope=personal&include_due=1` → `{ tasks }` for "Link to Task". */
@Serializable
data class CalendarTask(val id: Long, val title: String)

@Serializable
data class CalendarTaskList(val tasks: List<CalendarTask> = emptyList())

/** `GET /api/chat/search?q=` people for the participant picker. */
@Serializable
data class ParticipantUser(
    val id: Long,
    val name: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val email: String? = null,
    val avatar: String? = null,
) {
    fun display(): String = name ?: fullName ?: username.orEmpty()
}

@Serializable
data class MeetingSettings(val muteOnJoin: Boolean = false, val allowScreenShare: Boolean = true)

/** `POST /api/meetings` (created once per generated event occurrence). */
@Serializable
data class CreateMeetingRequest(
    val title: String,
    val description: String? = null,
    @SerialName("required_participant_ids") val requiredParticipantIds: List<Long>,
    @SerialName("optional_participant_ids") val optionalParticipantIds: List<Long>,
    val settings: MeetingSettings,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

@Serializable
data class CreatedMeeting(val id: Long, @SerialName("meeting_code") val meetingCode: String? = null)

@Serializable
data class ConflictRequest(
    @SerialName("user_ids") val userIds: List<Long>,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
)

@Serializable
data class ConflictEvent(val id: Long? = null, val title: String? = null)

@Serializable
data class ParticipantConflict(val userId: Long, val name: String, val events: List<ConflictEvent> = emptyList())

@Serializable
data class ConflictResponse(val conflicts: List<ParticipantConflict> = emptyList())

/** `GET /api/meetings/:code` — only the participant list is shown on the event. */
@Serializable
data class MeetingParticipant(
    @SerialName("user_id") val userId: Long,
    @SerialName("full_name") val fullName: String? = null,
    val username: String? = null,
    val role: String? = null,
    @SerialName("participant_type") val participantType: String? = null,
) {
    fun display(): String = fullName ?: username.orEmpty()
}

@Serializable
data class MeetingDetail(
    val id: Long? = null,
    @SerialName("meeting_code") val meetingCode: String? = null,
    val participants: List<MeetingParticipant> = emptyList(),
)
