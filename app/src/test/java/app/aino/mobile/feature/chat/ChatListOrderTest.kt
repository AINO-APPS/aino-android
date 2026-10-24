package app.aino.mobile.feature.chat

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListOrderTest {

    private fun conversation(
        id: Long,
        lastMessageAt: String? = null,
        updatedAt: String? = null,
        pinned: Boolean = false,
        archived: Boolean = false,
        unread: Int = 0,
    ) = ChatConversation(
        id = id,
        lastMessageAt = lastMessageAt,
        updatedAt = updatedAt,
        isPinned = pinned,
        isArchived = archived,
        unreadCount = unread,
    )

    private fun message(
        conversationId: Long,
        createdAt: String = "2026-10-08T10:05:00Z",
        content: String? = "hi",
        senderId: Long = 6,
        fileType: String? = null,
        formatType: String? = null,
        metadata: String? = null,
    ): ChatRealtimeMessage {
        val meta = metadata?.let { ",\"metadata\":$it" }.orEmpty()
        val file = fileType?.let { ",\"fileType\":\"$it\"" }.orEmpty()
        val format = formatType?.let { ",\"formatType\":\"$formatType\"" }.orEmpty()
        val body = content?.let { ",\"content\":\"$it\"" }.orEmpty()
        return decodeChatRealtime<ChatRealtimeMessage>(
            Json.parseToJsonElement(
                """{"id":9,"conversationId":$conversationId,"senderId":$senderId,"createdAt":"$createdAt"$body$file$format$meta}""",
            ),
        )!!
    }

    @Test
    fun newestLastMessageSortsFirst() {
        val sorted = sortConversations(
            listOf(
                conversation(1, lastMessageAt = "2026-10-08T09:00:00Z"),
                conversation(2, lastMessageAt = "2026-10-08T11:00:00Z"),
                conversation(3, lastMessageAt = "2026-10-08T10:00:00Z"),
            ),
        )
        assertEquals(listOf(2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun aConversationWithNoMessagesFallsBackToUpdatedAt() {
        val sorted = sortConversations(
            listOf(
                conversation(1, updatedAt = "2026-10-08T09:00:00Z"),
                conversation(2, lastMessageAt = "2026-10-08T11:00:00Z"),
                conversation(3, updatedAt = "2026-10-08T12:00:00Z"),
            ),
        )
        assertEquals(listOf(3L, 2L, 1L), sorted.map { it.id })
    }

    @Test
    fun pinnedChatsLeadRegardlessOfTimestamp() {
        val sorted = sortConversations(
            listOf(
                conversation(1, lastMessageAt = "2026-10-08T11:00:00Z"),
                conversation(2, lastMessageAt = "2026-10-08T09:00:00Z", pinned = true),
            ),
        )
        assertEquals(listOf(2L, 1L), sorted.map { it.id })
    }

    @Test
    fun sameTimestampBreaksTheTieOnIdSoTheOrderIsStable() {
        val same = "2026-10-08T10:00:00Z"
        val sorted = sortConversations(listOf(conversation(1, lastMessageAt = same), conversation(7, lastMessageAt = same), conversation(4, lastMessageAt = same)))
        assertEquals(listOf(7L, 4L, 1L), sorted.map { it.id })
        // Re-sorting must not reshuffle: the comparator is total.
        assertEquals(sorted.map { it.id }, sortConversations(sorted).map { it.id })
    }

    @Test
    fun aMessageMovesItsConversationToTheTop() {
        val list = listOf(
            conversation(1, lastMessageAt = "2026-10-08T11:00:00Z"),
            conversation(2, lastMessageAt = "2026-10-08T09:00:00Z"),
        )
        val promoted = promoteConversation(list, message(conversationId = 2, createdAt = "2026-10-08T12:00:00Z"))
        assertEquals(listOf(2L, 1L), promoted.map { it.id })
    }

    @Test
    fun theRowCarriesTheNewPreviewAndSender() {
        val list = listOf(conversation(1, lastMessageAt = "2026-10-08T09:00:00Z", unread = 2))
        val promoted = promoteConversation(
            list,
            message(conversationId = 1, createdAt = "2026-10-08T12:00:00Z", content = "are you there", senderId = 42),
        )[0]
        assertEquals("are you there", promoted.lastMessage)
        assertEquals("2026-10-08T12:00:00Z", promoted.lastMessageAt)
        assertEquals(42L, promoted.lastSenderId)
        assertEquals(3, promoted.unreadCount)
        assertFalse(promoted.lastMessageRead)
        assertFalse(promoted.lastMessageDelivered)
        assertEquals("are you there", promoted.preview())
    }

    @Test
    fun anOlderMessageNeverDragsItsRowBackDown() {
        val list = listOf(
            conversation(1, lastMessageAt = "2026-10-08T12:00:00Z", updatedAt = "2026-10-08T12:00:00Z"),
            conversation(2, lastMessageAt = "2026-10-08T11:00:00Z"),
        )
        // Out-of-order/replayed delivery: conversation 1's newest message is already later.
        val promoted = promoteConversation(list, message(conversationId = 1, createdAt = "2026-10-08T10:00:00Z", content = "late"))
        assertEquals(listOf(1L, 2L), promoted.map { it.id })
        assertEquals("2026-10-08T12:00:00Z", promoted[0].lastMessageAt)
        // The row's own description of the last message still converges on the message it was given.
        assertEquals("late", promoted[0].lastMessage)
    }

    @Test
    fun anUnplaceableTimestampLeavesTheRowWhereItWas() {
        val list = listOf(conversation(1, lastMessageAt = "2026-10-08T12:00:00Z"), conversation(2, lastMessageAt = "2026-10-08T11:00:00Z"))
        val promoted = promoteConversation(list, message(conversationId = 2, createdAt = "not-a-date"))
        assertEquals(listOf(1L, 2L), promoted.map { it.id })
        assertEquals("2026-10-08T11:00:00Z", promoted[1].lastMessageAt)
    }

    @Test
    fun aMessageInTheOpenConversationStillMovesItsRow() {
        val list = listOf(conversation(1, lastMessageAt = "2026-10-08T11:00:00Z"), conversation(2, lastMessageAt = "2026-10-08T09:00:00Z"))
        val promoted = promoteConversation(list, message(conversationId = 2, createdAt = "2026-10-08T12:00:00Z"), visibleConversationId = 2)
        assertEquals(listOf(2L, 1L), promoted.map { it.id })
    }

    @Test
    fun theOpenConversationKeepsItsBadgeCleared() {
        val list = listOf(conversation(1, lastMessageAt = "2026-10-08T09:00:00Z"), conversation(2, lastMessageAt = "2026-10-08T09:00:00Z", unread = 4))
        val promoted = promoteConversation(list, message(conversationId = 2, createdAt = "2026-10-08T12:00:00Z"), visibleConversationId = 2)
        assertEquals(0, promoted.first { it.id == 2L }.unreadCount)
    }

    @Test
    fun anArchivedConversationIsNeverResurrected() {
        val list = listOf(conversation(1, lastMessageAt = "2026-10-08T11:00:00Z"), conversation(2, lastMessageAt = "2026-10-08T09:00:00Z", archived = true))
        val promoted = promoteConversation(list, message(conversationId = 2, createdAt = "2026-10-08T12:00:00Z"))
        val archived = promoted.first { it.id == 2L }
        assertTrue(archived.isArchived)
            assertEquals("2026-10-08T12:00:00Z", archived.lastMessageAt)
    }

    @Test
    fun aMessageNeverChangesThePinFlag() {
        val promoted = promoteConversation(
            listOf(conversation(1, pinned = false)),
            message(conversationId = 1, createdAt = "2026-10-08T12:00:00Z"),
        )[0]
        assertFalse(promoted.isPinned)
        assertTrue(promoteConversation(listOf(conversation(1, pinned = true)), message(conversationId = 1)).single().isPinned)
    }

    @Test
    fun anUnrelatedConversationIsUntouched() {
        val other = conversation(7, lastMessageAt = "2026-10-08T09:00:00Z", unread = 2)
        val promoted = promoteConversation(listOf(conversation(1), other), message(conversationId = 1))
        assertEquals(other, promoted.first { it.id == 7L })
    }

    @Test
    fun aPhotoMessageRendersAsAPhotoRowNotItsFileName() {
        val promoted = promoteConversation(
            listOf(conversation(1)),
            message(conversationId = 1, content = null, fileType = "image/jpeg"),
        )[0]
        val snippet = promoted.previewSnippet()
        assertEquals(PreviewKind.Photo, snippet.kind)
        assertEquals("Photo", snippet.text)
    }

    @Test
    fun aViewOnceMessageNeverLeaksItsCaptionIntoTheList() {
        val promoted = promoteConversation(
            listOf(conversation(1)),
            message(
                conversationId = 1,
                content = "secret caption",
                fileType = "image/jpeg",
                metadata = """{"viewOnce":"true"}""",
            ),
        )[0]
        assertEquals(VIEW_ONCE_FORMAT, promoted.lastFormatType)
        val snippet = promoted.previewSnippet()
        assertEquals(PreviewKind.Photo, snippet.kind)
        assertEquals("Photo", snippet.text)
        assertFalse(promoted.preview().contains("secret"))
    }

    @Test
    fun aViewOnceVideoRowReadsVideo() {
        val promoted = promoteConversation(
            listOf(conversation(1)),
            message(conversationId = 1, content = "secret", fileType = "video/mp4", metadata = """{"viewOnce":"true"}"""),
        )[0]
        assertEquals("Video", promoted.preview())
    }

    @Test
    fun aStaleRowNeverClaimsADeletedLastMessage() {
        val promoted = promoteConversation(
            listOf(conversation(1, lastMessageAt = "2026-10-08T09:00:00Z").copy(lastDeleted = "2026-10-08T09:00:00Z")),
            message(conversationId = 1, createdAt = "2026-10-08T12:00:00Z", content = "back"),
        )[0]
        assertNull(promoted.lastDeleted)
        assertEquals("back", promoted.preview())
    }

    @Test
    fun orderedConversationsClearsTheOpenThreadBadgeAndSorts() {
        val ordered = orderedConversations(
            listOf(
                conversation(1, lastMessageAt = "2026-10-08T11:00:00Z"),
                conversation(2, lastMessageAt = "2026-10-08T09:00:00Z", unread = 3),
            ),
            visibleConversationId = 2,
        )
        assertEquals(listOf(1L, 2L), ordered.map { it.id })
        assertEquals(0, ordered.first { it.id == 2L }.unreadCount)
    }
}