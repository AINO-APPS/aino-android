package app.aino.mobile.feature.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** `MyRequests.tsx`; a row opens its detail sheet. */
@Composable
internal fun MyRequestsTab(ui: ManagerUiState, viewModel: ManagerViewModel) {
    val colors = LocalWebColors.current
    val section = ui.myRequests
    val rows = section.data.orEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your Submitted Requests", color = colors.text, fontSize = 0.95.rem, fontWeight = FontWeight.SemiBold)
        section.error?.let { ManagerErrorText(it) }
        when {
            section.initialLoading -> ManagerLoading()
            rows.isEmpty() -> ManagerEmpty("No requests submitted")
            else -> rows.forEach { row -> MyRequestRowCard(row) { viewModel.openRequest(row, RequestSource.Mine) } }
        }
    }
}

@Composable
private fun MyRequestRowCard(row: ApprovalRow, onOpen: () -> Unit) {
    val colors = LocalWebColors.current
    ManagerRowCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(requestTypeLabel(row.type, row.metadata?.edit == true), color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            ApprovalBadge(row.status)
        }
        RequestDetails(row)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(formatApprovalDate(row.createdAt), color = colors.textMuted, fontSize = 0.72.rem)
            Text(
                if (row.status == "pending") row.approverName?.let { "Awaiting $it" } ?: "Awaiting approval" else row.approverName ?: "—",
                color = colors.textSecondary,
                fontSize = 0.75.rem,
            )
        }
    }
}
