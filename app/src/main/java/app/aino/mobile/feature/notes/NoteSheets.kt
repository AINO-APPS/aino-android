package app.aino.mobile.feature.notes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.network.NetworkConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun NotesSearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, autoFocus: Boolean = false) {
    val colors = LocalWebColors.current
    val focus = remember { FocusRequester() }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = colors.textMuted) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, tint = colors.textMuted) },
        shape = RoundedCornerShape(10.dp),
        colors = notesFieldColors(),
        modifier = modifier.fillMaxWidth().focusRequester(focus),
    )
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
internal fun notesFieldColors() = LocalWebColors.current.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.text, unfocusedTextColor = c.text,
        focusedContainerColor = c.inputBg, unfocusedContainerColor = c.inputBg,
        focusedBorderColor = c.primary, unfocusedBorderColor = c.inputBorder, cursorColor = c.primary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.bgElevated,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) { content() }
    }
}

@Composable
internal fun SheetTitle(title: String, subtitle: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val colors = LocalWebColors.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        icon?.let { Icon(it, null, tint = colors.primary, modifier = Modifier.size(18.dp)) }
        Column(Modifier.padding(start = if (icon != null) 10.dp else 0.dp)) {
            Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 1.05.rem)
            subtitle?.let { Text(it, color = colors.textMuted, fontSize = 0.8.rem) }
        }
    }
}

@Composable
private fun SheetRow(icon: @Composable () -> Unit, title: String, subtitle: String? = null, trailing: String? = null, onClick: (() -> Unit)?) {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(title, color = colors.text, fontSize = 0.92.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let { Text(it, color = colors.textMuted, fontSize = 0.75.rem, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let { Text(it, color = colors.textMuted, fontSize = 0.75.rem) }
    }
}

@Composable
internal fun PageIcon(page: NotePage, size: Int = 18) {
    val colors = LocalWebColors.current
    if (page.icon.isNotEmpty()) Text(page.icon, fontSize = (size * 0.9f / 16f).rem)
    else Icon(Icons.Outlined.Description, null, tint = colors.textMuted, modifier = Modifier.size(size.dp))
}

// ── Page link picker (slash → "Link to page") ─────────────────────────────

@Composable
internal fun PageLinkPickerSheet(pages: List<NotePage>, onPick: (NotePage) -> Unit, onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(pages, q) {
        val list = pages.filter { !it.archived }
        (if (q.isEmpty()) list else list.filter { it.title.contains(q, ignoreCase = true) }).take(12)
    }
    val showCreate = q.isNotEmpty() && filtered.none { it.title.equals(q, ignoreCase = true) }
    NotesSheet(onDismiss) {
        SheetTitle("Link to page", icon = Icons.Outlined.Link)
        NotesSearchField(query, { query = it }, "Search or create page…", autoFocus = true)
        Spacer(Modifier.padding(4.dp))
        LazyColumn(Modifier.heightIn(max = 380.dp)) {
            if (filtered.isEmpty() && !showCreate) item { EmptyRow("No pages found") }
            items(filtered, key = { it.id }) { p -> SheetRow({ PageIcon(p) }, p.title, onClick = { onPick(p) }) }
            if (showCreate) item {
                SheetRow({ Icon(Icons.Outlined.Add, null, tint = LocalWebColors.current.primary) }, "Create “$q”", onClick = { onCreate(q) })
            }
        }
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(text, color = LocalWebColors.current.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(vertical = 16.dp, horizontal = 4.dp))
}

// ── Report picker (1-on-1 with prefill) ────────────────────────────────────

@Composable
internal fun ReportPickerSheet(viewModel: NotesViewModel, onSelect: (Long) -> Unit, onDismiss: () -> Unit) {
    var filter by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var reports by remember { mutableStateOf<List<MentionUser>>(emptyList()) }
    LaunchedEffect(Unit) {
        reports = viewModel.directReports().getOrDefault(emptyList())
        loading = false
    }
    val filtered = if (filter.isBlank()) reports else reports.filter {
        (it.fullName ?: "").contains(filter, true) || (it.username ?: "").contains(filter, true)
    }
    NotesSheet(onDismiss) {
        SheetTitle("Select a direct report for 1-on-1", icon = Icons.Outlined.Inbox)
        NotesSearchField(filter, { filter = it }, "Search reports…")
        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
            when {
                loading -> item { EmptyRow("Loading…") }
                filtered.isEmpty() -> item { EmptyRow(if (reports.isEmpty()) "No direct reports found" else "No matches") }
            }
            items(filtered, key = { it.id }) { r ->
                SheetRow({ UserAvatar(r.fullName, r.avatar, 28.dp) }, r.fullName.orEmpty(), "@${r.username.orEmpty()}", onClick = { onSelect(r.id) })
            }
        }
    }
}

// ── Share page (ShareNoteModal) ────────────────────────────────────────────

internal fun shareUrl(state: ShareState): String? {
    val token = state.token ?: return null
    val url = state.url
    return if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) url else "${NetworkConfig.serverOrigin.trimEnd('/')}/n/$token"
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("Share link", text))
}

