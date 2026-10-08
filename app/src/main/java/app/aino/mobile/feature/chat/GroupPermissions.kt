package app.aino.mobile.feature.chat

/**
 * Client mirror of the server's group permission rules (`utils/groupPerms`
 * `canDo`). Used only to decide which controls to show; the server remains
 * authoritative and answers 403 for anything not allowed.
 *
 * Org governance (hr_admin and above) is not mirrored: those users manage
 * groups from the web admin, and showing them member-level controls here is
 * the safe default.
 */
data class GroupPermissions(
    val role: String?,
    val postPolicy: String = "all",
    val addPolicy: String = "admins",
) {
    val isOwner: Boolean get() = role == "owner"
    val isAdmin: Boolean get() = role == "owner" || role == "admin"
    val isMember: Boolean get() = role != null

    val canSend: Boolean get() = isMember && (postPolicy != "admins" || isAdmin)
    val canEditInfo: Boolean get() = isAdmin
    val canAddMembers: Boolean get() = if (addPolicy == "all") isMember else isAdmin
    val canRemoveMembers: Boolean get() = isAdmin
    val canManageLink: Boolean get() = isAdmin
    val canChangeRoles: Boolean get() = isOwner
    val canTransferOwnership: Boolean get() = isOwner
    val canChangePolicies: Boolean get() = isOwner

    /** An admin may not remove the owner; nobody removes themselves (that is Leave). */
    fun canRemove(target: ConversationMember, currentUserId: Long?): Boolean =
        canRemoveMembers && target.id != currentUserId && (target.role != "owner")

    companion object {
        /**
         * The members list is fresher than the conversation row after a role
         * change, so prefer the caller's role from it.
         */
        fun of(conversation: ChatConversation, members: List<ConversationMember>, currentUserId: Long?): GroupPermissions =
            GroupPermissions(
                role = members.firstOrNull { it.id == currentUserId }?.role ?: conversation.myRole,
                postPolicy = conversation.postPolicy ?: "all",
                addPolicy = conversation.addPolicy ?: "admins",
            )
    }
}

/** Owner first, then admins, then members, each alphabetically (server order, kept after local edits). */
fun sortMembers(members: List<ConversationMember>): List<ConversationMember> =
    members.sortedWith(compareBy<ConversationMember> { roleRank(it.role) }.thenBy { it.display().lowercase() })

private fun roleRank(role: String): Int = when (role) {
    "owner" -> 0
    "admin" -> 1
    else -> 2
}

/** "Owner" / "Admin" badge text, or null for a regular member. */
fun roleBadge(role: String): String? = when (role) {
    "owner" -> "Owner"
    "admin" -> "Admin"
    else -> null
}
