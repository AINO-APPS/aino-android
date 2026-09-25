package app.aino.mobile.feature.notes

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.AssignmentTurnedIn
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import kotlinx.coroutines.launch

// ── Slash menu (SlashMenu.tsx, Android-editable subset) ───────────────────

internal data class SlashCommand(val id: String, val label: String, val hint: String, val icon: ImageVector, val keys: List<String>)

private val CODE_LANGUAGES = listOf(
    "plaintext" to "Plain text", "javascript" to "JavaScript", "typescript" to "TypeScript", "python" to "Python", "java" to "Java",
    "csharp" to "C#", "go" to "Go", "json" to "JSON", "sql" to "SQL", "bash" to "Bash", "html" to "HTML", "css" to "CSS",
)

internal val SLASH_COMMANDS: List<SlashCommand> = listOf(
    SlashCommand("h1", "Heading 1", "Large section title", Icons.Outlined.Title, listOf("h1", "heading1", "title")),
    SlashCommand("h2", "Heading 2", "Medium section title", Icons.Outlined.Title, listOf("h2", "heading2", "subtitle")),
    SlashCommand("h3", "Heading 3", "Small section title", Icons.Outlined.Title, listOf("h3", "heading3")),
    SlashCommand("p", "Text", "Plain paragraph", Icons.Outlined.TextFields, listOf("p", "paragraph", "text")),
    SlashCommand("ul", "Bulleted list", "Simple bullet list", Icons.AutoMirrored.Outlined.FormatListBulleted, listOf("ul", "bullet", "unordered")),
    SlashCommand("ol", "Numbered list", "Ordered list", Icons.Outlined.FormatListNumbered, listOf("ol", "numbered", "ordered")),
    SlashCommand("todo", "To-do list", "Checkbox list", Icons.Outlined.CheckBox, listOf("todo", "check", "task", "checkbox")),
    SlashCommand("quote", "Quote", "Blockquote", Icons.Outlined.FormatQuote, listOf("quote", "blockquote")),
    SlashCommand("code", "Code block", "Monospace block (auto-detect)", Icons.Outlined.Code, listOf("code", "codeblock", "pre")),
) + CODE_LANGUAGES.map { (id, label) ->
    SlashCommand("code-$id", "Code · $label", "$label code block", Icons.Outlined.Code, listOf("code", id, label.lowercase()))
} + listOf(
    SlashCommand("divider", "Divider", "Horizontal rule", Icons.Outlined.HorizontalRule, listOf("divider", "hr", "rule", "separator")),
    SlashCommand("timestamp", "Timestamp", "Insert current date and time", Icons.Outlined.Schedule, listOf("timestamp", "date", "time", "now")),
    SlashCommand("today", "Today", "Insert today's date as a chip", Icons.Outlined.Today, listOf("today", "date", "now")),
    SlashCommand("pagelink", "Link to page", "Insert link to another note", Icons.Outlined.Link, listOf("link", "page", "pagelink", "wiki")),
    SlashCommand("toc", "Table of contents", "Auto-generate from headings", Icons.Outlined.AccountTree, listOf("toc", "contents", "outline")),
    SlashCommand("sprint", "Sprint board", "Embed live sprint board & burndown", Icons.Outlined.RocketLaunch, listOf("sprint", "board", "burndown", "kanban", "scrum")),
    SlashCommand("time", "Time tracking", "Insert today's tracked time summary", Icons.Outlined.Timer, listOf("time", "hours", "clock", "track", "timer", "timesheet")),
    SlashCommand("promote-task", "Convert to task", "Promote checklist item to a real task", Icons.Outlined.AssignmentTurnedIn, listOf("task", "promote", "convert", "todo", "create task")),
    SlashCommand("oneonone", "1-on-1 with prefill", "Auto-prefilled 1-on-1 for a direct report", Icons.Outlined.Groups, listOf("oneonone", "1on1", "1-on-1", "one on one", "manager", "report")),
    SlashCommand("link-task", "Link task", "Link a task to this page", Icons.Outlined.CheckBox, listOf("link", "task", "linked")),
    SlashCommand("link-event", "Link event", "Link a calendar event to this page", Icons.Outlined.CalendarMonth, listOf("link", "event", "calendar", "linked")),
    SlashCommand("link-meeting", "Link meeting", "Link a meeting to this page", Icons.Outlined.Videocam, listOf("link", "meeting", "linked")),
)

/** SlashMenu filter: label contains, or any alias starts with / contains the query. */
internal fun filterSlashCommands(query: String): List<SlashCommand> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return SLASH_COMMANDS
    return SLASH_COMMANDS.filter { c -> c.label.lowercase().contains(q) || c.keys.any { it.startsWith(q) || it.contains(q) } }
}

