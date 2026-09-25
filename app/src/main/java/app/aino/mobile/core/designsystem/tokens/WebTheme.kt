package app.aino.mobile.core.designsystem.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/** Radii from global.css (P1.1). */
object WebDimens {
    val radius = 8.dp
    val radiusSm = 6.dp
    val radiusFull = 9999.dp
}

val LocalWebColors = compositionLocalOf { WebColorsDark }
val LocalWebDimens = compositionLocalOf { WebDimens }

/** Current web-parity palette. Dark unless the persisted override says light. */
@Composable
fun rememberWebColors(darkTheme: Boolean): WebColors =
    remember(darkTheme) { if (darkTheme) WebColorsDark else WebColorsLight }

/**
 * Provides web-parity tokens + (P1.8) drives the Material3 color scheme so the
 * whole app repaints without restart when the user toggles the theme.
 */
@Composable
fun WebTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = rememberWebColors(darkTheme)
    CompositionLocalProvider(
        LocalWebColors provides colors,
        LocalWebDimens provides WebDimens,
    ) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = if (darkTheme) darkColorSchemeFor(colors) else lightColorSchemeFor(colors),
            content = content,
        )
    }
}

private fun darkColorSchemeFor(c: WebColors) = androidx.compose.material3.darkColorScheme(
    primary = c.primary,
    onPrimary = c.onAccent,
    primaryContainer = c.primaryDark,
    onPrimaryContainer = c.onAccent,
    secondary = c.primaryLight,
    background = c.bg,
    onBackground = c.text,
    surface = c.bgSecondary,
    onSurface = c.text,
    surfaceVariant = c.bgElevated,
    onSurfaceVariant = c.textSecondary,
    surfaceContainerLowest = c.bg,
    surfaceContainerLow = c.bgSecondary,
    surfaceContainer = c.bgElevated,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surfaceHover,
    outline = c.border,
    outlineVariant = c.glassBorder,
    error = c.danger,
)

private fun lightColorSchemeFor(c: WebColors) = androidx.compose.material3.lightColorScheme(
    primary = c.primary,
    onPrimary = c.onAccent,
    primaryContainer = c.primaryLight,
    onPrimaryContainer = c.onAccent,
    secondary = c.primaryDark,
    background = c.bg,
    onBackground = c.text,
    surface = c.bgSecondary,
    onSurface = c.text,
    surfaceVariant = c.bgElevated,
    onSurfaceVariant = c.textSecondary,
    surfaceContainerLowest = c.bg,
    surfaceContainerLow = c.bgSecondary,
    surfaceContainer = c.bgElevated,
    surfaceContainerHigh = c.surface,
    surfaceContainerHighest = c.surfaceHover,
    outline = c.border,
    outlineVariant = c.glassBorder,
    error = c.danger,
)
