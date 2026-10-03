package app.aino.mobile.feature.attendance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.FirstLoadSpinner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * Requests page: manual-entry and overtime requests in one newest-first list
 * with type + status chips. New requests are created from the page FAB menu
 * in bottom-sheet forms that keep the existing VM validation.
 */
@Composable
fun RequestsPage(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    var kindFilter by rememberSaveable { mutableStateOf<RequestKind?>(null) }
    val all = remember(ui.manualRequests, ui.overtimeRequests) { mergeRequests(ui.manualRequests, ui.overtimeRequests) }
    val shown = all.filter { kindFilter == null || it.kind == kindFilter }

    AttendancePageList(loading = ui.loading, onRefresh = viewModel::refresh) {
        item(key = "filters") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { AttendanceFilterChip("All ${all.size}", kindFilter == null, { kindFilter = null }) }
                items(RequestKind.entries) { kind ->
                    AttendanceFilterChip(
                        "${kind.label} ${all.count { it.kind == kind }}",
                        kindFilter == kind,
                        { kindFilter = kind },
                        icon = kind.icon(),
                    )
                }
            }
        }
        when {
            ui.loading && all.isEmpty() && ui.status == null -> item(key = "loading") { FirstLoadSpinner(Modifier.height(120.dp)) }
            shown.isEmpty() -> item(key = "empty") {
                EmptyState(HeroIcons.ClipboardDocumentList, "No requests yet", "Missed a clock-in or worked extra? Tap New request.")
            }
            else -> items(shown, key = { it.key }) { RequestCard(it) }
        }
    }
    when (ui.sheet) {
        AttendanceSheet.ManualEntry -> ManualEntrySheet(ui, viewModel)
        AttendanceSheet.Overtime -> OvertimeSheet(ui, viewModel)
        else -> Unit
    }
}

private fun RequestKind.icon() = if (this == RequestKind.Manual) HeroIcons.PencilSquare else HeroIcons.Bolt

@Composable
private fun RequestCard(item: AttendanceRequestItem) {
    val colors = LocalWebColors.current
    val status = leaveStatusMeta(item.status)
    AttendanceCard(contentPadding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(38.dp).background(colors.primaryGlow, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(item.kind.icon(), null, Modifier.size(18.dp), tint = colors.primary)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.date?.let(::fmtDate) ?: "—", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem)
                if (item.detail.isNotBlank()) {
                    Text(item.detail, color = colors.textSecondary, fontSize = 0.8.rem, modifier = Modifier.padding(top = 2.dp))
                }
            }
            StatusPill(status.label, status.color, dot = true)
        }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill(item.typeLabel, colors.textSecondary, icon = item.kind.icon())
            if (item.isPending) {
                Text(
                    item.approverName?.takeIf(String::isNotBlank)?.let { "Awaiting approval by $it" } ?: "Awaiting approval",
                    color = colors.textMuted,
                    fontSize = 0.75.rem,
                )
            }
            item.approverName?.takeIf { it.isNotBlank() && !item.isPending }?.let {
                Text("by $it", color = colors.textMuted, fontSize = 0.75.rem)
            }
        }
        item.reason?.takeIf(String::isNotBlank)?.let {
            Text("“$it”", color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(top = 6.dp))
        }
        item.rejectReason?.takeIf(String::isNotBlank)?.let {
            Text("Rejected: $it", color = colors.danger, fontSize = 0.8.rem, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Requests FAB: "New request" with a menu for the two request types. */
@Composable
fun RequestsFab(viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    var menu by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = { menu = true },
            icon = { Icon(HeroIcons.Plus, null, Modifier.size(18.dp)) },
            text = { Text("New request", fontWeight = FontWeight.SemiBold) },
            containerColor = colors.primary,
            contentColor = colors.onAccent,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.bgElevated) {
            DropdownMenuItem(
                text = { Column { Text("Manual entry", color = colors.text); Text("Add or fix clock times", color = colors.textMuted, fontSize = 0.75.rem) } },
                leadingIcon = { Icon(HeroIcons.PencilSquare, null, tint = colors.primary) },
                onClick = { menu = false; viewModel.openSheet(AttendanceSheet.ManualEntry) },
            )
            DropdownMenuItem(
                text = { Column { Text("Overtime", color = colors.text); Text("Request extra hours", color = colors.textMuted, fontSize = 0.75.rem) } },
                leadingIcon = { Icon(HeroIcons.Bolt, null, tint = colors.warning) },
                onClick = { menu = false; viewModel.openSheet(AttendanceSheet.Overtime) },
            )
        }
    }
}

