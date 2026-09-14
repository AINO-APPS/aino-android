package app.aino.mobile.feature.attendance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoAlert
import app.aino.mobile.core.designsystem.AinoAtmosphere
import app.aino.mobile.core.designsystem.AinoBadge
import app.aino.mobile.core.designsystem.AinoGlassCard
import app.aino.mobile.core.designsystem.AinoPrimaryButton
import app.aino.mobile.core.designsystem.AinoSectionHeader
import app.aino.mobile.core.designsystem.AlertTone
import app.aino.mobile.feature.home.formatDuration

@Composable
fun AttendanceScreen(
    viewModel: AttendanceViewModel,
    onLocationPermission: () -> Unit,
    onBiometricRequired: () -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val status = ui.status
    AinoAtmosphere {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AinoSectionHeader("Attendance", "Secure clocking with tenant policy enforcement")
            ui.error?.let { AinoAlert(it, AlertTone.Error) }
            ui.message?.let { AinoAlert(it, AlertTone.Success) }
            AinoGlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        AinoBadge(statusLabel(status?.state), statusTone(status?.state))
                        AinoBadge(ui.workMode.name, if (ui.workMode == WorkMode.Remote) AlertTone.Warning else AlertTone.Info)
                    }
                    val floorSeconds = (status?.floorMinutes ?: 0) * 60L
                    val breakSeconds = (status?.breakMinutes ?: 0) * 60L
                    Text(formatDuration(floorSeconds), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Text("Work today · Break ${formatDuration(breakSeconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ui.policy?.verificationEnabled == true) {
                        AinoAlert("Attendance verification is enabled for this workspace.", AlertTone.Info)
                        ui.locationProof?.let { proof ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
                                Text("Precise location ±${proof.accuracyMeters.toInt()} m", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if (status?.state == "logged_out") {
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Work mode", style = MaterialTheme.typography.titleMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WorkMode.entries.forEach { mode ->
                                Button(
                                    onClick = { viewModel.setWorkMode(mode) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (ui.workMode == mode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (ui.workMode == mode) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                ) { Text(mode.name.lowercase().replaceFirstChar(Char::uppercase), maxLines = 1) }
                            }
                        }
                        AinoPrimaryButton(
                            if (ui.loading) "Preparing…" else "Clock in",
                            { viewModel.prepare(AttendanceAction.ClockIn, onLocationPermission, onBiometricRequired) },
                            Modifier.fillMaxWidth(),
                            !ui.loading && status.isWeekend.not(),
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Login, null, Modifier.padding(end = 8.dp), tint = Color.White) },
                        )
                    }
                }
            } else {
                AinoGlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (status?.state == "on_floor") {
                            AinoPrimaryButton(
                                "Start break",
                                { viewModel.breakAction(start = true) },
                                Modifier.fillMaxWidth(),
                                !ui.loading,
                                leadingIcon = { Icon(Icons.Outlined.Coffee, null, Modifier.padding(end = 8.dp), tint = Color.White) },
                            )
                        } else if (status?.state == "on_break") {
                            AinoPrimaryButton("Resume work", { viewModel.breakAction(start = false) }, Modifier.fillMaxWidth(), !ui.loading)
                        }
                        AinoPrimaryButton(
                            if (ui.loading) "Preparing…" else "Clock out",
                            { viewModel.prepare(AttendanceAction.ClockOut, onLocationPermission, onBiometricRequired) },
                            Modifier.fillMaxWidth(),
                            !ui.loading,
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.padding(end = 8.dp), tint = Color.White) },
                        )
                    }
                }
            }
            AinoPrimaryButton("Refresh attendance", viewModel::refresh, Modifier.fillMaxWidth(), !ui.loading, leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.padding(end = 8.dp), tint = Color.White) })
            if (ui.policy?.verificationEnabled == true && ui.workMode == WorkMode.Remote) {
                AinoAlert("Remote verified clock-in requires face matching. Password and fingerprint alone cannot satisfy this policy.", AlertTone.Warning)
            } else if (ui.policy?.verificationEnabled == true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colorScheme.primary)
                    Text("Office actions require precise location and strong biometric confirmation.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun statusLabel(state: String?): String = when (state) {
    "on_floor" -> "Working"
    "on_break" -> "On break"
    else -> "Logged out"
}

private fun statusTone(state: String?): AlertTone = when (state) {
    "on_floor" -> AlertTone.Success
    "on_break" -> AlertTone.Warning
    else -> AlertTone.Info
}