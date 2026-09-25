package app.aino.mobile.feature.home.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.component.WebCard
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.feature.home.Approval

private fun formatType(type: String): String = when (type) {
    "leave" -> "Leave"
    "manual_entry" -> "Manual Entry"
    "overtime" -> "Overtime"
    "leave_withdraw" -> "Leave Withdraw"
    else -> type.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

/** `PendingApprovalsCard` (P2.7): manager-gated approve/reject list. */
@Composable
fun PendingApprovalsCard(approvals: List<Approval>, onApprove: (Long) -> Unit, onReject: (Long) -> Unit) {
    val colors = LocalWebColors.current
    if (approvals.isEmpty()) return

    WebCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Assignment, null, Modifier.size(18.dp), tint = colors.warning)
            Text(" Pending Approvals", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(" ${approvals.size}", color = colors.textMuted, fontSize = 13.sp)
        }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            approvals.forEach { approval ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(approval.requesterName ?: approval.requesterUsername ?: "Unknown", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(formatType(approval.type), color = colors.textSecondary, fontSize = 12.sp)
                    }
                    IconButton(onClick = { onApprove(approval.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Check, "Approve", tint = colors.success, modifier = Modifier.size(18.dp))
                    }
                    IconButton(onClick = { onReject(approval.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, "Reject", tint = colors.danger, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}