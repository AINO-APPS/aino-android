package app.aino.mobile.feature.tasks

enum class TaskCapability {
    Edit,
    ChangeState,
    Cancel,
    Transfer,
    Delete,
    PlaceInSprint,
    Schedule,
    MoveProject,
    AddSubtask,
    Comment,
    LogWork,
    LogWorkForOthers,
    EditEstimate,
}

fun Task.capability(action: TaskCapability): TaskCapabilityDecision {
    val can = capabilities?.can ?: return TaskCapabilityDecision()
    return when (action) {
        TaskCapability.Edit -> can.edit
        TaskCapability.ChangeState -> can.changeState
        TaskCapability.Cancel -> can.cancel
        TaskCapability.Transfer -> can.transfer
        TaskCapability.Delete -> can.delete
        TaskCapability.PlaceInSprint -> can.placeInSprint
        TaskCapability.Schedule -> can.schedule
        TaskCapability.MoveProject -> can.canMoveProject
        TaskCapability.AddSubtask -> can.canAddSubtask
        TaskCapability.Comment -> can.comment
        TaskCapability.LogWork -> can.logWork
        TaskCapability.LogWorkForOthers -> can.logWorkForOthers
        TaskCapability.EditEstimate -> can.canEditEstimate
    }
}

fun Task.transitionDecision(stateId: Long): TaskCapabilityDecision {
    val ticketCapabilities = capabilities ?: return TaskCapabilityDecision()
    return ticketCapabilities.transitions.firstOrNull { it.to == stateId }
        ?.let { TaskCapabilityDecision(it.allowed, it.reason) }
        ?: TaskCapabilityDecision(allowed = false, reason = "INVALID_TRANSITION")
}

fun Task.dropTargetDecision(stateId: Long, accepting: Boolean): TaskCapabilityDecision {
    val transition = transitionDecision(stateId)
    if (!transition.allowed) return transition
    val changeState = capability(TaskCapability.ChangeState)
    if (!changeState.allowed) return changeState
    return if (accepting) transition else TaskCapabilityDecision(false, "WIP_EXCEEDED")
}

fun capabilityReasonCopy(reason: String?): String = when (reason) {
    "INVALID_TRANSITION" -> "This ticket cannot move to that status."
    "WIP_EXCEEDED" -> "This column has reached its work limit."
    "ACCESS_DENIED" -> "You don't have permission to do this."
    "SPRINT_FROZEN" -> "This sprint is frozen."
    "IN_SPRINT" -> "Remove this ticket from its sprint before scheduling it."
    "SPRINT_TEAM_MISMATCH" -> "Choose a sprint from the ticket's team."
    "SPRINT_PROJECT_MISMATCH" -> "Choose a sprint from the ticket's project."
    "PROJECT_ARCHIVED" -> "This project is archived. Tickets are read-only; comments are still available."
    "READ_ONLY_ROLE" -> "Your project role allows viewing and commenting only."
    "CANCELLED_TERMINAL" -> "Cancelled tickets cannot be changed."
    "WORKLOG_CANCELLED" -> "Work cannot be logged on a cancelled ticket."
    "SUBTASK_FOLLOWS_PARENT" -> "Subtasks follow their parent's project and sprint."
    "PROJECT_HAS_NO_SPRINTS" -> "This project does not use sprints."
    "INVALID_HIERARCHY" -> "This parent and ticket type are not a valid hierarchy."
    else -> "This action is not available."
}

fun TaskCapabilityDecision.reasonCopy(): String = capabilityReasonCopy(reason)

fun capabilityFailureCopy(message: String?): String? =
    message?.takeIf { it in CAPABILITY_REASON_CODES }?.let(::capabilityReasonCopy)

private val CAPABILITY_REASON_CODES = setOf(
    "INVALID_TRANSITION",
    "WIP_EXCEEDED",
    "ACCESS_DENIED",
    "SPRINT_FROZEN",
    "IN_SPRINT",
    "SPRINT_TEAM_MISMATCH",
    "SPRINT_PROJECT_MISMATCH",
    "PROJECT_ARCHIVED",
    "READ_ONLY_ROLE",
    "CANCELLED_TERMINAL",
    "WORKLOG_CANCELLED",
    "SUBTASK_FOLLOWS_PARENT",
    "PROJECT_HAS_NO_SPRINTS",
    "INVALID_HIERARCHY",
)
