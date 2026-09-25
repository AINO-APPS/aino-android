package app.aino.mobile.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/**
 * Manual Entry tab (P3.4) — port of `pages/ManualEntry.tsx`: date check
 * (existing entries / leave conflict / live session), the entry form with
 * breaks, pending-requests list, and the overtime request section.
 */
@Composable
fun ManualEntryTab(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column {
            Text("Manual Time Entry", color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.25.rem)
            Text(
                "Add or edit attendance records for a specific date — changes may require approval",
                color = colors.textMuted,
                fontSize = 0.85.rem,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        AttendanceCard {
            Text(
                if (ui.manualEditMode) "Edit Manual Entry" else "Add Manual Entry",
                color = colors.text,
                fontWeight = FontWeight.Bold,
                fontSize = 1.05.rem,
            )
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    WebFieldLabel("Date")
                    WebDateField(
                        ui.manualDate,
                        { viewModel.updateManualForm(date = it) },
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    if (ui.checkingManualDate) {
                        Text("Checking date…", color = colors.textMuted, fontSize = 0.78.rem, modifier = Modifier.padding(top = 6.dp))
                    }
                }

                ManualDateBanners(ui)
                if (ui.manualLeaveConflict == null && !ui.manualLiveSession) {
                    ManualFormFields(ui, viewModel)
                }
            }
        }

        AttendanceCard {
            CardTitle(Icons.Outlined.Assignment, "Your Requests")
            Column(Modifier.padding(top = 12.dp)) {
                PendingRequestsList(
                    requests = ui.manualRequests.map {
                        PendingRequestUi(
                            id = it.requestId,
                            status = it.approvalStatus,
                            date = it.metadata?.date,
                            timeText = it.metadata?.let { m -> "${m.clockIn.orEmpty()}${m.clockOut?.let { c -> " → $c" }.orEmpty()}" }.orEmpty(),
                            rejectReason = it.rejectReason,
                        )
                    },
                    rejectLabel = "Reason: ",
                    emptyText = "No requests yet.",
                )
            }
        }

        OvertimeSection(ui, viewModel)
    }
}

/** Banners for the checked date: leave conflict, live session, edit hint. */
@Composable
private fun ManualDateBanners(ui: AttendanceUiState) {
    val colors = LocalWebColors.current
    ui.manualLeaveConflict?.let { leave ->
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0x1AEF4444), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x40EF4444), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Block, null, Modifier.size(16.dp), tint = Color(0xFFEF4444))
            Text(
                "A ${leave.leaveType} leave exists on this date. Withdraw it before adding a manual entry.",
                color = Color(0xFFEF4444),
                fontSize = 0.82.rem,
            )
        }
    }
    if (ui.manualLiveSession) {
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0x1ACB912F), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x40CB912F), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(16.dp), tint = colors.warning)
            Text(
                "You are clocked in right now. Clock out before editing today's entries.",
                color = colors.warning,
                fontSize = 0.82.rem,
            )
        }
    }
    if (ui.manualEditMode) {
        Row(
            Modifier.fillMaxWidth()
                .background(colors.primaryGlow, RoundedCornerShape(8.dp))
                .border(1.dp, colors.primary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = colors.primary)
            Text(
                "Existing entries found — you are editing this day.",
                color = colors.primary,
                fontSize = 0.82.rem,
            )
        }
    }
}

/** The manual-entry form body: mode chips, login/logout times, breaks, submit. */
@Composable
private fun ManualFormFields(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current

    Column {
        WebFieldLabel("Work Mode")
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkMode.entries.forEach { mode ->
                val active = ui.manualMode == mode
                Row(
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (active) colors.primaryGlow else colors.surface)
                        .border(1.dp, if (active) colors.primary else colors.border, RoundedCornerShape(8.dp))
                        .clickable { viewModel.updateManualForm(mode = mode) }
                        .padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (mode == WorkMode.Remote) Icons.Outlined.Home else Icons.Outlined.Apartment,
                        null,
                        Modifier.size(14.dp),
                        tint = if (active) colors.primary else colors.textSecondary,
                    )
                    Text(
                        "  " + mode.name.lowercase().replaceFirstChar(Char::uppercase),
                        color = if (active) colors.primary else colors.textSecondary,
                        fontSize = 0.8.rem,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            WebFieldLabel("Login Time")
            WebTimeField(ui.manualClockIn, { viewModel.updateManualForm(clockIn = it) }, Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        Column(Modifier.weight(1f)) {
            WebFieldLabel("Logout Time")
            if (ui.manualSkipClockOut) {
                Box(
                    Modifier.fillMaxWidth().padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text("Skipped", color = colors.textMuted, fontSize = 0.85.rem)
                }
            } else {
                WebTimeField(ui.manualClockOut, { viewModel.updateManualForm(clockOut = it) }, Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }

    Row(
        Modifier.clickable { viewModel.updateManualForm(skipClockOut = !ui.manualSkipClockOut) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Checkbox(
            checked = ui.manualSkipClockOut,
            onCheckedChange = { viewModel.updateManualForm(skipClockOut = it) },
            colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = colors.primary),
        )
        Text("No clock-out (still working)", color = colors.text, fontSize = 0.85.rem)
    }

    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            WebFieldLabel("Breaks")
            Spacer(Modifier.weight(1f))
            Text(
                "+ Add Break",
                color = colors.primary,
                fontSize = 0.8.rem,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = viewModel::addManualBreak).padding(4.dp),
            )
        }
        ui.manualBreaks.forEachIndexed { index, item ->
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WebTimeField(item.start, { viewModel.updateManualBreak(index, start = it) }, Modifier.weight(1f), "Start")
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(12.dp), tint = colors.textMuted)
                WebTimeField(item.end, { viewModel.updateManualBreak(index, end = it) }, Modifier.weight(1f), "End")
                Icon(
                    Icons.Outlined.Close,
                    "Remove break",
                    Modifier.size(16.dp).clickable { viewModel.removeManualBreak(index) },
                    tint = colors.textMuted,
                )
            }
        }
    }

    WebPrimaryButton(
        when {
            ui.loading -> if (ui.manualEditMode) "Updating..." else "Saving..."
            ui.manualEditMode -> "✓ Update Entry"
            else -> "✓ Save Manual Entry"
        },
        enabled = !ui.loading,
        onClick = viewModel::submitManualEntry,
    )
}

