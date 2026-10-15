package app.aino.mobile.feature.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import app.aino.mobile.core.designsystem.tokens.WebColors
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors

/**
 * Signal-Android chat measurements (values re-typed from Signal's public
 * `dimens.xml`; no Signal code or assets are used).
 */
object SignalDimens {
    /** Signal `message_corner_radius` / `message_corner_collapse_radius`. */
    val bubbleCorner = 18.dp
    val bubbleCornerCollapsed = 4.dp
    val bubbleHPad = 12.dp
    val bubbleTopPad = 7.dp
    val bubbleBottomPad = 7.dp
    val bubbleFooterBottomPad = 5.dp
    val bubbleEdgeMargin = 32.dp
    val gutter = 16.dp
    val receivedGutter = 8.dp
    val groupAvatar = 28.dp
    val bodyText = 16.sp
    val bodyLine = 22.sp
    val footerText = 12.sp
    val mediaDefault = 210.dp
    val mediaMaxWidth = 240.dp
    val mediaMinWidthSolo = 150.dp
    val mediaMinWidthWithContent = 240.dp
    val mediaMinHeight = 100.dp
    val mediaMaxHeight = 320.dp
    val quoteCorner = 10.dp
    val quoteThumb = 60.dp
    val replyIcon = 38.dp
    val reactionScrubberWidth = 320.dp
    val emojiCell = 46.dp
    val composeHeight = 44.dp
    val toolbarHeight = 64.dp
    val toolbarAvatar = 28.dp
    val listAvatar = 48.dp
    val unreadBadgeMin = 25.dp
    val selectionHeader = 64.dp
}

data class SignalColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val outgoing: Color,
    val onOutgoing: Color,
    val onOutgoingSecondary: Color,
    val incoming: Color,
    val onIncoming: Color,
    val onIncomingSecondary: Color,
    val primary: Color,
    val text: Color,
    val textSecondary: Color,
    val divider: Color,
    val searchPill: Color,
    val datePill: Color,
    val danger: Color,
    val scrim: Color,
    val highlight: Color,
    /** Web `.myBubble` 1px border: `color-mix(--primary 16%, transparent)`. */
    val outgoingBorder: Color = Color.Transparent,
    /** Web `.tickDelivered` (`--text-secondary`). */
    val tickDelivered: Color = onIncomingSecondary,
    /** Web `.tickSent` / `.tickSending` (`--text-muted`). */
    val tickMuted: Color = onIncomingSecondary,
    /** Web `.tickRead` (`--primary`, the org accent). */
    val tickRead: Color = primary,
    /** Signal reaction pill (`signal_colorSurface1`). */
    val reactionPill: Color = surface,
)

/**
 * Signal-Android chat colours (values re-typed from Signal's public
 * `material3_colors_*.xml`, `light_colors.xml` / `dark_colors.xml` and
 * `ChatColorsPalette`; no Signal code or assets are used). The bubble, bubble
 * text and receipt colours here are base values only: [orgChatColors] replaces
 * them with the web org theme.
 */
val SignalLight = SignalColors(
    isDark = false,
    background = Color(0xFFFBFCFF),
    surface = Color(0xFFF2F5F9),
    outgoing = Color(0xFF315FF4),
    onOutgoing = Color(0xFFFFFFFF),
    onOutgoingSecondary = Color(0xB8FFFFFF),
    incoming = Color(0xFFE7EBF3),
    onIncoming = Color(0xFF1B1B1D),
    onIncomingSecondary = Color(0xFF545863),
    primary = Color(0xFF2C58C3),
    text = Color(0xFF1B1B1D),
    textSecondary = Color(0xFF545863),
    divider = Color(0x1F000000),
    searchPill = Color(0xFFEDF0F6),
    datePill = Color(0xFFEDF0F6),
    danger = Color(0xFFF44336),
    scrim = Color(0x99000000),
    highlight = Color(0x66FFD54F),
    reactionPill = Color(0xFFF2F5F9),
)

val SignalDark = SignalColors(
    isDark = true,
    background = Color(0xFF1B1C1F),
    surface = Color(0xFF23242A),
    outgoing = Color(0xFF315FF4),
    onOutgoing = Color(0xE6FFFFFF),
    onOutgoingSecondary = Color(0xA3FFFFFF),
    incoming = Color(0xFF303133),
    onIncoming = Color(0xFFE2E1E5),
    onIncomingSecondary = Color(0xFFBEBFC5),
    primary = Color(0xFFB6C5FA),
    text = Color(0xFFE2E1E5),
    textSecondary = Color(0xFFBEBFC5),
    divider = Color(0x1FFFFFFF),
    searchPill = Color(0xFF272A31),
    datePill = Color(0xFF272A31),
    danger = Color(0xFFFF6F61),
    scrim = Color(0xB3000000),
    highlight = Color(0x80FFB300),
    reactionPill = Color(0xFF23242A),
)