@Composable
internal fun ShareNoteSheet(viewModel: NotesViewModel, page: NotePage, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var state by remember { mutableStateOf(ShareState(null, null)) }
    var copied by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    LaunchedEffect(page.id) {
        viewModel.shareState(page.id)
            .onSuccess { state = it }
            .onFailure { error = it.message ?: "Failed to load share state" }
        loading = false
    }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val url = shareUrl(state)
    NotesSheet(onDismiss) {
        SheetTitle("Share page", "Anyone with the link can read this page.", Icons.Outlined.Public)
        Row(Modifier.padding(bottom = 10.dp)) {
            Text("Page:", color = colors.textMuted, fontSize = 0.85.rem)
            Text(page.title, color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
        }
        if (error.isNotEmpty()) Text(error, color = colors.danger, fontSize = 0.85.rem, modifier = Modifier.padding(bottom = 8.dp))
        when {
            loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.primary) }
            url != null -> {
                Row(
                    Modifier.fillMaxWidth().background(colors.inputBg, RoundedCornerShape(10.dp)).border(1.dp, colors.inputBorder, RoundedCornerShape(10.dp))
                        .padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Link, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                    Text(url, color = colors.text, fontSize = 0.82.rem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    IconButton(onClick = { copyToClipboard(context, url); copied = true }) {
                        Icon(if (copied) Icons.Outlined.CheckBox else Icons.Outlined.ContentCopy, "Copy link", tint = colors.primary)
                    }
                }
                Text(
                    "Anyone who opens this link can view the page — they cannot edit, comment, or see other pages. Revoke at any time.",
                    color = colors.textSecondary, fontSize = 0.8.rem, modifier = Modifier.padding(vertical = 10.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, page.title)
                                putExtra(Intent.EXTRA_TEXT, url)
                            }
                            context.startActivity(Intent.createChooser(send, "Share page"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    ) {
                        Icon(Icons.Outlined.Share, null, modifier = Modifier.size(16.dp))
                        Text("Share link", modifier = Modifier.padding(start = 6.dp))
                    }
                    OutlinedButton(onClick = { confirmRevoke = true }, enabled = !busy) {
                        Icon(Icons.Outlined.Delete, null, modifier = Modifier.size(16.dp), tint = colors.danger)
                        Text(if (busy) "Revoking…" else "Revoke link", color = colors.danger, modifier = Modifier.padding(start = 6.dp))
                    }
                }
            }
            else -> {
                Text(
                    "This page is private. Create a share link to give read-only access to anyone — without requiring them to log in.",
                    color = colors.textSecondary, fontSize = 0.85.rem, modifier = Modifier.padding(bottom = 12.dp),
                )
                Button(
                    onClick = {
                        busy = true
                        error = ""
                        scope.launch {
                            viewModel.createShare(page.id)
                                .onSuccess { state = it }
                                .onFailure { error = it.message ?: "Failed to create share link" }
                            busy = false
                        }
                    },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                ) {
                    Icon(Icons.Outlined.Link, null, modifier = Modifier.size(16.dp))
                    Text(if (busy) "Creating…" else "Create share link", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
    if (confirmRevoke) {
        ConfirmDialog(
            title = "Revoke link",
            message = "Revoke this share link? Anyone with the URL will lose access.",
            confirm = "Revoke link",
            onConfirm = {
                confirmRevoke = false
                busy = true
                error = ""
                scope.launch {
                    viewModel.revokeShare(page.id)
                        .onSuccess { state = ShareState(null, null); copied = false }
                        .onFailure { error = it.message ?: "Failed to revoke link" }
                    busy = false
                }
            },
            onDismiss = { confirmRevoke = false },
        )
    }
}

// ── Link a task / event / meeting (LinkedEntitiesPanel "add") ─────────────

internal fun entityIcon(type: String) = when (type) {
    "task" -> Icons.Outlined.CheckBox
    "meeting" -> Icons.Outlined.Videocam
    else -> Icons.Outlined.CalendarMonth
}

internal fun entityWhen(raw: JsonObjectLike): String {
    val iso = raw.str("start_time") ?: raw.str("scheduled_start") ?: return ""
    val millis = parseIsoMillis(iso) ?: return ""
    return java.time.format.DateTimeFormatter.ofPattern("MMM d, hh:mm a", java.util.Locale.getDefault())
        .format(java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()))
}

internal typealias JsonObjectLike = kotlinx.serialization.json.JsonObject

private val PRIORITY_ICONS = mapOf("low" to "↓", "medium" to "—", "high" to "↑")

internal fun entitySubtitle(type: String, raw: JsonObjectLike): String = when (type) {
    "task" -> listOfNotNull(raw.str("status")?.replace('_', ' '), raw.str("priority")?.let { PRIORITY_ICONS[it] }).joinToString(" · ")
    else -> entityWhen(raw)
}

@Composable
internal fun LinkEntitySheet(
    viewModel: NotesViewModel,
    pageId: String,
    type: String,
    linked: List<NoteLink>,
    onLinked: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<LinkCandidate>>(emptyList()) }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(300)
        searching = true
        results = viewModel.search(type, query.trim()).getOrDefault(emptyList())
        searching = false
    }
    val noun = if (type == "calendar_event") "event" else type
    NotesSheet(onDismiss) {
        SheetTitle("Link $noun", icon = entityIcon(type))
        NotesSearchField(query, { query = it }, "Search ${if (type == "calendar_event") "events" else type + "s"}…", autoFocus = true)
        LazyColumn(Modifier.heightIn(max = 420.dp).padding(top = 6.dp)) {
            if (searching) item { EmptyRow("Searching…") }
            if (!searching && results.isEmpty()) item { EmptyRow("No results") }
            items(results, key = { it.id }) { r ->
                val already = linked.any { it.entityType == type && it.entityId == r.id }
                SheetRow(
                    { Icon(entityIcon(type), null, tint = colors.textSecondary, modifier = Modifier.size(18.dp)) },
                    r.title, entitySubtitle(type, r.raw).ifEmpty { null }, if (already) "Linked" else null,
                    onClick = if (already) null else ({
                        scope.launch {
                            viewModel.addLink(pageId, type, r.id).onSuccess { onLinked() }
                        }
                    }),
                )
            }
        }
    }
}

// ── Icon & cover (IconPicker) ──────────────────────────────────────────────

private val ICON_GROUPS = listOf(
    "Frequent" to listOf("📝", "📄", "📒", "📓", "📕", "📗", "📘", "📙", "📔", "📚", "📖", "🗒️", "🗂️", "📁", "📂"),
    "Symbols" to listOf("⭐", "✨", "💡", "🔥", "🚀", "🎯", "🏆", "🎉", "🌟", "⚡", "💎", "🌈", "☘️", "🍀", "🌸"),
    "Work" to listOf("💼", "📊", "📈", "📉", "🗓️", "📅", "🔧", "🔨", "⚙️", "🧰", "📌", "📍", "🔑", "🗝️", "✅"),
    "People" to listOf("👤", "👥", "👨‍💻", "👩‍💻", "🤝", "🙋", "🧠", "💬", "💭", "👀", "👋", "🤔", "😀", "😎", "🥳"),
    "Misc" to listOf("🌍", "🏠", "🏢", "☕", "🍕", "🎨", "🎵", "🔔", "❤️", "💛", "💚", "💙", "💜", "🖤", "🤍"),
)

private val COVER_COLORS = listOf(
    "", "#ef4444", "#f97316", "#f59e0b", "#eab308", "#84cc16", "#10b981", "#14b8a6", "#06b6d4", "#0ea5e9", "#3b82f6",
    "#6366f1", "#8b5cf6", "#a855f7", "#d946ef", "#ec4899", "#f43f5e", "#64748b",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconPickerSheet(page: NotePage, onChange: (icon: String?, cover: String?) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    NotesSheet(onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Icon", color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onChange("", null) }) { Text("Remove", color = colors.textSecondary) }
            }
            ICON_GROUPS.forEach { (label, icons) ->
                Text(label, color = colors.textMuted, fontSize = 0.75.rem, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    icons.forEach { icon ->
                        Box(
                            Modifier.size(40.dp)
                                .background(if (icon == page.icon) colors.primary.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onChange(icon, null) },
                            contentAlignment = Alignment.Center,
                        ) { Text(icon, fontSize = 1.3.rem) }
                    }
                }
            }
            Text("Cover colour", color = colors.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                COVER_COLORS.forEach { c ->
                    val selected = c == page.coverColor
                    Box(
                        Modifier.size(32.dp).background(parseCssColor(c) ?: colors.bgSecondary, CircleShape)
                            .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.border, CircleShape)
                            .clickable { onChange(null, c) },
                        contentAlignment = Alignment.Center,
                    ) { if (c.isEmpty()) Text("×", color = colors.textMuted) }
                }
            }
        }
    }
}

