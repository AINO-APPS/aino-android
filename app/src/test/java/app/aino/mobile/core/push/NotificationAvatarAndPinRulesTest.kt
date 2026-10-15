package app.aino.mobile.core.push

import app.aino.mobile.core.settings.ChatPrefs
import app.aino.mobile.feature.chat.ChatConversation
import app.aino.mobile.feature.chat.canUnpinMessage
import app.aino.mobile.feature.profile.NotificationPrefs
import app.aino.mobile.feature.profile.mirrorSyncedPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAvatarAndPinRulesTest {
    @Test fun reactorPhotoComesFromTheEventThenTheOneToOnePeer() {
        val direct = ChatConversation(id = 4, isGroup = false, otherUserId = 9, otherAvatar = "/uploads/peer.png")
        assertEquals("/uploads/actor.png", reactionAvatarUrl("/uploads/actor.png", 9, direct))
        assertEquals("/uploads/peer.png", reactionAvatarUrl("", 9, direct))
        assertEquals("/uploads/peer.png", reactionAvatarUrl(null, 9, direct))
        assertNull(reactionAvatarUrl(null, 5, direct))
        assertNull(reactionAvatarUrl(null, 9, ChatConversation(id = 4, isGroup = true, groupAvatar = "/uploads/group.png")))
        assertNull(reactionAvatarUrl(null, 9, null))
    }

    @Test fun messageNotificationsLoadSenderAndGroupPhotoOrCollageMembers() {
        assertEquals(listOf("/a.png"), notificationAvatarUrls(mapOf("senderAvatar" to "/a.png", "isGroup" to "false")))
        assertEquals(listOf("/a.png", "/g.png"), notificationAvatarUrls(mapOf(
            "senderAvatar" to "/a.png", "isGroup" to "true", "groupAvatar" to "/g.png",
            "groupMemberPreviews" to """[{"name":"B","avatar":"/b.png"}]""",
        )))
        assertEquals(listOf("/a.png", "/b.png"), notificationAvatarUrls(mapOf(
            "senderAvatar" to "/a.png", "isGroup" to "true", "groupAvatar" to "",
            "groupMemberPreviews" to """[{"name":"A","avatar":"/a.png"},{"name":"B","avatar":"/b.png"},{"name":"C","avatar":null}]""",
        )))
        assertEquals(emptyList<String>(), notificationAvatarUrls(mapOf("senderAvatar" to "", "isGroup" to "false")))
    }

    @Test fun onlyThePinnerOrAGroupAdminMayUnpin() {
        assertTrue(canUnpinMessage(pinnedBy = 1, currentUserId = 1, isGroup = true, isAdmin = false))
        assertFalse(canUnpinMessage(pinnedBy = 2, currentUserId = 1, isGroup = true, isAdmin = false))
        assertTrue(canUnpinMessage(pinnedBy = 2, currentUserId = 1, isGroup = true, isAdmin = true))
        assertFalse(canUnpinMessage(pinnedBy = 2, currentUserId = 1, isGroup = false, isAdmin = false))
        assertTrue(canUnpinMessage(pinnedBy = 1, currentUserId = 1, isGroup = false, isAdmin = false))
        assertTrue(canUnpinMessage(pinnedBy = null, currentUserId = 1, isGroup = false, isAdmin = false))
        assertFalse(canUnpinMessage(pinnedBy = null, currentUserId = 1, isGroup = true, isAdmin = false))
    }

    @Test fun syncedTonesFromWebAndDesktopReachTheDevicePrefs() {
        val mirrored = mirrorSyncedPrefs(ChatPrefs(inChatSounds = true), NotificationPrefs(
            ringtone = "vapor", outgoingTone = "echo", messageTone = "glassy", mentionTone = "spark", reactionTone = "click",
        ))
        assertEquals(listOf("vapor", "echo", "glassy", "spark", "click"),
            listOf(mirrored.ringtone, mirrored.outgoingTone, mirrored.messageTone, mirrored.mentionTone, mirrored.reactionTone))
        assertTrue(mirrored.inChatSounds)
        assertEquals("glassy", mirrored.syncedValue("messageTone"))
    }
}
