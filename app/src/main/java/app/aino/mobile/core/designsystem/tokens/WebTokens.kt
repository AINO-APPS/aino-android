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
    // Surfaces
    val bg: Color,
    val bgSecondary: Color,
    val bgHover: Color,
    val bgElevated: Color,
    val surface: Color,
    val surfaceHover: Color,
    val glass: Color,
    val glassBorder: Color,
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
    bg = Color(0xFF131314),
    bgSecondary = Color(0xFF1B1B1C),
    bgHover = Color(0x0EFFFFFF), // rgba(255,255,255,0.055)
    bgElevated = Color(0xFF202021),
    surface = Color(0x0AFFFFFF), // rgba(255,255,255,0.04)
    surfaceHover = Color(0x12FFFFFF), // rgba(255,255,255,0.07)
    glass = Color(0x0AFFFFFF), // rgba(255,255,255,0.04)
    glassBorder = Color(0x17FFFFFF), // rgba(255,255,255,0.09)
    text = Color(0xCEFFFFFF), // rgba(255,255,255,0.81)
    textPrimary = Color(0xCEFFFFFF), // rgba(255,255,255,0.81)
    textSecondary = Color(0x87FFFFFF), // rgba(255,255,255,0.53)
    textMuted = Color(0x61FFFFFF), // rgba(255,255,255,0.38)
    inputBg = Color(0x11FFFFFF), // rgba(255,255,255,0.065)
    inputBorder = Color(0x1AFFFFFF), // rgba(255,255,255,0.1)
    inputBorderFocus = Color(0x992383E2), // rgba(35,131,226,0.6)
    cardBg = Color(0x09FFFFFF), // rgba(255,255,255,0.035)
    border = Color(0x17FFFFFF), // rgba(255,255,255,0.09)
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
    bg = Color(0xFFFBFBFA),
    bgSecondary = Color(0xFFF5F4F1),
    bgHover = Color(0x0F37352F), // rgba(55,53,47,0.06)
    bgElevated = Color(0xFFFFFFFF),
    surface = Color(0x0A37352F), // rgba(55,53,47,0.04)
    surfaceHover = Color(0x1437352F), // rgba(55,53,47,0.08)
    glass = Color(0xE0FFFFFF), // rgba(255,255,255,0.88)
    glassBorder = Color(0x1F37352F), // rgba(55,53,47,0.12)
    text = Color(0xFF37352F),
    textPrimary = Color(0xFF37352F),
    textSecondary = Color(0xA637352F), // rgba(55,53,47,0.65)
    textMuted = Color(0x7337352F), // rgba(55,53,47,0.45)
    inputBg = Color(0xFFF7F6F3),
    inputBorder = Color(0x2937352F), // rgba(55,53,47,0.16)
    inputBorderFocus = Color(0x802383E2), // rgba(35,131,226,0.5)
    cardBg = Color(0xFFFFFFFF),
    border = Color(0x2137352F), // rgba(55,53,47,0.13)
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
