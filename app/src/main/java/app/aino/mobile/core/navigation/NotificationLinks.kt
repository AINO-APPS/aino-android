package app.aino.mobile.core.navigation

import app.aino.mobile.core.push.PushTap

/** Requester-side decisions on a manual entry / overtime request (server `routes/manager.ts`). */
private val MANUAL_ENTRY_DECISION = Regex("^(Manual Entry|Overtime) (Approved|Rejected)", RegexOption.IGNORE_CASE)

/**
 * Web target for a notification the server stored without a `link` (rows
 * written before links existed, or older servers). `approval` is both
 * "awaiting your approval" (approver) and a manual-entry / overtime decision
 * (requester); only the decision titles identify the latter.
 */
fun legacyNotificationLink(type: String?, title: String?): String? = when (type) {
    "approval" ->
        if (title != null && MANUAL_ENTRY_DECISION.containsMatchIn(title.trim())) "/attendance#manual-entry"
        else "/manager?tab=approvals"
    "leave" -> "/attendance#leaves"
    else -> null
}

/**
 * Android route for a tapped system notification: a chat opens its thread;
 * otherwise the server's `link`, then its linked task, then a type-based
 * fallback, and finally the notifications page.
 */
fun pushTapRoute(tap: PushTap, linkRoute: (String) -> String? = { webLinkToRoute(it) }): String {
    if (tap.type == "chat_message" && tap.conversationId != null) return chatThreadRoute(tap.conversationId)
    tap.link?.takeIf { it.startsWith("/") }?.let(linkRoute)?.let { return it }
    tap.taskId?.takeIf { it > 0 }?.let { linkRoute("/tasks?task=$it") }?.let { return it }
    legacyNotificationLink(tap.type, tap.title)?.let(linkRoute)?.let { return it }
    return AinoDestination.Notifications.route
}
