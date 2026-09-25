package app.aino.mobile.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class DiffType { EQ, ADD, DEL }
data class DiffLine(val type: DiffType, val text: String)

/** One line per block (web `stripHtml` joins blocks, which makes every diff a single line). */
internal fun noteLines(html: String): List<String> =
    parseNoteDoc(html).blocks.map { b ->
        when (b.type) {
            BlockType.OPAQUE -> htmlToPlainText(b.source).replace('\u00A0', ' ').trim()
            BlockType.DIVIDER -> "———"
            else -> b.content.plainText().replace('\u00A0', ' ')
        }
    }.let { if (it.size == 1 && it[0].isEmpty()) emptyList() else it }

/** `lineDiff` (LCS) from notesUtils. */
fun lineDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) for (j in n - 1 downTo 0) {
        dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < m && j < n) {
        when {
            oldLines[i] == newLines[j] -> { out += DiffLine(DiffType.EQ, oldLines[i]); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { out += DiffLine(DiffType.DEL, oldLines[i]); i++ }
            else -> { out += DiffLine(DiffType.ADD, newLines[j]); j++ }
        }
    }
    while (i < m) out += DiffLine(DiffType.DEL, oldLines[i++])
    while (j < n) out += DiffLine(DiffType.ADD, newLines[j++])
    return out
}

private fun fmtDate(iso: String?): String {
    val millis = parseIsoMillis(iso) ?: return ""
    return DateTimeFormatter.ofPattern("MMM d, yyyy, hh:mm a", Locale.getDefault()).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/**
 * Version history (VersionHistory.tsx) as a full-screen route
 * (suggested `notes/history/{pageId}`); also opened in-place by the editor.
 */
@Composable
fun NoteHistoryScreen(viewModel: NotesViewModel, pageId: String, onBack: () -> Unit) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = LocalWebColors.current
    val uriHandler = LocalUriHandler.current
    val page = ui.page(pageId)
    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<HistorySnapshot?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var diffMode by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf(false) }
    LaunchedEffect(pageId) {
        viewModel.history(pageId).onSuccess { rows = it }.onFailure { error = "Could not load history." }
        loading = false
    }
    LaunchedEffect(selectedId) {
        val id = selectedId ?: return@LaunchedEffect
        previewLoading = true
        preview = viewModel.snapshot(id).getOrNull()
        previewLoading = false
    }
    val showingPreview = selectedId != null
    if (showingPreview) BackHandler { selectedId = null; preview = null }
    AinoFullPage(
        title = "Version History",
        onBack = { if (showingPreview) { selectedId = null; preview = null } else onBack() },
        scrollable = false,
    ) {
        if (!showingPreview) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item {
                    Text(
                        "Saved versions of ${page?.title ?: "this page"}",
                        color = colors.textSecondary, fontSize = 0.85.rem, modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
                when {
                    loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.primary) } }
                    error != null -> item { Text(error.orEmpty(), color = colors.textMuted) }
                    rows.isEmpty() -> item {
                        Text("No versions saved yet — changes are recorded automatically every time you save.", color = colors.textMuted, fontSize = 0.85.rem)
                    }
                }
                itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
                    Column(Modifier.fillMaxWidth().clickable { selectedId = row.id }.padding(vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtDate(row.savedAt), color = colors.text, fontSize = 0.9.rem, fontWeight = FontWeight.Medium)
                            if (index == 0) {
                                Text(
                                    "Latest", color = colors.primary, fontSize = 0.68.rem,
                                    modifier = Modifier.padding(start = 8.dp).background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                )
                            }
                        }
                        Text(row.pageTitle ?: "Untitled", color = colors.textMuted, fontSize = 0.8.rem)
                    }
                    HorizontalDivider(color = colors.border)
                }
            }
            return@AinoFullPage
        }
        val snap = preview
        if (previewLoading || snap == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (previewLoading) CircularProgressIndicator(color = colors.primary)
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Description, null, tint = colors.textMuted, modifier = Modifier.size(36.dp))
                    Text("Select a version to preview it", color = colors.textMuted)
                }
            }
            return@AinoFullPage
        }
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(snap.pageTitle ?: "Untitled", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 1.05.rem)
            Text(fmtDate(snap.savedAt), color = colors.textMuted, fontSize = 0.78.rem)
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.border(1.dp, colors.border, RoundedCornerShape(8.dp))) {
                    ModeButton(Icons.Outlined.Description, "Preview", !diffMode) { diffMode = false }
                    ModeButton(Icons.Outlined.AccountTree, "Diff vs current", diffMode) { diffMode = true }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        restoring = true
                        viewModel.restoreSnapshot(pageId, snap.content, snap.pageTitle)
                        onBack()
                    },
                    enabled = !restoring,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.primary),
                ) {
                    Icon(Icons.Outlined.Restore, null, modifier = Modifier.size(14.dp))
                    Text(if (restoring) "Restoring…" else "Restore", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        HorizontalDivider(color = colors.border)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!diffMode) {
                if (snap.content.isBlank()) Text("Empty page", color = colors.textMuted, fontStyle = FontStyle.Italic)
                else ReadOnlyDocument(remember(snap.id) { parseNoteDoc(snap.content) }, onOpenPage = {}, onOpenUrl = { runCatching { uriHandler.openUri(it) } })
            } else {
                val current = viewModel.editor?.takeIf { it.pageId == pageId }?.html ?: page?.content.orEmpty()
                val rowsDiff = remember(snap.id, current) { lineDiff(noteLines(snap.content), noteLines(current)) }
                if (rowsDiff.isEmpty() || rowsDiff.all { it.type == DiffType.EQ }) {
                    Text("No differences vs current content.", color = colors.textMuted)
                } else {
                    Column(Modifier.horizontalScroll(rememberScrollState())) {
                        rowsDiff.forEach { r ->
                            val (marker, bg, fg) = when (r.type) {
                                DiffType.ADD -> Triple("+", colors.success.copy(alpha = 0.14f), colors.success)
                                DiffType.DEL -> Triple("−", colors.danger.copy(alpha = 0.14f), colors.danger)
                                DiffType.EQ -> Triple(" ", Color.Transparent, colors.textSecondary)
                            }
                            Row(Modifier.background(bg).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(marker, color = fg, fontFamily = FontFamily.Monospace, fontSize = 0.8.rem, modifier = Modifier.padding(end = 8.dp))
                                Text(r.text.ifEmpty { " " }, color = fg, fontFamily = FontFamily.Monospace, fontSize = 0.8.rem)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = LocalWebColors.current
    Row(
        Modifier.background(if (active) colors.primary.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, null, tint = if (active) colors.primary else colors.textSecondary, modifier = Modifier.size(12.dp))
        Text(label, color = if (active) colors.primary else colors.textSecondary, fontSize = 0.75.rem)
    }
}
