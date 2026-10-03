package app.aino.mobile.feature.attendance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.common.getLeaveType
import app.aino.mobile.core.designsystem.component.FirstLoadSpinner
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME = DateTimeFormatter.ofPattern("HH:mm")
private fun LocalTime?.label(): String = this?.format(TIME) ?: "—"

/**
 * Overview day sheet: status, clock in/out, breaks, worked hours, leave or
 * holiday info and a "Request correction" shortcut into the manual-entry form.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DayDetailSheet(ui: AttendanceUiState, viewModel: AttendanceViewModel) {
    val detail = ui.dayDetail ?: return
    val colors = LocalWebColors.current
    val date = detail.date
    val today = LocalDate.now()
    val future = date > today
    val kind = calendarKind(ui, date, today)
    val day = ui.history[date]
    val leave = ui.leaves[date]
    val holiday = ui.holidays[date]
    val timeline = dayTimeline(detail.entries)

    ModalBottomSheet(
        onDismissRequest = viewModel::closeDayDetail,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
        contentColor = colors.text,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.US)),
                    color = colors.text,
                    fontWeight = FontWeight.Bold,
                    fontSize = 1.15.rem,
                    modifier = Modifier.semantics { heading() },
                )
                FlowRow(
                    Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    kind?.let { StatusPill(kindLabel(it), kindColor(it) ?: colors.primary, dot = true) }
                    timeline.workMode?.let {
                        StatusPill(
                            it.replaceFirstChar(Char::uppercase),
                            colors.textSecondary,
                            icon = if (it == "remote") HeroIcons.Home else HeroIcons.BuildingOffice2,
                        )
                    }
                    if (timeline.hasManual) StatusPill("Manual entry", colors.primary, icon = HeroIcons.PencilSquare)
                    if (timeline.pendingApproval) StatusPill("Pending approval", SwatchLeave)
                }
            }

            holiday?.let {
                InfoNotice(
                    "Holiday · ${it.name}" + if (it.isOptional) " (optional)" else "",
                    SwatchHoliday,
                    HeroIcons.Sun,
                )
            }
            leave?.let {
                val type = buildLeaveTypeMeta(ui.leavePolicies)[it.leaveType] ?: getLeaveType(it.leaveType)
                InfoNotice(
                    "${type.label} · ${durationLabel(it.duration)} · ${leaveStatusMeta(it.status).label}",
                    leaveStatusMeta(it.status).color,
                    type.icon,
                )
            }

            when {
                future -> Text("This day hasn't happened yet.", color = colors.textMuted, fontSize = 0.85.rem)
                detail.loading && detail.entries.isEmpty() -> FirstLoadSpinner(Modifier.height(96.dp))
                detail.error != null -> ErrorNotice(detail.error, onRetry = { viewModel.openDayDetail(date) })
                timeline.isEmpty -> EmptyState(HeroIcons.Clock, "No time recorded", "Missed a clock-in? Request a correction below.")
                else -> {
                    val stillWorking = timeline.stillOpen && date == today
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailTile("Clock in", timeline.clockIn.label(), HeroIcons.Play, colors.success, Modifier.weight(1f))
                        DetailTile(
                            "Clock out",
                            if (stillWorking) "Working" else timeline.clockOut.label(),
                            HeroIcons.ArrowRightStartOnRectangle,
                            colors.danger,
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailTile("Worked", day?.let { hoursLabel(it.floorMinutes) } ?: "—", HeroIcons.Clock, colors.primary, Modifier.weight(1f))
                        DetailTile("Break", day?.let { hoursLabel(it.breakMinutes) } ?: "—", HeroIcons.Cup, colors.warning, Modifier.weight(1f))
                    }
                    if (timeline.sessions > 1) {
                        Text("${timeline.sessions} sessions this day", color = colors.textMuted, fontSize = 0.78.rem)
                    }
                    if (timeline.breaks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            FieldLabel("Breaks")
                            timeline.breaks.forEachIndexed { index, (start, end) ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(colors.surface, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(HeroIcons.Cup, null, Modifier.size(14.dp), tint = colors.warning)
                                    Text("  Break ${index + 1}", color = colors.textSecondary, fontSize = 0.82.rem, modifier = Modifier.weight(1f))
                                    Text(
                                        "${start.label()} – ${end?.label() ?: "ongoing"}",
                                        color = colors.text,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 0.82.rem,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = { viewModel.requestCorrection(date) },
                enabled = !future,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (future) colors.border else colors.primary),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary, disabledContentColor = colors.textMuted),
            ) {
                Icon(HeroIcons.PencilSquare, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Request correction", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DetailTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, modifier: Modifier) {
    val colors = LocalWebColors.current
    Row(
        modifier
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = accent)
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, color = colors.textMuted, fontSize = 0.72.rem)
            Text(value, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 0.95.rem)
        }
    }
}
