package app.aino.mobile.feature.tasks

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProjectMovePayload(@SerialName("project_id") val projectId: Long)

@Serializable
data class ProjectMovePreview(
    @SerialName("target_project_id") val targetProjectId: Long,
    @SerialName("key_format") val keyFormat: String,
    @SerialName("workflow_state") val workflowState: WorkflowState,
    @SerialName("leaves_sprint") val leavesSprint: Boolean,
)

@Serializable
data class ProjectMoveResponse(val task: Task)
