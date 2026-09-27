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
    /** Web `.bubble { border-radius: 16px }` with the 4px notch corner. */
    val bubbleCorner = 16.dp
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
)

val SignalLight = SignalColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF6F6F6),
    outgoing = Color(0xFF2C6BED),
    onOutgoing = Color(0xFFFFFFFF),
    onOutgoingSecondary = Color(0xCCFFFFFF),
    incoming = Color(0xFFE9E9E9),
    onIncoming = Color(0xFF1B1B1B),
    onIncomingSecondary = Color(0xFF5E5E5E),
    primary = Color(0xFF2C6BED),
    text = Color(0xFF1B1B1B),
    textSecondary = Color(0xFF5E5E5E),
    divider = Color(0x1F000000),
    searchPill = Color(0xFFEFF1F4),
    datePill = Color(0xFFF0F0F0),
    danger = Color(0xFFF44336),
    scrim = Color(0x99000000),
    highlight = Color(0x66FFD54F),
)

val SignalDark = SignalColors(
    isDark = true,
    background = Color(0xFF121212),
    surface = Color(0xFF1B1B1D),
    outgoing = Color(0xFF2C6BED),
    onOutgoing = Color(0xFFFFFFFF),
    onOutgoingSecondary = Color(0xCCFFFFFF),
    incoming = Color(0xFF303133),
    onIncoming = Color(0xFFE9E9E9),
    onIncomingSecondary = Color(0xFFB9B9B9),
    primary = Color(0xFF6191F3),
    text = Color(0xFFE9E9E9),
    textSecondary = Color(0xFFB9B9B9),
    divider = Color(0x1FFFFFFF),
    searchPill = Color(0xFF2A2A2C),
    datePill = Color(0xFF2A2A2C),
    danger = Color(0xFFFF6F61),
    scrim = Color(0xB3000000),
    highlight = Color(0x80FFB300),
)

/**
 * Chat palette: Signal's layout colours with the bubbles, receipts and
 * accents taken from the web org theme (`MessageBubble.module.css`,
 * `DeliveryStatus.tsx`), so the org branding accent flows into chat exactly
 * like it does on desktop/web.
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
