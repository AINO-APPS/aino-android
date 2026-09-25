package app.aino.mobile.feature.meeting

import android.content.Context
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.call.ActiveCallService
import app.aino.mobile.core.call.CallAudio
import app.aino.mobile.core.call.webrtc.IceConfigDto
import app.aino.mobile.core.call.webrtc.IceConfigRepository
import app.aino.mobile.core.call.webrtc.LocalMedia
import app.aino.mobile.core.call.webrtc.RtcPeer
import app.aino.mobile.core.call.webrtc.WebRtcRuntime
import app.aino.mobile.core.call.webrtc.fallbackIceConfig
import app.aino.mobile.core.call.webrtc.IceCandidateSignal
import app.aino.mobile.core.realtime.RealtimeEnvelope
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.VideoTrack

enum class MeetingStatus { Joining, Joined, Failed }

enum class PeerLink { Connecting, Connected, Reconnecting, Failed }

data class MeetingPeer(
    val userId: Long,
    val name: String,
    val avatar: String?,
    val muted: Boolean = false,
    val videoOff: Boolean = false,
    val screenSharing: Boolean = false,
    val handRaised: Boolean = false,
    val link: PeerLink = PeerLink.Connecting,
    val video: VideoTrack? = null,
    val screen: VideoTrack? = null,
    val level: Float = 0f,
    val levelAt: Long = 0,
)

data class MeetingState(
    val meeting: Meeting,
    val code: String,
    val selfId: Long,
    val selfName: String,
    val selfAvatar: String?,
    val status: MeetingStatus = MeetingStatus.Joining,
    val joinedAt: Long = System.currentTimeMillis(),
    val muted: Boolean = false,
    val videoOff: Boolean = true,
    val handRaised: Boolean = false,
    val speakerOn: Boolean = true,
    val localVideo: VideoTrack? = null,
    val selfLevel: Float = 0f,
    val peers: List<MeetingPeer> = emptyList(),
    val activeSpeakerId: Long? = null,
    val messages: List<MeetingChatMessage> = emptyList(),
    val unread: Int = 0,
    val chatOpen: Boolean = false,
) {
    val isHost: Boolean get() = meeting.isHost(selfId)
    val presenter: MeetingPeer? get() = peers.firstOrNull { it.screenSharing && it.screen != null }
}

/**
 * The web `MeetingContext` + `useMeetingState`: one meeting (or group call)
 * per process, kept alive across navigation so the room can be minimised to
 * the floating widget. Full-mesh WebRTC — existing members offer to a
 * newcomer; collisions resolve by [meetingPolite]. All state changes run on
 * the main thread; WebRTC callbacks hop onto it.
 * ponytail: no `meeting_request_quality` / high-count video demotion; add when
 * meshes above 6 peers are common on phones.
 */