// ── Move to folder ─────────────────────────────────────────────────────────

internal fun folderTreeList(folders: List<NoteFolder>, parentId: String? = null, depth: Int = 0, seen: MutableSet<String> = mutableSetOf()): List<Pair<NoteFolder, Int>> =
    folders.filter { it.parentId == parentId && seen.add(it.id) }.sortedBy { it.sortOrder }.flatMap { listOf(it to depth) + folderTreeList(folders, it.id, depth + 1, seen) }

@Composable
internal fun MoveToFolderSheet(folders: List<NoteFolder>, current: String?, onSelect: (String?) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    NotesSheet(onDismiss) {
        SheetTitle("Move to folder", icon = Icons.Outlined.Folder)
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            item {
                SheetRow({ Icon(Icons.Outlined.Inbox, null, tint = colors.textSecondary) }, "No folder", trailing = if (current == null) "✓" else null) { onSelect(null) }
            }
            items(folderTreeList(folders), key = { it.first.id }) { (f, depth) ->
                Row(Modifier.padding(start = (depth * 16).dp)) {
                    SheetRow({ Icon(Icons.Outlined.Folder, null, tint = colors.textSecondary) }, f.name, trailing = if (current == f.id) "✓" else null) { onSelect(f.id) }
                }
            }
        }
    }
}

// ── Dialogs ────────────────────────────────────────────────────────────────

@Composable
internal fun TextPromptDialog(
    title: String,
    placeholder: String,
    initial: String = "",
    confirm: String = "Save",
    allowBlank: Boolean = false,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalWebColors.current
    var value by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text(title, color = colors.text) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it }, singleLine = true,
                placeholder = { Text(placeholder, color = colors.textMuted) },
                colors = notesFieldColors(), modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = allowBlank || value.isNotBlank()) { Text(confirm, color = colors.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, danger: Boolean = true) {
    val colors = LocalWebColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text(title, color = colors.text) },
        text = { Text(message, color = colors.textSecondary) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) colors.danger else colors.primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textSecondary) } },
    )
}
