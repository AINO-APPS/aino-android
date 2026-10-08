package app.aino.mobile.feature.attendance

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.common.TimerAnchor
import app.aino.mobile.core.common.formatDuration
import app.aino.mobile.core.designsystem.component.FirstLoadSpinner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.delay

/**
 * Today hero card: the app's only live worked-time timer, plus status, work
 * mode, target progress and the clock / break actions. Actions go through
 * [AttendanceViewModel], which owns the verification sheet, permission prompt
 * and busy guard. The ticking anchor is [AttendanceViewModel.timer].
 */
@Composable
fun TodayHeroCard(
    ui: AttendanceUiState,
    onWorkMode: (WorkMode) -> Unit,
    onClock: (AttendanceAction) -> Unit,
    onBreak: (start: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** The selected mode differs from today's locked mode: ask the manager. */
    onRequestModeChange: (WorkMode) -> Unit = {},
    /** [AttendanceViewModel.timer]: the live anchor survives recomposition, refreshes and tab switches. */
    timer: TimerAnchor? = null,
) {
    val colors = LocalWebColors.current
    val status = ui.status
    val running = status?.state == "on_floor" || status?.state == "on_break"
    // Only the clock lives here; the anchor comes from the ViewModel, so a fresh `now` never resets the count.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timer, running) {
        now = System.currentTimeMillis()
        while (running) {
            delay(timer?.msToNextSecond(now) ?: 1_000L)
            now = System.currentTimeMillis()
        }
    }
    val today = todaySnapshot(status, timer, now)
    val phaseColor = when (today.phase) {
        TodayPhase.Working -> colors.success
        TodayPhase.OnBreak -> colors.warning
        TodayPhase.Done -> colors.primary
        TodayPhase.NotStarted -> colors.textSecondary
    }
    val progressColor = when {
        today.progress >= 0.9f -> colors.success
        today.progress >= 0.6f -> colors.primary
        today.progress >= 0.35f -> colors.warning
        else -> colors.danger
    }
    var confirmClockOut by remember { mutableStateOf(false) }
    val firstLoad = status == null && ui.loading

    AttendanceCard(modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!firstLoad) StatusPill(today.phase.label, phaseColor, dot = true)
            if (today.phase != TodayPhase.NotStarted && !firstLoad) {
                val remote = today.workMode == "remote"
                StatusPill(
                    if (remote) "Remote" else today.workMode.replaceFirstChar(Char::uppercase),
                    colors.textSecondary,
                    icon = if (remote) HeroIcons.Home else HeroIcons.BuildingOffice2,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(friendlyDate(java.time.LocalDate.now()), color = colors.textMuted, fontSize = 0.78.rem)
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
            Column(
                Modifier.weight(1f).clearAndSetSemantics {
                    contentDescription = "Worked ${today.workedMinutes / 60} hours ${today.workedMinutes % 60} minutes today"
                },
            ) {
                Text(
                    formatDuration(today.workedSeconds),
                    color = colors.text,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                )
                Text(
                    if (today.phase == TodayPhase.OnBreak) "On break · ${formatDuration(today.breakSeconds)}" else "worked today",
                    color = if (today.phase == TodayPhase.OnBreak) colors.warning else colors.textSecondary,
                    fontSize = 0.8.rem,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                val overtime = today.overtimeMinutes > 0
                Text(if (overtime) "Overtime" else "Remaining", color = colors.textMuted, fontSize = 0.72.rem)
                Text(
                    hoursLabel(if (overtime) today.overtimeMinutes else today.remainingMinutes),
                    color = if (overtime) colors.warning else colors.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 0.95.rem,
                )
            }
        }

        LinearProgressIndicator(
            progress = { today.progress },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(6.dp).clip(CircleShape),
            color = progressColor,
            trackColor = colors.surfaceHover,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(
                "${(today.progress * 100).toInt()}% of ${hoursLabel(today.targetMinutes)} target",
                color = colors.textSecondary,
                fontSize = 0.74.rem,
                modifier = Modifier.weight(1f),
            )
            if (today.breakSeconds >= 60 && today.phase != TodayPhase.OnBreak) {
                Text("Break ${hoursLabel((today.breakSeconds / 60).toInt())}", color = colors.textMuted, fontSize = 0.74.rem)
            }
        }

        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                firstLoad -> FirstLoadSpinner(Modifier.height(48.dp))
                today.canClockIn -> {
                    WorkModeSelector(ui.workMode, onWorkMode)
                    val lock = workModeLock(status, ui.workMode)
                    lock.hint?.let { Text(it, color = colors.textSecondary, fontSize = 0.74.rem) }
                    if (lock.needsRequest) {
                        ActionButton(
                            label = if (lock.pending) "Change requested" else "Request ${ui.workMode.name.lowercase()} for today",
                            icon = HeroIcons.ArrowsRightLeft,
                            container = colors.primary,
                            busy = false,
                            modifier = Modifier.fillMaxWidth(),
                        ) { if (!lock.pending) onRequestModeChange(ui.workMode) }
                    } else {
                        ActionButton(
                            label = if (ui.clockBusy) "Clocking in…" else "Clock in",
                            icon = HeroIcons.Play,
                            container = colors.success,
                            busy = ui.clockBusy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { onClock(AttendanceAction.ClockIn) }
                    }
                }
                today.phase == TodayPhase.Working -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryAction("Start break", HeroIcons.Cup, !ui.clockBusy, Modifier.weight(1f)) { onBreak(true) }
                    ActionButton("Clock out", HeroIcons.ArrowRightStartOnRectangle, colors.danger, ui.clockBusy, Modifier.weight(1f)) {
                        confirmClockOut = true
                    }
                }
                today.phase == TodayPhase.OnBreak -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton("End break", HeroIcons.Play, colors.success, ui.clockBusy, Modifier.weight(1f)) { onBreak(false) }
                    SecondaryAction("Clock out", HeroIcons.ArrowRightStartOnRectangle, !ui.clockBusy, Modifier.weight(1f)) {
                        confirmClockOut = true
                    }
                }
                today.targetMet -> InfoNotice("Daily target complete — nice work!", colors.success, HeroIcons.CheckCircle)
            }
        }
    }

    if (confirmClockOut) {
        AlertDialog(
            onDismissRequest = { confirmClockOut = false },
            containerColor = colors.bgElevated,
            titleContentColor = colors.text,
            textContentColor = colors.textSecondary,
            title = { Text("Clock out?") },
            text = { Text("You've worked ${hoursLabel(today.workedMinutes)} today.") },
            confirmButton = {
                TextButton(onClick = { confirmClockOut = false; onClock(AttendanceAction.ClockOut) }) {
                    Text("Clock out", color = colors.danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClockOut = false }) { Text("Cancel") } },
        )
    }
}

