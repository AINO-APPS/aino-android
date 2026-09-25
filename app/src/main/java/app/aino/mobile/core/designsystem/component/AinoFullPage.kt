package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Full-screen page chrome for routes that hide the shell bars (see
 * `isFullScreenRoute`): back arrow + title bar, optional trailing actions.
 * With [scrollable] the body scrolls and pads for the IME/navigation bar;
 * otherwise the caller owns the layout (lists, editors).
 */
@Composable
fun AinoFullPage(
    title: String,
    onBack: () -> Unit,
    scrollable: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalWebColors.current
    Column(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.background(colors.bgSecondary)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = colors.text)
                }
                Text(
                    title, color = colors.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp).weight(1f),
                )
                actions()
                Spacer(Modifier.padding(end = 4.dp))
            }
            HorizontalDivider(color = colors.border)
        }
        if (scrollable) {
            Column(
                Modifier.fillMaxWidth().weight(1f).imePadding().verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        } else {
            Column(Modifier.fillMaxWidth().weight(1f).imePadding().navigationBarsPadding(), content = content)
        }
    }
}