/** Manual entry form: date check, work mode, in/out (or still working), breaks. */
@Composable
private fun ManualEntrySheet(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    FormSheet(if (ui.manualEditMode) "Request correction" else "Manual time entry", onDismiss = viewModel::closeSheet) {
        DateField("Date", ui.manualDate, { viewModel.updateManualForm(date = it) }, allowFuture = false)
        AnimatedVisibility(ui.checkingManualDate) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = colors.primary, strokeWidth = 2.dp)
                Text("  Checking this date…", color = colors.textMuted, fontSize = 0.8.rem)
            }
        }
        ui.manualLeaveConflict?.let {
            InfoNotice("You have ${it.leaveType.replace('_', ' ')} leave on this date. Withdraw it before adding time.", colors.danger, HeroIcons.NoSymbol)
        }
        if (ui.manualLiveSession) {
            InfoNotice("You're clocked in right now. Clock out before editing today.", colors.warning, HeroIcons.ExclamationCircle)
        }
        manualEntryNote(ui.manualEditMode)?.let {
            InfoNotice(it, colors.primary, HeroIcons.InformationCircle)
        }
        if (ui.manualLeaveConflict == null && !ui.manualLiveSession) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel("Work mode")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    WorkMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = ui.manualMode == mode,
                            onClick = { viewModel.updateManualForm(mode = mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, WorkMode.entries.size),
                            colors = segmentedColors(),
                            icon = {},
                        ) { Text(mode.name, maxLines = 1) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeField("Clock in", ui.manualClockIn, { viewModel.updateManualForm(clockIn = it) }, Modifier.weight(1f))
                if (ui.manualSkipClockOut) {
                    PickerField("Clock out", "Still working", HeroIcons.Clock, { viewModel.updateManualForm(skipClockOut = false) }, Modifier.weight(1f))
                } else {
                    TimeField("Clock out", ui.manualClockOut, { viewModel.updateManualForm(clockOut = it) }, Modifier.weight(1f))
                }
            }
            SwitchRow("No clock-out (still working)", ui.manualSkipClockOut) { viewModel.updateManualForm(skipClockOut = it) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel("Breaks${if (ui.manualBreaks.isNotEmpty()) " (${ui.manualBreaks.size})" else ""}", Modifier.weight(1f))
                    TextButton(onClick = viewModel::addManualBreak, enabled = ui.manualBreaks.size < 20) {
                        Icon(HeroIcons.Plus, null, Modifier.size(14.dp))
                        Text(" Add break", fontWeight = FontWeight.SemiBold)
                    }
                }
                ui.manualBreaks.forEachIndexed { index, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeField("Start", item.start, { viewModel.updateManualBreak(index, start = it) }, Modifier.weight(1f))
                        TimeField("End", item.end, { viewModel.updateManualBreak(index, end = it) }, Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeManualBreak(index) }) {
                            Icon(HeroIcons.XMark, "Remove break ${index + 1}", Modifier.size(18.dp), tint = colors.textMuted)
                        }
                    }
                }
            }

            ui.error?.let { ErrorNotice(it) }
            SubmitButton(
                label = when {
                    ui.loading -> "Submitting…"
                    ui.manualEditMode -> "Submit correction"
                    else -> "Submit entry"
                },
                busy = ui.loading,
                onClick = viewModel::submitManualEntry,
            )
            Text("Manual entries and corrections are applied once approved.", color = colors.textMuted, fontSize = 0.75.rem)
        }
    }
}

/** Overtime request form: date, extra hours, reason. */
@Composable
private fun OvertimeSheet(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    FormSheet("Request overtime", onDismiss = viewModel::closeSheet) {
        DateField("Date", ui.overtimeDate, { viewModel.updateOvertimeForm(date = it) })
        TextInput(
            "Extra hours",
            ui.overtimeHours,
            { viewModel.updateOvertimeForm(hours = it) },
            placeholder = "e.g. 2",
            keyboardType = KeyboardType.Decimal,
            leadingIcon = HeroIcons.Clock,
        )
        TextInput(
            "Reason",
            ui.overtimeReason,
            { viewModel.updateOvertimeForm(reason = it) },
            placeholder = "What needed the extra time?",
            singleLine = false,
            minLines = 3,
        )
        ui.error?.let { ErrorNotice(it) }
        SubmitButton(if (ui.loading) "Submitting…" else "Submit request", ui.loading, viewModel::submitOvertime)
    }
}

@Composable
private fun SubmitButton(label: String, busy: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Button(
        onClick = onClick,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onAccent),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = colors.onAccent, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
