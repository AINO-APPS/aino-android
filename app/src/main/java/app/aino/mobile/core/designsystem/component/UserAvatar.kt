package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Web `ProfileMenu` initials: first letter of each word, max two, uppercase. */
fun avatarInitials(name: String?): String = name.orEmpty().trim().split(Regex("\\s+"))
    .mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifEmpty { "?" }

/**
 * A user's photo loaded through the shared authenticated Coil loader
 * (`/uploads` sits behind auth). The initials circle is the placeholder while
 * the image loads and the fallback when there is no photo or it fails.
 */
@Composable
fun UserAvatar(
    name: String?,
    avatarUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    background: Color = LocalWebColors.current.primary,
) {
    val context = LocalContext.current
    val url = avatarUrl?.takeIf(String::isNotBlank)?.let(::resolveServerMediaUrl)
    Box(modifier.size(size).clip(CircleShape).background(background), contentAlignment = Alignment.Center) {
        Text(avatarInitials(name), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * .38f).sp)
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
