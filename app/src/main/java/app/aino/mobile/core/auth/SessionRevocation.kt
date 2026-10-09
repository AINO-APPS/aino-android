package app.aino.mobile.core.auth

import android.content.Context
import app.aino.mobile.core.db.AinoDatabase
import app.aino.mobile.core.db.OutboxWorker
import app.aino.mobile.core.db.SessionScopeStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The account signed in on another phone, so the server ended this device's
 * session (one active session per client class). Reached through the socket
 * close reason while the app is open, or the `session_revoked` push when it is
 * backgrounded or killed.
 */
object SessionRevocation {
    /** Socket close reason sent by the server (`services/sessionSignOut.ts`). */
    const val SIGNED_IN_ELSEWHERE_REASON = "Signed in on another device"

    const val SIGNED_IN_ELSEWHERE_MESSAGE =
        "You were signed out because your account signed in on another device."

    private const val PREFS = "aino_session_revocation"
    private const val PENDING_MESSAGE = "pending_message"

    private val _revoked = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Emits the user-facing message when a push signed this device out while the UI runs. */
    val revoked: SharedFlow<String> = _revoked.asSharedFlow()

    fun messageFor(closeReason: String?): String =
        if (closeReason == SIGNED_IN_ELSEWHERE_REASON) SIGNED_IN_ELSEWHERE_MESSAGE else AuthViewModel.SIGNED_IN_ELSEWHERE

    /** The message of a revocation handled while no UI was running; read once. */
    fun consumePendingMessage(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val message = prefs.getString(PENDING_MESSAGE, null) ?: return null
        prefs.edit().remove(PENDING_MESSAGE).apply()
        return message
    }

    /** A fresh sign-in supersedes an old revocation notice. */
    fun clearPendingMessage(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(PENDING_MESSAGE).apply()
    }

    /**
     * Push path: drop the credential and everything scoped to the signed-out
     * user, without waiting for the UI. A running [AuthViewModel] observes
     * [revoked] and switches to the sign-in screen; otherwise the next launch
     * shows the pending message.
     */
    suspend fun signOutLocally(context: Context) {
        val appContext = context.applicationContext
        val container = app.aino.mobile.core.AppContainer.get(appContext)
        if (container.tokens.getToken().isNullOrBlank()) return
        container.tokens.clearToken()
        container.tokens.clearFeatures()
        val scopes = SessionScopeStore(appContext)
        scopes.read()?.let { scope ->
            OutboxWorker.cancel(appContext, scope)
            AinoDatabase.get(appContext).dao().clearScope(scope.tenantId, scope.userId)
        }
        scopes.clear()
        container.responses.apply { this.scope = null; clearAll() }
        app.aino.mobile.core.push.PushTokenRegistrar.forget(appContext)
        app.aino.mobile.core.push.NotificationReconciler.clearAll(appContext)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PENDING_MESSAGE, SIGNED_IN_ELSEWHERE_MESSAGE).apply()
        _revoked.tryEmit(SIGNED_IN_ELSEWHERE_MESSAGE)
    }
}
