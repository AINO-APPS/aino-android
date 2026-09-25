package app.aino.mobile.core.realtime

/**
 * Exhaustive registry of every realtime event the platform can emit (A-100).
 *
 * The server declares `WSType = string` and emits through
 * `sendToUser(tenantId, userId, "<type>", data)` and
 * `broadcast(tenantId, "<type>", data)`. There is no server-side enum to
 * import, so this registry is the Android-side contract and
 * `scripts/check-realtime-parity.mjs` re-derives the server list from those
 * call sites and fails the build on any divergence.
 *
 * Stage 11 mandate: no event may be silently dropped. Every entry below is
 * routed to a domain bus even when the consuming feature is not built yet —
 * an unconsumed typed event is still observable, whereas an unrouted one is
 * invisible.
 */
enum class RealtimeEvent(
    /** Wire identifier exactly as emitted by the server. */
    val type: String,
    /** Domain bus this event is dispatched to. */
    val domain: RealtimeDomain,
    /**
     * How the client should react, mirroring the web client's semantics.
     * Parity means matching web's patch-vs-refetch behavior, not inventing
     * a uniform policy.
     */
    val reaction: RealtimeReaction,
) {
    // ── Chat (20) ────────────────────────────────────────────────────────
    ChatMessage("chat_message", RealtimeDomain.Chat, RealtimeReaction.PatchThenReconcile),
    ChatTyping("chat_typing", RealtimeDomain.Chat, RealtimeReaction.Ephemeral),
    ChatReaction("chat_reaction", RealtimeDomain.Chat, RealtimeReaction.PatchThenReconcile),
    ChatReadReceipt("chat_read_receipt", RealtimeDomain.Chat, RealtimeReaction.Patch),
    ChatEdit("chat_edit", RealtimeDomain.Chat, RealtimeReaction.Patch),
    ChatDelete("chat_delete", RealtimeDomain.Chat, RealtimeReaction.Patch),
    ChatPin("chat_pin", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatMention("chat_mention", RealtimeDomain.Chat, RealtimeReaction.Notify),
    ChatMediaJob("chat_media_job", RealtimeDomain.Chat, RealtimeReaction.Patch),
    ChatPollVote("chat_poll_vote", RealtimeDomain.Chat, RealtimeReaction.Patch),
    ChatViewOnce("chat_view_once", RealtimeDomain.Chat, RealtimeReaction.Patch),
    ChatCleared("chat_cleared", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatUserBlocked("chat_user_blocked", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatConvArchived("chat_conv_archived", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatConvMuted("chat_conv_muted", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatConvDeleted("chat_conv_deleted", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatGroupCreated("chat_group_created", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatGroupAdded("chat_group_added", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatGroupRemoved("chat_group_removed", RealtimeDomain.Chat, RealtimeReaction.Refetch),
    ChatGroupRoleChanged("chat_group_role_changed", RealtimeDomain.Chat, RealtimeReaction.Refetch),

    // ── Calls (13) ───────────────────────────────────────────────────────
    CallIncoming("call_incoming", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallStarted("call_started", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallAccepted("call_accepted", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallRejected("call_rejected", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallEnded("call_ended", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallBusy("call_busy", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallError("call_error", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallSignal("call_signal", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallPeerReady("call_peer_ready", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallReconnect("call_reconnect", RealtimeDomain.Call, RealtimeReaction.CallControl),
    CallReaction("call_reaction", RealtimeDomain.Call, RealtimeReaction.Ephemeral),
    CallHandledElsewhere("call_handled_elsewhere", RealtimeDomain.Call, RealtimeReaction.CallControl),
    HuddleDeclined("huddle_declined", RealtimeDomain.Call, RealtimeReaction.CallControl),

    // ── Meetings (22) — handled by MeetingSession (P9) ───────────────────
    MeetingStarted("meeting_started", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingEnded("meeting_ended", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingUpdated("meeting_updated", RealtimeDomain.Meeting, RealtimeReaction.Refetch),
    MeetingCancelled("meeting_cancelled", RealtimeDomain.Meeting, RealtimeReaction.Refetch),
    MeetingInvite("meeting_invite", RealtimeDomain.Meeting, RealtimeReaction.Notify),
    MeetingRemoved("meeting_removed", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingParticipantJoined(
        "meeting_participant_joined",
        RealtimeDomain.Meeting,
        RealtimeReaction.MeetingState,
    ),
    MeetingParticipantLeft(
        "meeting_participant_left",
        RealtimeDomain.Meeting,
        RealtimeReaction.MeetingState,
    ),
    MeetingPeerReady("meeting_peer_ready", RealtimeDomain.Meeting, RealtimeReaction.MeetingMedia),
    MeetingSignal("meeting_signal", RealtimeDomain.Meeting, RealtimeReaction.MeetingMedia),
    MeetingMuted("meeting_muted", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingHandRaised("meeting_hand_raised", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingAudioLevel("meeting_audio_level", RealtimeDomain.Meeting, RealtimeReaction.Ephemeral),
    MeetingTrackState("meeting_track_state", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingScreenTrackId(
        "meeting_screen_track_id",
        RealtimeDomain.Meeting,
        RealtimeReaction.MeetingMedia,
    ),
    MeetingRequestQuality(
        "meeting_request_quality",
        RealtimeDomain.Meeting,
        RealtimeReaction.MeetingMedia,
    ),
    MeetingHlsStarted("meeting_hls_started", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingHlsStopped("meeting_hls_stopped", RealtimeDomain.Meeting, RealtimeReaction.MeetingState),
    MeetingMessage("meeting_message", RealtimeDomain.Meeting, RealtimeReaction.PatchThenReconcile),
    MeetingMessageAck("meeting_message_ack", RealtimeDomain.Meeting, RealtimeReaction.Patch),
    MeetingMessageError("meeting_message_error", RealtimeDomain.Meeting, RealtimeReaction.Patch),
    MeetingChatReplayDone(
        "meeting_chat_replay_done",
        RealtimeDomain.Meeting,
        RealtimeReaction.MeetingState,
    ),

    // ── Work surfaces (7) ────────────────────────────────────────────────
    TaskAssigned("task_assigned", RealtimeDomain.Tasks, RealtimeReaction.Refetch),
    ApprovalUpdate("approval_update", RealtimeDomain.Approvals, RealtimeReaction.Refetch),
    LeaveUpdate("leave_update", RealtimeDomain.Leaves, RealtimeReaction.Refetch),
    CalendarRefresh("calendar_refresh", RealtimeDomain.Calendar, RealtimeReaction.Refetch),
    Notification("notification", RealtimeDomain.Notifications, RealtimeReaction.Notify),
    ThemeChanged("theme_changed", RealtimeDomain.Identity, RealtimeReaction.Patch),
    UserStatus("user_status", RealtimeDomain.Presence, RealtimeReaction.Patch),

    // ── Identity and control plane (11) ──────────────────────────────────
    //
    // NOTE: `group_renamed`, `group_info_updated`, `member_added`,
    // `member_removed`, `member_left`, `owner_transferred` and `role_changed`
    // are deliberately absent. They are NOT WebSocket event types: the server
    // writes them through `emitSystemMessage` as `metadata.type` inside a
    // `formatType: "system"` message that fans out as an ordinary
    // `chat_message` envelope (server/modules/chat/chat.shared.ts). The web
    // client renders them from message metadata in SystemMessage.tsx. Android
    // must do the same — see ChatSystemMessageType.
    UserProfileUpdated("user_profile_updated", RealtimeDomain.Identity, RealtimeReaction.Patch),
    PlanChanged("plan_changed", RealtimeDomain.ControlPlane, RealtimeReaction.Regate),
    TenantFeaturesChanged(
        "tenant_features_changed",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Regate,
    ),
    BrandingChanged("branding_changed", RealtimeDomain.ControlPlane, RealtimeReaction.Regate),
    PlatformAccessRequestCreated(
        "platform_access_request_created",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Notify,
    ),
    PlatformAccessRequestApproved(
        "platform_access_request_approved",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Notify,
    ),
    PlatformAccessRequestDenied(
        "platform_access_request_denied",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Notify,
    ),
    PlatformAccessRequestUpdated(
        "platform_access_request_updated",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Notify,
    ),
    PlatformAccessSessionStarted(
        "platform_access_session_started",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Regate,
    ),
    PlatformAccessSessionEnded(
        "platform_access_session_ended",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Regate,
    ),
    PlatformAccessSessionRevoked(
        "platform_access_session_revoked",
        RealtimeDomain.ControlPlane,
        RealtimeReaction.Regate,
    ),
    ;

    companion object {
        private val byType: Map<String, RealtimeEvent> = entries.associateBy(RealtimeEvent::type)

        /** Resolve a wire type, or null when the server sent something unknown. */
        fun from(type: String): RealtimeEvent? = byType[type]

        fun inDomain(domain: RealtimeDomain): List<RealtimeEvent> = entries.filter { it.domain == domain }
    }
}

/**
 * In-band chat system-message subtypes (A-100).
 *
 * These are **not** WebSocket event types. The server persists them via
 * `emitSystemMessage` and delivers them inside an ordinary `chat_message`
 * envelope carrying `formatType: "system"` and a `metadata.type` discriminator
 * (`server/modules/chat/chat.shared.ts`). The web client renders them from that
 * metadata in `client/src/components/chat/SystemMessage.tsx`; Android renders
 * the same set so a group rename looks identical on both clients.
 *
 * Kept deliberately separate from [RealtimeEvent] so the realtime parity guard
 * cannot confuse the two contracts.
 */
enum class ChatSystemMessageType(val type: String) {
    CallStarted("call_started"),
    CallEnded("call_ended"),
    CallMissed("call_missed"),
    MeetingStarted("meeting_started"),
    MeetingEnded("meeting_ended"),
    MeetingJoined("meeting_joined"),
    MeetingLeft("meeting_left"),
    MeetingCreated("meeting_created"),
    MeetingUpdated("meeting_updated"),
    MeetingCancelled("meeting_cancelled"),
    ParticipantAdded("participant_added"),
    MemberAdded("member_added"),
    MemberRemoved("member_removed"),
    MemberLeft("member_left"),
    GroupRenamed("group_renamed"),
    GroupInfoUpdated("group_info_updated"),
    OwnerTransferred("owner_transferred"),
    RoleChanged("role_changed"),
    ;

    companion object {
        private val byType = entries.associateBy(ChatSystemMessageType::type)

        fun from(type: String?): ChatSystemMessageType? = type?.let(byType::get)
    }
}

/** Domain buses. Each feature subscribes to exactly its slice. */
enum class RealtimeDomain {
    Chat,
    Call,
    Meeting,
    Tasks,
    Leaves,
    Approvals,
    Calendar,
    Notifications,
    Presence,
    Identity,
    ControlPlane,
}

/** Client reaction policy, chosen to mirror the web client per event. */
enum class RealtimeReaction {
    /** Apply directly to local state; no network round trip. */
    Patch,

    /** Apply optimistically, then reconcile against the authoritative list. */
    PatchThenReconcile,

    /** Invalidate and reload the affected collection. */
    Refetch,

    /** Transient UI only (typing, audio level); never persisted or refetched. */
    Ephemeral,

    /** Raise a user-visible notification. */
    Notify,

    /** Drive the call session state machine. */
    CallControl,

    /** Meeting room state (participants, track state, hands) — `MeetingSession`. */
    MeetingState,

    /** Meeting mesh negotiation (offers/answers/candidates) — `MeetingSession`. */
    MeetingMedia,

    /** Re-evaluate navigation/feature gates, as the web client does live. */
    Regate,
}
