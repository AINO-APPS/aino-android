package app.aino.mobile.core.designsystem.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
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
 * [accent] is the org branding accent (P10.4, see [withBrandAccent]).
 */
@Composable
fun WebTheme(
    darkTheme: Boolean = true,
    accent: String? = null,
    content: @Composable () -> Unit,
) {
    val base = rememberWebColors(darkTheme)
    val colors = remember(base, accent) { base.withBrandAccent(accent) }
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

/**
 * The web `--surface` / `--surface-hover` tokens are translucent washes (4–8%
 * alpha) meant to sit on a page. Material3 uses `surfaceContainerHigh` as the
 * default AlertDialog / DatePicker / TimePicker container, so mapping the raw
 * token made every default dialog see-through. Composite onto the elevated
 * background so the slots are always fully opaque but keep the same tint.
 */
internal fun opaqueSurface(token: Color, base: Color): Color =
    if (token.alpha >= 1f) token else token.compositeOver(base)

internal fun surfaceContainerHighFor(c: WebColors): Color = opaqueSurface(c.surface, c.bgElevated)
internal fun surfaceContainerHighestFor(c: WebColors): Color = opaqueSurface(c.surfaceHover, c.bgElevated)

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
    surfaceContainerHigh = surfaceContainerHighFor(c),
    surfaceContainerHighest = surfaceContainerHighestFor(c),
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
    surfaceContainerHigh = surfaceContainerHighFor(c),
    surfaceContainerHighest = surfaceContainerHighestFor(c),
    outline = c.border,
    outlineVariant = c.glassBorder,
    error = c.danger,
)
