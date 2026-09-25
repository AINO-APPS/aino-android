package app.aino.mobile.core.call

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.compose.AsyncImage

internal val CallRed = Color(0xFFEF4444)
internal val CallGreen = Color(0xFF22C55E)
internal val CallBackground = Color(0xFF121212)

/** Signal's call backdrop: the peer's photo full-bleed, blurred (API 31+) and dimmed. */
@Composable
internal fun BoxScope.BlurredAvatarBackdrop(avatar: String?) {
    Box(Modifier.matchParentSize().background(CallBackground))
    val url = avatar?.takeIf(String::isNotBlank)?.let(::resolveServerMediaUrl)
    if (url != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AsyncImage(
            model = url,
            imageLoader = AppContainer.get(LocalContext.current).imageLoader,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize().blur(40.dp),
        )
    }
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f)))
}

/** A round Signal call button with its label underneath (incoming screen). */
@Composable
internal fun LabeledCallButton(icon: ImageVector, label: String, background: Color, onClick: () -> Unit, size: Dp = 64.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(background).clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, Modifier.size(28.dp), tint = Color.White) }
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Signal's in-call toggle: "checked" is a white disc with a dark glyph,
 * unchecked a translucent disc with a white glyph.
 */
@Composable
internal fun CallToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(CircleShape)
            .background(if (checked) Color.White else Color.White.copy(alpha = .16f))
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(26.dp), tint = if (checked) CallBackground else Color.White) }
}
