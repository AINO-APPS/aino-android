package app.aino.mobile.core.designsystem.tokens

import androidx.compose.ui.graphics.Color

/**
 * Design tokens transcribed verbatim from `client/src/global.css` (P1.1).
 *
 * Dark is the default theme. Every colour below maps 1:1 to a CSS custom
 * property so a Compose screen can reproduce the web client's pixels at 430px.
 */
data class WebColors(
    // Brand
    val primary: Color,
    val primaryLight: Color,
    val primaryDark: Color,
    val primaryGlow: Color,
    val accent: Color,
    val accentHover: Color,
    val onAccent: Color,
    // Status
    val success: Color,
    val successGlow: Color,
    val warning: Color,
    val warningGlow: Color,
    val danger: Color,
    val dangerGlow: Color,
    // Chip ink: the tone adjusted until it clears 4.5:1 on its own 14% tint.
    val primaryInk: Color,
    val successInk: Color,
    val warningInk: Color,
    val dangerInk: Color,
    // Surfaces
    val bg: Color,
    val bgSecondary: Color,
    val bgHover: Color,
    val bgElevated: Color,
    val surface: Color,
    val surfaceHover: Color,
    // Text
    val text: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    // Inputs / cards / borders
    val inputBg: Color,
    val inputBorder: Color,
    val inputBorderFocus: Color,
    val cardBg: Color,
    val border: Color,
)

/** `:root, [data-theme="dark"]` — the default. */
val WebColorsDark = WebColors(
    primary = Color(0xFF2383E2),
    primaryLight = Color(0xFF529CCA),
    primaryDark = Color(0xFF1A6DBE),
    primaryGlow = Color(0x262383E2), // rgba(35,131,226,0.15)
    accent = Color(0xFF2383E2),
    accentHover = Color(0xFF529CCA),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF4DAA57),
    successGlow = Color(0x2E4DAA57), // rgba(77,170,87,0.18)
    warning = Color(0xFFCB912F),
    warningGlow = Color(0x2ECB912F), // rgba(203,145,47,0.18)
    danger = Color(0xFFE03E3E),
    dangerGlow = Color(0x2EE03E3E), // rgba(224,62,62,0.18)
    primaryInk = Color(0xFF4093E6),
    successInk = Color(0xFF4DAA57),
    warningInk = Color(0xFFCB912F),
    dangerInk = Color(0xFFE66363),
    bg = Color(0xFF131314),
    bgSecondary = Color(0xFF1B1B1C),
    bgHover = Color(0xFF242426),
    bgElevated = Color(0xFF202021),
    surface = Color(0xFF1B1B1C),
    surfaceHover = Color(0xFF242426),
    text = Color(0xCEFFFFFF), // rgba(255,255,255,0.81)
    textPrimary = Color(0xCEFFFFFF), // rgba(255,255,255,0.81)
    textSecondary = Color(0x87FFFFFF), // rgba(255,255,255,0.53)
    textMuted = Color(0x61FFFFFF), // rgba(255,255,255,0.38)
    inputBg = Color(0xFF1B1B1C),
    inputBorder = Color(0xFF2F2F32),
    inputBorderFocus = Color(0x992383E2), // rgba(35,131,226,0.6)
    cardBg = Color(0xFF1B1B1C),
    border = Color(0xFF2A2A2C),
)

/** `[data-theme="light"]` — full override set from global.css. */
val WebColorsLight = WebColors(
    primary = Color(0xFF2383E2),
    primaryLight = Color(0xFF4A9DE6),
    primaryDark = Color(0xFF1A6DBE),
    primaryGlow = Color(0x142383E2), // rgba(35,131,226,0.08)
    accent = Color(0xFF2383E2),
    accentHover = Color(0xFF1B6FC2),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF4DAA57),
    successGlow = Color(0x2E4DAA57),
    warning = Color(0xFFCB912F),
    warningGlow = Color(0x2ECB912F),
    danger = Color(0xFFE03E3E),
    dangerGlow = Color(0x2EE03E3E),
    primaryInk = Color(0xFF1D6BB9),
    successInk = Color(0xFF377A3F),
    warningInk = Color(0xFF8E6621),
    dangerInk = Color(0xFFC13535),
    bg = Color(0xFFF7F7F8),
    bgSecondary = Color(0xFFF1F1F3),
    bgHover = Color(0xFFF1F1F3),
    bgElevated = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceHover = Color(0xFFF1F1F3),
    text = Color(0xFF37352F),
    textPrimary = Color(0xFF37352F),
    textSecondary = Color(0xA637352F), // rgba(55,53,47,0.65)
    textMuted = Color(0x7337352F), // rgba(55,53,47,0.45)
    inputBg = Color(0xFFFFFFFF),
    inputBorder = Color(0xFFD4D4D8),
    inputBorderFocus = Color(0x802383E2), // rgba(35,131,226,0.5)
    cardBg = Color(0xFFFFFFFF),
    border = Color(0xFFE4E4E7),
)

/** Per-status dot colour map (ProfileMenu §2). `offline` is transparent+ring. */
object WebStatusColors {
    val available = Color(0xFF4DAA57) // --success
    val busy = Color(0xFFEF4444)
    val dnd = Color(0xFFEF4444)
    val brb = Color(0xFFCB912F) // --warning
    val away = Color(0xFFCB912F) // --warning
    val offlineRing = Color(0xFF64748B) // ring colour; fill is transparent
    val inCall = Color(0xFFEF4444)
    val inMeeting = Color(0xFFF59E0B)
}