/** Signal `ChatColorsPalette.Names` (light, dark): group sender names keep one colour per person. */
private val SignalNameColors = listOf(
    0xFF006DA3 to 0xFF00A7FA, 0xFF067906 to 0xFF0AB80A, 0xFFB814B8 to 0xFFF65AF6, 0xFFC13215 to 0xFFFF6F52,
    0xFF5B6976 to 0xFF8BA1B6, 0xFFCC0066 to 0xFFF76EB2, 0xFF2E51FF to 0xFF8599FF, 0xFF007575 to 0xFF00B2B2,
    0xFF9C5711 to 0xFFD5920B, 0xFFD00B4D to 0xFFFF6B9C, 0xFF8F2AF4 to 0xFFBF80FF, 0xFF3D7406 to 0xFF5EB309,
    0xFFD00B0B to 0xFFFF7070, 0xFF007A3D to 0xFF00B85C, 0xFF5151F6 to 0xFF9494FF, 0xFF866118 to 0xFFD68F00,
    0xFF067953 to 0xFF00B87A, 0xFFA20CED to 0xFFCF7CF8, 0xFF4B7000 to 0xFF74AD00, 0xFFC70A88 to 0xFFF76EC9,
    0xFFB34209 to 0xFFF57A3D, 0xFF06792D to 0xFF0AB844, 0xFF7A3DF5 to 0xFFAF8AF9, 0xFF6B6B24 to 0xFFA4A437,
    0xFFD00B2C to 0xFFF77389, 0xFF2D7906 to 0xFF42B309, 0xFFAF0BD0 to 0xFFE06EF7, 0xFF32763E to 0xFF4BAF5C,
    0xFF2662D9 to 0xFF7DA1E8, 0xFF76681E to 0xFFB89B0A, 0xFF067462 to 0xFF09B397, 0xFF6447F5 to 0xFFA18FF9,
    0xFF5E6E0C to 0xFF8FAA09, 0xFF077288 to 0xFF00AED1, 0xFFC20AA3 to 0xFFF75FDD, 0xFF2D761E to 0xFF43B42D,
)

/** The colour of [senderId]'s name in group bubbles and quotes. */
internal fun signalNameColor(senderId: Long?, dark: Boolean): Color {
    val (light, night) = SignalNameColors[Math.floorMod((senderId ?: 0L).hashCode(), SignalNameColors.size)]
    return Color(if (dark) night else light)
}

/**
 * Chat palette: Signal's layout (background, pills, bubble geometry, name
 * colours) with the bubbles, receipts and accents taken from the web org theme
 * (`MessageBubble.module.css`, `DeliveryStatus.tsx`), so the org branding
 * accent flows into chat exactly like it does on desktop/web.
 */
val signalColors: SignalColors
    @Composable @ReadOnlyComposable
    get() {
        val web = LocalWebColors.current
        return orgChatColors(if (web.bg.luminance() < 0.5f) SignalDark else SignalLight, web)
    }

/**
 * Web parity:
 *  - `.myBubble`  → `color-mix(--primary 14%, --surface-hover)`, `--text`, border `--primary 16%`
 *  - `.theirBubble` → `--surface`, `--text`
 *  - ticks: sent/sending `--text-muted`, delivered `--text-secondary`, read `--primary`
 * Web tokens are translucent washes, so they are composited onto the chat
 * background to give solid bubble fills.
 */
internal fun orgChatColors(base: SignalColors, web: WebColors): SignalColors {
    val page = base.background
    val surfaceHover = web.surfaceHover.compositeOver(page)
    val outgoing = web.primary.copy(alpha = .14f).compositeOver(surfaceHover)
    val incoming = web.surface.compositeOver(page)
    val text = web.text.compositeOver(page)
    val secondary = web.textSecondary.compositeOver(page)
    val muted = web.textMuted.compositeOver(page)
    return base.copy(
        outgoing = outgoing,
        onOutgoing = text,
        onOutgoingSecondary = muted,
        incoming = incoming,
        onIncoming = text,
        onIncomingSecondary = muted,
        primary = web.primary,
        outgoingBorder = web.primary.copy(alpha = .16f),
        tickDelivered = secondary,
        tickMuted = muted,
        tickRead = web.primary,
    )
}