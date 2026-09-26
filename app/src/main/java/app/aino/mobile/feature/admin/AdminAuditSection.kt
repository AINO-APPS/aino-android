package app.aino.mobile.feature.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private fun humanize(key: String) = key.replace('_', ' ').replaceFirstChar(Char::uppercase)

/** `AuditLogs.tsx`: date presets, entity/action/actor filters, 50-row offset paging. */
@Composable
internal fun AdminAuditSection(state: AdminUiState, viewModel: AdminViewModel) {
    val colors = LocalWebColors.current
    val f = state.auditFilters
    var preset by rememberSaveable { mutableStateOf(if (f.from.isEmpty() && f.to.isEmpty()) "all" else "custom") }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    AdminChipRow(AUDIT_DATE_PRESETS.map { it.first to it.second } + ("custom" to "Custom"), preset) { key ->
        preset = key
        val days = AUDIT_DATE_PRESETS.firstOrNull { it.first == key }
        if (days != null) viewModel.setAuditFilters(f.copy(from = auditPresetFrom(days.third), to = ""))
        else showFilters = true
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        AdminButton(
            if (showFilters) "Hide filters" else "Filters" + if (f.activeCount > 0) " (${f.activeCount})" else "",
            { showFilters = !showFilters }, style = AdminButtonStyle.Secondary, small = true,
        )
        Spacer(Modifier.weight(1f))
        if (f.activeCount > 0) AdminButton("Clear", { preset = "all"; viewModel.setAuditFilters(AuditFilters()) }, style = AdminButtonStyle.Cancel, small = true)
    }
    if (showFilters) {
        AdminRowCard {
            AdminPicker("Entity", listOf("" to "All entities") + AdminCatalog.ENTITY_TYPES.map { it to humanize(it) }, f.entityType, { viewModel.setAuditFilters(f.copy(entityType = it)) })
            AdminPicker("Action", listOf("" to "All actions") + AdminCatalog.ACTIONS.map { it to humanize(it) }, f.action, { viewModel.setAuditFilters(f.copy(action = it)) })
            AdminPicker("Actor", listOf("" to "Anyone") + state.members.data.orEmpty().map { it.id.toString() to it.fullName }, f.actorId, { viewModel.setAuditFilters(f.copy(actorId = it)) })
            if (preset == "custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminDateField("From", f.from, { viewModel.setAuditFilters(f.copy(from = it)) }, Modifier.weight(1f))
                    AdminDateField("To", f.to, { viewModel.setAuditFilters(f.copy(to = it)) }, Modifier.weight(1f))
                }
            }
        }
    }
    AdminLoadState(state.audit) { page ->
        Text(auditRange(state.auditOffset, page.logs.size, page.total), color = colors.textSecondary, fontSize = 0.8.rem)
        if (page.logs.isEmpty()) AdminEmpty("No audit logs match these filters")
        page.logs.forEach { log -> AuditCard(log) }
        if (page.total > AUDIT_PAGE_SIZE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AdminButton("Previous", { viewModel.setAuditOffset((state.auditOffset - AUDIT_PAGE_SIZE).coerceAtLeast(0)) },
                    style = AdminButtonStyle.Secondary, enabled = state.auditOffset > 0, small = true)
                Spacer(Modifier.weight(1f))
                AdminButton("Next", { viewModel.setAuditOffset(state.auditOffset + AUDIT_PAGE_SIZE) },
                    style = AdminButtonStyle.Secondary, enabled = state.auditOffset + page.logs.size < page.total, small = true)
            }
        }
    }
}

@Composable
private fun AuditCard(log: AuditLog) {
    val colors = LocalWebColors.current
    AdminRowCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminPill(humanize(log.action), colors.primary)
            log.entityType?.let { Text(humanize(it) + (log.entityIdText?.let { id -> " #$id" } ?: ""), color = colors.textSecondary, fontSize = 0.78.rem) }
        }
        val actor = log.actorName ?: log.actorUsername ?: "System"
        val inspector = if (log.actorIsInspector == true) " (inspector${log.actorInspectorRealName?.let { ": $it" } ?: ""})" else ""
        Text(actor + inspector, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem)
        log.details?.takeIf { it.isNotBlank() }?.let { Text(it, color = colors.textSecondary, fontSize = 0.8.rem) }
        Text(shortDateTime(log.createdAt) + (log.ipAddress?.let { " · $it" } ?: ""), color = colors.textMuted, fontSize = 0.72.rem)
    }
}
