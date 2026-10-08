package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlin.math.abs

/** One member's slot in the automatic group avatar: a photo, else initials. */
data class GroupAvatarMember(val name: String?, val avatarUrl: String?)

/** A tile in unit coordinates (0..1) of the avatar's square. */
data class AvatarTile(val x: Float, val y: Float, val width: Float, val height: Float)

/**
 * Collage layout for [count] members: 1 fills the circle, 2 split vertically,
 * 3 put one on the left and two stacked on the right, 4+ use quadrants.
 */
fun groupAvatarTiles(count: Int): List<AvatarTile> = when {
    count <= 0 -> emptyList()
    count == 1 -> listOf(AvatarTile(0f, 0f, 1f, 1f))
    count == 2 -> listOf(AvatarTile(0f, 0f, .5f, 1f), AvatarTile(.5f, 0f, .5f, 1f))
    count == 3 -> listOf(AvatarTile(0f, 0f, .5f, 1f), AvatarTile(.5f, 0f, .5f, .5f), AvatarTile(.5f, .5f, .5f, .5f))
    else -> listOf(AvatarTile(0f, 0f, .5f, .5f), AvatarTile(.5f, 0f, .5f, .5f), AvatarTile(0f, .5f, .5f, .5f), AvatarTile(.5f, .5f, .5f, .5f))
}

/** AINO avatar hues (background, foreground), picked by a stable key such as the conversation id. */
private val GROUP_AVATAR_PALETTE = listOf(
    0xFF2383E2 to 0xFFFFFFFF, 0xFF0F9D7A to 0xFFFFFFFF, 0xFF8B5CF6 to 0xFFFFFFFF, 0xFFE0702B to 0xFFFFFFFF,
    0xFFD9466F to 0xFFFFFFFF, 0xFF4F6BED to 0xFFFFFFFF, 0xFF2E9E5B to 0xFFFFFFFF, 0xFF8A6D3B to 0xFFFFFFFF,
)

/** Deterministic avatar colours for [key]; ARGB ints so notifications can share them. */
fun groupAvatarColors(key: String): Pair<Int, Int> =
    GROUP_AVATAR_PALETTE[abs(key.hashCode() % GROUP_AVATAR_PALETTE.size)].let { (bg, fg) -> bg.toInt() to fg.toInt() }

/** The members shown in the collage: photos first (they read best small), then initials, at most four. */
fun collageMembers(members: List<GroupAvatarMember>): List<GroupAvatarMember> =
    members.sortedBy { it.avatarUrl.isNullOrBlank() }.take(4)

/**
 * The automatic group avatar: the uploaded group photo when there is one,
 * otherwise a collage of up to four members (photo or initials), otherwise a
 * group glyph on a colour derived from [key]. Used for the chat list, thread
 * header, group settings, the group-call lobby/room and the incoming ring.
 */
@Composable
fun GroupAvatar(
    title: String?,
    photoUrl: String?,
    members: List<GroupAvatarMember>,
    key: String,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    if (!photoUrl.isNullOrBlank()) {
        UserAvatar(title, photoUrl, size, modifier)
        return
    }
    val (bg, fg) = groupAvatarColors(key)
    val shown = collageMembers(members)
    if (shown.size < 2) {
        Box(modifier.size(size).clip(CircleShape).background(Color(bg)), contentAlignment = Alignment.Center) {
            Icon(HeroIcons.UserGroup, null, Modifier.size(size * .5f), tint = Color(fg))
        }
        return
    }
    val gap = (size.value / 48f).coerceIn(1f, 2f).dp
    BoxWithConstraints(modifier.size(size).clip(CircleShape).background(Color(bg))) {
        groupAvatarTiles(shown.size).zip(shown).forEachIndexed { index, (tile, member) ->
            Box(
                Modifier
                    .offset(maxWidth * tile.x + if (tile.x > 0f) gap / 2 else 0.dp, maxHeight * tile.y + if (tile.y > 0f) gap / 2 else 0.dp)
                    .size(maxWidth * tile.width - if (tile.width < 1f) gap / 2 else 0.dp, maxHeight * tile.height - if (tile.height < 1f) gap / 2 else 0.dp),
            ) { CollageTile(member, "$key-$index", size * tile.width.coerceAtMost(tile.height)) }
        }
    }
}

@Composable
private fun CollageTile(member: GroupAvatarMember, key: String, glyphBox: Dp) {
    val context = LocalContext.current
    val (bg, fg) = groupAvatarColors(member.name ?: key)
    val url = member.avatarUrl?.takeIf(String::isNotBlank)?.let(::resolveServerMediaUrl)
    Box(Modifier.fillMaxSize().background(Color(bg)), contentAlignment = Alignment.Center) {
        Text(avatarInitials(member.name).take(1), color = Color(fg), fontWeight = FontWeight.Bold, fontSize = (glyphBox.value * .42f).sp)
        if (url != null) {
            val request = remember(url) { ImageRequest.Builder(context).data(url).build() }
            AsyncImage(
                model = request,
                imageLoader = AppContainer.get(context).imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
