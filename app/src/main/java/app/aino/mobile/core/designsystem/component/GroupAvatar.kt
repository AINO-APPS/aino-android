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

/** One member's slot in the automatic group avatar: a photo, else initials. */
typealias GroupAvatarMember = app.aino.mobile.core.avatar.GroupAvatarMember

/** A tile in unit coordinates (0..1) of the avatar's square. */
typealias AvatarTile = app.aino.mobile.core.avatar.AvatarTile

/**
 * Collage layout for [count] members: 1 fills the circle, 2 split vertically,
 * 3 put one on the left and two stacked on the right, 4+ use quadrants.
 */
fun groupAvatarTiles(count: Int): List<AvatarTile> = app.aino.mobile.core.avatar.groupAvatarTiles(count)

/** Deterministic avatar colours for [key]; ARGB ints so notifications can share them. */
fun groupAvatarColors(key: String): Pair<Int, Int> = app.aino.mobile.core.avatar.groupAvatarColors(key)

/** The members shown in the collage: photos first (they read best small), then initials, at most four. */
fun collageMembers(members: List<GroupAvatarMember>): List<GroupAvatarMember> = app.aino.mobile.core.avatar.collageMembers(members)

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