/** MentionMenu filter: full name or username contains the query; first 8. */
internal fun filterMentionUsers(users: List<MentionUser>, query: String): List<MentionUser> {
    val q = query.lowercase()
    return users.filter { q.isEmpty() || (it.fullName ?: "").lowercase().contains(q) || (it.username ?: "").lowercase().contains(q) }.take(8)
}

/** In-app route for a linked entity (`onOpenLink`). */
internal fun linkRoute(link: NoteLink): String? = when (link.entityType) {
    "task" -> "/tasks?task=${link.entityId}"
    "meeting" -> link.detail?.str("meeting_code")?.takeIf { it.isNotBlank() }?.let { "/meeting/$it" } ?: "/calendar"
    "calendar_event" -> "/calendar"
    else -> null
}

private sealed interface EditorSheet {
    data class PageLink(val at: FocusTarget?) : EditorSheet
    data object Reports : EditorSheet
    data object Share : EditorSheet
    data object Icon : EditorSheet
    data object Move : EditorSheet
    data class LinkEntity(val type: String) : EditorSheet
    data object Insert : EditorSheet
    data object Tag : EditorSheet
    data object Url : EditorSheet
    data object Delete : EditorSheet
}

/**
 * Full-screen page editor (NotesModal/ModalEditor/EditorTopBar port) with a
 * native block editor over the page's Quill HTML. Untouched blocks keep their
 * exact HTML; tables, diagrams, math, audio and other web-only blocks are
 * shown read-only and preserved byte-for-byte.
 *
 * [onOpenLink] receives web routes: `/tasks?task=<id>`, `/meeting/<code>`,
 * `/calendar`, and any relative in-page link (`/…`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteEditorScreen(
    viewModel: NotesViewModel,
    pageId: String,
    userId: Long,
    onBack: () -> Unit,
    onOpenPage: (String) -> Unit,
    onOpenLink: (webRoute: String) -> Unit,
    onOpenHistory: ((pageId: String) -> Unit)? = null,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(userId) { viewModel.bindUser(userId) }
    val page = ui.page(pageId)
    LaunchedEffect(pageId, page != null) { if (page != null) viewModel.openEditor(pageId) }
    var wasLoaded by remember { mutableStateOf(false) }
    var exited by remember { mutableStateOf(false) }
    LaunchedEffect(page != null, ui.loaded) {
        if (page != null) wasLoaded = true
        else if (wasLoaded && !exited) {
            exited = true
            onBack()
        }
    }
    DisposableEffect(pageId) { onDispose { viewModel.closeEditor(pageId) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) viewModel.flush() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }
    var showHistory by remember { mutableStateOf(false) }
    if (showHistory) {
        BackHandler { showHistory = false }
        NoteHistoryScreen(viewModel, pageId, onBack = { showHistory = false })
        return
    }

    val session = viewModel.editor?.takeIf { it.pageId == pageId }
    var sheet by remember { mutableStateOf<EditorSheet?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var linksVersion by remember { mutableIntStateOf(0) }
    val openUrl: (String) -> Unit = { url ->
        when {
            url.startsWith("/") -> onOpenLink(url)
            url.isBlank() || url == "#" -> Unit
            else -> runCatching { uriHandler.openUri(url) }
        }
    }
    val crumb = page?.folderId?.let { folderPath(it, ui.folders) }?.ifEmpty { null } ?: "Notes"

    AinoFullPage(
        title = crumb,
        onBack = {
            viewModel.flush()
            onBack()
        },
        scrollable = false,
        actions = {
            SaveBadge(ui.save, ui.saveError)
            if (page != null) {
                IconButton(onClick = { if (onOpenHistory != null) onOpenHistory(pageId) else showHistory = true }) {
                    Icon(Icons.Outlined.History, "Version history", tint = colors.textSecondary)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "More options", tint = colors.textSecondary) }
                    EditorMenu(
                        expanded = menuOpen,
                        page = page,
                        onDismiss = { menuOpen = false },
                        onAction = { action ->
                            menuOpen = false
                            when (action) {
                                "pin" -> viewModel.togglePin(pageId)
                                "lock" -> viewModel.toggleReadOnly(pageId)
                                "duplicate" -> viewModel.duplicate(pageId)?.let(onOpenPage)
                                "archive" -> viewModel.toggleArchive(pageId)
                                "share" -> sheet = EditorSheet.Share
                                "icon" -> sheet = EditorSheet.Icon
                                "move" -> sheet = EditorSheet.Move
                                "subpage" -> onOpenPage(viewModel.createSubPage(pageId))
                                "delete" -> sheet = EditorSheet.Delete
                            }
                        },
                    )
                }
            }
        },
    ) {
        if (page == null || session == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    ui.error != null && !ui.loaded -> Text(ui.error.orEmpty(), color = colors.danger)
                    ui.loaded && page == null -> Text("This page no longer exists.", color = colors.textMuted)
                    else -> CircularProgressIndicator(color = colors.primary)
                }
            }
            return@AinoFullPage
        }
        val readOnly = page.readOnly
        val doc = session.doc
        val numbers = remember(doc.blocks) { listNumbers(doc.blocks) }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)) {
            if (page.coverColor.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().height(56.dp).background(parseCssColor(page.coverColor) ?: colors.bgSecondary))
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Breadcrumbs(page, ui.pages, onOpenPage)
                TitleRow(viewModel, page, readOnly) { sheet = EditorSheet.Icon }
                MetaRow(page, ui.folders, readOnly, onFolder = { sheet = EditorSheet.Move }, onAddTag = { sheet = EditorSheet.Tag }, onRemoveTag = { viewModel.removeTag(pageId, it) })
                if (readOnly) LockedBanner()
                Spacer(Modifier.height(8.dp))
                if (readOnly) {
                    ReadOnlyDocument(doc, onOpenPage, openUrl, viewModel)
                } else {
                    val single = doc.blocks.size == 1
                    doc.blocks.forEach { block ->
                        androidx.compose.runtime.key(block.id) {
                            EditorBlock(viewModel, session, block, numbers[block.id], if (single) "Start writing… or press / for commands" else null)
                        }
                    }
                    Spacer(
                        Modifier.fillMaxWidth().height(96.dp).clickable(remember { MutableInteractionSource() }, null) {
                            val last = session.doc.blocks.lastOrNull()
                            if (last != null && last.isEditable) session.requestFocus(last.id)
                            else session.insertBlocks(listOf(NoteBlock(type = BlockType.PARAGRAPH)), last?.id)
                        },
                    )
                }
                SubPagesSection(viewModel, page, ui.pages, onOpenPage)
                BacklinksSection(page, ui.pages, onOpenPage)
                LinkedItemsSection(viewModel, pageId, linksVersion, onOpenLink, onAdd = { sheet = EditorSheet.LinkEntity(it) })
                FooterStats(session, page, userId, ui.mentionableUsers)
            }
        }
        if (!readOnly && session.hasFocus) {
            EditorToolbar(
                viewModel = viewModel,
                session = session,
                pages = ui.pages,
                users = ui.mentionableUsers,
                onOpenPage = onOpenPage,
                onOpenUrl = openUrl,
                onSlash = { command -> runSlash(command, viewModel, session) { sheet = it } },
                onSheet = { sheet = it },
            )
        }
    }

    when (val s = sheet) {
        null -> Unit
        is EditorSheet.PageLink -> PageLinkPickerSheet(
            ui.pages.filter { it.id != pageId },
            onPick = { viewModel.insertPageLink(it, s.at); sheet = null },
            onCreate = { viewModel.createPageAndLink(it, s.at); sheet = null },
            onDismiss = { sheet = null },
        )
        EditorSheet.Reports -> ReportPickerSheet(viewModel, onSelect = { id ->
            sheet = null
            viewModel.newOneOnOne(id, page?.folderId, onOpenPage)
        }, onDismiss = { sheet = null })
        EditorSheet.Share -> page?.let { ShareNoteSheet(viewModel, it) { sheet = null } }
        EditorSheet.Icon -> page?.let { p -> IconPickerSheet(p, { icon, cover -> viewModel.setIcon(pageId, icon, cover) }) { sheet = null } }
        EditorSheet.Move -> MoveToFolderSheet(ui.folders, page?.folderId, { viewModel.moveToFolder(pageId, it); sheet = null }) { sheet = null }
        is EditorSheet.LinkEntity -> {
            var linked by remember { mutableStateOf<List<NoteLink>>(emptyList()) }
            LaunchedEffect(Unit) { linked = viewModel.links(pageId).getOrDefault(emptyList()) }
            LinkEntitySheet(viewModel, pageId, s.type, linked, onLinked = { linksVersion++; sheet = null }, onDismiss = { sheet = null })
        }
        EditorSheet.Insert -> InsertSheet(onPick = { command -> sheet = null; runSlash(command, viewModel, session ?: return@InsertSheet) { sheet = it } }) { sheet = null }
        EditorSheet.Tag -> TextPromptDialog("Add tag", "Tag name…", confirm = "Add", onConfirm = { viewModel.addTag(pageId, it); sheet = null }) { sheet = null }
        EditorSheet.Url -> {
            val existing = session?.linkAtSelection().orEmpty()
            TextPromptDialog(
                "Insert link", "https://…", initial = existing, confirm = "Apply", allowBlank = existing.isNotEmpty(),
                onConfirm = { session?.setLink(it.trim().ifEmpty { null }); sheet = null },
            ) { sheet = null }
        }
        EditorSheet.Delete -> ConfirmDialog(
            "Delete Page",
            "Are you sure you want to delete \"${page?.title ?: "this page"}\"? This action cannot be undone.",
            "Delete",
            onConfirm = {
                sheet = null
                // Leaving the screen happens via the page-removed effect above.
                viewModel.deletePage(pageId)
            },
            onDismiss = { sheet = null },
        )
    }
}

private fun runSlash(command: SlashCommand, viewModel: NotesViewModel, session: NoteEditorSession, openSheet: (EditorSheet) -> Unit) {
    val at = session.consumeTrigger() ?: session.focusedBlock()?.let { FocusTarget(it.id, session.selection(it.id).start) }
    when {
        command.id == "h1" -> session.setBlockType(BlockType.HEADING, 1, toggle = false)
        command.id == "h2" -> session.setBlockType(BlockType.HEADING, 2, toggle = false)
        command.id == "h3" -> session.setBlockType(BlockType.HEADING, 3, toggle = false)
        command.id == "p" -> session.setBlockType(BlockType.PARAGRAPH, toggle = false)
        command.id == "ul" -> session.setBlockType(BlockType.LIST_ITEM, listType = ListType.BULLET, toggle = false)
        command.id == "ol" -> session.setBlockType(BlockType.LIST_ITEM, listType = ListType.ORDERED, toggle = false)
        command.id == "todo" -> session.setBlockType(BlockType.LIST_ITEM, listType = ListType.UNCHECKED, toggle = false)
        command.id == "quote" -> session.setBlockType(BlockType.QUOTE, toggle = false)
        command.id == "code" -> session.setBlockType(BlockType.CODE, language = "plain", toggle = false)
        command.id.startsWith("code-") -> session.setBlockType(BlockType.CODE, language = command.id.removePrefix("code-"), toggle = false)
        command.id == "divider" -> session.insertBlocks(listOf(NoteBlock(type = BlockType.DIVIDER)), at?.blockId)
        command.id == "timestamp" -> viewModel.insertTimestamp()
        command.id == "today" -> viewModel.insertToday()
        command.id == "pagelink" -> openSheet(EditorSheet.PageLink(at))
        command.id == "toc" -> viewModel.insertToc()
        command.id == "sprint" -> session.insertBlocks(embedBlocks(EmbedKind.SPRINT), at?.blockId)
        command.id == "time" -> session.insertBlocks(embedBlocks(EmbedKind.TIME), at?.blockId)
        command.id == "promote-task" -> at?.let { viewModel.convertToTask(it.blockId) }
        command.id == "oneonone" -> openSheet(EditorSheet.Reports)
        command.id == "link-task" -> openSheet(EditorSheet.LinkEntity("task"))
        command.id == "link-event" -> openSheet(EditorSheet.LinkEntity("calendar_event"))
        command.id == "link-meeting" -> openSheet(EditorSheet.LinkEntity("meeting"))
    }
}

@Composable
private fun SaveBadge(status: SaveStatus, error: String?) {
    val colors = LocalWebColors.current
    val (text, tint) = when (status) {
        SaveStatus.Saved -> "Saved" to colors.success
        SaveStatus.Saving -> "Saving…" to colors.textMuted
        SaveStatus.Pending -> "Unsaved" to colors.textMuted
        SaveStatus.Failed -> (error ?: "Not saved") to colors.danger
        SaveStatus.Idle -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp).widthInMax(140)) {
        if (status == SaveStatus.Saved) Icon(Icons.Outlined.Check, null, tint = tint, modifier = Modifier.size(12.dp))
        Text(text, color = tint, fontSize = 0.72.rem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp))
    }
}

private fun Modifier.widthInMax(dp: Int) = this.then(Modifier.widthIn(max = dp.dp))

@Composable
private fun EditorMenu(expanded: Boolean, page: NotePage, onDismiss: () -> Unit, onAction: (String) -> Unit) {
    val colors = LocalWebColors.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = colors.bgElevated) {
        @Composable
        fun item(id: String, label: String, icon: ImageVector, danger: Boolean = false) = DropdownMenuItem(
            text = { Text(label, color = if (danger) colors.danger else colors.text) },
            leadingIcon = { Icon(icon, null, tint = if (danger) colors.danger else colors.textSecondary) },
            onClick = { onAction(id) },
        )
        item("pin", if (page.pinned) "Unpin" else "Pin to top", Icons.Outlined.PushPin)
        item("lock", if (page.readOnly) "Unlock for editing" else "Lock as read-only", if (page.readOnly) Icons.Outlined.LockOpen else Icons.Outlined.Lock)
        item("duplicate", "Duplicate", Icons.Outlined.ContentCopy)
        item("archive", if (page.archived) "Unarchive" else "Archive", if (page.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive)
        HorizontalDivider(color = colors.border)
        item("share", "Share page…", Icons.Outlined.Share)
        item("icon", "Change icon / cover", Icons.Outlined.EmojiEmotions)
        item("move", "Move to folder", Icons.Outlined.Folder)
        item("subpage", "Add sub-page", Icons.Outlined.SubdirectoryArrowRight)
        HorizontalDivider(color = colors.border)
        item("delete", "Delete", Icons.Outlined.Delete, danger = true)
    }
}

@Composable
private fun Breadcrumbs(page: NotePage, pages: List<NotePage>, onOpenPage: (String) -> Unit) {
    val ancestors = remember(page.id, pages) { pageAncestors(page.id, pages) }
    if (ancestors.isEmpty()) return
    val colors = LocalWebColors.current
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ancestors.forEach { a ->
            Text(a.title, color = colors.textSecondary, fontSize = 0.8.rem, modifier = Modifier.clickable { onOpenPage(a.id) }.padding(vertical = 4.dp))
            Text(" › ", color = colors.textMuted, fontSize = 0.8.rem)
        }
        Text(page.title, color = colors.text, fontSize = 0.8.rem, maxLines = 1)
    }
}

@Composable
private fun TitleRow(viewModel: NotesViewModel, page: NotePage, readOnly: Boolean, onIcon: () -> Unit) {
    val colors = LocalWebColors.current
    var title by remember(page.id) { mutableStateOf(page.rawTitle) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(page.id) {
        if (!readOnly && page.content.isEmpty() && page.rawTitle == "Untitled") {
            title = ""
            runCatching { focus.requestFocus() }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).background(colors.bgSecondary, RoundedCornerShape(10.dp)).clickable(enabled = !readOnly, onClick = onIcon),
            contentAlignment = Alignment.Center,
        ) { Text(page.icon.ifEmpty { "📝" }, fontSize = 1.4.rem) }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            val style = TextStyle(color = colors.text, fontSize = 1.6.rem, fontWeight = FontWeight.Bold)
            if (title.isEmpty()) Text("Page title…", style = style.copy(color = colors.textMuted))
            BasicTextField(
                value = title,
                onValueChange = { value ->
                    val clean = value.replace("\n", "")
                    title = clean
                    viewModel.setTitle(page.id, clean.ifEmpty { "Untitled" })
                },
                enabled = !readOnly,
                textStyle = style,
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetaRow(page: NotePage, folders: List<NoteFolder>, readOnly: Boolean, onFolder: () -> Unit, onAddTag: () -> Unit, onRemoveTag: (String) -> Unit) {
    val colors = LocalWebColors.current
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Chip(Icons.Outlined.Folder, folderPath(page.folderId, folders).ifEmpty { "No folder" }, onClick = if (readOnly) null else onFolder)
        page.tags.forEach { tag ->
            val tint = androidx.compose.ui.graphics.Color(tagColorArgb(tag))
            Row(
                Modifier.background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).padding(start = 10.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tag, color = tint, fontSize = 0.78.rem)
                Text("×", color = tint, fontSize = 0.9.rem, modifier = Modifier.padding(start = 4.dp).clickable { onRemoveTag(tag) }.padding(horizontal = 4.dp))
            }
        }
        if (!readOnly) Chip(null, "+ tag", onClick = onAddTag)
    }
}

@Composable
private fun Chip(icon: ImageVector?, text: String, onClick: (() -> Unit)?) {
    val colors = LocalWebColors.current
    Row(
        Modifier.background(colors.bgSecondary, RoundedCornerShape(12.dp)).border(1.dp, colors.border, RoundedCornerShape(12.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = colors.textMuted, modifier = Modifier.size(13.dp).padding(end = 2.dp)) }
        Text(text, color = colors.textSecondary, fontSize = 0.78.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun LockedBanner() {
    val colors = LocalWebColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp).background(colors.warning.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Lock, null, tint = colors.warning, modifier = Modifier.size(14.dp))
        Text("This page is locked. Choose Unlock for editing in the ⋮ menu to enable editing.", color = colors.text, fontSize = 0.8.rem, modifier = Modifier.padding(start = 8.dp))
    }
}

// ── Toolbar above the keyboard ─────────────────────────────────────────────

@Composable
private fun EditorToolbar(
    viewModel: NotesViewModel,
    session: NoteEditorSession,
    pages: List<NotePage>,
    users: List<MentionUser>,
    onOpenPage: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSlash: (SlashCommand) -> Unit,
    onSheet: (EditorSheet) -> Unit,
) {
    val colors = LocalWebColors.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val block = session.focusedBlock()
    val trigger = session.trigger
    Surface(color = colors.bgSecondary, shadowElevation = 6.dp) {
        Column {
            HorizontalDivider(color = colors.border)
            when (trigger?.kind) {
                TriggerKind.SLASH -> {
                    val commands = filterSlashCommands(trigger.query)
                    SuggestionList {
                        if (commands.isEmpty()) item { Text("No commands match \"${trigger.query}\"", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(12.dp)) }
                        items(commands, key = { it.id }) { c -> CommandRow(c) { onSlash(c) } }
                    }
                }
                TriggerKind.MENTION -> {
                    val matches = filterMentionUsers(users, trigger.query)
                    if (matches.isNotEmpty()) SuggestionList {
                        items(matches, key = { it.id }) { u ->
                            Row(
                                Modifier.fillMaxWidth().clickable { viewModel.insertMention(u) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                UserAvatar(u.display, u.avatar, 28.dp)
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text(u.display, color = colors.text, fontSize = 0.9.rem)
                                    u.username?.let { Text("@$it", color = colors.textMuted, fontSize = 0.75.rem) }
                                }
                            }
                        }
                    }
                }
                null -> ContextAction(session, pages, onOpenPage, onOpenUrl)
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.Outlined.Add, "Insert block") { onSheet(EditorSheet.Insert) }
                BlockStyleMenu(session)
                val inline = block != null && block.type != BlockType.CODE
                ToolButton(Icons.Outlined.FormatBold, "Bold", session.isMarkActive(MarkType.BOLD), inline) { session.toggleMark(MarkType.BOLD) }
                ToolButton(Icons.Outlined.FormatItalic, "Italic", session.isMarkActive(MarkType.ITALIC), inline) { session.toggleMark(MarkType.ITALIC) }
                ToolButton(Icons.Outlined.FormatUnderlined, "Underline", session.isMarkActive(MarkType.UNDERLINE), inline) { session.toggleMark(MarkType.UNDERLINE) }
                ToolButton(Icons.Outlined.FormatStrikethrough, "Strikethrough", session.isMarkActive(MarkType.STRIKE), inline) { session.toggleMark(MarkType.STRIKE) }
                ToolButton(Icons.Outlined.DataObject, "Inline code", session.isMarkActive(MarkType.CODE), inline) { session.toggleMark(MarkType.CODE) }
                ToolButton(Icons.Outlined.Link, "Insert link", session.isMarkActive(MarkType.LINK), inline) { onSheet(EditorSheet.Url) }
                ToolButton(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bullet list", block?.type == BlockType.LIST_ITEM && block.listType == ListType.BULLET) {
                    session.setBlockType(BlockType.LIST_ITEM, listType = ListType.BULLET)
                }
                ToolButton(Icons.Outlined.FormatListNumbered, "Numbered list", block?.type == BlockType.LIST_ITEM && block.listType == ListType.ORDERED) {
                    session.setBlockType(BlockType.LIST_ITEM, listType = ListType.ORDERED)
                }
                ToolButton(Icons.Outlined.CheckBox, "Checklist", block?.type == BlockType.LIST_ITEM && block.listType.isCheck) {
                    session.setBlockType(BlockType.LIST_ITEM, listType = ListType.UNCHECKED)
                }
                if (block?.type == BlockType.LIST_ITEM) {
                    ToolButton(Icons.AutoMirrored.Outlined.FormatIndentDecrease, "Outdent", enabled = block.indent > 0) { session.indent(-1) }
                    ToolButton(Icons.AutoMirrored.Outlined.FormatIndentIncrease, "Indent") { session.indent(1) }
                }
                ToolButton(Icons.Outlined.AlternateEmail, "Mention", enabled = inline) {
                    val b = session.focusedBlock() ?: return@ToolButton
                    val pos = session.selection(b.id).start
                    val before = b.content.text.getOrNull(pos - 1)
                    session.insertText(if (before == null || before.isWhitespace()) "@" else " @")
                }
                ToolButton(Icons.Outlined.Description, "Link to page", enabled = inline) {
                    onSheet(EditorSheet.PageLink(session.focusedBlock()?.let { FocusTarget(it.id, session.selection(it.id).start) }))
                }
                ToolButton(Icons.Outlined.KeyboardHide, "Hide keyboard") {
                    keyboard?.hide()
                    focusManager.clearFocus()
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), content = content)
}

@Composable
private fun CommandRow(command: SlashCommand, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).background(colors.bgHover, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(command.icon, null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(command.label, color = colors.text, fontSize = 0.9.rem)
            Text(command.hint, color = colors.textMuted, fontSize = 0.72.rem)
        }
    }
}

/** "Open [[Page]]" / "Open link" when the caret sits on a page link or hyperlink. */
@Composable
private fun ContextAction(session: NoteEditorSession, pages: List<NotePage>, onOpenPage: (String) -> Unit, onOpenUrl: (String) -> Unit) {
    val colors = LocalWebColors.current
    val block = session.focusedBlock() ?: return
    val sel = session.selection(block.id)
    if (!sel.collapsed) return
    val text = block.content.text
    val pos = sel.start
    fun tokenAt(i: Int): InlineToken? =
        if (i in text.indices && text[i] == TOKEN_CHAR) block.content.tokens.getOrNull(text.substring(0, i).count { it == TOKEN_CHAR }) else null
    val pageToken = listOf(tokenAt(pos - 1), tokenAt(pos)).firstOrNull { it?.kind == TokenKind.PAGE_LINK }
    val href = block.content.markAt(pos, MarkType.LINK)?.href
    val (label, action) = when {
        pageToken != null -> {
            val id = pageToken.attrs["id"].orEmpty()
            val title = pages.firstOrNull { it.id == id }?.title ?: pageToken.label
            "Open “$title”" to { if (id.isNotEmpty()) onOpenPage(id) }
        }
        href != null -> "Open link" to { onOpenUrl(href) }
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, tint = colors.primary, modifier = Modifier.size(16.dp))
        Text(label, color = colors.primary, fontSize = 0.85.rem, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, active: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp)) {
        Box(
            Modifier.size(34.dp).background(if (active) colors.primary.copy(alpha = 0.16f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = when {
                !enabled -> colors.textMuted.copy(alpha = 0.5f)
                active -> colors.primary
                else -> colors.textSecondary
            }, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun BlockStyleMenu(session: NoteEditorSession) {
    val colors = LocalWebColors.current
    var open by remember { mutableStateOf(false) }
    val block = session.focusedBlock()
    Box {
        ToolButton(Icons.Outlined.Title, "Heading style", block?.type == BlockType.HEADING) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.bgElevated) {
            listOf(
                Triple("Text", BlockType.PARAGRAPH, 0), Triple("Heading 1", BlockType.HEADING, 1), Triple("Heading 2", BlockType.HEADING, 2),
                Triple("Heading 3", BlockType.HEADING, 3), Triple("Quote", BlockType.QUOTE, 0), Triple("Code block", BlockType.CODE, 0),
            ).forEach { (label, type, level) ->
                val selected = block?.type == type && (type != BlockType.HEADING || block.level == level)
                DropdownMenuItem(
                    text = { Text(label, color = if (selected) colors.primary else colors.text) },
                    onClick = {
                        open = false
                        session.setBlockType(type, level, toggle = false)
                    },
                )
            }
        }
    }
}

@Composable
private fun InsertSheet(onPick: (SlashCommand) -> Unit, onDismiss: () -> Unit) {
    NotesSheet(onDismiss) {
        SheetTitle("Insert block", icon = Icons.Outlined.Add)
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(SLASH_COMMANDS, key = { it.id }) { c -> CommandRow(c) { onPick(c) } }
        }
    }
}

// ── Panels below the page ──────────────────────────────────────────────────

@Composable
private fun SectionHeader(icon: ImageVector, title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
        Text(title, color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 0.82.rem, modifier = Modifier.padding(start = 6.dp).weight(1f))
        if (action != null && onAction != null) {
            Row(Modifier.clickable(onClick = onAction).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                Text(action, color = colors.primary, fontSize = 0.8.rem)
            }
        }
    }
}

@Composable
private fun PageRow(page: NotePage, subtitle: String?, trailing: String, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PageIcon(page, 16)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(page.title, color = colors.text, fontSize = 0.9.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = colors.textMuted, fontSize = 0.75.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(trailing, color = colors.textMuted, fontSize = 0.72.rem)
    }
}

@Composable
private fun SubPagesSection(viewModel: NotesViewModel, page: NotePage, pages: List<NotePage>, onOpenPage: (String) -> Unit) {
    val children = remember(page.id, pages) { childPages(page.id, pages) }
    SectionHeader(Icons.Outlined.SubdirectoryArrowRight, "Sub-pages" + if (children.isNotEmpty()) " (${children.size})" else "", "Add") {
        onOpenPage(viewModel.createSubPage(page.id))
    }
    if (children.isEmpty()) {
        Text("No sub-pages yet. Add one to organise related notes underneath this page.", color = LocalWebColors.current.textMuted, fontSize = 0.8.rem)
    } else {
        children.forEach { c -> PageRow(c, snippetOf(c.content, 80), formatNoteDate(c.updatedAt)) { onOpenPage(c.id) } }
    }
}

@Composable
private fun BacklinksSection(page: NotePage, pages: List<NotePage>, onOpenPage: (String) -> Unit) {
    val links = remember(page.id, pages) { backlinks(page.id, pages) }
    if (links.isEmpty()) return
    SectionHeader(Icons.Outlined.Link, "Linked from ${links.size} ${if (links.size == 1) "page" else "pages"}")
    links.forEach { p -> PageRow(p, null, formatNoteDate(p.updatedAt)) { onOpenPage(p.id) } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LinkedItemsSection(viewModel: NotesViewModel, pageId: String, version: Int, onOpenLink: (String) -> Unit, onAdd: (String) -> Unit) {
    val colors = LocalWebColors.current
    val scope = rememberCoroutineScope()
    var links by remember(pageId) { mutableStateOf<List<NoteLink>>(emptyList()) }
    var loading by remember(pageId) { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(true) }
    LaunchedEffect(pageId, version) {
        loading = true
        viewModel.links(pageId).onSuccess { links = it }
        loading = false
    }
    val visible = links.filter { it.detail != null }
    Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Link, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
        Text("Linked items", color = colors.textSecondary, fontWeight = FontWeight.SemiBold, fontSize = 0.82.rem, modifier = Modifier.padding(start = 6.dp))
        if (visible.isNotEmpty()) {
            Text("${visible.size}", color = colors.primary, fontSize = 0.7.rem, modifier = Modifier.padding(start = 6.dp).background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(if (expanded) "▾" else "▸", color = colors.textMuted)
    }
    if (!expanded) return
    if (loading && links.isEmpty()) Text("Loading…", color = colors.textMuted, fontSize = 0.8.rem)
    listOf("task" to "Tasks", "calendar_event" to "Events", "meeting" to "Meetings").forEach { (type, label) ->
        val group = visible.filter { it.entityType == type }
        if (group.isNotEmpty()) {
            Text(label, color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(top = 6.dp))
            group.forEach { link ->
                var menu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { linkRoute(link)?.let(onOpenLink) }, onLongClick = { menu = true }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(entityIcon(type), null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(link.detail?.str("title").orEmpty(), color = colors.text, fontSize = 0.88.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            entitySubtitle(type, link.detail!!).takeIf { it.isNotEmpty() }?.let { Text(it, color = colors.textMuted, fontSize = 0.72.rem) }
                        }
                        IconButton(onClick = {
                            scope.launch { viewModel.removeLink(pageId, type, link.entityId).onSuccess { links = links - link } }
                        }, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Unlink", tint = colors.textMuted, modifier = Modifier.size(14.dp)) }
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Remove link") }, onClick = {
                            menu = false
                            scope.launch { viewModel.removeLink(pageId, type, link.entityId).onSuccess { links = links - link } }
                        })
                    }
                }
            }
        }
    }
    if (!loading && visible.isEmpty()) Text("No linked items yet", color = colors.textMuted, fontSize = 0.8.rem)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("task" to "Task", "calendar_event" to "Event", "meeting" to "Meeting").forEach { (type, label) ->
            Row(
                Modifier.border(1.dp, colors.border, RoundedCornerShape(14.dp)).clickable { onAdd(type) }.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Add, null, tint = colors.textSecondary, modifier = Modifier.size(12.dp))
                Icon(entityIcon(type), null, tint = colors.textSecondary, modifier = Modifier.size(12.dp).padding(start = 2.dp))
                Text(label, color = colors.textSecondary, fontSize = 0.78.rem, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun FooterStats(session: NoteEditorSession, page: NotePage, userId: Long, users: List<MentionUser>) {
    val colors = LocalWebColors.current
    val doc = session.doc
    val (words, chars) = remember(doc) {
        val text = doc.blocks.joinToString("") {
            if (it.type == BlockType.OPAQUE) htmlToPlainText(it.source) else it.content.plainText()
        }.trim()
        if (text.isEmpty()) 0 to 0 else text.split(Regex("\\s+")).size to text.length
    }
    fun who(id: Long?): String = when {
        id == null -> ""
        id == userId -> " by you"
        else -> users.firstOrNull { it.id == id }?.fullName?.let { " by $it" }.orEmpty()
    }
    val parts = buildList {
        add("$words words · $chars chars")
        page.createdAt?.let { add("Created ${formatNoteDate(it)}${who(page.createdBy)}") }
        page.updatedAt?.let { add("Edited ${formatNoteDate(it)}${who(page.lastEditedBy)}") }
    }
    Text(parts.joinToString(" · "), color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(top = 24.dp, bottom = 16.dp))
}
