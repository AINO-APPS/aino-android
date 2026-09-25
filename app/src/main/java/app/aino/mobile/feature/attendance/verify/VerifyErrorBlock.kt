package app.aino.mobile.feature.attendance.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.feature.attendance.VerifyErrorKind
import app.aino.mobile.feature.attendance.VerifySubmitError
import kotlinx.coroutines.delay

/** Face-attempt lockout window enforced by the server ("Please wait 15 minutes"). */
private const val LOCKOUT_SECONDS = 15 * 60

private fun fmtClock(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/**
 * `VerifyError` port (P3.7): kind-specific icon + title + the server's exact
 * detail message, plus optional remediation buttons. For `FACE_ATTEMPTS_LOCKED`
 * it shows a live "try again in mm:ss" countdown.
 */
@Composable
fun VerifyErrorBlock(
    error: VerifySubmitError,
    onRetry: (() -> Unit)? = null,
    retryLabel: String = "Try again",
    onSecondary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
) {
    val colors = LocalWebColors.current
    val isLocked = error.code == "FACE_ATTEMPTS_LOCKED"
    var remaining by remember(error.message) { mutableIntStateOf(if (isLocked) LOCKOUT_SECONDS else 0) }
    LaunchedEffect(isLocked, error.message) {
        if (!isLocked) return@LaunchedEffect
        remaining = LOCKOUT_SECONDS
        while (remaining > 0) {
            delay(1000)
            remaining -= 1
        }
    }
    val locked = isLocked && remaining > 0
    val icon = when {
        isLocked -> Icons.Outlined.Schedule
        error.kind == VerifyErrorKind.Location -> Icons.Outlined.LocationOn
        error.kind == VerifyErrorKind.Face -> Icons.Outlined.Face
        else -> Icons.Outlined.ErrorOutline
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0x1AEF4444), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0x40EF4444), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = Color(0xFFEF4444))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(error.title, color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 0.88.rem)
            Text(error.message, color = Color(0xFFFCA5A5), fontSize = 0.8.rem)
            if (isLocked) {
                Text(
                    if (locked) "You can try again in ${fmtClock(remaining)}" else "You can try again now.",
                    color = Color(0xFFFCA5A5),
                    fontSize = 0.78.rem,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onRetry != null) {
                    Text(
                        retryLabel,
                        color = colors.onAccent,
                        fontSize = 0.8.rem,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(if (locked) colors.primary.copy(alpha = 0.4f) else colors.primary, RoundedCornerShape(7.dp))
                            .clickable(enabled = !locked, onClick = onRetry)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                if (onSecondary != null && secondaryLabel != null) {
                    Text(
                        secondaryLabel,
                        color = colors.text,
                        fontSize = 0.8.rem,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .border(1.dp, colors.border, RoundedCornerShape(7.dp))
                            .clickable(onClick = onSecondary)
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
