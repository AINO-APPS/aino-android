package app.aino.mobile.feature.chat

import app.aino.mobile.core.db.CacheScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ChatModelsTest {
    @Test
    fun notificationBodyNeverLeaksViewOnceFileNames() {
        val viewOnce = Json.parseToJsonElement("""{"viewOnce":true,"viewedBy":[]}""")
        assertEquals("Photo", chatNotificationBody(null, "image/jpeg", "IMG_2041.jpg", viewOnce))
        assertEquals("Video", chatNotificationBody("caption", "video/mp4", "VID.mp4", viewOnce))
        assertEquals("Photo", chatNotificationBody(null, "image/jpeg", "IMG_2041.jpg", null))
        assertEquals("Look", chatNotificationBody(" Look ", "video/mp4", "v.mp4", null))
        assertEquals("Voice message", chatNotificationBody(null, "audio/mp4", "voice.m4a", null))
        assertEquals("GIF", chatNotificationBody(null, "image/gif", "a.gif", null))
        assertEquals("logo.svg", chatNotificationBody(null, "application/octet-stream", "logo.svg", null))
        assertEquals("report.pdf", chatNotificationBody("", "application/pdf", "report.pdf", null))
        assertEquals("Hello", chatNotificationBody("Hello", null, null, null))
    }
    @Test
    fun cachedConversationKeepsPreviewCollageAndMemberCount() = kotlinx.coroutines.runBlocking {
        val scope = CacheScope(1, 7)
        val cache = ChatCache(scope, app.aino.mobile.core.db.ScopedCache(scope, app.aino.mobile.core.db.InMemoryAinoDao()))
        val group = ChatConversation(
            id = 3, isGroup = true, groupName = "Design", lastMessage = "Ship it", memberCount = 5,
            groupMemberPreviews = listOf(GroupMemberPreview("Asha", "/a.png")),
        )
        cache.replace(listOf(group, ChatConversation(id = 4, otherUserId = 8, otherFullName = "Ravi", lastMessage = "Hi")))
        val restored = cache.snapshot().associateBy(ChatConversation::id)
        assertEquals(group, restored[3])
        assertEquals("Ship it", restored[3]?.preview())
        assertFalse(restored[4]!!.isGroup)
        assertEquals("Hi", restored[4]?.preview())
    }

    @Test
    fun legacyCachedRowIsNotAGroup() {
        val row = app.aino.mobile.core.db.ConversationEntity(1, 7, 4, "Ravi", null, 0, 0).toCachedConversation()
        assertFalse(row.isGroup)
        assertEquals("Ravi", row.title())
    }

    @Test
    fun groupMemberLabelNeverSaysZero() {
        assertNull(groupMemberLabel(ChatConversation(1, isGroup = true), emptyList()))
        assertEquals("5 members", groupMemberLabel(ChatConversation(1, isGroup = true, memberCount = 5), emptyList()))
    }

    @Test
    fun decodesRealtimeChatPayloadsAndRejectsMalformedFrames() {
        val typing = decodeChatRealtime<ChatTypingEvent>(
            Json.parseToJsonElement("""{"conversationId":9,"userId":4}"""),
        )
        val receipt = decodeChatRealtime<ChatReadReceiptEvent>(
            Json.parseToJsonElement("""{"conversationId":9,"userId":8,"readAt":"2026-09-17T04:00:00Z"}"""),
        )

        assertEquals(ChatTypingEvent(9, 4), typing)
        assertEquals(8L, receipt?.userId)
        assertNull(decodeChatRealtime<ChatTypingEvent>(Json.parseToJsonElement("""{"conversationId":"bad"}""")))
        assertNull(decodeChatRealtime<ChatTypingEvent>(null))
    }

    @Test
    fun realtimeReactionPatchIsIdempotentAndScopedToOneMessage() {
        val original = listOf(
            ChatMessage(1, 9, 4, "one", "2026-09-17T04:00:00Z"),
            ChatMessage(2, 9, 8, "two", "2026-09-17T04:01:00Z"),
        )
        val add = ChatReactionEvent(2, 9, 4, "Asha", "👍", "added")
        val once = applyRealtimeReaction(original, add)
        val twice = applyRealtimeReaction(once, add)

        assertEquals(original[0], twice[0])
        assertEquals(listOf(ChatReaction("👍", 4, "Asha")), twice[1].reactions)

        val removed = applyRealtimeReaction(twice, add.copy(action = "removed"))
        assertTrue(removed[1].reactions.isEmpty())
    }

    @Test
    fun realtimeReceiptReplacesOnlyTheSameUserCursor() {
        val current = listOf(
            ReadReceipt(4, "2026-09-17T03:00:00Z", "Asha"),
            ReadReceipt(8, "2026-09-17T03:10:00Z", "Ben"),
        )
        val updated = applyRealtimeReceipt(
            current,
            ChatReadReceiptEvent(9, 4, "2026-09-17T04:00:00Z"),
        )

        assertEquals(2, updated.size)
        assertEquals("2026-09-17T04:00:00Z", updated.single { it.userId == 4L }.lastReadAt)
        assertEquals("Asha", updated.single { it.userId == 4L }.fullName)
        assertEquals("Ben", updated.single { it.userId == 8L }.fullName)
    }

    @Test
    fun typingEnvelopeMatchesTheServerCommandContract() {
        val envelope = typingEnvelope(42)
        assertEquals("chat_typing", envelope.type)
        assertEquals("42", envelope.data!!.jsonObject["conversationId"].toString())
    }

    @Test
    fun filtersForwardDestinationsWithoutShowingArchivedChats() {
        val conversations = listOf(
            ChatConversation(1, otherFullName = "Asha Kumar", otherUsername = "asha"),
            ChatConversation(2, isGroup = true, groupName = "Release Team"),
            ChatConversation(3, otherFullName = "Archived", isArchived = true),
        )

        assertEquals(listOf(1L, 2L), forwardDestinations(conversations, "").map(ChatConversation::id))
        assertEquals(listOf(2L), forwardDestinations(conversations, "release").map(ChatConversation::id))
        assertEquals(listOf(1L), forwardDestinations(conversations, "ASHA").map(ChatConversation::id))
    }

    @Test
    fun realtimeEditPatchesContentButNeverRevivesDeletedMessages() {
        val live = ChatMessage(1, 9, 4, "old", "2026-09-17T04:00:00Z")
        val deleted = ChatMessage(2, 9, 4, "", "2026-09-17T04:01:00Z", deletedAt = "2026-09-17T04:02:00Z")
        val event = ChatEditEvent(1, 9, "new", "2026-09-17T04:03:00Z")

        val patched = applyRealtimeEdit(listOf(live, deleted), event)

        assertEquals("new", patched[0].content)
        assertEquals(event.editedAt, patched[0].editedAt)
        assertEquals(deleted, applyRealtimeEdit(patched, event.copy(messageId = 2))[1])
    }

    @Test
    fun realtimeDeleteClearsSensitiveMessageStateIdempotently() {
        val message = ChatMessage(
            id = 7,
            conversationId = 9,
            senderId = 4,
            content = "secret",
            createdAt = "2026-09-17T04:00:00Z",
            fileName = "secret.pdf",
            fileUrl = "/uploads/secret.pdf",
            fileType = "application/pdf",
            fileSize = 42,
            starred = true,
            reactions = listOf(ChatReaction("👍", 8, "Ben")),
        )
        val event = ChatDeleteEvent(7, 9)
        val once = applyRealtimeDelete(listOf(message), event, "2026-09-17T04:01:00Z").single()
        val twice = applyRealtimeDelete(listOf(once), event, "2026-09-17T04:02:00Z").single()

        assertEquals("Message deleted", once.body())
        assertNull(once.fileUrl)
        assertTrue(once.reactions.isEmpty())
        assertFalse(once.starred)
        assertEquals(once.deletedAt, twice.deletedAt)
    }

    @Test
    fun realtimePinPatchesOnlyTheTargetAndIgnoresDeletedMessages() {
        val target = ChatMessage(7, 9, 4, "target", "2026-09-17T04:00:00Z")
        val other = ChatMessage(8, 9, 8, "other", "2026-09-17T04:01:00Z")
        val pinned = applyRealtimePin(
            listOf(target, other),
            ChatPinEvent(7, 9, true, pinnedBy = 4),
            pinnedAt = "2026-09-17T04:02:00Z",
        )
        assertEquals("2026-09-17T04:02:00Z", pinned[0].pinnedAt)
        assertEquals(4L, pinned[0].pinnedBy)
        assertEquals(other, pinned[1])

        val unpinned = applyRealtimePin(pinned, ChatPinEvent(7, 9, false))
        assertNull(unpinned[0].pinnedAt)
        assertNull(unpinned[0].pinnedBy)

        val deleted = target.copy(deletedAt = "2026-09-17T04:03:00Z")
        assertEquals(deleted, applyRealtimePin(listOf(deleted), ChatPinEvent(7, 9, true))[0])
    }

    @Test
    fun buildsChronologicalThreadWithDateSeparatorsAndMessageGroups() {
        val messages = listOf(
            ChatMessage(1, 9, 4, "one", "2026-09-15T23:59:00Z"),
            ChatMessage(2, 9, 4, "two", "2026-09-16T00:01:00Z"),
            ChatMessage(3, 9, 4, "three", "2026-09-16T00:04:00Z"),
            ChatMessage(4, 9, 8, "reply", "2026-09-16T00:05:00Z"),
        )

        val items = buildThreadItems(messages, emptyList(), currentUserId = 4, zoneId = ZoneId.of("UTC"))

        assertEquals(6, items.size)
        assertTrue(items[0] is ThreadItem.DateSeparator)
        assertTrue(items[2] is ThreadItem.DateSeparator)
        val firstDayMessage = items[1] as ThreadItem.Message
        assertTrue(firstDayMessage.startsGroup)
        assertTrue(firstDayMessage.endsGroup)
        val second = items[3] as ThreadItem.Message
        val third = items[4] as ThreadItem.Message
        assertTrue(second.startsGroup)
        assertFalse(second.endsGroup)
        assertFalse(third.startsGroup)
        assertTrue(third.endsGroup)
        val reply = items[5] as ThreadItem.Message
        assertTrue(reply.startsGroup)
        assertTrue(reply.endsGroup)
    }

    @Test
    fun queuedTextKeepsClusterCornersAsMessagesAreConfirmed() {
        val zone = ZoneId.of("UTC")
        val sent = ChatMessage(1, 9, 4, "sent", "2026-09-16T09:00:00Z")
        val first = QueuedMessage("first", 9, 4, "one", Instant.parse("2026-09-16T09:01:00Z").toEpochMilli(), sequence = 1)
        val second = first.copy(clientMessageId = "second", content = "two", createdAtEpochMs = first.createdAtEpochMs + 60_000, sequence = 2)
        val firstEcho = ChatMessage(2, 9, 4, first.content, "2026-09-16T09:01:00Z")
        val secondEcho = ChatMessage(3, 9, 4, second.content, "2026-09-16T09:02:00Z")
        fun groups(items: List<ThreadItem>) = items.mapNotNull {
            when (it) {
                is ThreadItem.Message -> it.startsGroup to it.endsGroup
                is ThreadItem.Queued -> it.startsGroup to it.endsGroup
                else -> null
            }
        }
        val expected = listOf(true to false, false to false, false to true)
        assertEquals(expected, groups(buildThreadItems(listOf(sent), listOf(first, second), 4, zone)))
        assertEquals(expected, groups(buildThreadItems(listOf(sent, firstEcho), listOf(second), 4, zone)))
        assertEquals(expected, groups(buildThreadItems(listOf(sent, firstEcho, secondEcho), emptyList(), 4, zone)))
        assertEquals(listOf(true to true), groups(buildThreadItems(emptyList(), listOf(first), 4, zone)))
    }

    @Test
    fun queuedTextRespectsSenderDayTimeoutAndUploadBoundaries() {
        val zone = ZoneId.of("UTC")
        val sent = ChatMessage(1, 9, 4, "sent", "2026-09-16T23:58:00Z")
        val queued = QueuedMessage("q", 9, 4, "next", Instant.parse("2026-09-16T23:59:00Z").toEpochMilli(), sequence = 2)
        fun assertSeparate(messages: List<ChatMessage>, next: QueuedMessage, uploads: List<OutgoingMediaItem> = emptyList()) {
            val items = buildThreadItems(messages, listOf(next), 4, zone, uploads)
            assertTrue(items.filterIsInstance<ThreadItem.Message>().single().endsGroup)
            assertTrue(items.filterIsInstance<ThreadItem.Queued>().single().startsGroup)
        }
        assertSeparate(listOf(sent.copy(senderId = 8)), queued)
        assertSeparate(listOf(sent), queued.copy(createdAtEpochMs = Instant.parse("2026-09-17T00:00:00Z").toEpochMilli()))
        assertSeparate(listOf(sent.copy(createdAt = "2026-09-16T23:55:59Z")), queued)
        assertSeparate(listOf(sent), queued, listOf(OutgoingMediaItem("photo", queued.createdAtEpochMs, sequence = 1)))
        val items = buildThreadItems(emptyList(), listOf(queued.copy(sequence = 0), queued.copy(clientMessageId = "after")), 4, zone,
            listOf(OutgoingMediaItem("photo", queued.createdAtEpochMs, sequence = 1)))
        assertTrue(items.filterIsInstance<ThreadItem.Queued>().all { it.startsGroup && it.endsGroup })
    }

    @Test
    fun unsentItemsFollowServerRowsInSendOrderDespiteClockSkew() {
        val messages = listOf(
            ChatMessage(1, 9, 4, "sent", "2026-09-16T09:00:00Z"),
            ChatMessage(2, 9, 8, "reply", "2026-09-16T09:02:00Z"),
        )
        // Device clock behind the server; text typed after the photo.
        val queued = listOf(QueuedMessage("q", 9, 4, "after photo", 1_789_549_200_000L, sequence = 2))
        val uploads = listOf(OutgoingMediaItem("photo", 1_789_549_260_000L, sequence = 1))

        val items = buildThreadItems(messages, queued, currentUserId = 4, zoneId = ZoneId.of("UTC"), uploads = uploads)
        val bubbles = items.drop(1)
        assertEquals(listOf("server-1", "server-2", "pending-photo", "queued-q"), bubbles.map(ThreadItem::key))
        assertTrue((bubbles[1] as ThreadItem.Message).endsGroup)
        assertEquals(1, items.count { it is ThreadItem.DateSeparator })
    }

    @Test
    fun reconcileLeavesQueuedTextOfOtherChatsAlone() {
        val queued = listOf(
            QueuedMessage("here", 12, 4, "ok", 1_000),
            QueuedMessage("there", 13, 4, "ok", 2_000),
        )
        val echo = ChatMessage(7, 12, 4, "ok", "2026-09-15T00:00:00Z")
        assertEquals(listOf("there"), reconcileQueuedMessages(queued, listOf(echo), 4).map { it.clientMessageId })
    }

    @Test
    fun excludesQueuedMessagesFromAnotherSignedInUser() {
        val queued = listOf(
            QueuedMessage("mine", 9, 4, "mine", 1_757_927_400_000L),
            QueuedMessage("other", 9, 8, "other", 1_757_927_401_000L),
        )
        val items = buildThreadItems(emptyList(), queued, currentUserId = 4, zoneId = ZoneId.of("UTC"))
        assertEquals(listOf("date-2025-09-15", "queued-mine"), items.map(ThreadItem::key))
    }

    @Test
    fun derivesDirectGroupAndSelfTitles() {
        assertEquals("Asha K", ChatConversation(1, otherFullName = "Asha K", otherUsername = "asha").title())
        assertEquals("asha", ChatConversation(1, otherUsername = "asha").title())
        assertEquals("Mobile Team", ChatConversation(2, isGroup = true, groupName = "Mobile Team").title())
        assertEquals("Group", ChatConversation(2, isGroup = true).title())
        assertEquals("Note to self", ChatConversation(3, isSelfChat = true).title())
    }

    @Test
    fun derivesLastMessagePreviewWithPrivacySafeFallbacks() {
        assertEquals("hello", ChatConversation(1, lastMessage = "hello").preview())
        assertEquals("plan.pdf", ChatConversation(1, lastFileName = "plan.pdf").preview())
        // Signal list snippets: never the raw upload name for media.
        assertEquals("Photo", ChatConversation(1, lastFileName = "IMG_2231.jpg", lastFileType = "image/jpeg").preview())
        assertEquals("Video", ChatConversation(1, lastFileName = "VID_1.mp4", lastFileType = "video/mp4").preview())
        assertEquals("Voice message", ChatConversation(1, lastFileName = "voice.m4a", lastFileType = "audio/mp4").preview())
        assertEquals("Look at this", ChatConversation(1, lastMessage = "Look at this", lastFileName = "a.png", lastFileType = "image/png").preview())
        assertEquals("Lunch?", ChatConversation(1, lastMessage = "Lunch?", lastFormatType = "poll").preview())
        assertEquals("Message deleted", ChatConversation(1, lastMessage = "secret", lastDeleted = "2026-09-14T00:00:00Z").preview())
        assertEquals("No messages yet", ChatConversation(1).preview())
    }

    @Test
    fun mapsIntoTheExactTenantAndUserCacheScope() {
        val conversation = ChatConversation(
            id = 9,
            updatedAt = "2026-09-14T10:00:00Z",
            otherFullName = "Asha K",
            otherAvatar = "/uploads/asha.png",
            unreadCount = 3,
        )
        val entity = conversation.toEntity(CacheScope(7, 4))
        assertEquals(7, entity.tenantId)
        assertEquals(4, entity.userId)
        assertEquals(9, entity.conversationId)
        assertEquals("Asha K", entity.title)
        assertEquals(3, entity.unreadCount)
        assertTrue(entity.updatedAtEpochMs > 0)
    }

    @Test
    fun unreadCountsClampNegativeServerValues() {
        assertEquals(5, totalUnread(listOf(ChatConversation(1, unreadCount = 5), ChatConversation(2, unreadCount = -2))))
    }

    @Test
    fun labelsPresenceAndWorkMode() {
        assertEquals("Offline", presenceLabel(null))
        assertEquals("Offline", presenceLabel(ChatPresence("offline", "available", "office")))
        assertEquals("Remote", presenceLabel(ChatPresence("online", "available", "remote")))
        assertEquals("Do not disturb", presenceLabel(ChatPresence("online", "do_not_disturb", null)))
    }

    @Test
    fun refreshesOnlyForConversationListEvents() {
        assertTrue(shouldRefreshConversationList("chat_message"))
        assertTrue(shouldRefreshConversationList("chat_group_created"))
        assertTrue(shouldRefreshConversationList("chat_conv_archived"))
        assertFalse(shouldRefreshConversationList("chat_typing"))
        assertFalse(shouldRefreshConversationList("chat_read_receipt"))
        assertFalse(shouldRefreshConversationList("task_updated"))

        // A-100: these are real server events that the previous hardcoded set
        // ignored entirely, so the list went stale until a manual refresh.
        assertTrue(shouldRefreshConversationList("chat_pin"))
        assertTrue(shouldRefreshConversationList("chat_cleared"))
        assertTrue(shouldRefreshConversationList("chat_user_blocked"))
        assertTrue(shouldRefreshConversationList("chat_group_role_changed"))

        // Ephemeral and patch-only events must never cause a network reload.
        assertFalse(shouldRefreshConversationList("chat_media_job"))
        assertFalse(shouldRefreshConversationList("chat_poll_vote"))

        // Non-chat domains are not this predicate's concern.
        assertFalse(shouldRefreshConversationList("leave_update"))
        assertFalse(shouldRefreshConversationList("meeting_started"))

        // `chat_group_updated` is not a server event; it must not be revived.
        assertFalse(shouldRefreshConversationList("chat_group_updated"))
    }

    @Test
    fun mapsThreadMessageIntoTheExactCacheScope() {
        val message = ChatMessage(
            id = 77,
            senderId = 9,
            content = "Hello",
            createdAt = "2026-09-15T04:00:00Z",
            senderName = "Asha K",
        )
        val entity = message.toEntity(CacheScope(3, 4), fallbackConversationId = 12)
        assertEquals(3, entity.tenantId)
        assertEquals(4, entity.userId)
        assertEquals(12, entity.conversationId)
        assertEquals(9, entity.senderId)
        assertEquals("Hello", entity.body)
        assertTrue(entity.createdAtEpochMs > 0)
    }

    @Test
    fun threadBodyHonoursDeletedAndAttachmentPrecedence() {
        assertEquals("Message deleted", ChatMessage(1, senderId = 2, content = "hidden", createdAt = "2026-09-15T00:00:00Z", deletedAt = "2026-09-15T01:00:00Z").body())
        assertEquals("report.pdf", ChatMessage(2, senderId = 2, createdAt = "2026-09-15T00:00:00Z", fileName = "report.pdf").body())
        assertEquals("Photo", ChatMessage(3, senderId = 2, createdAt = "2026-09-15T00:00:00Z", fileName = "x.jpg", fileType = "image/jpeg").body())
    }

    @Test
    fun reconcilesQueuedMessagesOneToOneByOwnEcho() {
        val queued = listOf(
            QueuedMessage("one", 12, 4, "one", 1_000),
            QueuedMessage("two", 12, 4, "two", 2_000),
        )
        val messages = listOf(
            ChatMessage(9, senderId = 4, content = "one", createdAt = java.time.Instant.ofEpochMilli(1_500).toString()),
        )
        assertEquals(listOf("two"), reconcileQueuedMessages(queued, messages, 4).map { it.clientMessageId })
        assertEquals(queued, reconcileQueuedMessages(queued, messages, 99))
    }

    @Test
    fun reconcileSurvivesDeviceClockAheadOfServer() {
        // Regression: phone clock 30 s ahead meant the server row looked "older"
        // than the optimistic bubble, so both rendered ("sent twice").
        val queued = listOf(QueuedMessage("c1", 12, 4, "hello", 1_000_030_000))
        val serverEcho = ChatMessage(50, senderId = 4, content = "hello", createdAt = java.time.Instant.ofEpochMilli(1_000_000_000).toString())
        assertTrue(reconcileQueuedMessages(queued, listOf(serverEcho), 4).isEmpty())
    }

    @Test
    fun reconcilePrefersExactClientMessageId() {
        val queued = listOf(
            QueuedMessage("a", 12, 4, "same", 1_000),
            QueuedMessage("b", 12, 4, "same", 2_000),
        )
        val echo = ChatMessage(7, senderId = 4, content = "same", createdAt = "2026-09-15T00:00:00Z", clientMessageId = "b")
        assertEquals(listOf("a"), reconcileQueuedMessages(queued, listOf(echo), 4).map { it.clientMessageId })
    }

    @Test
    fun realtimeChatMessageDecodesCamelCaseAndMergesOnce() {
        val frame = decodeChatRealtime<ChatRealtimeMessage>(
            Json.parseToJsonElement(
                """{"id":42,"conversationId":12,"senderId":4,"content":"hi","createdAt":"2026-09-15T00:00:05Z","clientMsgId":"c9","fileType":null}""",
            ),
        )!!
        val message = frame.toChatMessage()
        assertEquals("c9", message.clientMessageId)
        val existing = listOf(ChatMessage(41, 12, 8, "earlier", "2026-09-15T00:00:00Z"))
        val merged = mergeIncomingMessage(existing, message)
        assertEquals(listOf(41L, 42L), merged.map { it.id })
        // A replayed echo (or the REST reload) never duplicates the row.
        assertEquals(merged, mergeIncomingMessage(merged, message))
    }

    /**
     * A quote painted before its text arrived (the send response used to omit
     * it) must be completed by the realtime echo, not by a full thread refetch.
     */
    @Test
    fun realtimeEchoBackfillsAQuoteTheRowOnScreenIsMissing() {
        val optimistic = ChatMessage(
            42, 12, 4, "Reply", "2026-09-15T00:00:05Z",
            replyToId = 70, clientMessageId = "c9",
        )
        val echo = optimistic.copy(replyContent = "Original", replySenderName = "Vishnu")

        val merged = mergeIncomingMessage(listOf(optimistic), echo).single()

        assertEquals("Original", merged.replyContent)
        assertEquals("Vishnu", merged.replySenderName)
        // The echo's own quote never overwrites text the row already shows.
        val renamed = echo.copy(replyContent = "Changed elsewhere")
        assertEquals("Original", mergeIncomingMessage(listOf(merged), renamed).single().replyContent)
    }

    @Test
    fun pinnedListTracksToggles() {
        val a = ChatMessage(1, 12, 4, "a", "2026-09-15T00:00:00Z")
        val b = ChatMessage(2, 12, 4, "b", "2026-09-15T00:01:00Z")
        val pinned = updatePinnedList(emptyList(), listOf(a, b), 2, isPinned = true)
        assertEquals(listOf(2L), pinned.map { it.id })
        val both = updatePinnedList(pinned, listOf(a, b), 1, isPinned = true)
        assertEquals(listOf(1L, 2L), both.map { it.id })
        assertEquals(listOf(1L), updatePinnedList(both, listOf(a, b), 2, isPinned = false).map { it.id })
    }

    @Test
    fun pendingUploadsJoinThreadChronology() {
        val messages = listOf(ChatMessage(1, 12, 4, "before", java.time.Instant.ofEpochMilli(1_000).toString()))
        val items = buildThreadItems(messages, emptyList(), 4, ZoneId.of("UTC"), uploads = listOf(OutgoingMediaItem("m1", 2_000)))
        assertTrue(items.last() is ThreadItem.PendingUpload)
        assertEquals("pending-m1", items.last().key)
    }

    @Test
    fun callHistoryChoosesPeerOrGroupTitle() {
        val outgoing = CallLog(1, 12, callerId = 4, status = "answered", createdAt = "2026-09-15T00:00:00Z", otherName = "Asha")
        val incoming = outgoing.copy(id = 2, callerId = 8, callerName = "Asha")
        val group = outgoing.copy(id = 3, isGroup = true, groupName = "Mobile Team")
        assertEquals("Asha", outgoing.title(4))
        assertEquals("Asha", incoming.title(4))
        assertEquals("Mobile Team", group.title(4))
    }
}