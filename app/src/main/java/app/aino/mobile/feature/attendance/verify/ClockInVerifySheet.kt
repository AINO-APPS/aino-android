package app.aino.mobile.feature.attendance.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.feature.attendance.AttendanceAction
import app.aino.mobile.feature.attendance.VerifyFix
import app.aino.mobile.feature.attendance.VerifySession
import app.aino.mobile.feature.attendance.VerifyStep
import app.aino.mobile.feature.attendance.WorkMode

/** One-tap remediations the host wires to Android intents / flows. */
data class VerifyActions(
    val onRetryLocation: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onEnableLocation: () -> Unit,
    val onSetUpScreenLock: () -> Unit,
    val onEnableFingerprint: () -> Unit,
)

/**
 * Android clock verification (web `ClockInVerifyModal` layout, native proof):
 * ① Location → ② Fingerprint / PIN. Opens fully expanded with fixed-height
 * sections so nothing jumps while signals load, and launches the OS identity
 * prompt automatically once presence is settled ([VerifySession.promptToken]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockInVerifySheet(
    session: VerifySession,
    onLaunchIdentityPrompt: () -> Unit,
    onUseFingerprint: () -> Unit,
    actions: VerifyActions,
    onClose: () -> Unit,
) {
    val colors = LocalWebColors.current
    val isClockOut = session.action == AttendanceAction.ClockOut
    val busy = session.step == VerifyStep.Submitting || session.step == VerifyStep.Authenticating
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Auto-launch the OS prompt once per token (presence settled / retry).
    LaunchedEffect(session.promptToken) {
        if (session.promptToken > 0 && session.step == VerifyStep.Ready && session.submitError == null) {
            onLaunchIdentityPrompt()
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = sheetState, containerColor = colors.bgElevated) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Shield, null, Modifier.size(22.dp), tint = colors.primary)
                Column {
                    Text(
                        if (isClockOut) "Verify & Clock Out" else "Verify & Clock In",
                        color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem,
                    )
                    Text(subtitle(session), color = colors.textSecondary, fontSize = 0.8.rem)
                }
            }

            StepIndicator(session)

            if (session.needsPresence) PresencePanel(session)

            IdentityPanel(session, onUseFingerprint)

            session.submitError?.let { error ->
                VerifyErrorBlock(
                    error = error,
                    onRetry = fixAction(error.fix, actions, onUseFingerprint),
                    retryLabel = fixLabel(error.fix),
                )
            }

            Text(
                "Cancel",
                color = if (busy) colors.textMuted else colors.textSecondary,
                fontSize = 0.85.rem,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .background(colors.surface, RoundedCornerShape(8.dp))
                    .clickable(enabled = !busy, onClick = onClose)
                    .padding(horizontal = 22.dp, vertical = 9.dp),
            )
        }
    }
}

private fun subtitle(session: VerifySession): String = when {
    !session.needsPresence && session.action == AttendanceAction.ClockOut -> "Confirm it's you to end your remote session"
    !session.needsPresence -> "Remote — confirm it's you"
    session.workMode == WorkMode.Hybrid -> "Hybrid — office if you're on site, otherwise remote"
    else -> "Office — confirm your location and identity"
}

private fun fixLabel(fix: VerifyFix): String = when (fix) {
    VerifyFix.RetryLocation -> "Retry location"
    VerifyFix.OpenAppSettings -> "Open app settings"
    VerifyFix.EnableLocation -> "Turn on location"
    VerifyFix.SetUpScreenLock -> "Set up screen lock"
    VerifyFix.EnableFingerprint -> "Enable fingerprint"
    VerifyFix.Retry, VerifyFix.None -> "Try again"
}

private fun fixAction(fix: VerifyFix, actions: VerifyActions, onUseFingerprint: () -> Unit): (() -> Unit)? = when (fix) {
    VerifyFix.RetryLocation -> actions.onRetryLocation
    VerifyFix.OpenAppSettings -> actions.onOpenAppSettings
    VerifyFix.EnableLocation -> actions.onEnableLocation
    VerifyFix.SetUpScreenLock -> actions.onSetUpScreenLock
    VerifyFix.EnableFingerprint -> actions.onEnableFingerprint
    VerifyFix.Retry -> onUseFingerprint
    VerifyFix.None -> null
}

private enum class StepState { Pending, Active, Done, Failed }

/** Web modal's numbered step strip: ① Location → ② Fingerprint (remote: ① only). */
@Composable
private fun StepIndicator(session: VerifySession) {
    val presenceState = when {
        session.step == VerifyStep.Collecting -> StepState.Active
        session.presenceProven -> StepState.Done
        session.submitError != null && session.submitError.kind == app.aino.mobile.feature.attendance.VerifyErrorKind.Location -> StepState.Failed
        else -> StepState.Done // hybrid fallback to remote counts as settled
    }
    val identityState = when {
        session.step == VerifyStep.Collecting -> StepState.Pending
        session.step == VerifyStep.Submitting -> StepState.Done
        session.submitError != null && session.submitError.kind != app.aino.mobile.feature.attendance.VerifyErrorKind.Location -> StepState.Failed
        else -> StepState.Active
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (session.needsPresence) {
            StepChip(1, "Location", presenceState)
            Box(Modifier.weight(1f).padding(horizontal = 8.dp).height(2.dp).background(LocalWebColors.current.border))
            StepChip(2, "Fingerprint", identityState)
        } else {
            StepChip(1, "Fingerprint", identityState)
        }
    }
}

