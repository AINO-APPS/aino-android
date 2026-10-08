package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import app.aino.mobile.core.db.MessageEntity
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import java.net.URLEncoder
import java.util.UUID
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChatRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    // Dynamic-route coverage declarations for the endpoint parity scanner.
    // @api GET chat/blocked
    // @api POST chat/calls/:callId/accept
    // @api POST chat/calls/:callId/end
    // @api POST chat/calls/:callId/reject
    // @api GET chat/calls/active
    // @api POST chat/calls/delete
    // @api DELETE chat/conversations/:id
    // @api PUT chat/conversations/:id/group
    // @api POST chat/conversations/:id/leave
    // @api DELETE chat/conversations/:id/messages
    // @api GET chat/conversations/:id/messages
    // @api PUT chat/conversations/:id/participants/:userId/role
    // @api GET chat/conversations/:id/pinned
    // @api POST chat/conversations/:id/polls
    // @api POST chat/conversations/:id/transfer-owner
    // @api POST chat/conversations/:id/unread
    // @api POST chat/conversations/group
    // @api POST chat/conversations/:id/avatar
    // @api GET chat/conversations/:id/invite-link
    // @api PUT chat/conversations/:id/invite-link
    // @api POST chat/conversations/:id/invite-link/reset
    // @api GET chat/conversations/:id/join-requests
    // @api POST chat/conversations/:id/join-requests/:userId/approve
    // @api POST chat/conversations/:id/join-requests/:userId/deny
    // @api GET chat/conversations/:id/active-call
    // @api GET chat/invite/:token
    // @api POST chat/invite/:token/join
    // @api DELETE chat/invite/:token/request
    // @api GET chat/link-preview
    // @api POST chat/messages/:id/delivered
    // @api POST chat/messages/:id/view
    // @api GET chat/polls/:id
    // @api POST chat/polls/:id/vote
    // @api GET chat/presence
    // @api GET chat/search
    // @api GET chat/search-messages
    // @api GET chat/starred
    // @api DELETE chat/users/:userId/block
    // @api POST chat/users/:userId/block
    fun loadConversations(): List<ChatConversation> =
        decode(api.execute(ApiRequest(path = "chat/conversations")))

    fun loadPresence(userIds: List<Long>): Map<Long, ChatPresence> {
        if (userIds.isEmpty()) return emptyMap()
        val ids = userIds.distinct().joinToString(",")
        val raw = json.parseToJsonElement(
            api.execute(ApiRequest(path = "chat/presence?userIds=$ids")).bodyAsString(),
        ).jsonObject
        return raw.mapNotNull { (key, value) ->
            key.toLongOrNull()?.let { it to json.decodeFromJsonElement<ChatPresence>(value) }
        }.toMap()
    }

    fun searchUsers(term: String): List<ChatUser> {
        if (term.trim().length < 2) return emptyList()
        return decode(api.execute(ApiRequest(path = "chat/search?q=${URLEncoder.encode(term.trim().take(100), "UTF-8")}")))
    }

    fun createDirect(userId: Long): ConversationCreated =
        mutate("chat/conversations", DirectConversationRequest(userId))

    fun createGroup(name: String, userIds: List<Long>): ConversationCreated =
        mutate("chat/conversations/group", CreateGroupRequest(name.trim(), userIds.distinct()))

    fun loadBlockedUsers(): List<ChatUser> =
        decode(api.execute(ApiRequest(path = "chat/blocked")))

    fun blockUser(userId: Long): BlockResponse =
        mutate("chat/users/$userId/block", Unit)

    fun unblockUser(userId: Long): BlockResponse =
        mutate("chat/users/$userId/block", Unit, method = "DELETE")

    fun markRead(conversationId: Long): ChatOk =
        mutate("chat/conversations/$conversationId/read", Unit)

    fun loadMessages(conversationId: Long, before: Long? = null): List<ChatMessage> {
        val suffix = before?.let { "?limit=50&before=$it" } ?: "?limit=50"
        return decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/messages$suffix")))
    }

    fun sendMessage(
        conversationId: Long,
        content: String,
        replyToId: Long? = null,
        clientMessageId: String? = null,
    ): ChatMessage = mutate(
        "chat/conversations/$conversationId/messages",
        SendMessageRequest(content.trim(), replyToId, clientMessageId),
    )

    fun searchMessages(term: String, conversationId: Long? = null): List<ChatMessage> {
        if (term.trim().length < 2) return emptyList()
        val query = "q=${encode(term.trim())}" + (conversationId?.let { "&convId=$it" } ?: "")
        return decode(api.execute(ApiRequest(path = "chat/search-messages?$query")))
    }

    fun loadPinnedMessages(conversationId: Long): List<ChatMessage> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/pinned")))

    fun loadStarredMessages(): List<ChatMessage> =
        decode(api.execute(ApiRequest(path = "chat/starred")))

    fun loadSharedFiles(conversationId: Long): List<SharedChatFile> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/files")))

    fun loadReadReceipts(conversationId: Long): List<ReadReceipt> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/read-status")))

    fun toggleReaction(messageId: Long, emoji: String): ChatOk =
        mutate("chat/messages/$messageId/reactions", ReactionRequest(emoji))

    fun editMessage(messageId: Long, content: String): ChatOk =
        mutate("chat/messages/$messageId", EditMessageRequest(content.trim()), method = "PUT")

    fun deleteMessage(messageId: Long): ChatOk =
        mutate("chat/messages/$messageId", Unit, method = "DELETE")

    fun toggleStar(messageId: Long): ToggleStarResponse =
        mutate("chat/messages/$messageId/star", Unit)

    fun toggleMessagePin(messageId: Long): ToggleMessagePinResponse =
        mutate("chat/messages/$messageId/pin", Unit)

    fun forwardMessage(messageId: Long, conversationIds: List<Long>): ChatOk {
        require(conversationIds.isNotEmpty()) { "Choose at least one conversation" }
        require(conversationIds.size <= 20) { "Choose no more than 20 conversations" }
        return mutate("chat/messages/$messageId/forward", ForwardMessageRequest(conversationIds.distinct()))
    }

    fun acknowledgeDelivered(messageId: Long): ChatOk =
        mutate("chat/messages/$messageId/delivered", Unit)

    fun viewMessage(messageId: Long): ViewMessageResponse =
        mutate("chat/messages/$messageId/view", Unit)

    fun uploadFile(conversationId: Long, upload: ChatUpload, onProgress: ((Long, Long) -> Unit)? = null): ChatMessage {
        val boundary = "aino-${UUID.randomUUID()}"
        val multipart = streamChatMultipart(upload, boundary)
        try {
            return decode(
                api.execute(
                    ApiRequest(
                        "POST",
                        "chat/conversations/$conversationId/files",
                        headers = mapOf("Content-Type" to multipart.contentType),
                        streamBody = multipart,
                        onUploadProgress = onProgress,
                    ),
                ),
            )
        } catch (error: ApiError.Http) {
            val message = runCatching {
                json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull() ?: "File upload failed"
            throw ChatFailure(message, error.statusCode, error)
        }
    }

    fun cancelMediaJob(mediaJobId: Long): MediaJobResponse =
        mutate("chat/media-jobs/$mediaJobId/cancel", Unit)

    fun retryMediaJob(mediaJobId: Long): MediaJobResponse =
        mutate("chat/media-jobs/$mediaJobId/retry", Unit)

    fun loadCalls(): List<CallLog> = decode(api.execute(ApiRequest(path = "chat/calls")))

    fun loadActiveCall(): CallLog? =
        decode(api.execute(ApiRequest(path = "chat/calls/active")))

    fun acceptCall(callId: Long, conversationId: Long): CallActionResponse =
        mutate("chat/calls/$callId/accept", CallActionRequest(conversationId))

    fun rejectCall(callId: Long, conversationId: Long): CallActionResponse =
        mutate("chat/calls/$callId/reject", CallActionRequest(conversationId))

    fun endCall(callId: Long, conversationId: Long): CallActionResponse =
        mutate("chat/calls/$callId/end", CallActionRequest(conversationId))

    fun deleteCalls(ids: List<Long>): DeleteCallsResponse =
        mutate("chat/calls/delete", DeleteCallsRequest(ids = ids.distinct()))

    fun deleteAllCalls(): DeleteCallsResponse =
        mutate("chat/calls/delete", DeleteCallsRequest(all = true))

    fun loadConversationCalls(conversationId: Long): List<CallLog> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/calls")))

    fun loadMembers(conversationId: Long): List<ConversationMember> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/members")))

    fun updateGroup(conversationId: Long, request: GroupUpdateRequest): ChatOk =
        mutate("chat/conversations/$conversationId/group", request, method = "PUT")

    fun leaveGroup(conversationId: Long): ChatOk =
        mutate("chat/conversations/$conversationId/leave", Unit)

    fun setParticipantRole(conversationId: Long, userId: Long, role: String): RoleResponse =
        mutate(
            "chat/conversations/$conversationId/participants/$userId/role",
            SetParticipantRoleRequest(role),
            method = "PUT",
        )

    fun transferOwner(conversationId: Long, userId: Long): ChatOk =
        mutate("chat/conversations/$conversationId/transfer-owner", TransferOwnerRequest(userId))

    fun addMembers(conversationId: Long, userIds: List<Long>): ChatOk =
        updateGroup(conversationId, GroupUpdateRequest(addUserIds = userIds.distinct()))

    fun removeMember(conversationId: Long, userId: Long): ChatOk =
        updateGroup(conversationId, GroupUpdateRequest(removeUserIds = listOf(userId)))

    /** `upload.single("avatar")` on the group (owner/admin); returns the new photo URL. */
    fun uploadGroupAvatar(conversationId: Long, fileName: String, mimeType: String, bytes: ByteArray): GroupAvatarResponse {
        val multipart = app.aino.mobile.core.network.buildAvatarMultipart(fileName, mimeType, bytes, "aino-${UUID.randomUUID()}")
        return send("chat/conversations/$conversationId/avatar", multipart.first, multipart.second)
    }

    fun removeGroupAvatar(conversationId: Long): ChatOk =
        mutate("chat/conversations/$conversationId/group", RemoveGroupAvatarRequest(null), method = "PUT")

    fun inviteLink(conversationId: Long): InviteLinkState =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/invite-link")))

    fun updateInviteLink(conversationId: Long, update: InviteLinkUpdate): InviteLinkState =
        mutate("chat/conversations/$conversationId/invite-link", update, method = "PUT")

    fun resetInviteLink(conversationId: Long): InviteLinkState =
        mutate("chat/conversations/$conversationId/invite-link/reset", Unit)

    fun invitePreview(token: String): InvitePreview = try {
        decode(api.execute(ApiRequest(path = "chat/invite/${encode(token)}")))
    } catch (error: ApiError.Http) {
        throw ChatFailure(serverError(error) ?: "This group link is no longer valid", error.statusCode, error)
    }

    fun joinByInvite(token: String): JoinInviteResult = mutate("chat/invite/${encode(token)}/join", Unit)

    fun cancelJoinRequest(token: String): ChatOk = mutate("chat/invite/${encode(token)}/request", Unit, method = "DELETE")

    fun joinRequests(conversationId: Long): List<JoinRequest> =
        decode(api.execute(ApiRequest(path = "chat/conversations/$conversationId/join-requests")))

    fun resolveJoinRequest(conversationId: Long, userId: Long, approve: Boolean): ResolveJoinResult =
        mutate("chat/conversations/$conversationId/join-requests/$userId/${if (approve) "approve" else "deny"}", Unit)

    /** The group's running call, or null (server 204). */
    fun activeGroupCall(conversationId: Long): ActiveGroupCall? {
        val body = api.execute(ApiRequest(path = "chat/conversations/$conversationId/active-call")).bodyAsString()
        return body.takeIf(String::isNotBlank)?.let { json.decodeFromString<ActiveGroupCall>(it) }
    }

    fun markUnread(conversationId: Long): UnreadResponse =
        mutate("chat/conversations/$conversationId/unread", Unit)

    fun clearMessages(conversationId: Long): ChatOk =
        mutate("chat/conversations/$conversationId/messages", Unit, method = "DELETE")

    fun deleteConversation(conversationId: Long): ChatOk =
        mutate("chat/conversations/$conversationId", Unit, method = "DELETE")

    fun createPoll(
        conversationId: Long,
        question: String,
        options: List<String>,
        multiSelect: Boolean = false,
    ): CreatePollResponse = mutate(
        "chat/conversations/$conversationId/polls",
        CreatePollRequest(question, options, multiSelect),
    )

    fun loadPoll(pollId: Long): ChatPoll =
        decode(api.execute(ApiRequest(path = "chat/polls/$pollId")))

    fun votePoll(pollId: Long, optionIndex: Int): PollVoteResponse =
        mutate("chat/polls/$pollId/vote", PollVoteRequest(optionIndex))

    fun loadLinkPreview(url: String): LinkPreview =
        decode(api.execute(ApiRequest(path = "chat/link-preview?url=${encode(url)}")))

    fun togglePinConversation(conversationId: Long): TogglePinResponse =
        mutate("chat/conversations/$conversationId/pin", Unit)

    fun toggleFavouriteConversation(conversationId: Long): ToggleFavouriteResponse =
        mutate("chat/conversations/$conversationId/favourite", Unit)

    fun setMute(conversationId: Long, duration: String?): ToggleMuteResponse =
        mutate("chat/conversations/$conversationId/mute", MuteRequest(duration))

    fun toggleArchive(conversationId: Long): ToggleArchiveResponse =
        mutate("chat/conversations/$conversationId/archive", Unit)

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw ChatFailure(serverError(error) ?: "Chat action failed", error.statusCode, error)
        }
    }

    private inline fun <reified R> send(path: String, contentType: String, body: ByteArray): R = try {
        decode(api.execute(ApiRequest("POST", path, headers = mapOf("Content-Type" to contentType), body = body)))
    } catch (error: ApiError.Http) {
        throw ChatFailure(serverError(error) ?: "Upload failed", error.statusCode, error)
    }

    private fun serverError(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class ChatCache(
    private val scope: CacheScope,
    private val cache: ScopedCache,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun replace(conversations: List<ChatConversation>) =
        cache.replaceConversations(conversations.map { it.toEntity(scope) })

    suspend fun snapshot(): List<ChatConversation> =
        cache.conversationSnapshot().map { it.toCachedConversation() }

    suspend fun replaceMessages(conversationId: Long, messages: List<ChatMessage>) =
        cache.replaceMessages(conversationId, messages.map { message ->
            message.toEntity(scope, conversationId).copy(payloadJson = runCatching { json.encodeToString(message) }.getOrNull())
        })

    /** Live rows (realtime) join the stored thread without dropping the rest of it. */
    suspend fun upsertMessages(conversationId: Long, messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        cache.upsertMessages(messages.map { message ->
            message.toEntity(scope, conversationId).copy(payloadJson = runCatching { json.encodeToString(message) }.getOrNull())
        })
    }

    suspend fun clearMessages(conversationId: Long) = cache.clearMessages(conversationId)

    suspend fun deleteConversation(conversationId: Long) = cache.deleteConversation(conversationId)

    suspend fun messageSnapshot(conversationId: Long): List<ChatMessage> =
        cache.messageSnapshot(conversationId).map { it.decodeMessage() ?: it.toCachedMessage() }

    /**
     * Rows older than [before] (newest [limit]) stored with their full payload;
     * empty when any is a text-only legacy row, which would repaint once the server answers.
     */
    suspend fun fullMessages(conversationId: Long, before: Long = Long.MAX_VALUE, limit: Int = Int.MAX_VALUE): List<ChatMessage> =
        cache.messageSnapshot(conversationId).filter { it.messageId < before }.takeLast(limit)
            .map { it.decodeMessage() ?: return emptyList() }

    private fun MessageEntity.decodeMessage(): ChatMessage? =
        payloadJson?.let { runCatching { json.decodeFromString<ChatMessage>(it) }.getOrNull() }
}

class ChatFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)