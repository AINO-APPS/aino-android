package app.aino.mobile.core.designsystem.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** Web `BrandingContext.tsx` `DEFAULT_ACCENT` (the design-system `--primary`). */
const val DEFAULT_BRAND_ACCENT = "#2383e2"

private val HEX6 = Regex("^#[0-9a-fA-F]{6}$")

/** `#RRGGBB` → opaque colour; anything else is null (the server only stores `#RRGGBB`). */
fun parseBrandAccent(hex: String?): Color? =
    hex?.trim()?.takeIf(HEX6::matches)?.let { Color(0xFF000000L or it.drop(1).toLong(16)) }

/** `#rrggbb` (lower-case) of an opaque colour. */
fun Color.toHexRgb(): String = String.format("#%06x", toArgb() and 0xFFFFFF)

/**
 * Web `BrandingContext.tsx` accent override: only a valid, non-default accent
 * repaints the palette, so unbranded orgs keep the tuned original shades.
 * Companion shades use the same `color-mix(in srgb, …)` weights:
 * hover = 85% accent + black, dark = 80% + black, light = 70% + white,
 * glow = the accent at 30% alpha.
 */
fun WebColors.withBrandAccent(accent: String?): WebColors {
    val color = parseBrandAccent(accent) ?: return this
    if (accent!!.trim().equals(DEFAULT_BRAND_ACCENT, ignoreCase = true)) return this
    return copy(
        primary = color,
        accent = color,
        accentHover = mix(color, Color.Black, 0.85f),
        primaryDark = mix(color, Color.Black, 0.80f),
        primaryLight = mix(color, Color.White, 0.70f),
        primaryGlow = color.copy(alpha = 0.30f),
    )
}

/** `color-mix(in srgb, [color] weight, [other])` for opaque colours. */
internal fun mix(color: Color, other: Color, weight: Float): Color = Color(
    red = color.red * weight + other.red * (1 - weight),
    green = color.green * weight + other.green * (1 - weight),
    blue = color.blue * weight + other.blue * (1 - weight),
    alpha = 1f,
)