/** Office / Remote choice for the next clock-in (same options as the dashboard timer). */
@Composable
private fun WorkModeSelector(selected: WorkMode, onSelect: (WorkMode) -> Unit) {
    val colors = LocalWebColors.current
    val options = listOf(WorkMode.Office to HeroIcons.BuildingOffice2, WorkMode.Remote to HeroIcons.Home)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, icon) ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = segmentedColors(),
                icon = { Icon(icon, null, Modifier.size(16.dp)) },
            ) { Text(mode.name, fontSize = 0.85.rem) }
        }
    }
}

/** Segmented-button colours from the web tokens. */
@Composable
fun segmentedColors() = LocalWebColors.current.let { colors ->
    SegmentedButtonDefaults.colors(
        activeContainerColor = colors.primaryGlow,
        activeContentColor = colors.primary,
        activeBorderColor = colors.primary.copy(alpha = 0.6f),
        inactiveContainerColor = Color.Transparent,
        inactiveContentColor = colors.textSecondary,
        inactiveBorderColor = colors.border,
    )
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    container: Color,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalWebColors.current
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = modifier.height(46.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = colors.onAccent,
            disabledContainerColor = container.copy(alpha = 0.5f),
            disabledContentColor = colors.onAccent,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = colors.onAccent, strokeWidth = 2.dp)
        } else {
            Icon(icon, null, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryAction(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(46.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text, disabledContentColor = colors.textMuted),
    ) {
        Icon(icon, null, Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