class MeetingSession(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository = MeetingRepository(AppContainer.get(appContext).api)
    private val audio = CallAudio(appContext, ActiveCallService.OWNER_MEETING)
    private val _state = MutableStateFlow<MeetingState?>(null)
    val state: StateFlow<MeetingState?> = _state.asStateFlow()

    /** Injected by the shell's realtime ViewModel. */
    @Volatile var send: (RealtimeEnvelope) -> Boolean = { false }

    private var media: LocalMedia? = null
    private var ice: IceConfigDto? = null
    private val peers = mutableMapOf<Long, RtcPeer>()
    private val screenTrackIds = mutableMapOf<Long, MutableSet<String>>()
    private val iceRestarts = mutableMapOf<Long, Int>()
    private val peerTimers = mutableMapOf<Long, Job>()
    private val pendingChats = LinkedHashMap<String, JsonObject>()
    private val sessionJobs = mutableListOf<Job>()
    private var joinAcked = false
    private var readySent = false

    private val meetingId: Long? get() = _state.value?.meeting?.id

    fun join(meeting: Meeting, selfId: Long, selfName: String, selfAvatar: String?, initialMuted: Boolean, initialVideoOff: Boolean) {
        val current = _state.value
        if (current != null && current.meeting.id == meeting.id) return
        if (current != null) leave()
        val runtime = WebRtcRuntime.shared(appContext)
        val local = LocalMedia(appContext, runtime, withVideo = true).also { media = it }
        local.setMuted(initialMuted)
        val videoOn = !initialVideoOff && local.setVideoEnabled(true)
        val speaker = meeting.isHuddle != true || meeting.isVideoCall()
        audio.start(speaker)
        ActiveCallService.start(
            appContext,
            mapOf(
                ActiveCallService.EXTRA_TITLE to (meeting.title ?: "Meeting"),
                ActiveCallService.EXTRA_BODY to "Tap to return to the meeting",
                ActiveCallService.EXTRA_CALL_TYPE to "video",
            ),
            ActiveCallService.OWNER_MEETING,
        )
        joinAcked = false
        readySent = false
        _state.value = MeetingState(
            meeting = meeting,
            code = meeting.meetingCode.orEmpty(),
            selfId = selfId,
            selfName = selfName,
            selfAvatar = selfAvatar,
            muted = initialMuted,
            videoOff = !videoOn,
            speakerOn = speaker,
            localVideo = local.videoTrack,
        )
        sessionJobs += scope.launch {
            // The first offer/answer waits for ICE servers (web: `waitForIceConfig`).
            ice = withContext(Dispatchers.IO) {
                runCatching { IceConfigRepository(AppContainer.get(appContext).api).load() }.getOrDefault(fallbackIceConfig())
            }
            sendJoin()
            launch {
                delay(2_500)
                if (!joinAcked) sendJoin()
                delay(12_500)
                if (!joinAcked) _state.update { it?.copy(status = MeetingStatus.Failed) }
            }
        }
        sessionJobs += scope.launch { loadHistory(null) }
        sessionJobs += scope.launch { levelTicker() }
    }

    private fun sendJoin() {
        val id = meetingId ?: return
        send(meetingFrame("meeting_join", id))
        send(meetingFrame("meeting_subscribe", id))
        scope.launch {
            delay(300)
            sendTrackState()
        }
    }

    private suspend fun loadHistory(sinceId: Long?) {
        val code = _state.value?.code?.takeIf(String::isNotBlank) ?: return
        val history = withContext(Dispatchers.IO) { runCatching { repository.messages(code, sinceId = sinceId) }.getOrNull() } ?: return
        _state.update { s -> s?.copy(messages = history.fold(s.messages) { list, dto -> list.mergeMessage(dto.toChat()) }) }
    }

    /** Web: audio level every 200 ms, sent at most every 500 ms above 0.05; active speaker every ~350 ms. */
    private suspend fun levelTicker() {
        var lastSent = 0L
        while (scope.isActive) {
            delay(200)
            val s = _state.value ?: return
            val level = if (s.muted) 0f else WebRtcRuntime.micLevel
            val now = System.currentTimeMillis()
            if (level > 0.05f && now - lastSent > 500) {
                lastSent = now
                send(meetingFrame("meeting_audio_level", s.meeting.id) { put("level", Math.round(level * 1000) / 1000.0) })
            }
            val speaker = s.peers.filter { now - it.levelAt <= 2_000 && it.level >= 0.08f }.maxByOrNull { it.level }?.userId
                ?: s.selfId.takeIf { level >= 0.08f }
            _state.update { it?.copy(selfLevel = level, activeSpeakerId = speaker) }
        }
    }

    fun onRealtimeEvent(envelope: RealtimeEnvelope) {
        val s = _state.value ?: return
        if (!envelope.type.startsWith("meeting_")) return
        val data = envelope.data as? JsonObject ?: return
        if (envelope.type == "meeting_message_error") {
            val cid = data.string("clientMsgId") ?: return
            _state.update { st -> st?.copy(messages = st.messages.updateDelivery(cid) { it.copy(delivery = ChatDelivery.Failed, failureReason = data.string("reason")) }) }
            return
        }
        if (data.long("meetingId") != s.meeting.id) return
        when (envelope.type) {
            "meeting_participant_joined" -> onParticipantJoined(data)
            "meeting_signal" -> {
                val from = data.long("fromUserId") ?: return
                val signal = parseMeetingSignal(data["signal"] as? JsonObject ?: return) ?: return
                val peer = peers[from] ?: createPeer(from, initiator = false) ?: return
                when (signal) {
                    is MeetingSignal.Offer -> peer.handleOffer(signal.sdp)
                    is MeetingSignal.Answer -> peer.handleAnswer(signal.sdp)
                    is MeetingSignal.Candidate -> peer.handleCandidate(signal.candidate)
                }
            }
            "meeting_peer_ready" -> {
                val userId = data.long("userId") ?: return
                if (userId == s.selfId) return
                val existing = peers[userId]
                if (existing == null) createPeer(userId, initiator = true) else existing.resendLocalOffer()
            }
            "meeting_participant_left" -> data.long("userId")?.let(::removePeer)
            "meeting_ended" -> end()
            "meeting_muted" -> {
                val muted = data.flag("muted") ?: true
                media?.setMuted(muted)
                _state.update { it?.copy(muted = muted) }
                sendTrackState()
            }
            "meeting_hand_raised" -> {
                val userId = data.long("userId") ?: return
                val raised = data.flag("raised") ?: false
                if (userId == s.selfId) _state.update { it?.copy(handRaised = raised) } else updatePeer(userId) { it.copy(handRaised = raised) }
            }
            "meeting_track_state" -> {
                val userId = data.long("userId") ?: return
                updatePeer(userId) { p ->
                    p.copy(
                        muted = data.flag("muted") ?: p.muted,
                        videoOff = data.flag("videoOff") ?: p.videoOff,
                        screenSharing = data.flag("screenSharing") ?: p.screenSharing,
                    )
                }
            }
            "meeting_screen_track_id" -> {
                val from = data.long("fromUserId") ?: return
                val ids = screenTrackIds.getOrPut(from) { mutableSetOf() }
                if (data.flag("sharing") == true) data.string("trackId")?.let(ids::add) else ids.clear()
                updatePeer(from) { p ->
                    when {
                        p.video != null && p.video.safeId() in ids -> p.copy(video = p.screen, screen = p.video)
                        ids.isEmpty() -> p.copy(screen = null)
                        else -> p
                    }
                }
            }
            "meeting_message" -> {
                val message = (data["message"] as? JsonObject)?.toMeetingChat() ?: return
                val replay = data.flag("_replay") == true
                _state.update { st ->
                    st ?: return@update null
                    val fromOther = message.senderId != st.selfId && !message.system
                    val isNew = st.messages.none { (message.id != null && it.id == message.id) || (message.clientMsgId != null && it.clientMsgId == message.clientMsgId) }
                    st.copy(
                        messages = st.messages.mergeMessage(message),
                        unread = if (fromOther && isNew && !replay && !st.chatOpen) st.unread + 1 else st.unread,
                    )
                }
            }
            "meeting_message_ack" -> {
                val cid = data.string("clientMsgId") ?: return
                pendingChats.remove(cid)
                _state.update { st ->
                    st?.copy(messages = st.messages.updateDelivery(cid) { it.copy(id = data.long("id") ?: it.id, createdAt = data.string("createdAt") ?: it.createdAt, delivery = ChatDelivery.Sent) })
                }
            }
            "meeting_audio_level" -> {
                val userId = data.long("userId") ?: return
                val level = data.float("level") ?: return
                updatePeer(userId) { it.copy(level = level, levelAt = System.currentTimeMillis()) }
            }
        }
    }

    private fun onParticipantJoined(data: JsonObject) {
        val s = _state.value ?: return
        val userId = data.long("userId") ?: return
        if (userId == s.selfId) {
            joinAcked = true
            _state.update { it?.copy(status = MeetingStatus.Joined) }
            val existing = (data["existingPeers"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            existing.forEach { peer ->
                val id = peer.long("userId") ?: return@forEach
                if (id == s.selfId) return@forEach
                upsertPeer(id, peer.string("fullName") ?: peer.string("username"), peer.string("avatar"))
                createPeer(id, initiator = false)
            }
            if (existing.isNotEmpty() && !readySent) {
                readySent = true
                send(meetingFrame("meeting_ready", s.meeting.id))
            }
        } else {
            upsertPeer(userId, data.string("fullName") ?: data.string("username"), data.string("avatar"))
            createPeer(userId, initiator = true)
            sendTrackState()
        }
        capBitrates()
    }

    private fun createPeer(userId: Long, initiator: Boolean): RtcPeer? {
        val s = _state.value ?: return null
        val config = ice ?: return null
        val local = media ?: return null
        peers.remove(userId)?.close()
        if (s.peers.none { it.userId == userId }) {
            val member = s.meeting.participants.firstOrNull { it.userId == userId }
            upsertPeer(userId, member?.display(), member?.avatar)
        }
        val peer = runCatching {
            RtcPeer(WebRtcRuntime.shared(appContext), config, meetingPolite(s.selfId, userId), PeerListener(userId))
        }.getOrElse { return null }
        peer.addLocalTracks(local.audioTrack, local.videoTrack)
        peers[userId] = peer
        updatePeer(userId) { it.copy(link = PeerLink.Connecting, video = null, screen = null) }
        armConnectTimeout(userId)
        if (initiator) peer.createOffer()
        return peer
    }

    /** ParticipantTile: 30 s without a connection shows "Couldn't connect" + Retry. */
    private fun armConnectTimeout(userId: Long) {
        peerTimers.remove(userId)?.cancel()
        peerTimers[userId] = scope.launch {
            delay(30_000)
            if (peers[userId]?.connectionState != PeerConnection.PeerConnectionState.CONNECTED) {
                updatePeer(userId) { it.copy(link = PeerLink.Failed) }
            }
        }
    }

    fun retryPeer(userId: Long) {
        iceRestarts.remove(userId)
        createPeer(userId, initiator = true)
    }

    private inner class PeerListener(private val userId: Long) : RtcPeer.Listener {
        override fun onLocalDescription(type: String, sdp: String) {
            scope.launch { sendSignal(userId, meetingDescriptionSignal(type, sdp)) }
        }

        override fun onLocalCandidate(candidate: IceCandidateSignal) {
            scope.launch { sendSignal(userId, meetingCandidateSignal(candidate)) }
        }

        override fun onRemoteTrack(track: MediaStreamTrack) {
            if (track !is VideoTrack) return
            scope.launch {
                val screenIds = screenTrackIds[userId].orEmpty()
                updatePeer(userId) { p ->
                    when {
                        track.safeId() in screenIds -> p.copy(screen = track)
                        p.video == null -> p.copy(video = track)
                        else -> p.copy(screen = track)
                    }
                }
            }
        }

        override fun onConnectionState(state: PeerConnection.PeerConnectionState) {
            scope.launch { onPeerState(userId, state) }
        }
    }

    private fun onPeerState(userId: Long, state: PeerConnection.PeerConnectionState) {
        when (state) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                peerTimers.remove(userId)?.cancel()
                iceRestarts.remove(userId)
                updatePeer(userId) { it.copy(link = PeerLink.Connected) }
                capBitrates()
            }
            PeerConnection.PeerConnectionState.DISCONNECTED,
            PeerConnection.PeerConnectionState.FAILED,
            -> {
                updatePeer(userId) { if (it.link == PeerLink.Failed) it else it.copy(link = PeerLink.Reconnecting) }
                peerTimers.remove(userId)?.cancel()
                // UMS:928 — ICE restart after 2 s still disconnected, at most 3 per peer.
                peerTimers[userId] = scope.launch {
                    delay(2_000)
                    val peer = peers[userId] ?: return@launch
                    if (peer.connectionState == PeerConnection.PeerConnectionState.CONNECTED) return@launch
                    val attempts = iceRestarts[userId] ?: 0
                    if (attempts < 3) {
                        iceRestarts[userId] = attempts + 1
                        peer.restartIce()
                        armConnectTimeout(userId)
                    } else {
                        updatePeer(userId) { it.copy(link = PeerLink.Failed) }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun sendSignal(userId: Long, signal: JsonObject) {
        val id = meetingId ?: return
        send(meetingFrame("meeting_signal", id) {
            put("targetUserId", userId)
            put("signal", signal)
        })
    }

    private fun capBitrates() {
        val video = meetingVideoBitrate(peers.size)
        peers.values.forEach { it.capBitrates(video) }
    }

    private fun sendTrackState() {
        val s = _state.value ?: return
        send(meetingFrame("meeting_track_state", s.meeting.id) {
            put("muted", s.muted)
            put("videoOff", s.videoOff)
            put("screenSharing", false)
        })
    }

    private fun upsertPeer(userId: Long, name: String?, avatar: String?) {
        _state.update { s ->
            s ?: return@update null
            val existing = s.peers.firstOrNull { it.userId == userId }
            if (existing != null) {
                s.copy(peers = s.peers.map { if (it.userId == userId) it.copy(name = name ?: it.name, avatar = avatar ?: it.avatar) else it })
            } else {
                s.copy(peers = s.peers + MeetingPeer(userId, name ?: "Participant", avatar))
            }
        }
    }

    private fun updatePeer(userId: Long, transform: (MeetingPeer) -> MeetingPeer) {
        _state.update { s -> s?.copy(peers = s.peers.map { if (it.userId == userId) transform(it) else it }) }
    }

    private fun removePeer(userId: Long) {
        peers.remove(userId)?.close()
        peerTimers.remove(userId)?.cancel()
        iceRestarts.remove(userId)
        screenTrackIds.remove(userId)
        _state.update { s -> s?.copy(peers = s.peers.filterNot { it.userId == userId }) }
        capBitrates()
    }

    // ─── Controls ────────────────────────────────────────────────────────────

    fun toggleMute() {
        val s = _state.value ?: return
        val muted = !s.muted
        media?.setMuted(muted)
        _state.update { it?.copy(muted = muted) }
        send(meetingFrame("meeting_track_state", s.meeting.id) { put("muted", muted) })
    }

    fun toggleVideo() {
        val s = _state.value ?: return
        val on = media?.setVideoEnabled(s.videoOff) ?: false
        _state.update { it?.copy(videoOff = !on) }
        send(meetingFrame("meeting_track_state", s.meeting.id) { put("videoOff", !on) })
    }

    fun switchCamera() {
        media?.switchCamera()
    }

    fun toggleSpeaker() {
        val on = !(_state.value?.speakerOn ?: return)
        audio.setSpeaker(on)
        _state.update { it?.copy(speakerOn = on) }
    }

    fun toggleHand() {
        val s = _state.value ?: return
        val raised = !s.handRaised
        _state.update { it?.copy(handRaised = raised) }
        send(meetingFrame("meeting_raise_hand", s.meeting.id) {
            put("raised", raised)
            put("clientMsgId", UUID.randomUUID().toString())
        })
    }

    fun muteParticipant(userId: Long, muted: Boolean) {
        val id = meetingId ?: return
        send(meetingFrame("meeting_mute_participant", id) {
            put("targetUserId", userId)
            put("muted", muted)
            put("clientMsgId", UUID.randomUUID().toString())
        })
    }

    /** Web `muteAllParticipants`: `muteParticipant(uid)` for every remote participant. */
    fun muteAll() {
        _state.value?.peers?.forEach { muteParticipant(it.userId, true) }
    }

    fun addParticipant(userId: Long) {
        val id = meetingId ?: return
        send(meetingFrame("meeting_add_participant", id) { put("targetUserId", userId) })
    }

    fun setChatOpen(open: Boolean) {
        _state.update { it?.copy(chatOpen = open, unread = if (open) 0 else it.unread) }
    }

    fun sendChat(text: String) {
        val s = _state.value ?: return
        val normalized = normalizeMeetingChat(text) ?: return
        val cid = UUID.randomUUID().toString()
        _state.update {
            it?.copy(messages = it.messages + MeetingChatMessage(clientMsgId = cid, senderId = s.selfId, senderName = s.selfName, text = normalized, createdAt = java.time.Instant.now().toString(), delivery = ChatDelivery.Sending))
        }
        dispatchChat(cid, buildJsonObject {
            put("text", normalized)
            put("clientMsgId", cid)
        })
    }

    /** Web `sendChatFile`: upload into the meeting conversation, then post the URL over WS. */
    fun sendFile(fileName: String, mimeType: String, bytes: ByteArray) {
        val s = _state.value ?: return
        val cid = UUID.randomUUID().toString()
        _state.update {
            it?.copy(messages = it.messages + MeetingChatMessage(clientMsgId = cid, senderId = s.selfId, senderName = s.selfName, fileName = fileName, fileSize = bytes.size.toLong(), createdAt = java.time.Instant.now().toString(), delivery = ChatDelivery.Uploading))
        }
        scope.launch {
            val conversationId = s.meeting.conversationId
            if (conversationId == null) {
                dispatchChat(cid, buildJsonObject {
                    put("clientMsgId", cid)
                    put("text", "📎 $fileName")
                    put("file_name", fileName)
                    put("file_size", bytes.size)
                })
                return@launch
            }
            val uploaded = withContext(Dispatchers.IO) { runCatching { repository.uploadChatFile(conversationId, fileName, mimeType, bytes) }.getOrNull() }
            if (uploaded == null) {
                _state.update { st -> st?.copy(messages = st.messages.updateDelivery(cid) { it.copy(delivery = ChatDelivery.Failed, failureReason = "upload-failed") }) }
                return@launch
            }
            _state.update { st -> st?.copy(messages = st.messages.updateDelivery(cid) { it.copy(fileUrl = uploaded.url, fileName = uploaded.name, fileSize = uploaded.size, delivery = ChatDelivery.Sending) }) }
            dispatchChat(cid, buildJsonObject {
                put("clientMsgId", cid)
                put("file_url", uploaded.url)
                put("file_name", uploaded.name)
                put("file_size", uploaded.size)
            })
        }
    }

    fun retryMessage(clientMsgId: String) {
        val payload = pendingChats[clientMsgId] ?: return
        _state.update { st -> st?.copy(messages = st.messages.updateDelivery(clientMsgId) { it.copy(delivery = ChatDelivery.Sending, failureReason = null) }) }
        dispatchChat(clientMsgId, payload)
    }

    /** Queued until acked; resent after a socket reconnect (web outbound queue). */
    private fun dispatchChat(clientMsgId: String, payload: JsonObject) {
        val id = meetingId ?: return
        pendingChats[clientMsgId] = payload
        send(RealtimeEnvelope("meeting_chat", buildJsonObject {
            put("meetingId", id)
            payload.forEach { (k, v) -> put(k, v) }
        }))
    }

    /**
     * Fed every realtime state change. The flag lives here (process scope), not
     * in composition, so an Activity recreation that keeps the socket does not
     * look like a reconnect and tear the mesh down.
     */
    fun onRealtimeState(connected: Boolean) {
        if (connected && !lastConnected) onRealtimeReconnected()
        lastConnected = connected
    }

    private var lastConnected = false

    /** UMS:2490 — a new socket rebuilds the mesh from `existingPeers` and replays chat. */
    private fun onRealtimeReconnected() {
        val s = _state.value ?: return
        peers.values.forEach(RtcPeer::close)
        peers.clear()
        peerTimers.values.forEach(Job::cancel)
        peerTimers.clear()
        iceRestarts.clear()
        readySent = false
        _state.update { st -> st?.copy(peers = st.peers.map { it.copy(link = PeerLink.Connecting, video = null, screen = null) }) }
        sendJoin()
        pendingChats.toMap().forEach { (cid, payload) -> dispatchChat(cid, payload) }
        val since = s.messages.mapNotNull { it.id }.maxOrNull() ?: 0
        send(meetingFrame("meeting_chat_replay", s.meeting.id) { put("sinceMessageId", since) })
    }

    fun leave() {
        val id = meetingId ?: return
        send(meetingFrame("meeting_leave", id))
        teardown()
    }

    /** Host only (`created_by`): ends it for everyone. */
    fun endForAll() {
        val id = meetingId ?: return
        send(meetingFrame("meeting_end", id))
        teardown()
    }

    private fun end() = teardown()

    private fun teardown() {
        sessionJobs.forEach(Job::cancel)
        sessionJobs.clear()
        peerTimers.values.forEach(Job::cancel)
        peerTimers.clear()
        peers.values.forEach(RtcPeer::close)
        peers.clear()
        screenTrackIds.clear()
        iceRestarts.clear()
        pendingChats.clear()
        _state.value = null
        media?.dispose()
        media = null
        audio.stop()
        ActiveCallService.stop(appContext, ActiveCallService.OWNER_MEETING)
    }
}

private fun VideoTrack.safeId(): String? = runCatching { id() }.getOrNull()

object MeetingRuntime {
    @Volatile private var instance: MeetingSession? = null

    fun get(context: Context): MeetingSession = instance ?: synchronized(this) {
        instance ?: MeetingSession(context.applicationContext).also { instance = it }
    }
}
