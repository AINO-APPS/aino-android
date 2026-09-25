package app.aino.mobile.feature.notes

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val MANAGER_ROLES = setOf("team_lead", "manager", "hr_admin", "super_admin", "platform_admin")

/** NotesHome `getGreeting`. */
internal fun greeting(hour: Int = LocalTime.now().hour): String = when {
    hour < 5 -> "Working late"
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    hour < 22 -> "Good evening"
    else -> "Good night"
}

/** Live search: title or page text contains the query; `#tag` filters by tag. */
internal fun searchPages(pages: List<NotePage>, query: String, limit: Int = 5): List<NotePage> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    val active = pages.filter { !it.archived }
    val hits = if (q.startsWith("#") && q.length > 1) active.filter { q.drop(1) in it.tags }
    else active.filter { it.title.lowercase().contains(q) || htmlToPlainText(it.content).lowercase().contains(q) }
    return hits.take(limit)
}

private fun templateIcon(id: String): ImageVector = when (id) {
    "blank" -> Icons.AutoMirrored.Outlined.NoteAdd
    "journal" -> Icons.Outlined.Bookmarks
    "meeting" -> Icons.Outlined.Handshake
    "decision" -> Icons.Outlined.TaskAlt
    "weekly" -> Icons.Outlined.CalendarViewWeek
    "oneonone" -> Icons.Outlined.Groups
    else -> Icons.Outlined.Repeat
}

private sealed interface HomeDialog {
    data object NewFolder : HomeDialog
    data class SubFolder(val parentId: String) : HomeDialog
    data class NewNoteIn(val folderId: String) : HomeDialog
    data class RenameFolder(val folder: NoteFolder) : HomeDialog
    data class DeleteFolder(val folder: NoteFolder) : HomeDialog
    data class DeletePage(val page: NotePage) : HomeDialog
    data class Move(val page: NotePage) : HomeDialog
    data object Reports : HomeDialog
    data object QuickCapture : HomeDialog
}

