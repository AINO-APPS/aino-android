package app.aino.mobile.core.avatar

import kotlin.math.abs
import kotlinx.serialization.Serializable

fun avatarInitials(name: String?): String = name.orEmpty().trim().split(Regex("\\s+"))
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "?" }

data class GroupAvatarMember(val name: String?, val avatarUrl: String?)
data class AvatarTile(val x: Float, val y: Float, val width: Float, val height: Float)

@Serializable
data class AvatarMemberPreview(val name: String?, val avatar: String?)

data class GroupAvatarMetadata(val photoUrl: String?, val members: List<GroupAvatarMember>)

fun groupAvatarTiles(count: Int): List<AvatarTile> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(AvatarTile(0f, 0f, 1f, 1f))
    count == 2 -> listOf(AvatarTile(0f, 0f, .5f, 1f), AvatarTile(.5f, 0f, .5f, 1f))
    count == 3 -> listOf(AvatarTile(0f, 0f, .5f, 1f), AvatarTile(.5f, 0f, .5f, .5f), AvatarTile(.5f, .5f, .5f, .5f))
    else -> listOf(AvatarTile(0f, 0f, .5f, .5f), AvatarTile(.5f, 0f, .5f, .5f), AvatarTile(0f, .5f, .5f, .5f), AvatarTile(.5f, .5f, .5f, .5f))
}

private val GROUP_AVATAR_PALETTE = listOf(
    0xFF2383E2 to 0xFFFFFFFF, 0xFF0F9D7A to 0xFFFFFFFF, 0xFF8B5CF6 to 0xFFFFFFFF, 0xFFE0702B to 0xFFFFFFFF,
    0xFFD9466F to 0xFFFFFFFF, 0xFF4F6BED to 0xFFFFFFFF, 0xFF2E9E5B to 0xFFFFFFFF, 0xFF8A6D3B to 0xFFFFFFFF,
)

fun groupAvatarColors(key: String): Pair<Int, Int> =
    GROUP_AVATAR_PALETTE[abs(key.hashCode() % GROUP_AVATAR_PALETTE.size)].let { (bg, fg) -> bg.toInt() to fg.toInt() }

fun collageMembers(members: List<GroupAvatarMember>): List<GroupAvatarMember> =
    members.sortedBy { it.avatarUrl.isNullOrBlank() }.take(4)
