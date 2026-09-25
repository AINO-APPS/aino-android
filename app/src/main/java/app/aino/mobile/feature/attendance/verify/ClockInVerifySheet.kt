package app.aino.mobile.feature.attendance.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.feature.attendance.AttendanceAction
import app.aino.mobile.feature.attendance.VerifyErrorKind
import app.aino.mobile.feature.attendance.VerifySession
import app.aino.mobile.feature.attendance.VerifyStep
import app.aino.mobile.feature.attendance.WorkMode

/** `ClockInVerifyModal` equivalent (P3.7): signals + face capture + errors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockInVerifySheet(
    session: VerifySession,
    onVerifyIdentity: () -> Unit,
    onRetryLocation: () -> Unit,
    onEnrollFace: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = LocalWebColors.current
    val isClockOut = session.action == AttendanceAction.ClockOut

    ModalBottomSheet(onDismissRequest = onClose, containerColor = colors.bgElevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(22.dp), tint = colors.primary)
                Column {
                    Text(
                        if (isClockOut) "Verify & Clock Out" else "Verify & Login",
                        color = colors.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 1.05.rem,
                    )
                    Text(
                        "Confirm it's you — " + when (session.workMode) {
                            WorkMode.Remote -> "remote login"
                            WorkMode.Hybrid -> "hybrid login"
                            WorkMode.Office -> "at the office"
                        },
                        color = colors.textSecondary,
                        fontSize = 0.8.rem,
                    )
                }
            }

            if (session.step == VerifyStep.Collecting) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 18.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = colors.primary, strokeWidth = 2.dp)
                    Text("Collecting office signals…", color = colors.textSecondary, fontSize = 0.85.rem, modifier = Modifier.padding(start = 10.dp))
                }
            } else {
                SignalRows(session)
                session.submitError?.let { error ->
                    VerifyErrorBlock(
                        error = error,
                        onRetry = if (error.kind == VerifyErrorKind.Location) onRetryLocation else null,
                        retryLabel = "Retry location",
                        onSecondary = if (error.code == "FACE_NOT_ENROLLED") onEnrollFace else null,
                        secondaryLabel = "Enroll face",
                    )
                }
                FaceCaptureBox(
                    autoCapture = session.step == VerifyStep.Ready && session.submitError == null,
                    captureLabel = if (isClockOut) "Verify & Clock Out" else "Verify & Login",
                    capturingLabel = "Verifying...",
                    capturing = session.step == VerifyStep.Submitting,
                    onCapture = onVerifyIdentity,
                )
            }

            Text(
                "Cancel",
                color = colors.textSecondary,
                fontSize = 0.85.rem,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(colors.surface, RoundedCornerShape(8.dp))
                    .clickable(enabled = session.step != VerifyStep.Submitting, onClick = onClose)
                    .padding(horizontal = 22.dp, vertical = 9.dp),
            )
        }
    }
}

/** Office-signal status rows, matching the web modal's Wi-Fi/location lines. */
@Composable
private fun SignalRows(session: VerifySession) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (session.wifiConfigured) {
            when {
                session.wifiVerified -> SignalRow(
                    Icons.Outlined.Wifi,
                    colors.success,
                    "Office Wi-Fi verified",
                )
                session.wifiBssid != null -> SignalRow(
                    Icons.Outlined.WifiOff,
                    colors.textSecondary,
                    "Not a registered office AP — using location instead",
                )
                else -> SignalRow(
                    Icons.Outlined.WifiOff,
                    colors.textMuted,
                    "Not connected to Wi-Fi",
                )
            }
        }
        session.locationError?.let {
            SignalRow(Icons.Outlined.ErrorOutline, colors.danger, it)
        }
        if (session.location != null && !session.wifiVerified) {
            val accuracy = session.location.accuracyMeters.toInt()
            if (session.location.accuracyMeters > 200) {
                SignalRow(
                    Icons.Outlined.LocationOn,
                    colors.warning,
                    "Approximate location (±$accuracy m) — may be too coarse for the office geofence. Connect to office Wi-Fi if this fails.",
                )
            } else {
                SignalRow(Icons.Outlined.CheckCircle, colors.success, "Location verified (±$accuracy m)")
            }
        }
    }
}

@Composable
private fun SignalRow(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(14.dp).padding(top = 2.dp), tint = tint)
        Text(text, color = tint, fontSize = 0.8.rem)
    }
}
