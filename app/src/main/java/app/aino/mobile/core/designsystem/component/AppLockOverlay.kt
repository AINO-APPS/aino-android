package app.aino.mobile.core.designsystem.component

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/** Signal's lock screen: covers the app (and swallows touches) until unlocked. */
@Composable
fun AppLockOverlay(onUnlock: () -> Unit) {
    val colors = LocalWebColors.current
    BackHandler { }
    Box(
        Modifier.fillMaxSize().background(colors.bg)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(HeroIcons.LockClosed, null, Modifier.size(56.dp), tint = colors.primary)
            Text("AINO is locked", color = colors.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Button(onClick = onUnlock, modifier = Modifier.padding(top = 8.dp)) { Text("Unlock") }
        }
    }
}
