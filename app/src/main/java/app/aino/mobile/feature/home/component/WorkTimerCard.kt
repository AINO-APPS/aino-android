package app.aino.mobile.feature.home.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.core.common.formatDuration
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColors
import app.aino.mobile.core.common.formatMinutes

/**
 * `WorkTimerCard` port (P2.3). State machine: logged_out / on_floor / on_break.
 * Controls, labels and colours match the web card; the SVG ring is rendered as
 * a large live timer + progress bar.
 */
@Composable
fun WorkTimerCard(
    status: TrackerStatus?,
    loading: Boolean,
    floorSeconds: Long,
    breakSeconds: Long,
    workMode: String,
    onWorkMode: (String) -> Unit,
    onAction: (String) -> Unit,
    onBreak: (Boolean) -> Unit,
) {
    val colors = LocalWebColors.current
    val state = status?.state ?: "logged_out"
    val targetMinutes = (status?.targetMinutes ?: 480).coerceAtLeast(1)
    val dailyTargetMet = status?.dailyTargetMet == true
    // useFloatingTimer: live seconds while a session runs, server minutes once logged out.
    val floorMinutes = if (state == "logged_out") status?.floorMinutes ?: 0 else (floorSeconds / 60).toInt()
    val breakMinutes = if (state == "logged_out") status?.breakMinutes ?: 0 else (breakSeconds / 60).toInt()
    val progressPercent = (floorMinutes * 100f / targetMinutes).coerceIn(0f, 100f)
    val completedTarget = floorMinutes >= targetMinutes
    val remaining = (targetMinutes - floorMinutes).coerceAtLeast(0)
    val overtime = (floorMinutes - targetMinutes).coerceAtLeast(0)
    val breakCount = status?.entries?.count { it.entryType == "break_start" } ?: 0
    val progressColor = when {
        progressPercent >= 90 -> colors.success
        progressPercent >= 60 -> colors.primary
        progressPercent >= 35 -> colors.warning
        else -> colors.danger
    }
    val eta = if (state == "on_floor" && !completedTarget) {
        val remainingSec = targetMinutes * 60L - floorSeconds
        if (remainingSec > 0) java.time.LocalTime.now().plusSeconds(remainingSec).format(java.time.format.DateTimeFormatter.ofPattern("hh:mm a")) else null
    } else null
    var confirmLogout by remember { mutableStateOf(false) }

    WebCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Timer, null, Modifier.size(14.dp), tint = colors.textSecondary)
            Text("Work Timer", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp))
            Box(Modifier.weight(1f))
            Box(Modifier.size(9.dp).background(when (state) { "on_floor" -> colors.success; "on_break" -> colors.warning; else -> colors.textMuted }, CircleShape))
        }

        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when (state) { "on_floor" -> formatDuration(floorSeconds); "on_break" -> formatDuration(breakSeconds); else -> if (dailyTargetMet) "✓" else "—" },
                color = colors.text, fontSize = 34.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                when (state) { "on_floor" -> "Working"; "on_break" -> "On Break"; else -> if (dailyTargetMet) "Target Met" else "Logged Out" },
                color = colors.textSecondary, fontSize = 13.sp,
            )
        }

        if (state != "logged_out") {
            StatRow(
                listOfNotNull(
                    "Work" to formatMinutes(floorMinutes),
                    "Break" to formatDuration(breakSeconds),
                    "Remaining" to if (completedTarget) "—" else formatMinutes(remaining),
                    if (breakCount > 0) "Breaks" to breakCount.toString() else null,
                ),
                colors,
            )
            Box(Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).background(colors.surface, CircleShape)) {
                Box(Modifier.fillMaxWidth(progressPercent / 100f).height(6.dp).background(progressColor, CircleShape))
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${progressPercent.toInt()}%", color = colors.textSecondary, fontSize = 12.sp)
                Text("${targetMinutes / 60}hr target", color = colors.textMuted, fontSize = 12.sp)
            }
        } else if (floorMinutes > 0 || breakMinutes > 0) {
            StatRow(
                listOf("Work" to formatMinutes(floorMinutes), "Break" to formatMinutes(breakMinutes), "Total" to formatMinutes(floorMinutes + breakMinutes)),
                colors,
            )
        }
        eta?.let {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = colors.textSecondary)
                Text(" ${targetMinutes / 60}hr by ", color = colors.textSecondary, fontSize = 12.sp)
                Text(it, color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (overtime > 0) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bolt, null, Modifier.size(12.dp), tint = colors.warning)
                Text(" Overtime: ", color = colors.warning, fontSize = 12.sp)
                Text(formatMinutes(overtime), color = colors.warning, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        when {
            state == "logged_out" && !dailyTargetMet -> {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton("Office", workMode == "office", Icons.Outlined.Apartment, colors, Modifier.weight(1f)) { onWorkMode("office") }
                    ModeButton("Remote", workMode == "remote", Icons.Outlined.Home, colors, Modifier.weight(1f)) { onWorkMode("remote") }
                }
                Button(onClick = { onAction("clock_in") }, enabled = !loading, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.success)) {
                    Text(if (loading) "Logging in..." else "▶ Login", color = colors.onAccent)
                }
            }
            state == "logged_out" && dailyTargetMet -> Text("✅ Daily target complete!", color = colors.success, modifier = Modifier.padding(top = 12.dp))
            else -> {
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state == "on_floor") {
                        Button(onClick = { onBreak(true) }, enabled = !loading, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = colors.warning)) {
                            Icon(Icons.Outlined.Coffee, null, Modifier.size(14.dp)); Text(" Break", color = colors.onAccent)
                        }
                    } else {
                        Button(onClick = { onBreak(false) }, enabled = !loading, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = colors.success)) {
                            Icon(Icons.Outlined.PlayArrow, null, Modifier.size(14.dp)); Text(" Resume", color = colors.onAccent)
                        }
                    }
                    Button(onClick = { confirmLogout = true }, enabled = !loading, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = colors.danger)) {
                        Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(14.dp)); Text(" Logout", color = colors.onAccent)
                    }
                }
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Logout") },
            text = { Text("You've worked ${formatMinutes(floorMinutes)} today. Are you sure you want to logout?") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onAction("clock_out") }) { Text(if (loading) "Logging out..." else "Logout", color = colors.danger) } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatRow(stats: List<Pair<String, String>>, colors: WebColors) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        stats.forEach { (label, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, color = colors.textMuted, fontSize = 11.sp)
                Text(value, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, active: Boolean, icon: ImageVector, colors: WebColors, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clickable(onClick = onClick).background(if (active) colors.primaryGlow else colors.surface, CircleShape).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(13.dp), tint = if (active) colors.primary else colors.textSecondary)
        Text(" $label", color = if (active) colors.primary else colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}