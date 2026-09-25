package app.aino.mobile.feature.chat

import app.aino.mobile.core.media.nextPlaybackSpeed
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatVoiceAndMediaTest {
    private fun VoicePhase.run(vararg events: VoiceEvent, elapsed: Long = 5_000): List<Pair<VoicePhase, VoiceEffect>> {
        var phase = this
        return events.map { event -> phase.reduce(event, elapsed).also { phase = it.first } }
    }

    @Test fun `hold then release sends`() {
        assertEquals(
            listOf(VoicePhase.Holding to VoiceEffect.Start, VoicePhase.Idle to VoiceEffect.Send),
            VoicePhase.Idle.run(VoiceEvent.Press, VoiceEvent.Release),
        )
    }

    @Test fun `release under one second discards`() {
        assertEquals(VoicePhase.Idle to VoiceEffect.Discard, VoicePhase.Holding.reduce(VoiceEvent.Release, 400))
    }

    @Test fun `slide left cancels`() {
        assertEquals(VoicePhase.Idle to VoiceEffect.Discard, VoicePhase.Holding.reduce(VoiceEvent.SlideCancel))
    }

    @Test fun `lock, pause, resume, stop to draft, then send`() {
        assertEquals(
            listOf(
                VoicePhase.Holding to VoiceEffect.Start,
                VoicePhase.Locked to VoiceEffect.None,
                VoicePhase.Paused to VoiceEffect.Pause,
                VoicePhase.Locked to VoiceEffect.Resume,
                VoicePhase.Draft to VoiceEffect.StopToDraft,
                VoicePhase.Idle to VoiceEffect.Send,
            ),
            VoicePhase.Idle.run(VoiceEvent.Press, VoiceEvent.Lock, VoiceEvent.Pause, VoiceEvent.Resume, VoiceEvent.Stop, VoiceEvent.Send),
        )
    }

    @Test fun `release after lock is ignored and paused note can be sent directly`() {
        assertEquals(VoicePhase.Locked to VoiceEffect.None, VoicePhase.Locked.reduce(VoiceEvent.Release))
        assertEquals(VoicePhase.Idle to VoiceEffect.Send, VoicePhase.Paused.reduce(VoiceEvent.Send))
    }

    @Test fun `draft delete discards`() {
        assertEquals(VoicePhase.Idle to VoiceEffect.Discard, VoicePhase.Draft.reduce(VoiceEvent.Delete))
    }

    @Test fun `amplitude is normalised and clamped`() {
        assertEquals(0f, amplitudeLevel(-5))
        assertEquals(1f, amplitudeLevel(40_000))
        assertEquals(0.5f, amplitudeLevel(32_767 / 4), 0.01f)
    }

    @Test fun `media urls resolve every relative form against the server origin`() {
        val origin = "https://acme.aino.app"
        assertEquals("https://acme.aino.app/uploads/a.png", resolveChatMediaUrl("/uploads/a.png", origin))
        assertEquals("https://acme.aino.app/uploads/a.png", resolveChatMediaUrl("uploads/a.png", origin))
        assertEquals("https://acme.aino.app/api/chat/files/1", resolveChatMediaUrl("/api/chat/files/1", origin + "/"))
        assertEquals("https://cdn.example.com/a.png", resolveChatMediaUrl("https://cdn.example.com/a.png", origin))
        assertEquals("content://x/y", resolveChatMediaUrl("content://x/y", origin))
        assertEquals("file:///data/voice.m4a", resolveChatMediaUrl("file:///data/voice.m4a", origin))
    }

    @Test fun `playback speed cycles like the web`() {
        assertEquals(1.5f, nextPlaybackSpeed(1f))
        assertEquals(2f, nextPlaybackSpeed(1.5f))
        assertEquals(1f, nextPlaybackSpeed(2f))
    }

    private val ana = ConversationMember(id = 2, username = "ana", fullName = "Ana Lima")
    private val bob = ConversationMember(id = 3, username = "bob", fullName = "Bob Stone")

    @Test fun `mention query is the trailing at-word only`() {
        assertEquals("an", activeMentionQuery("hi @an"))
        assertEquals("", activeMentionQuery("hi @"))
        assertEquals(null, activeMentionQuery("hi @ana done"))
    }

    @Test fun `mention suggestions match name or username and exclude self`() {
        assertEquals(listOf(ana), mentionSuggestions(listOf(ana, bob), "lim", currentUserId = 1))
        assertEquals(listOf(bob), mentionSuggestions(listOf(ana, bob), "", currentUserId = 2))
    }

    @Test fun `inserting a mention replaces the query with the full name`() {
        assertEquals("hi @Ana Lima ", insertMention("hi @an", ana))
    }

    @Test fun `first link matches the web URL_RE`() {
        assertEquals("https://aino.app/x?y=1", firstLinkIn("see https://aino.app/x?y=1 now"))
        assertEquals("http://a.b", firstLinkIn("http://a.b<tag>"))
        assertEquals(null, firstLinkIn("no links here"))
    }

    @Test fun `ws chat_message carries mentions and link preview only when present`() {
        val plain = chatMessageEnvelope(7, "hi", "c1", null, emptyList(), null)
        assertEquals("chat_message", plain.type)
        assertEquals(setOf("conversationId", "content", "clientMsgId"), plain.data!!.jsonObject.keys)
        val rich = chatMessageEnvelope(7, "hi @Ana", "c2", 3, listOf(2L), LinkPreview(url = "https://a.b", title = "A"))
        assertEquals(setOf("conversationId", "content", "clientMsgId", "replyToId", "mentions", "linkPreview"), rich.data!!.jsonObject.keys)
    }

    @Test fun `unread divider sits on the oldest unread incoming message`() {
        fun msg(id: Long, sender: Long) = ThreadItem.Message(ChatMessage(id = id, senderId = sender, createdAt = "2024-01-01T00:00:00Z"), true, true)
        val newestFirst = listOf(msg(5, 9), msg(4, 1), msg(3, 9), msg(2, 9))
        assertEquals("server-3", unreadDividerKey(newestFirst, unread = 2, currentUserId = 1))
        assertEquals(null, unreadDividerKey(newestFirst, unread = 0, currentUserId = 1))
        assertEquals(null, unreadDividerKey(newestFirst, unread = 9, currentUserId = 1))
    }
}
