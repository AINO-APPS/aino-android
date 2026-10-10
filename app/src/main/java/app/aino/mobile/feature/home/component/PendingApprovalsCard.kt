package app.aino.mobile.feature.home.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.Approval
import app.aino.mobile.core.designsystem.icons.HeroIcons

private fun formatType(type: String): String = when (type) {
    "leave" -> "Leave"
    "manual_entry" -> "Manual Entry"
    "overtime" -> "Overtime"
    "leave_withdraw" -> "Leave Withdraw"
    else -> type.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/** Rows previewed on the dashboard; the rest are behind "View all" (web `PendingApprovalsCard`). */
internal const val PENDING_APPROVALS_PREVIEW = 3

/**
 * `PendingApprovalsCard` (P2.7): manager-gated approve/reject list. Like web,
 * the card opens the approvals queue and a row opens that request; the
 * approve / reject buttons keep their own taps.
 */
@Composable
fun PendingApprovalsCard(
    approvals: List<Approval>,
    onApprove: (Long) -> Unit,
    onReject: (Long) -> Unit,
    onOpenAll: () -> Unit,
    onOpenRequest: (Long) -> Unit,
) {
    val colors = LocalWebColors.current
    if (approvals.isEmpty()) return

    WebCard(Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = "Open approvals", onClick = onOpenAll)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(HeroIcons.ClipboardDocumentList, null, Modifier.size(18.dp), tint = colors.warning)
            Text(" Pending Approvals", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(" ${approvals.size}", color = colors.textMuted, fontSize = 13.sp)
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            approvals.take(PENDING_APPROVALS_PREVIEW).forEach { approval ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Open request") { onOpenRequest(approval.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(approval.requesterName ?: approval.requesterUsername ?: "Unknown", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(formatType(approval.type), color = colors.textSecondary, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onApprove(approval.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(HeroIcons.Check, "Approve", tint = colors.success, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onReject(approval.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(HeroIcons.XMark, "Reject", tint = colors.danger, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (approvals.size > PENDING_APPROVALS_PREVIEW) {
            Text(
                "View all \u2192",
                color = colors.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
