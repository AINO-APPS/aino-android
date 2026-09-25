package app.aino.mobile.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.theme.AinoBlue
import app.aino.mobile.core.designsystem.theme.AinoCyan
import app.aino.mobile.core.designsystem.theme.AinoDanger
import app.aino.mobile.core.designsystem.theme.AinoSuccess
import app.aino.mobile.core.designsystem.theme.AinoWarning

@Composable
fun AinoAtmosphere(content: @Composable () -> Unit) {
    // P1.9: retained ONLY for surfaces with no web counterpart (incoming-call UI).
    // Parity screens use WebCard/WebTheme tokens instead. See WEB_PARITY_PLAN P1.9.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/** Shared edge-to-edge Material 3 page scaffold. */
@Composable
fun AinoScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = topBar,
        bottomBar = bottomBar,
        content = content,
    )
}

/** Standard page top bar for feature screens that own their navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AinoTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                subtitle?.let {
                    Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    )
}

data class AinoNavigationItem(
    val key: String,
    val label: String,
    val icon: ImageVector,
    val badgeCount: Int = 0,
)

/** Shared Material 3 bottom navigation with accessible labels and badges. */
@Composable
fun AinoNavigationBar(
    items: List<AinoNavigationItem>,
    selectedKey: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = item.key == selectedKey,
                onClick = { onSelect(item.key) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (item.badgeCount > 0) {
                                Badge { Text(if (item.badgeCount > 99) "99+" else item.badgeCount.toString()) }
                            }
                        },
                    ) {
                        Icon(item.icon, contentDescription = item.label)
                    }
                },
                label = { Text(item.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        }
    }
}

/** Compact, scroll-free segmented selector for two to five feature modes. */
@Composable
fun <T> AinoSegmentedTabs(
    items: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable (T, Boolean) -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().selectableGroup()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.large)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items.forEach { item ->
            val isSelected = item == selected
            Surface(
                modifier = Modifier.weight(1f).selectable(
                    selected = isSelected,
                    onClick = { onSelect(item) },
                    role = Role.Tab,
                ),
                shape = MaterialTheme.shapes.medium,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                tonalElevation = if (isSelected) 1.dp else 0.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon?.invoke(item, isSelected)
                    Text(label(item), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Reusable honest empty state; it never advertises an unavailable action. */
@Composable
fun AinoEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    Column(
        modifier.fillMaxWidth().semantics { contentDescription = title }.padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Icon(icon, contentDescription = null, Modifier.padding(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AinoGlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Kept under the established name to avoid a disruptive call-site rename,
    // but this is now a real Material 3 tonal surface rather than translucent
    // white paint. The old treatment had weak contrast in both themes.
    ElevatedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) { content() }
}

@Composable
fun AinoPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable RowScope.() -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AinoBlue, disabledContainerColor = AinoBlue),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leadingIcon?.invoke(this)
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

enum class AlertTone { Info, Success, Warning, Error }

@Composable
fun AinoAlert(text: String, tone: AlertTone, modifier: Modifier = Modifier) {
    val color = when (tone) {
        AlertTone.Info -> AinoBlue
        AlertTone.Success -> AinoSuccess
        AlertTone.Warning -> AinoWarning
        AlertTone.Error -> AinoDanger
    }
    Text(
        text = text,
        modifier = modifier.fillMaxWidth().background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(8.dp)).padding(12.dp),
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
fun AinoSectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
fun AinoBadge(text: String, tone: AlertTone = AlertTone.Info, modifier: Modifier = Modifier) {
    val color = when (tone) {
        AlertTone.Info -> AinoCyan
        AlertTone.Success -> AinoSuccess
        AlertTone.Warning -> AinoWarning
        AlertTone.Error -> AinoDanger
    }
    Text(
        text,
        modifier = modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
fun AinoMetric(icon: ImageVector, label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(9.dp)).padding(9.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, contentDescription = null, tint = tint)
        }
        Column {
            Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, color = tint, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun AinoStatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.background(color, CircleShape))
}