/** Flat row model for the shared `PendingRequestsList` port. */
data class PendingRequestUi(
    val id: Long,
    val status: String,
    val date: String?,
    val timeText: String,
    val reason: String? = null,
    val rejectReason: String? = null,
)

/** `PendingRequestsList` port: date, time line, status badge, reasons. */
@Composable
private fun PendingRequestsList(
    requests: List<PendingRequestUi>,
    rejectLabel: String = "Rejected: ",
    emptyText: String = "No requests yet.",
) {
    val colors = LocalWebColors.current
    if (requests.isEmpty()) {
        Text(emptyText, color = colors.textMuted, fontSize = 0.85.rem)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        requests.forEach { request ->
            val status = leaveStatusMeta(request.status)
            Column(
                Modifier.fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(request.date?.let(::fmtDate) ?: "—", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.85.rem)
                        if (request.timeText.isNotBlank()) {
                            Text(request.timeText, color = colors.textSecondary, fontSize = 0.78.rem, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    Text(
                        status.label,
                        color = status.color,
                        fontSize = 0.72.rem,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(status.bg, RoundedCornerShape(20.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                request.reason?.takeIf(String::isNotBlank)?.let {
                    Text(it, color = colors.textMuted, fontSize = 0.78.rem, modifier = Modifier.padding(top = 4.dp))
                }
                request.rejectReason?.takeIf(String::isNotBlank)?.let {
                    Text("$rejectLabel$it", color = Color(0xFFEF4444), fontSize = 0.78.rem, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** Overtime section (`OvertimeRequestForm` + its requests list). */
@Composable
private fun OvertimeSection(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val colors = LocalWebColors.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Timer, null, Modifier.size(22.dp), tint = colors.text)
            Text("Overtime Request", color = colors.text, fontWeight = FontWeight.ExtraBold, fontSize = 1.1.rem)
        }

        AttendanceCard {
            Text("➕ Request Overtime", color = colors.text, fontWeight = FontWeight.Bold, fontSize = 1.05.rem)
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column {
                    WebFieldLabel("Date")
                    WebDateField(ui.overtimeDate, { viewModel.updateOvertimeForm(date = it) }, Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Column {
                    WebFieldLabel("Extra Hours")
                    WebTextInput(
                        ui.overtimeHours,
                        { viewModel.updateOvertimeForm(hours = it) },
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        placeholder = "e.g. 2",
                        keyboardType = KeyboardType.Decimal,
                    )
                }
                Column {
                    WebFieldLabel("Reason")
                    WebTextInput(
                        ui.overtimeReason,
                        { viewModel.updateOvertimeForm(reason = it) },
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        placeholder = "Why do you need overtime?",
                        singleLine = false,
                        minLines = 3,
                    )
                }
                WebPrimaryButton(
                    if (ui.loading) "Submitting..." else "✓ Submit Overtime Request",
                    enabled = !ui.loading,
                    onClick = viewModel::submitOvertime,
                )
            }
        }

        AttendanceCard {
            CardTitle(Icons.Outlined.Assignment, "Overtime Requests")
            Column(Modifier.padding(top = 12.dp)) {
                PendingRequestsList(
                    requests = ui.overtimeRequests.map {
                        PendingRequestUi(
                            id = it.id,
                            status = it.status,
                            date = it.metadata?.date,
                            timeText = it.metadata?.hours?.let { h -> "${formatDays(h)}h overtime" }.orEmpty(),
                            reason = it.reason,
                            rejectReason = it.rejectReason,
                        )
                    },
                    emptyText = "No overtime requests yet.",
                )
            }
        }
    }
}
