package app.aino.mobile.core.push

import org.junit.Assert.*
import org.junit.Test

class NotificationGroupAvatarsTest {
    @Test fun authenticatedCacheResolvesOverBudgetMetadataWithoutChangingPhotoFirstOrderOrUrls() {
        val url = "/uploads/" + "界".repeat(900) + ".png"
        val members = """[{"name":"First photo","avatar":"$url"},{"name":"Second photo","avatar":"/uploads/b.png"},{"name":"No photo","avatar":null}]"""
        assertTrue(runCatching { parseGroupMemberPreviews(members) }.isFailure)
        val cache = """[{"id":7,"group_avatar":null,"group_member_previews":$members,"group_member_avatars":["/uploads/wrong-id-order.png"]}]"""
        val metadata = cachedGroupAvatarMetadata(cache, 7)!!
        assertEquals(listOf("First photo", "Second photo", "No photo"), metadata.members.map { it.name })
        assertEquals(url, metadata.members.first().avatarUrl)
        assertNull(cachedGroupAvatarMetadata(cache, 8))
        val resolved = pushGroupAvatarMetadata(mapOf("groupAvatar" to ""), metadata)
        assertEquals(metadata.members, resolved.members)
    }

    @Test fun explicitNewPushFieldsWinOverAnOlderCachedGroupPhotoOrMembers() {
        val cache = cachedGroupAvatarMetadata("""[{"id":7,"group_avatar":"/uploads/old.png","group_member_previews":[{"name":"Old","avatar":null}]}]""", 7)!!
        val metadata = pushGroupAvatarMetadata(mapOf("groupAvatar" to "", "groupMemberPreviews" to "[]"), cache)
        assertNull(metadata.photoUrl)
        assertTrue(metadata.members.isEmpty())
    }
}
