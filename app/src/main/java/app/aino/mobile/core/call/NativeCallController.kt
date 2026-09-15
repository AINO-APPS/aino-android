package app.aino.mobile.core.call

import android.app.Activity
import android.content.Context

/** Native application entry point replacing the legacy Expo bridge module. */
class NativeCallController(
    private val context: Context,
    private val lockScreen: LockScreenController = LockScreenController(),
    val pip: PipController = PipController(),
) {
    fun startRinging(options: Map<String, String>): Boolean = CallRingService.start(context, options)
    fun stopRinging() = CallRingService.stop(context)

    fun startActiveCall(options: Map<String, String>) = ActiveCallService.start(context, options)
    fun stopActiveCall() = ActiveCallService.stop(context)

    fun showCallOverLockScreen(activity: Activity) = lockScreen.showForCall(activity)
    fun hideCallFromLockScreen(activity: Activity) = lockScreen.hideAfterCall(activity)

    fun pendingAction(): Map<String, String>? = PendingCallActionStore.read(context)
    fun clearPendingAction() = PendingCallActionStore.clear(context)
}

fun incomingCallServiceExtras(data: Map<String, String>, bearerToken: String?): Map<String, String> = mapOf(
    CallRingService.EXTRA_TITLE to data.getValue("title"),
    CallRingService.EXTRA_BODY to data.getValue("body"),
    CallRingService.EXTRA_CALL_ID to data.getValue("callId"),
    CallRingService.EXTRA_CONVERSATION_ID to data.getValue("conversationId"),
    CallRingService.EXTRA_CALLER_ID to data.getValue("callerId"),
    CallRingService.EXTRA_CALLER_NAME to data["callerName"].orEmpty(),
    CallRingService.EXTRA_CALLER_AVATAR to data["callerAvatar"].orEmpty(),
    CallRingService.EXTRA_CALL_TYPE to data.getValue("callType"),
    CallRingService.EXTRA_TOKEN to bearerToken.orEmpty(),
    CallRingService.EXTRA_SCHEME to "aino",
    CallRingService.EXTRA_EXPIRES_AT to data["expiresAt"].orEmpty(),
)