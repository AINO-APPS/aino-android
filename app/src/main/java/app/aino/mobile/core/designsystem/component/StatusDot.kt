package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class StatusGlyph { Check, Minus, Clock, Ring, Dot }

data class StatusVisual(val label: String, val color: Color, val glyph: StatusGlyph, val ring: Boolean = false)

private val Success = Color(0xFF4DAA57) // --success
private val Warning = Color(0xFFCB912F) // --warning
private val Red = Color(0xFFEF4444)
private val Sky = Color(0xFF0EA5E9)
val OfflineRing = Color(0xFF64748B)

/**
 * `ProfileMenu.tsx` STATUS_META_MAP + Navbar `.dot-*` classes: the effective
 * status shown on the avatar dot and the header badge. `available` reads
 * "Working" / "Working Remotely" while clocked in.
 */
fun profileStatusVisual(effective: String?, workState: String? = null, workMode: String? = null): StatusVisual =
    when (effective) {
        "busy" -> StatusVisual("Busy", Red, StatusGlyph.Dot)
        "dnd" -> StatusVisual("Do Not Disturb", Red, StatusGlyph.Minus)
        "brb", "away" -> StatusVisual("Away", Warning, StatusGlyph.Clock)
        "offline" -> StatusVisual("Offline", OfflineRing, StatusGlyph.Ring, ring = true)
        "in_call" -> StatusVisual("In a Call", Red, StatusGlyph.Dot)
        "in_meeting" -> StatusVisual("In a Meeting", Sky, StatusGlyph.Dot)
        else -> StatusVisual(
            when {
                workState == "on_floor" && workMode == "remote" -> "Working Remotely"
                workState == "on_floor" -> "Working"
                else -> "Available"
            },
            Success,
            StatusGlyph.Check,
        )
    }

/** Round status dot with the per-status glyph (the web's inline 10×10 SVGs). */
@Composable
fun StatusDot(visual: StatusVisual, size: Dp, ringColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size)
            .background(ringColor, CircleShape)
            .padding(2.dp)
            .then(
                if (visual.ring) Modifier.border(1.5.dp, OfflineRing, CircleShape)
                else Modifier.background(visual.color, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        StatusGlyphIcon(visual.glyph, if (visual.ring) OfflineRing else Color.White, Modifier.size(size * .6f))
    }
}

/** Glyph paths transcribed from `ProfileMenu.tsx` `renderStatusGlyph` (viewBox 0 0 10 10). */
@Composable
fun StatusGlyphIcon(glyph: StatusGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension / 10f
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        when (glyph) {
            StatusGlyph.Check -> drawPath(
                Path().apply { moveTo(2.1f * s, 5.1f * s); lineTo(4.2f * s, 7f * s); lineTo(7.9f * s, 3.3f * s) },
                color, style = Stroke(1.7f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            StatusGlyph.Minus -> drawLine(color, p(2.6f, 5f), p(7.4f, 5f), 1.7f * s, StrokeCap.Round)
            StatusGlyph.Clock -> {
                drawCircle(color, 3.1f * s, p(5f, 5f), style = Stroke(1.3f * s))
                drawPath(
                    Path().apply { moveTo(5f * s, 3.2f * s); lineTo(5f * s, 5.1f * s); lineTo(6.4f * s, 6f * s) },
                    color, style = Stroke(1.3f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            StatusGlyph.Ring -> drawCircle(color, 3f * s, p(5f, 5f), style = Stroke(1.5f * s))
            StatusGlyph.Dot -> drawCircle(color, 1.6f * s, p(5f, 5f))
        }
    }
}
