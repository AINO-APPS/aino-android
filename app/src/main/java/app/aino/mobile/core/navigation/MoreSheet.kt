package app.aino.mobile.core.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebType
import kotlinx.coroutines.launch

/** Native More menu; destinations and access rules are supplied by the shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreSheet(
    destinations: List<AinoDestination>,
    currentRoute: String?,
    onNavigate: (AinoDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalWebColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val selectedRoute = bottomBarRoute(currentRoute)

    fun closeThen(action: () -> Unit) {
        if (!closing) {
            closing = true
            scope.launch {
                sheetState.hide()
                action()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!closing) {
                closing = true
                onDismiss()
            }
        },
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.bgElevated,
        contentColor = colors.text,
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("More", Modifier.semantics { heading() }, color = colors.text, style = WebType.title)
                    Text("Explore your workspace", color = colors.textSecondary, style = WebType.body)
                }
                IconButton(
                    onClick = { closeThen(onDismiss) },
                    enabled = !closing,
                    modifier = Modifier.background(colors.bgHover, RoundedCornerShape(16.dp)),
                ) {
                    Icon(HeroIcons.XMark, "Close More menu", Modifier.size(22.dp), tint = colors.textSecondary)
                }
            }
            Column(
                Modifier.weight(1f, fill = false)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (destinations.isEmpty()) {
                    Text(
                        "No additional sections are available for your account.",
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                        color = colors.textSecondary,
                        style = WebType.body,
                    )
                }
                destinations.forEach { destination ->
                    val active = selectedRoute == destination.route
                    val shape = RoundedCornerShape(16.dp)
                    Row(
                        Modifier.fillMaxWidth()
                            .heightIn(min = 64.dp)
                            .clip(shape)
                            .background(if (active) colors.primaryGlow else colors.surface)
                            .border(1.dp, if (active) colors.primary.copy(alpha = 0.3f) else colors.border, shape)
                            .selectable(
                                selected = active,
                                enabled = !closing,
                                role = Role.Button,
                                onClick = { closeThen { onNavigate(destination) } },
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            Modifier.size(40.dp).background(
                                if (active) colors.primaryGlow else colors.bgHover,
                                RoundedCornerShape(12.dp),
                            ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                destination.icon,
                                null,
                                Modifier.size(24.dp),
                                tint = if (active) colors.primary else colors.textSecondary,
                            )
                        }
                        Text(
                            destination.label,
                            Modifier.weight(1f),
                            color = colors.text,
                            style = WebType.bodyStrong,
                        )
                        Icon(
                            if (active) HeroIcons.Check else HeroIcons.ChevronRight,
                            null,
                            Modifier.size(20.dp),
                            tint = if (active) colors.primary else colors.textMuted,
                        )
                    }
                }
            }
        }
    }
}
