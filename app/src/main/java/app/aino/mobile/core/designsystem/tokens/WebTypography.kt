package app.aino.mobile.core.designsystem.tokens

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Web-parity typography (P1.2).
 *
 * Base size is 16px → `1.rem == 16.sp`. `line-height: 1.6` from `body {}`.
 * The family is Inter; on Android this resolves to a downloadable Google Font
 * via `WebFonts.inter`, falling back to the platform sans-serif while it loads.
 */

/** `1rem == 16.sp`. */
inline val Float.rem get() = (this * 16f).sp
inline val Double.rem get() = (this * 16).sp
inline val Int.rem get() = (this * 16).sp

/** Body text per global.css: 16sp, line-height 1.6, colour --text. */
val WebBody: TextStyle = TextStyle(
    fontFamily = WebFonts.inter,
    fontWeight = FontWeight.Normal,
    fontSize = 1.rem, // 16sp
    lineHeight = (16f * 1.6f).sp, // 1.6
)

/** Sizes observed across the app shell, mapped from rem. */
object WebType {
    val caption = TextStyle(fontFamily = WebFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 0.62.rem, lineHeight = 1.rem) // tab labels
    val micro = TextStyle(fontFamily = WebFonts.inter, fontWeight = FontWeight.Normal, fontSize = 0.6.rem, lineHeight = 0.9.rem) // chat badge
    val body = TextStyle(fontFamily = WebFonts.inter, fontWeight = FontWeight.Normal, fontSize = 0.85.rem, lineHeight = (0.85f * 16 * 1.6f).sp)
    val bodyStrong = TextStyle(fontFamily = WebFonts.inter, fontWeight = FontWeight.SemiBold, fontSize = 0.88.rem, lineHeight = (0.88f * 16 * 1.6f).sp)
    val title = TextStyle(fontFamily = WebFonts.inter, fontWeight = FontWeight.Bold, fontSize = 1.4.rem, lineHeight = (1.4f * 16 * 1.6f).sp)
}
