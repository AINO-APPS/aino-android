package app.aino.mobile.feature.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun typeIcon(value: String): ImageVector = when (value) {
    "bug" -> Icons.Outlined.BugReport
    "feature_request" -> Icons.Outlined.AutoAwesome
    "access_issue" -> Icons.Outlined.Shield
    else -> Icons.Outlined.HelpOutline
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)

/** `new Date(x).toLocaleDateString()` (en-US). */
private fun localeDate(value: String?): String = value?.let {
    runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().format(SHORT_DATE) }.getOrNull()
        ?: localDateOf(it)?.format(SHORT_DATE)
}.orEmpty()

/** `pages/tasks/ServiceDeskTab.tsx`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceDeskTab(viewModel: TaskViewModel, @Suppress("UNUSED_PARAMETER") role: String) {
    val repo = viewModel.serviceDesk ?: return
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var filterStatus by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf("") }
    var tickets by remember { mutableStateOf<List<ServiceTicket>>(emptyList()) }
    var stats by remember { mutableStateOf<ServiceDeskStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var formOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var deletingId by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<ServiceTicket?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("bug") }
    var priority by remember { mutableStateOf("medium") }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(filterStatus, filterType, reloadKey, ui.serviceDeskVersion) {
        withContext(Dispatchers.IO) {
            runCatching { repo.tickets(filterStatus, filterType) }
                .onSuccess { tickets = it; loadFailed = false }
                .onFailure { loadFailed = true }
            runCatching { repo.stats() }.onSuccess { stats = it }
        }
        loading = false
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val shownError = error.ifEmpty { if (loadFailed) "Failed to load tickets" else "" }
        if (shownError.isNotEmpty()) ErrorMsg(shownError)
        stats?.let { s ->
            FlowRow(
                Modifier.fillMaxWidth().background(colors.glass, RoundedCornerShape(6.dp)).border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 9.6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.6.dp),
                verticalArrangement = Arrangement.spacedBy(5.6.dp),
            ) {
                StatChip("${s.total}", "Total", colors.primary, filterStatus.isEmpty()) { filterStatus = "" }
                visibleStatusChips(s).forEach { st ->
                    StatChip("${s.count(st.value)}", st.label, Color(st.color), filterStatus == st.value) {
                        filterStatus = if (filterStatus == st.value) "" else st.value
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            WebSelect(listOf("" to "All Types") + TICKET_TYPES.map { it.value to it.label }, filterType, { filterType = it }, Modifier.fillMaxWidth())
            WebButton(
                if (formOpen) "Cancel" else "New Ticket", { formOpen = !formOpen }, Modifier.fillMaxWidth(),
                style = BtnStyle.Primary, icon = if (formOpen) Icons.Outlined.Close else Icons.Outlined.Add,
            )
        }
        if (formOpen) {
            GlassPanel(padding = 24.dp) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(16.dp), tint = colors.text)
                    Spacer(Modifier.width(5.dp))
                    Text("New Service Desk Ticket", color = colors.text, fontSize = 1.rem, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.Close, "Close", Modifier.size(14.dp).clickable { formOpen = false }, tint = colors.textMuted)
                }
                WebTextField(title, { title = it }, "Ticket title...", maxLength = 200)
                Spacer(Modifier.height(20.dp))
                WebTextField(description, { description = it }, "Provide details: steps to reproduce (for bugs), expected behavior, etc.", singleLine = false, minLines = 3)
                Spacer(Modifier.height(16.dp))
                FieldLabel("Type", Icons.Outlined.LocalOffer)
                WebSelect(TICKET_TYPES.map { it.value to it.label }, type, { type = it }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.4.dp)) {
                    TICKET_PRIORITIES.forEach { p ->
                        val active = priority == p.value
                        val tint = Color(p.color)
                        Text(
                            p.label,
                            color = if (active) colors.text else colors.textSecondary,
                            fontSize = 0.8.rem,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) tint.copy(alpha = 0.15f) else colors.surface)
                                .border(1.dp, if (active) tint else colors.border, RoundedCornerShape(8.dp))
                                .clickable { priority = p.value }
                                .padding(6.4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                WebButton(
                    if (submitting) "Submitting..." else "Create Ticket",
                    {
                        if (title.isBlank()) return@WebButton
                        submitting = true
                        error = ""
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching { repo.create(CreateTicketPayload(title.trim(), plainTextToHtml(description.trim()), type, priority)) }
                            }
                            result.onSuccess {
                                title = ""; description = ""; type = "bug"; priority = "medium"
                                formOpen = false
                                reloadKey++
                            }.onFailure { error = it.message ?: "Failed to submit ticket" }
                            submitting = false
                        }
                    },
                    Modifier.fillMaxWidth(), style = BtnStyle.Primary, enabled = !submitting && title.isNotBlank(),
                )
            }
        }
        when {
            loading -> WebSpinner()
            tickets.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🎫", fontSize = 2.5.rem)
                Spacer(Modifier.height(12.dp))
                Text("No tickets found", color = colors.text, fontSize = 1.rem, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.6.dp))
                Text("Submit a ticket to report bugs, request features, or get help with access issues.", color = colors.textMuted, fontSize = 0.85.rem, textAlign = TextAlign.Center)
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tickets.forEach { ticket ->
                    val t = ticketType(ticket.ticketType)
                    val p = ticketPriority(ticket.priority)
                    val st = ticketStatus(ticket.status)
                    val open = expanded == ticket.id
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(colors.glass).border(1.dp, colors.glassBorder, RoundedCornerShape(6.dp))) {
                        Column(
                            Modifier.fillMaxWidth().clickable { expanded = if (open) null else ticket.id }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val tint = Color(t.color)
                                Row(
                                    Modifier.background(tint.copy(alpha = 0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 8.8.dp, vertical = 3.2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(typeIcon(t.value), null, Modifier.size(14.dp), tint = tint)
                                    Spacer(Modifier.width(4.8.dp))
                                    Text(t.label, color = tint, fontSize = 0.72.rem, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                }
                                Spacer(Modifier.width(10.4.dp))
                                Text(ticket.title, color = colors.text, fontSize = 0.82.rem, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(p.label, color = Color(p.color), fontSize = 0.75.rem, fontWeight = FontWeight.Bold)
                                val sc = Color(st.color)
                                Text(st.label, color = sc, fontSize = 0.7.rem, fontWeight = FontWeight.SemiBold, modifier = Modifier.background(sc.copy(alpha = 0.15f), RoundedCornerShape(99.dp)).padding(horizontal = 8.8.dp, vertical = 3.2.dp))
                                Text(localeDate(ticket.createdAt), color = colors.textMuted, fontSize = 0.72.rem)
                                if (canDeleteTicket(ticket, ui.userId)) {
                                    Icon(
                                        Icons.Outlined.Delete, "Cancel this ticket",
                                        Modifier.size(16.dp).clickable(enabled = deletingId != ticket.id) { confirmDelete = ticket },
                                        tint = colors.textMuted,
                                    )
                                }
                                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(16.dp), tint = colors.text)
                            }
                        }
                        if (open) {
                            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ticket.description?.takeIf(String::isNotBlank)?.let { TaskHtml(it, colors.textSecondary, 0.88.rem) }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MetaText("Submitted by: ", ticket.submittedByName.orEmpty())
                                    ticket.tenantName?.let { MetaText("Organization: ", it) }
                                    ticket.assignedTo?.let { MetaText("Assigned to: ", it) }
                                    ticket.resolvedAt?.let { Text("Resolved: ${localeDate(it)}", color = colors.textMuted, fontSize = 0.8.rem) }
                                }
                                ticket.adminNotes?.takeIf(String::isNotBlank)?.let { notes ->
                                    Row(Modifier.fillMaxWidth().background(colors.surface, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                        Text("Admin Notes: ", color = colors.text, fontSize = 0.82.rem, fontWeight = FontWeight.SemiBold)
                                        Text(notes, color = colors.textSecondary, fontSize = 0.82.rem)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    confirmDelete?.let { ticket ->
        TaskConfirmDialog(
            ConfirmRequest(if (canDeleteTicket(ticket, ui.userId)) "Cancel Ticket" else "Delete Ticket", deleteTicketMessage(ticket, ui.userId), "OK", danger = true) {},
            onConfirm = {
                confirmDelete = null
                deletingId = ticket.id
                error = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { repo.delete(ticket.id) } }
                    result.onSuccess { reloadKey++ }.onFailure { error = it.message ?: "Failed to delete ticket" }
                    deletingId = null
                }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun StatChip(value: String, label: String, accent: Color, active: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) accent.copy(alpha = 0.12f) else colors.surface)
            .border(1.dp, if (active) accent else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold)
        Text(label.uppercase(), color = colors.textMuted, fontSize = 0.6.rem, fontWeight = FontWeight.SemiBold, letterSpacing = 0.04.rem)
    }
}

@Composable
private fun MetaText(label: String, value: String) {
    val colors = LocalWebColors.current
    Row {
        Text(label, color = colors.textMuted, fontSize = 0.8.rem)
        Text(value, color = colors.text, fontSize = 0.8.rem, fontWeight = FontWeight.SemiBold)
    }
}
