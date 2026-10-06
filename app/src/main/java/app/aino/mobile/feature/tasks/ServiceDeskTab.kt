package app.aino.mobile.feature.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun typeIcon(value: String): ImageVector = when (value) {
    "bug" -> HeroIcons.BugAnt
    "feature_request" -> HeroIcons.Sparkles
    "access_issue" -> HeroIcons.ShieldCheck
    else -> HeroIcons.QuestionMarkCircle
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

private fun localeDate(value: String?): String = value?.let {
    runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate().format(SHORT_DATE) }.getOrNull()
        ?: localDateOf(it)?.format(SHORT_DATE)
}.orEmpty()

/**
 * `pages/tasks/ServiceDeskTab.tsx` for phones: status chips (with counts) and
 * type chips filter an expandable ticket list; "New ticket" opens a sheet.
 * Reloads on pull, app resume, the Tasks poll and `serviceDeskVersion`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceDeskTab(viewModel: TaskViewModel, @Suppress("UNUSED_PARAMETER") role: String) {
    val repo = viewModel.serviceDesk ?: return
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var filterStatus by rememberSaveable { mutableStateOf("") }
    var filterType by rememberSaveable { mutableStateOf("") }
    var tickets by remember { mutableStateOf<List<ServiceTicket>>(emptyList()) }
    var stats by remember { mutableStateOf<ServiceDeskStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var formOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var deletingId by remember { mutableStateOf<Long?>(null) }
    var confirmDelete by remember { mutableStateOf<ServiceTicket?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(filterStatus, filterType, reloadKey, ui.serviceDeskVersion) {
        withContext(Dispatchers.IO) {
            runCatching { repo.tickets(filterStatus, filterType) }
                .onSuccess { tickets = it; loadFailed = false }
                .onFailure { loadFailed = true }
            runCatching { repo.stats() }.onSuccess { stats = it }
        }
        loading = false
    }

    Box(Modifier.fillMaxSize()) {
        TaskPage(loading = loading, onRefresh = { loading = true; reloadKey++ }) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 10.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        stats?.let { s ->
                            ChipStrip {
                                CountChip("All", filterStatus.isEmpty(), { filterStatus = "" }, count = s.total)
                                visibleStatusChips(s).forEach { st ->
                                    CountChip(st.label, filterStatus == st.value, { filterStatus = if (filterStatus == st.value) "" else st.value }, count = s.count(st.value), accent = Color(st.color), dot = true)
                                }
                            }
                        }
                        ChipStrip {
                            CountChip("All types", filterType.isEmpty(), { filterType = "" })
                            TICKET_TYPES.forEach { t ->
                                CountChip(t.label, filterType == t.value, { filterType = if (filterType == t.value) "" else t.value }, accent = Color(t.color), dot = true)
                            }
                        }
                        val shownError = error.ifEmpty { if (loadFailed) "Failed to load tickets" else "" }
                        if (shownError.isNotEmpty()) ErrorMsg(shownError, Modifier.padding(horizontal = 16.dp))
                    }
                }
                when {
                    loading && tickets.isEmpty() -> items(3, key = { "sk-$it" }) { Box(Modifier.padding(horizontal = 16.dp)) { TaskCardSkeleton() } }
                    tickets.isEmpty() -> item(key = "empty") {
                        TaskEmptyState(
                            HeroIcons.Lifebuoy, "No tickets found",
                            "Report a bug, request a feature, or get help with access issues.",
                            action = "New ticket", onAction = { formOpen = true },
                        )
                    }
                    else -> items(tickets, key = { "sd-${it.id}" }) { ticket ->
                        TicketCard(
                            ticket = ticket,
                            open = expanded == ticket.id,
                            canDelete = canDeleteTicket(ticket, ui.userId),
                            deleting = deletingId == ticket.id,
                            onToggle = { expanded = if (expanded == ticket.id) null else ticket.id },
                            onDelete = { confirmDelete = ticket },
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { formOpen = true },
            icon = { Icon(HeroIcons.Plus, null, Modifier.size(18.dp)) },
            text = { Text("New ticket", fontWeight = FontWeight.SemiBold) },
            containerColor = colors.primary,
            contentColor = colors.onAccent,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (formOpen) {
        NewTicketSheet(
            onDismiss = { formOpen = false },
            onSubmit = { payload, done ->
                error = ""
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { repo.create(payload) } }
                    result.onSuccess { formOpen = false; reloadKey++ }.onFailure { error = it.message ?: "Failed to submit ticket" }
                    done()
                }
            },
        )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TicketCard(ticket: ServiceTicket, open: Boolean, canDelete: Boolean, deleting: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalWebColors.current
    val t = ticketType(ticket.ticketType)
    val p = ticketPriority(ticket.priority)
    val st = ticketStatus(ticket.status)
    SectionCard(Modifier.padding(horizontal = 16.dp), padding = 0.dp) {
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val tint = Color(t.color)
                Box(Modifier.size(32.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(typeIcon(t.value), null, Modifier.size(17.dp), tint = tint)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ticket.title, color = colors.text, fontSize = 0.92.rem, fontWeight = FontWeight.SemiBold, maxLines = if (open) 4 else 2, overflow = TextOverflow.Ellipsis)
                    Text("${t.label} · ${localeDate(ticket.createdAt)}", color = colors.textMuted, fontSize = 0.74.rem, maxLines = 1)
                }
                Icon(HeroIcons.ChevronDown, null, Modifier.size(18.dp).rotate(if (open) 180f else 0f), tint = colors.textMuted)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(st.label, Color(st.color))
                Text(p.label, color = Color(p.color), fontSize = 0.74.rem, fontWeight = FontWeight.Bold)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ticket.description?.takeIf { stripHtml(it).isNotBlank() }?.let { TaskHtml(it, colors.textSecondary, 0.88.rem) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ticket.submittedByName?.let { MetaChip(HeroIcons.User, it, colors.textSecondary) }
                    ticket.tenantName?.let { MetaChip(HeroIcons.BuildingOffice, it, colors.textSecondary) }
                    ticket.assignedTo?.let { MetaChip(HeroIcons.UserPlus, it, colors.textSecondary) }
                    ticket.resolvedAt?.let { MetaChip(HeroIcons.CheckCircle, "Resolved ${localeDate(it)}", colors.success) }
                }
                ticket.adminNotes?.takeIf(String::isNotBlank)?.let { notes ->
                    Column(Modifier.fillMaxWidth().clip(CardShape).background(colors.surface).padding(12.dp)) {
                        Text("Admin notes", color = colors.textMuted, fontSize = 0.72.rem, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(2.dp))
                        Text(notes, color = colors.textSecondary, fontSize = 0.84.rem)
                    }
                }
                if (canDelete) {
                    PillButton(if (deleting) "Cancelling…" else "Cancel ticket", onDelete, danger = false, icon = HeroIcons.Trash, enabled = !deleting)
                }
            }
        }
    }
}

@Composable
private fun NewTicketSheet(onDismiss: () -> Unit, onSubmit: (CreateTicketPayload, done: () -> Unit) -> Unit) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("bug") }
    var priority by remember { mutableStateOf("medium") }
    var submitting by remember { mutableStateOf(false) }
    TaskSheetScaffold("New service desk ticket", onDismiss, HeroIcons.Lifebuoy) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WebTextField(title, { title = it }, "Ticket title", maxLength = 200, fontSize = 1.rem)
            WebTextField(description, { description = it }, "Details: steps to reproduce, expected behaviour…", singleLine = false, minLines = 4)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Type")
                ChipStripNoPad {
                    TICKET_TYPES.forEach { t -> CountChip(t.label, type == t.value, { type = t.value }, accent = Color(t.color), dot = true) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Priority")
                ChipStripNoPad {
                    TICKET_PRIORITIES.forEach { p -> CountChip(p.label, priority == p.value, { priority = p.value }, accent = Color(p.color), dot = true) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Cancel", onDismiss, Modifier.weight(1f))
            PillButton(
                if (submitting) "Submitting…" else "Create ticket",
                {
                    submitting = true
                    onSubmit(CreateTicketPayload(title.trim(), plainTextToHtml(description.trim()), type, priority)) { submitting = false }
                },
                Modifier.weight(1f), primary = true, enabled = title.isNotBlank() && !submitting,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipStripNoPad(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