/**
 * Notes landing (NotesHome.tsx) inside the app shell: greeting header, live
 * search, jump-back-in, recent / pinned / liked, templates (today's journal
 * with daily prefill, 1-on-1 prefill for managers), tags, the folder tree and
 * the archive.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NotesHomeScreen(
    viewModel: NotesViewModel,
    userId: Long,
    userRole: String,
    hasReports: Boolean,
    onOpenPage: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val context = LocalContext.current
    LaunchedEffect(userId) { viewModel.bindUser(userId) }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }
    var search by remember { mutableStateOf("") }
    var showArchive by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    val canOneOnOne = hasReports || userRole in MANAGER_ROLES
    val active = ui.activePages
    val uid = userId.toString()

    PullToRefreshBox(
        isRefreshing = ui.loading && ui.loaded,
        onRefresh = { viewModel.refresh() },
        modifier = modifier.fillMaxSize().background(colors.bg),
    ) {
        if (!ui.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (ui.error != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(ui.error.orEmpty(), color = colors.danger)
                    TextButton(onClick = { viewModel.refresh() }) { Text("Try again", color = colors.primary) }
                } else CircularProgressIndicator(color = colors.primary)
            }
            return@PullToRefreshBox
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 64.dp),
        ) {
            // ── Header ────────────────────────────────────────────────
            val now = remember { ZonedDateTime.now() }
            Text(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).format(now), color = colors.textMuted, fontSize = 0.82.rem)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(now.hour), color = colors.text, fontSize = 1.4.rem, fontWeight = FontWeight.ExtraBold)
                    Text(
                        buildString {
                            append("${active.size} ${if (active.size == 1) "note" else "notes"}")
                            if (ui.folders.isNotEmpty()) append("  •  ${ui.folders.size} ${if (ui.folders.size == 1) "folder" else "folders"}")
                        },
                        color = colors.textMuted, fontSize = 0.82.rem,
                    )
                }
                IconButton(onClick = { dialog = HomeDialog.QuickCapture }) { Icon(Icons.Outlined.Bolt, "Quick capture", tint = colors.textSecondary) }
                Button(
                    onClick = { onOpenPage(viewModel.createPage()) },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp))
                    Text("New note", modifier = Modifier.padding(start = 4.dp))
                }
            }
            if (ui.busy) Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Text("Preparing your page…", color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(14.dp))
            NotesSearchField(search, { search = it }, "Search your notes, folders, and tags…")
            if (search.isNotBlank()) {
                val matches = remember(search, ui.pages) { searchPages(ui.pages, search) }
                Card(Modifier.padding(top = 6.dp)) {
                    if (matches.isEmpty()) {
                        ResultRow(Icons.Outlined.Add, "Create note \"${search.trim()}\"", null) {
                            val title = search.trim().removePrefix("#")
                            search = ""
                            onOpenPage(viewModel.createPage(title))
                        }
                    } else matches.forEach { p ->
                        ResultRow(if (p.pinned) Icons.Outlined.PushPin else Icons.Outlined.Description, p.title, relativeFromNow(p.updatedAt)) {
                            search = ""
                            onOpenPage(p.id)
                        }
                    }
                }
            }

            // ── Home / Archive switch ─────────────────────────────────
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("Home", !showArchive) { showArchive = false }
                ToggleChip("Archive (${ui.pages.count { it.archived }})", showArchive) { showArchive = true }
            }

            if (showArchive) {
                ArchiveList(ui.pages.filter { it.archived }, viewModel, onOpenPage) { dialog = HomeDialog.DeletePage(it) }
                return@Column
            }

            if (active.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.AutoMirrored.Outlined.StickyNote2, null, tint = colors.textMuted, modifier = Modifier.size(44.dp))
                    Text("No notes yet", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 1.05.rem, modifier = Modifier.padding(top = 10.dp))
                    Text("Create your first note, or start from a template below.", color = colors.textMuted, fontSize = 0.85.rem)
                    Button(onClick = { onOpenPage(viewModel.createPage()) }, colors = ButtonDefaults.buttonColors(containerColor = colors.primary), modifier = Modifier.padding(top = 12.dp)) {
                        Icon(Icons.Outlined.Add, null, modifier = Modifier.size(15.dp))
                        Text("New note", modifier = Modifier.padding(start = 4.dp))
                    }
                }
            } else {
                val recent = remember(active) { active.sortedByDescending { it.updatedMillis }.take(9) }
                val last = recent.firstOrNull()
                last?.let { p ->
                    SectionTitle(Icons.Outlined.Schedule, "Jump back in")
                    Row(
                        Modifier.fillMaxWidth().background(colors.bgElevated, RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp))
                            .clickable { onOpenPage(p.id) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).background(colors.bgSecondary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { PageIcon(p, 20) }
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.title, color = colors.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val folder = p.folderId?.let { id -> ui.folders.firstOrNull { it.id == id }?.name }
                            Text("Edited ${relativeFromNow(p.updatedAt)}" + (folder?.let { " • in $it" } ?: ""), color = colors.textMuted, fontSize = 0.78.rem)
                        }
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = colors.primary)
                    }
                }
                val others = recent.drop(1).take(6)
                if (others.isNotEmpty()) {
                    SectionTitle(Icons.Outlined.Description, "Recent notes")
                    Card {
                        others.forEach { p ->
                            PageListRow(p, snippetOf(p.content, 64), relativeFromNow(p.updatedAt), viewModel, onOpenPage) { dialog = it }
                        }
                    }
                }
                val pinned = remember(active) { active.filter { it.pinned }.take(6) }
                if (pinned.isNotEmpty()) {
                    SectionTitle(Icons.Outlined.PushPin, "Pinned")
                    Card {
                        pinned.forEach { p ->
                            PageListRow(p, null, relativeFromNow(p.updatedAt), viewModel, onOpenPage, accent = p.tags.firstOrNull()?.let { Color(tagColorArgb(it)) } ?: colors.primary) { dialog = it }
                        }
                    }
                }
                val liked = remember(active, uid) {
                    active.filter { p -> p.reactions.values.any { uid in it } }.sortedByDescending { it.updatedMillis }.take(8)
                }
                if (liked.isNotEmpty()) {
                    SectionTitle(Icons.Outlined.Favorite, "Liked")
                    Card { liked.forEach { p -> PageListRow(p, null, relativeFromNow(p.updatedAt), viewModel, onOpenPage) { dialog = it } } }
                }
            }

            // ── Templates ─────────────────────────────────────────────
            SectionTitle(Icons.Outlined.Add, "Start from a template")
            val tiles = NOTE_TEMPLATES.map { Triple(it.id, it.name, it.description) } +
                if (canOneOnOne) listOf(Triple("oneonone-prefill", "1-on-1 with prefill", "Auto-prefilled 1-on-1 for a direct report")) else emptyList()
            tiles.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, name, description) ->
                        Column(
                            Modifier.weight(1f).heightIn(min = 96.dp).background(colors.bgElevated, RoundedCornerShape(12.dp))
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .clickable {
                                    when (id) {
                                        "journal" -> viewModel.openTodayJournal(onOpenPage)
                                        "blank" -> onOpenPage(viewModel.createPage())
                                        "oneonone-prefill" -> dialog = HomeDialog.Reports
                                        else -> onOpenPage(viewModel.createFromTemplate(id))
                                    }
                                }.padding(12.dp),
                        ) {
                            Icon(templateIcon(id.removeSuffix("-prefill")), null, tint = colors.primary, modifier = Modifier.size(18.dp))
                            Text(name, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.88.rem, modifier = Modifier.padding(top = 6.dp))
                            Text(description, color = colors.textMuted, fontSize = 0.75.rem)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            // ── Tags ──────────────────────────────────────────────────
            val tagCounts = remember(active) {
                active.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(14)
            }
            if (tagCounts.isNotEmpty()) {
                SectionTitle(Icons.Outlined.Sell, "Tags")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tagCounts.forEach { (tag, count) ->
                        val tint = Color(tagColorArgb(tag))
                        Row(
                            Modifier.background(tint.copy(alpha = 0.12f), RoundedCornerShape(14.dp)).clickable { search = "#$tag" }.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.dp).background(tint, CircleShape))
                            Text("#$tag", color = colors.text, fontSize = 0.8.rem, modifier = Modifier.padding(start = 6.dp))
                            Text("$count", color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }

            // ── Folder tree ───────────────────────────────────────────
            FolderTreeSection(ui.pages, ui.folders, viewModel, onOpenPage) { dialog = it }
        }
    }

    when (val d = dialog) {
        null -> Unit
        HomeDialog.NewFolder -> TextPromptDialog("New folder", "Folder name…", confirm = "Create", onConfirm = {
            viewModel.newFolder(it); dialog = null
        }) { dialog = null }
        is HomeDialog.SubFolder -> TextPromptDialog("New subfolder", "Subfolder name…", confirm = "Create", onConfirm = {
            viewModel.newFolder(it, d.parentId); dialog = null
        }) { dialog = null }
        is HomeDialog.NewNoteIn -> TextPromptDialog("New note in this folder", "Note title…", confirm = "Create", onConfirm = {
            dialog = null
            onOpenPage(viewModel.createPage(it, d.folderId))
        }) { dialog = null }
        is HomeDialog.RenameFolder -> TextPromptDialog("Rename", "Folder name…", initial = d.folder.name, onConfirm = {
            viewModel.renameFolder(d.folder.id, it); dialog = null
        }) { dialog = null }
        is HomeDialog.DeleteFolder -> ConfirmDialog(
            "Delete folder", "Delete \"${d.folder.name}\"? Its notes move to Uncategorized.", "Delete folder",
            onConfirm = { viewModel.deleteFolder(d.folder.id); dialog = null }, onDismiss = { dialog = null },
        )
        is HomeDialog.DeletePage -> ConfirmDialog(
            "Delete Page", "Are you sure you want to delete \"${d.page.title}\"? This action cannot be undone.", "Delete",
            onConfirm = { viewModel.deletePage(d.page.id); dialog = null }, onDismiss = { dialog = null },
        )
        is HomeDialog.Move -> MoveToFolderSheet(ui.folders, d.page.folderId, { viewModel.moveToFolder(d.page.id, it); dialog = null }) { dialog = null }
        HomeDialog.Reports -> ReportPickerSheet(viewModel, onSelect = { id ->
            dialog = null
            viewModel.newOneOnOne(id, null, onOpenPage)
        }, onDismiss = { dialog = null })
        HomeDialog.QuickCapture -> QuickCaptureDialog(onSave = { viewModel.appendToInbox(it); dialog = null }) { dialog = null }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        modifier.fillMaxWidth().background(colors.bgElevated, RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(vertical = 4.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(icon: ImageVector, title: String) {
    val colors = LocalWebColors.current
    Row(Modifier.padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        Text(title, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.95.rem, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Text(
        label,
        color = if (selected) colors.primary else colors.textSecondary,
        fontSize = 0.82.rem,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.background(if (selected) colors.primary.copy(alpha = 0.14f) else colors.bgSecondary, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun ResultRow(icon: ImageVector, title: String, meta: String?, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
        Text(title, color = colors.text, fontSize = 0.9.rem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 10.dp))
        meta?.let { Text(it, color = colors.textMuted, fontSize = 0.72.rem) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageListRow(
    page: NotePage,
    subtitle: String?,
    meta: String,
    viewModel: NotesViewModel,
    onOpenPage: (String) -> Unit,
    accent: Color? = null,
    indent: Int = 0,
    onDialog: (HomeDialog) -> Unit,
) {
    val colors = LocalWebColors.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenPage(page.id) }, onLongClick = { menu = true })
                .padding(start = (12 + indent * 16).dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (accent != null) Box(Modifier.width(3.dp).height(20.dp).background(accent, RoundedCornerShape(2.dp)))
            else PageIcon(page, 16)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(page.title, color = colors.text, fontSize = 0.9.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.textMuted, fontSize = 0.75.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (page.pinned && accent == null) Icon(Icons.Outlined.PushPin, null, tint = colors.textMuted, modifier = Modifier.size(12.dp).padding(end = 2.dp))
            Text(meta, color = colors.textMuted, fontSize = 0.72.rem)
        }
        PageMenu(menu, page, viewModel, onOpenPage, { menu = false }, onDialog)
    }
}

@Composable
private fun PageMenu(expanded: Boolean, page: NotePage, viewModel: NotesViewModel, onOpenPage: (String) -> Unit, onDismiss: () -> Unit, onDialog: (HomeDialog) -> Unit) {
    val colors = LocalWebColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = colors.bgElevated) {
        @Composable
        fun item(label: String, icon: ImageVector, danger: Boolean = false, action: () -> Unit) = DropdownMenuItem(
            text = { Text(label, color = if (danger) colors.danger else colors.text) },
            leadingIcon = { Icon(icon, null, tint = if (danger) colors.danger else colors.textSecondary) },
            onClick = { onDismiss(); action() },
        )
        item(if (page.pinned) "Unpin" else "Pin to top", Icons.Outlined.PushPin) { viewModel.togglePin(page.id) }
        item("Duplicate", Icons.Outlined.ContentCopy) { viewModel.duplicate(page.id)?.let(onOpenPage) }
        item("Move to folder", Icons.Outlined.Folder) { onDialog(HomeDialog.Move(page)) }
        item(if (page.archived) "Unarchive" else "Archive", if (page.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive) { viewModel.toggleArchive(page.id) }
        HorizontalDivider(color = colors.border)
        item("Delete", Icons.Outlined.Delete, danger = true) { onDialog(HomeDialog.DeletePage(page)) }
    }
}

@Composable
private fun ArchiveList(archived: List<NotePage>, viewModel: NotesViewModel, onOpenPage: (String) -> Unit, onDelete: (NotePage) -> Unit) {
    val colors = LocalWebColors.current
    SectionTitle(Icons.Outlined.Archive, "Archive")
    if (archived.isEmpty()) {
        Text("No archived notes", color = colors.textMuted, fontSize = 0.85.rem)
        return
    }
    Card {
        archived.sortedByDescending { it.updatedMillis }.forEach { p ->
            Row(Modifier.fillMaxWidth().clickable { onOpenPage(p.id) }.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PageIcon(p, 16)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(p.title, color = colors.text, fontSize = 0.9.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(relativeFromNow(p.updatedAt), color = colors.textMuted, fontSize = 0.72.rem)
                }
                IconButton(onClick = { viewModel.toggleArchive(p.id) }) { Icon(Icons.Outlined.Unarchive, "Unarchive", tint = colors.textSecondary) }
                IconButton(onClick = { onDelete(p) }) { Icon(Icons.Outlined.Delete, "Delete", tint = colors.danger) }
            }
        }
    }
}

// ── Folder tree (FolderTree.tsx, home variant) ─────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTreeSection(
    pages: List<NotePage>,
    folders: List<NoteFolder>,
    viewModel: NotesViewModel,
    onOpenPage: (String) -> Unit,
    onDialog: (HomeDialog) -> Unit,
) {
    val colors = LocalWebColors.current
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val byFolder = remember(pages) {
        pages.filter { !it.archived }
            .sortedWith(compareBy<NotePage> { !it.pinned }.thenBy { it.title.lowercase() })
            .groupBy { it.folderId ?: "__none__" }
    }
    Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Folders & notes", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.95.rem, modifier = Modifier.weight(1f))
        Row(Modifier.clickable { onDialog(HomeDialog.NewFolder) }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CreateNewFolder, null, tint = colors.primary, modifier = Modifier.size(14.dp))
            Text("New folder", color = colors.primary, fontSize = 0.8.rem, modifier = Modifier.padding(start = 4.dp))
        }
    }
    Card {
        val roots = folders.filter { it.parentId == null || folders.none { f -> f.id == it.parentId } }.sortedBy { it.sortOrder }


        @Composable
        fun FolderNode(folder: NoteFolder, depth: Int) {
            if (depth > 24) return
            val open = expanded[folder.id] ?: true
            val children = folders.filter { it.parentId == folder.id }.sortedBy { it.sortOrder }
            val folderPages = byFolder[folder.id].orEmpty()
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { expanded[folder.id] = !open }, onLongClick = { menu = true })
                        .padding(start = (8 + depth * 16).dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                    Icon(Icons.Outlined.Folder, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                    Text(folder.name, color = colors.text, fontSize = 0.9.rem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    if (folderPages.isNotEmpty()) Text("${folderPages.size}", color = colors.textMuted, fontSize = 0.72.rem)
                    IconButton(onClick = { onDialog(HomeDialog.NewNoteIn(folder.id)) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Add, "New note in this folder", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.bgElevated) {
                    DropdownMenuItem(text = { Text("New subfolder", color = colors.text) }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        onClick = { menu = false; onDialog(HomeDialog.SubFolder(folder.id)) })
                    DropdownMenuItem(text = { Text("Rename", color = colors.text) }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; onDialog(HomeDialog.RenameFolder(folder)) })
                    DropdownMenuItem(text = { Text("Delete folder", color = colors.danger) }, leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = colors.danger) },
                        onClick = { menu = false; onDialog(HomeDialog.DeleteFolder(folder)) })
                }
            }
            if (open) {
                children.forEach { FolderNode(it, depth + 1) }
                folderPages.forEach { p -> PageListRow(p, null, formatNoteDate(p.updatedAt), viewModel, onOpenPage, indent = depth + 1) { onDialog(it) } }
                if (children.isEmpty() && folderPages.isEmpty()) {
                    Text("Empty — use + to add a note", color = colors.textMuted, fontSize = 0.75.rem, modifier = Modifier.padding(start = (28 + depth * 16).dp, top = 2.dp, bottom = 6.dp))
                }
            }
        }

        roots.forEach { FolderNode(it, 0) }
        val uncategorized = byFolder["__none__"].orEmpty() + byFolder.filterKeys { k -> k != "__none__" && folders.none { it.id == k } }.values.flatten()
        val open = expanded["__none__"] ?: true
        Row(
            Modifier.fillMaxWidth().clickable { expanded["__none__"] = !open }.padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
            Icon(Icons.Outlined.Inbox, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp).padding(start = 2.dp))
            Text("Uncategorized", color = colors.text, fontSize = 0.9.rem, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (uncategorized.isNotEmpty()) Text("${uncategorized.size}", color = colors.textMuted, fontSize = 0.72.rem)
        }
        if (open) uncategorized.forEach { p -> PageListRow(p, null, formatNoteDate(p.updatedAt), viewModel, onOpenPage, indent = 1) { onDialog(it) } }
        if (roots.isEmpty() && uncategorized.isEmpty()) {
            Text("No folders yet. Tap New folder to get organized.", color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(12.dp))
        }
    }
}

@Composable
private fun QuickCaptureDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalWebColors.current
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgElevated,
        title = { Text("Quick capture", color = colors.text) },
        text = {
            Column {
                Text("· appends to Inbox", color = colors.textMuted, fontSize = 0.8.rem, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, minLines = 3, maxLines = 8,
                    placeholder = { Text("What's on your mind?", color = colors.textMuted) },
                    colors = notesFieldColors(), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save to Inbox", color = colors.primary) } },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Outlined.Close, null, tint = colors.textSecondary, modifier = Modifier.size(14.dp))
                Text("Cancel", color = colors.textSecondary)
            }
        },
    )
}
