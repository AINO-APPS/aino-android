package app.aino.mobile.feature.manager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** `approval_requests.type` as shown in the detail sheet; an edit of a recorded day reads "Correction". */
internal fun requestTypeLabel(type: String?, edit: Boolean = false): String = when (type) {
    "leave" -> "Leave"
    "leave_withdraw" -> "Leave withdrawal"
    "manual_entry" -> if (edit) "Correction" else "Manual entry"
    "overtime" -> "Overtime"
    null, "" -> "Request"
    else -> humanize(type)
}

private fun humanize(value: String): String = value.replace('_', ' ').replaceFirstChar { it.uppercase() }

private val DATE_TIME_FMT = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** Server timestamps (ISO instant, offset, or zone-less UTC) in the device zone; unparsable text is shown as-is. */
internal fun formatRequestTimestamp(value: String?, zone: ZoneId = ZoneId.systemDefault()): String? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val instant = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) }.getOrNull()
        ?: return text
    return instant.atZone(zone).format(DATE_TIME_FMT)
}

/**
 * Every populated field of a request, in reading order: what was asked for
 * (type-specific `metadata`), why, and what happened to it.
 */
internal fun requestDetailFields(row: ApprovalRow, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> = buildList {
    fun add(label: String, value: String?) { value?.trim()?.takeIf(String::isNotEmpty)?.let { add(label to it) } }
    val meta = row.metadata
    add("Type", requestTypeLabel(row.type))
    add("Status", row.status?.let(::humanize))
    if (meta != null) {
        add("Leave type", meta.leaveType?.let(::humanize))
        add("Date", meta.date)
        add("Duration", meta.duration?.let(::humanize))
        add("Previous status", meta.previousStatus?.let(::humanize))
        add("Clock in", meta.clockIn)
        if (row.type == "manual_entry") add("Clock out", meta.clockOut ?: "Not set")
        add("Work mode", meta.workMode?.let(::humanize))
        add("Hours", meta.hours?.let { "${it}h" })
        if (meta.edit == true) add("Change", "Edit of an existing day")
        meta.breaks?.filter { !it.start.isNullOrBlank() }?.takeIf { it.isNotEmpty() }?.let { breaks ->
            add("Breaks", breaks.joinToString("\n") { "${it.start} – ${it.end ?: "…"}" })
        }
    }
    add("Reason", row.reason)
    add("Submitted", formatRequestTimestamp(row.createdAt, zone))
    add("Approver", row.approverName)
    add("Reviewed", formatRequestTimestamp(row.reviewedAt, zone))
    add("Rejection reason", row.rejectReason)
}

/** The request detail sheet: all fields, plus Approve / Reject while the request is pending in the manager's queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RequestDetailSheet(detail: RequestDetail, ui: ManagerUiState, viewModel: ManagerViewModel) {
    val colors = LocalWebColors.current
    ModalBottomSheet(
        onDismissRequest = viewModel::closeRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val row = detail.row
            when {
                row == null && detail.loading -> ManagerLoading()
                row == null -> {
                    Text("Request #${detail.id}", color = colors.text, fontSize = 1.05.rem, fontWeight = FontWeight.SemiBold)
                    detail.error?.let { ManagerErrorText(it) }
                        ?: ManagerEmpty("This request is no longer available. It may have been withdrawn or moved to another approver.")
                }
                else -> RequestDetailBody(detail, row, ui, viewModel)
            }
        }
    }
}

@Composable
private fun RequestDetailBody(detail: RequestDetail, row: ApprovalRow, ui: ManagerUiState, viewModel: ManagerViewModel) {
    val colors = LocalWebColors.current
    val busy = ui.busy
    val mine = detail.source == RequestSource.Mine || isOwnRequest(row, ui.userId)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (!mine) {
            UserAvatar(row.requesterName, row.requesterAvatar, 44.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                if (mine) "Your request" else row.requesterName ?: "Team member",
                color = colors.text,
                fontSize = 1.05.rem,
                fontWeight = FontWeight.SemiBold,
            )
            Text(requestTypeLabel(row.type, row.metadata?.edit == true), color = colors.textSecondary, fontSize = 0.8.rem)
        }
        ApprovalBadge(row.status)
    }

    if (!mine && row.status != null && row.status != "pending") {
        val tint = if (row.status == "approved") colors.success else if (row.status == "rejected") colors.danger else colors.textSecondary
        Text(
            "This request was already handled — ${humanize(row.status)}",
            color = tint,
            fontSize = 0.85.rem,
            modifier = Modifier
                .fillMaxWidth()
                .background(tint.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                .border(1.dp, tint.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
    detail.error?.let { ManagerErrorText(it) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        requestDetailFields(row).forEach { (label, value) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
                Text(label, color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.width(118.dp))
                Text(value, color = colors.text, fontSize = 0.85.rem, modifier = Modifier.weight(1f))
            }
        }
    }

    if (ui.detailActionable(detail)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetActionButton("Reject", colors.danger, enabled = !busy, modifier = Modifier.weight(1f)) { viewModel.openReject(row.id) }
            SheetActionButton(if (busy) "Working…" else "Approve", colors.success, enabled = !busy, modifier = Modifier.weight(1f)) {
                viewModel.approve(row.id)
            }
        }
    } else {
        awaitingApprovalLabel(row, ui.role, ui.userId, detail.source)?.let {
            Text(it, color = colors.textSecondary, fontSize = 0.85.rem, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun SheetActionButton(label: String, tint: Color, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .background(tint.copy(alpha = if (enabled) 0.15f else 0.07f), RoundedCornerShape(10.dp))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 0.9.rem, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}