@Composable
private fun StepChip(number: Int, label: String, state: StepState) {
    val colors = LocalWebColors.current
    val tint = when (state) {
        StepState.Done -> colors.success
        StepState.Failed -> colors.danger
        StepState.Active -> colors.primary
        StepState.Pending -> colors.textMuted
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(22.dp).background(tint.copy(alpha = .15f), CircleShape).border(1.dp, tint, CircleShape), contentAlignment = Alignment.Center) {
            when (state) {
                StepState.Done -> Icon(Icons.Outlined.Check, null, Modifier.size(13.dp), tint = tint)
                StepState.Failed -> Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(13.dp), tint = tint)
                else -> Text("$number", color = tint, fontSize = 0.72.rem, fontWeight = FontWeight.Bold)
            }
        }
        Text(label, color = if (state == StepState.Pending) colors.textMuted else colors.text, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold)
    }
}

/** Office-presence rows in a fixed-height box so the sheet never jumps. */
@Composable
private fun PresencePanel(session: VerifySession) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .background(colors.surface, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (session.step == VerifyStep.Collecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), color = colors.primary, strokeWidth = 2.dp)
                Text("Checking office Wi-Fi and location…", color = colors.textSecondary, fontSize = 0.82.rem, modifier = Modifier.padding(start = 10.dp))
            }
            return@Column
        }
        when {
            session.wifiVerified -> SignalRow(Icons.Outlined.Wifi, colors.success, "Connected to office Wi-Fi")
            session.wifiConfigured && session.wifiBssid != null -> SignalRow(Icons.Outlined.WifiOff, colors.textSecondary, "Not a registered office Wi-Fi — using location")
            session.wifiConfigured -> SignalRow(Icons.Outlined.WifiOff, colors.textMuted, "Not on Wi-Fi — using location")
        }
        val location = session.location
        if (!session.wifiVerified && location != null) {
            val accuracy = location.accuracyMeters.toInt()
            val distance = session.distanceMeters
            if (session.presenceProven) {
                SignalRow(Icons.Outlined.CheckCircle, colors.success, "At the office" + (distance?.let { " · $it m away" } ?: "") + " (±$accuracy m)")
            } else {
                SignalRow(Icons.Outlined.LocationOn, colors.warning, "Outside the office" + (distance?.let { " · $it m away" } ?: "") + " (±$accuracy m)")
            }
        }
        if (!session.presenceProven && session.presenceOptional && session.step != VerifyStep.Collecting) {
            SignalRow(Icons.Outlined.LocationOn, colors.textSecondary, "Not verified at the office — this will be recorded as remote")
        }
    }
}

@Composable
private fun SignalRow(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.size(15.dp).padding(top = 1.dp), tint = tint)
        Text(text, color = tint, fontSize = 0.82.rem)
    }
}

/**
 * Step ② — a large fingerprint target (fixed height). The OS prompt opens on
 * its own; tapping here re-opens it. Screen lock (PIN/pattern) is accepted.
 */
@Composable
private fun IdentityPanel(session: VerifySession, onUseFingerprint: () -> Unit) {
    val colors = LocalWebColors.current
    val waiting = session.step == VerifyStep.Collecting
    val submitting = session.step == VerifyStep.Submitting
    val authenticating = session.step == VerifyStep.Authenticating
    val blockedByLocation = session.submitError?.kind == app.aino.mobile.feature.attendance.VerifyErrorKind.Location
    val enabled = !waiting && !submitting && !authenticating && !blockedByLocation
    val tint = when {
        submitting -> colors.success
        waiting || blockedByLocation -> colors.textMuted
        else -> colors.primary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(168.dp)
            .background(colors.surface, RoundedCornerShape(14.dp))
            .border(1.dp, if (enabled) colors.primary.copy(alpha = .35f) else colors.border, RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onUseFingerprint)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(72.dp).background(tint.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
            if (submitting) {
                CircularProgressIndicator(Modifier.size(34.dp), color = colors.success, strokeWidth = 3.dp)
            } else {
                Icon(Icons.Outlined.Fingerprint, null, Modifier.size(40.dp), tint = tint)
            }
        }
        Text(
            when {
                submitting -> if (session.action == AttendanceAction.ClockOut) "Clocking out…" else "Clocking in…"
                authenticating -> "Touch the sensor or enter your PIN"
                waiting -> "Waiting for location…"
                blockedByLocation -> "Fix the location issue to continue"
                else -> "Tap to verify with fingerprint or PIN"
            },
            color = if (enabled || submitting || authenticating) colors.text else colors.textSecondary,
            fontWeight = FontWeight.SemiBold,
            fontSize = 0.9.rem,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "Your fingerprint never leaves this device.",
            color = colors.textMuted, fontSize = 0.74.rem, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
