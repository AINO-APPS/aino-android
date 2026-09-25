package app.aino.mobile.feature.notes

import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.WebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

// ── Styling ────────────────────────────────────────────────────────────────

/** Quill snow sizes: h1 2em, h2 1.5em, h3 1.17em; body 1rem with 1.6 line height. */
internal fun blockTextStyle(block: NoteBlock, colors: WebColors): TextStyle {
    val base = TextStyle(fontSize = 1.rem, lineHeight = 25.6.sp, color = colors.text)
    return when (block.type) {
        BlockType.HEADING -> when (block.level) {
            1 -> base.copy(fontSize = 2.rem, lineHeight = 2.5.rem, fontWeight = FontWeight.Bold)
            2 -> base.copy(fontSize = 1.5.rem, lineHeight = 2.rem, fontWeight = FontWeight.Bold)
            3 -> base.copy(fontSize = 1.17.rem, lineHeight = 1.6.rem, fontWeight = FontWeight.Bold)
            else -> base.copy(fontWeight = FontWeight.Bold)
        }
        BlockType.QUOTE -> base.copy(color = colors.textSecondary)
        BlockType.CODE -> base.copy(fontFamily = FontFamily.Monospace, fontSize = 0.85.rem, lineHeight = 1.3.rem)
        BlockType.LIST_ITEM -> if (block.listType == ListType.CHECKED) base.copy(color = colors.textMuted) else base
        else -> base
    }
}

/** Parses `#rgb`, `#rrggbb`, `rgb(r, g, b)` and `rgba(...)`. */
internal fun parseCssColor(value: String?): Color? {
    val v = value?.trim()?.lowercase() ?: return null
    return runCatching {
        when {
            v.startsWith("#") && v.length == 4 -> Color(("ff" + v.substring(1).map { "$it$it" }.joinToString("")).toLong(16))
            v.startsWith("#") && v.length == 7 -> Color(("ff" + v.substring(1)).toLong(16))
            v.startsWith("rgb") -> {
                val parts = v.substringAfter('(').substringBefore(')').split(',').map { it.trim() }
                Color(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), ((parts.getOrNull(3)?.toFloat() ?: 1f) * 255).toInt())
            }
            else -> null
        }
    }.getOrNull()
}

private fun tokenDisplay(token: InlineToken?): String = when (token?.kind) {
    null -> ""
    TokenKind.PAGE_LINK -> "[[${token.label}]]"
    TokenKind.BREAK -> "\n"
    TokenKind.IMAGE -> "🖼 Image"
    else -> token.label
}

private fun markStyle(marks: List<Mark>, colors: WebColors): SpanStyle {
    var style = SpanStyle()
    val decorations = mutableListOf<TextDecoration>()
    for (m in marks) {
        when (m.type) {
            MarkType.BOLD -> style = style.copy(fontWeight = FontWeight.Bold)
            MarkType.ITALIC -> style = style.copy(fontStyle = FontStyle.Italic)
            MarkType.UNDERLINE -> decorations += TextDecoration.Underline
            MarkType.STRIKE -> decorations += TextDecoration.LineThrough
            MarkType.CODE -> style = style.copy(fontFamily = FontFamily.Monospace, background = colors.bgHover, color = colors.danger)
            MarkType.LINK -> {
                style = style.copy(color = colors.primary)
                decorations += TextDecoration.Underline
            }
            MarkType.OTHER -> Unit
        }
        parseCssColor(m.color)?.let { style = style.copy(color = it) }
        parseCssColor(m.background)?.let { style = style.copy(background = it) }
    }
    if (decorations.isNotEmpty()) style = style.copy(textDecoration = TextDecoration.combine(decorations))
    return style
}

private fun tokenStyle(token: InlineToken?, colors: WebColors): SpanStyle = when (token?.kind) {
    TokenKind.MENTION -> SpanStyle(color = colors.primary, fontWeight = FontWeight.SemiBold, background = colors.primary.copy(alpha = 0.12f))
    TokenKind.PAGE_LINK -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium, background = colors.primary.copy(alpha = 0.10f))
    TokenKind.DATE -> SpanStyle(color = colors.primary, fontWeight = FontWeight.Medium, background = colors.primary.copy(alpha = 0.10f))
    TokenKind.IMAGE, TokenKind.OTHER -> SpanStyle(color = colors.textMuted, fontStyle = FontStyle.Italic)
    else -> SpanStyle()
}

private class Rendered(val text: AnnotatedString, val o2t: IntArray, val t2o: IntArray)

/**
 * Builds the display string for [raw] (the model text, optionally prefixed
 * by [FIELD_SENTINEL]): token placeholders expand to their labels and marks
 * become span styles. Offsets map both ways so the caret never lands inside a
 * token label.
 */
private fun render(
    raw: String,
    offset: Int,
    content: InlineContent,
    colors: WebColors,
    links: ((InlineToken?, Mark?) -> LinkAnnotation.Clickable?)? = null,
): Rendered {
    val out = StringBuilder()
    val o2t = IntArray(raw.length + 1)
    val t2o = ArrayList<Int>(raw.length + 1)
    val tokenRanges = mutableListOf<Triple<Int, Int, InlineToken?>>()
    var tokenIndex = 0
    for (i in raw.indices) {
        o2t[i] = out.length
        val c = raw[i]
        if (i >= offset && c == TOKEN_CHAR) {
            val token = content.tokens.getOrNull(tokenIndex++)
            val label = tokenDisplay(token)
            val start = out.length
            out.append(label)
            repeat(label.length) { k -> t2o.add(if (k == 0) i else i + 1) }
            tokenRanges += Triple(start, out.length, token)
        } else {
            out.append(c)
            t2o.add(i)
        }
    }
    o2t[raw.length] = out.length
    t2o.add(raw.length)
    val builder = AnnotatedString.Builder(out.toString())
    val len = content.text.length
    val marks = content.marks.filter { it.start in 0..len && it.end in 0..len && it.end > it.start }
    val bounds = sortedSetOf(0, len).apply { marks.forEach { add(it.start); add(it.end) } }.toList()
    for (k in 0 until bounds.size - 1) {
        val a = bounds[k]
        val b = bounds[k + 1]
        val active = marks.filter { it.start <= a && it.end >= b }
        if (active.isEmpty()) continue
        val s = o2t[a + offset]
        val e = o2t[b + offset]
        if (e > s) {
            builder.addStyle(markStyle(active, colors), s, e)
            links?.invoke(null, active.firstOrNull { it.type == MarkType.LINK })?.let { builder.addLink(it, s, e) }
        }
    }
    tokenRanges.forEach { (s, e, token) ->
        if (e > s) {
            builder.addStyle(tokenStyle(token, colors), s, e)
            links?.invoke(token, null)?.let { builder.addLink(it, s, e) }
        }
    }
    return Rendered(builder.toAnnotatedString(), o2t, t2o.toIntArray())
}

private class NoteVisualTransformation(private val content: InlineContent, private val colors: WebColors) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val offset = if (raw.startsWith(FIELD_SENTINEL)) 1 else 0
        if (raw.length - offset != content.text.length) return TransformedText(text, OffsetMapping.Identity)
        val r = render(raw, offset, content, colors)
        val tLen = r.text.length
        return TransformedText(
            r.text,
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = r.o2t[offset.coerceIn(0, raw.length)].coerceIn(0, tLen)
                override fun transformedToOriginal(offset: Int): Int = r.t2o[offset.coerceIn(0, r.t2o.size - 1)].coerceIn(0, raw.length)
            },
        )
    }
}

// ── Editable blocks ────────────────────────────────────────────────────────

@Composable
private fun BlockField(session: NoteEditorSession, block: NoteBlock, placeholder: String?, modifier: Modifier = Modifier) {
    val colors = LocalWebColors.current
    val focusRequester = remember { FocusRequester() }
    val style = blockTextStyle(block, colors)
    val transformation = remember(block.content, colors) {
        if (block.type == BlockType.CODE) VisualTransformation.None else NoteVisualTransformation(block.content, colors)
    }
    val request = session.focusRequest
    LaunchedEffect(request) {
        if (request != null && request.blockId == block.id) {
            session.consumeFocusRequest(request)
            runCatching { focusRequester.requestFocus() }
        }
    }
    Box(modifier) {
        if (block.content.isEmpty && placeholder != null) Text(placeholder, style = style.copy(color = colors.textMuted))
        BasicTextField(
            value = session.fieldValue(block),
            onValueChange = { session.onValueChange(block.id, it) },
            textStyle = style,
            cursorBrush = SolidColor(colors.primary),
            visualTransformation = transformation,
            keyboardOptions = KeyboardOptions(
                capitalization = if (block.type == BlockType.CODE) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                autoCorrectEnabled = block.type != BlockType.CODE,
            ),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { session.onFocusChanged(block.id, it.isFocused) },
        )
    }
}

/** Ordered-list numbers: consecutive ordered items per indent level, reset by other blocks. */
internal fun listNumbers(blocks: List<NoteBlock>): Map<Long, Int> {
    val result = HashMap<Long, Int>()
    val counters = IntArray(16)
    for (b in blocks) {
        if (b.type != BlockType.LIST_ITEM) {
            counters.fill(0)
            continue
        }
        val d = b.indent.coerceIn(0, 15)
        for (k in d + 1 until counters.size) counters[k] = 0
        if (b.listType == ListType.ORDERED) {
            counters[d]++
            result[b.id] = counters[d]
        } else {
            counters[d] = 0
        }
    }
    return result
}

private fun bulletFor(indent: Int) = when (indent % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

@Composable
private fun ListMarker(block: NoteBlock, number: Int?, enabled: Boolean, onToggle: () -> Unit) {
    val colors = LocalWebColors.current
    Box(Modifier.width(28.dp).padding(top = 1.dp), contentAlignment = Alignment.TopCenter) {
        when {
            block.listType.isCheck -> Icon(
                if (block.listType == ListType.CHECKED) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = if (block.listType == ListType.CHECKED) "Checked" else "Unchecked",
                tint = if (block.listType == ListType.CHECKED) colors.primary else colors.textMuted,
                modifier = Modifier.size(22.dp).let { if (enabled) it.clickable(onClick = onToggle) else it },
            )
            block.listType == ListType.ORDERED -> Text("${number ?: 1}.", color = colors.textSecondary, fontSize = 1.rem, lineHeight = 25.6.sp)
            else -> Text(bulletFor(block.indent), color = colors.textSecondary, fontSize = 1.rem, lineHeight = 25.6.sp)
        }
    }
}

/** One block of the editor (editable, or a preserved read-only card). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorBlock(
    viewModel: NotesViewModel,
    session: NoteEditorSession,
    block: NoteBlock,
    number: Int?,
    placeholder: String?,
) {
    val colors = LocalWebColors.current
    var menu by remember { mutableStateOf(false) }
    val removable = Modifier.combinedClickable(onClick = {}, onLongClick = { menu = true })
    Box {
        when {
            block.embed == EmbedKind.SPRINT -> SprintEmbedCard(viewModel, removable)
            block.embed == EmbedKind.TIME -> TimeTrackingCard(viewModel, removable)
            block.type == BlockType.DIVIDER -> Box(removable.fillMaxWidth().padding(vertical = 12.dp)) { HorizontalDivider(color = colors.border) }
            block.type == BlockType.OPAQUE -> OpaqueBlockCard(block, removable)
            block.type == BlockType.LIST_ITEM -> Row(Modifier.fillMaxWidth().padding(start = (block.indent * 24).dp, top = 2.dp, bottom = 2.dp)) {
                ListMarker(block, number, enabled = true) { session.toggleCheck(block.id) }
                BlockField(session, block, null, Modifier.weight(1f))
            }
            block.type == BlockType.QUOTE -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(colors.border, RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(12.dp))
                BlockField(session, block, null, Modifier.weight(1f))
            }
            block.type == BlockType.CODE -> Column(
                Modifier.fillMaxWidth().padding(vertical = 6.dp).background(colors.bgSecondary, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                Text(block.language, color = colors.textMuted, fontSize = 0.7.rem, modifier = Modifier.padding(bottom = 4.dp))
                BlockField(session, block, null)
            }
            block.type == BlockType.HEADING -> BlockField(session, block, null, Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp))
            else -> BlockField(session, block, placeholder, Modifier.fillMaxWidth().padding(vertical = 3.dp))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Remove block", color = colors.danger) },
                onClick = {
                    menu = false
                    if (block.embed != null) session.removeEmbed(block.id) else session.removeBlock(block.id)
                },
            )
        }
    }
}

// ── Read-only rendering (locked pages, version previews) ──────────────────

@Composable
internal fun ReadOnlyBlock(
    block: NoteBlock,
    number: Int?,
    onOpenPage: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: NotesViewModel? = null,
) {
    val colors = LocalWebColors.current
    val style = blockTextStyle(block, colors)
    val text = remember(block.content, colors) {
        render(block.content.text, 0, block.content, colors) { token, mark ->
            when {
                token?.kind == TokenKind.PAGE_LINK -> token.attrs["id"]?.takeIf { it.isNotEmpty() }?.let { id ->
                    LinkAnnotation.Clickable("page:$id") { onOpenPage(id) }
                }
                mark?.href != null -> LinkAnnotation.Clickable("url:${mark.href}") { onOpenUrl(mark.href) }
                else -> null
            }
        }.text
    }
    when {
        block.embed == EmbedKind.SPRINT && viewModel != null -> SprintEmbedCard(viewModel, Modifier)
        block.embed == EmbedKind.TIME && viewModel != null -> TimeTrackingCard(viewModel, Modifier)
        block.type == BlockType.DIVIDER -> HorizontalDivider(color = colors.border, modifier = Modifier.padding(vertical = 12.dp))
        block.type == BlockType.OPAQUE -> OpaqueBlockCard(block, Modifier)
        block.type == BlockType.LIST_ITEM -> Row(Modifier.fillMaxWidth().padding(start = (block.indent * 24).dp, top = 2.dp, bottom = 2.dp)) {
            ListMarker(block, number, enabled = false) {}
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
        block.type == BlockType.QUOTE -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(colors.border, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(12.dp))
            Text(text, style = style, modifier = Modifier.weight(1f))
        }
        block.type == BlockType.CODE -> Column(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).background(colors.bgSecondary, RoundedCornerShape(8.dp)).padding(12.dp),
        ) {
            Text(block.language, color = colors.textMuted, fontSize = 0.7.rem)
            Text(block.content.text, style = style)
        }
        block.type == BlockType.HEADING -> Text(text, style = style, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
        else -> if (block.content.isEmpty) Spacer(Modifier.height(25.dp)) else Text(text, style = style, modifier = Modifier.padding(vertical = 3.dp))
    }
}

/** Whole document read-only (history previews, locked pages). */
@Composable
internal fun ReadOnlyDocument(
    doc: NoteDoc,
    onOpenPage: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    viewModel: NotesViewModel? = null,
) {
    val numbers = remember(doc) { listNumbers(doc.blocks) }
    Column(Modifier.fillMaxWidth()) {
        doc.blocks.forEach { ReadOnlyBlock(it, numbers[it.id], onOpenPage, onOpenUrl, viewModel) }
    }
}

// ── Preserved blocks ───────────────────────────────────────────────────────

private data class TableModel(val rows: List<List<Pair<String, Boolean>>>)

private fun tableModel(source: String): TableModel {
    val root = parseHtml(source).filterIsInstance<HElement>().firstOrNull() ?: return TableModel(emptyList())
    val rows = (sequenceOf(root) + root.descendants()).filter { it.name == "tr" }.map { tr ->
        tr.elements().filter { it.name == "td" || it.name == "th" }.map { it.textContent().replace('\u00A0', ' ').trim() to (it.name == "th") }
    }.toList()
    return TableModel(rows)
}

private fun imageSources(source: String): List<String> {
    val nodes = parseHtml(source)
    val out = mutableListOf<String>()
    fun walk(list: List<HNode>) {
        for (n in list) if (n is HElement) {
            if (n.name == "img") n.attr("src")?.let(out::add)
            walk(n.children)
        }
    }
    walk(nodes)
    return out
}

private fun decodeDataUri(src: String): ByteArray? = runCatching {
    if (!src.startsWith("data:") || !src.contains(";base64,")) return null
    Base64.decode(src.substringAfter(";base64,"), Base64.DEFAULT)
}.getOrNull()

@Composable
private fun NoteImage(src: String) {
    val context = LocalContext.current
    val model: Any = remember(src) { decodeDataUri(src) ?: resolveServerMediaUrl(src) }
    val request = remember(model) { ImageRequest.Builder(context).data(model).build() }
    AsyncImage(
        model = request,
        imageLoader = AppContainer.get(context).imageLoader,
        contentDescription = "Image",
        contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(vertical = 6.dp),
    )
}

@Composable
internal fun OpaqueBlockCard(block: NoteBlock, modifier: Modifier) {
    val colors = LocalWebColors.current
    val source = block.source.orEmpty()
    val element = remember(source) { parseHtml(source).filterIsInstance<HElement>().firstOrNull() }
    val text = remember(source) { element?.textContent()?.replace('\u00A0', ' ')?.replace("\uFEFF", "")?.trim() ?: htmlToPlainText(source).trim() }
    when (block.opaqueKind) {
        OpaqueKind.IMAGE -> Column(modifier.fillMaxWidth()) { remember(source) { imageSources(source) }.forEach { NoteImage(it) } }
        OpaqueKind.CALLOUT -> {
            val variant = element?.attr("data-callout") ?: "info"
            val accent = when (variant) {
                "warn" -> colors.warning
                "success" -> colors.success
                "tip" -> colors.warning
                else -> colors.primary
            }
            Row(
                modifier.fillMaxWidth().padding(vertical = 6.dp).background(accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                Text(
                    when (variant) {
                        "warn" -> "⚠️"
                        "success" -> "✅"
                        "tip" -> "💡"
                        else -> "ℹ️"
                    },
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(text, color = colors.text, fontSize = 1.rem, lineHeight = 25.6.sp)
            }
        }
        OpaqueKind.TABLE -> {
            val table = remember(source) { tableModel(source) }
            Column(
                modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState())
                    .border(1.dp, colors.border, RoundedCornerShape(6.dp)),
            ) {
                table.rows.forEach { row ->
                    Row {
                        row.forEach { (cell, header) ->
                            Text(
                                cell, color = colors.text, fontSize = 0.9.rem,
                                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.widthIn(min = 96.dp, max = 220.dp)
                                    .background(if (header) colors.bgSecondary else Color.Transparent)
                                    .border(0.5.dp, colors.border).padding(8.dp),
                            )
                        }
                    }
                }
            }
            ReadOnlyHint(Modifier)
        }
        else -> {
            val (icon, label) = when (block.opaqueKind) {
                OpaqueKind.TOGGLE -> "▸" to "Toggle block"
                OpaqueKind.DRAWIO -> "📐" to "Draw.io diagram"
                OpaqueKind.MATH -> "∑" to "Math (LaTeX)"
                OpaqueKind.AUDIO -> "🎙️" to (element?.attr("data-label")?.ifBlank { null } ?: "Recording")
                OpaqueKind.VIDEO -> "🎬" to "Embedded video"
                else -> "▦" to "Unsupported block"
            }
            val detail = when (block.opaqueKind) {
                OpaqueKind.MATH -> element?.attr("data-tex").orEmpty()
                OpaqueKind.DRAWIO, OpaqueKind.AUDIO -> ""
                OpaqueKind.VIDEO -> element?.attr("src").orEmpty()
                else -> text
            }
            Column(
                modifier.fillMaxWidth().padding(vertical = 6.dp).background(colors.bgSecondary, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(icon, modifier = Modifier.padding(end = 8.dp))
                    Text(label, color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem)
                }
                if (detail.isNotBlank()) {
                    Text(
                        detail, color = colors.textSecondary, fontSize = 0.85.rem, maxLines = 4, overflow = TextOverflow.Ellipsis,
                        fontFamily = if (block.opaqueKind == OpaqueKind.MATH) FontFamily.Monospace else null,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ReadOnlyHint(Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ReadOnlyHint(modifier: Modifier) {
    val colors = LocalWebColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
        Text("Edit this block on the web", color = colors.textMuted, fontSize = 0.72.rem, modifier = Modifier.padding(start = 4.dp))
    }
}

// ── Live embeds (SprintEmbedBlock / TimeTrackingBlock) ────────────────────

private val STATUS_LABELS = linkedMapOf("pending" to "To Do", "in_progress" to "In Progress", "in_review" to "In Review", "done" to "Done")
private val STATUS_COLORS = mapOf(
    "pending" to Color(0xFF94A3B8), "in_progress" to Color(0xFF3B82F6), "in_review" to Color(0xFFF59E0B), "done" to Color(0xFF10B981),
)

@Composable
private fun EmbedCard(modifier: Modifier, content: @Composable () -> Unit) {
    val colors = LocalWebColors.current
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp).background(colors.bgElevated, RoundedCornerShape(10.dp))
            .border(1.dp, colors.border, RoundedCornerShape(10.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
internal fun SprintEmbedCard(viewModel: NotesViewModel, modifier: Modifier) {
    val colors = LocalWebColors.current
    val state by produceState<Result<SprintEmbedData>?>(null) { value = viewModel.sprintEmbed() }
    var collapsed by rememberSaveable { mutableStateOf(false) }
    EmbedCard(modifier) {
        val data = state?.getOrNull()
        when {
            state == null -> Text("Loading sprint data…", color = colors.textMuted, fontSize = 0.85.rem)
            data?.sprint == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RocketLaunch, null, tint = colors.textMuted, modifier = Modifier.size(16.dp))
                Text("No active sprint", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(start = 6.dp))
            }
            else -> {
                val sprint = data.sprint
                val stats = data.stats
                val total = stats?.long("total") ?: data.tasks.size.toLong()
                val done = stats?.long("done") ?: 0
                val inProgress = stats?.long("inProgress") ?: 0
                val inReview = stats?.long("inReview") ?: 0
                val pending = stats?.long("pending") ?: 0
                val pct = if (total > 0) Math.round(done * 100.0 / total) else 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.RocketLaunch, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    Text(sprint.str("name").orEmpty(), color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem,
                        modifier = Modifier.padding(start = 6.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${sprint.str("start_date").orEmpty().take(10)} → ${sprint.str("end_date").orEmpty().take(10)}", color = colors.textMuted, fontSize = 0.72.rem)
                }
                sprint.str("goal")?.takeIf { it.isNotBlank() }?.let { Text("🎯 $it", color = colors.textSecondary, fontSize = 0.82.rem) }
                val t = if (total > 0) total.toFloat() else 1f
                Row(Modifier.fillMaxWidth().height(8.dp).background(colors.bgHover, CircleShape)) {
                    listOf("done" to done, "in_review" to inReview, "in_progress" to inProgress, "pending" to pending).forEach { (key, n) ->
                        if (n > 0) Box(Modifier.weight(n / t).height(8.dp).background(STATUS_COLORS.getValue(key)))
                    }
                    val rest = 1f - (done + inReview + inProgress + pending) / t
                    if (rest > 0.001f) Spacer(Modifier.weight(rest))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatChip(Icons.Outlined.CheckCircle, "$done/$total done ($pct%)", colors.textSecondary)
                    StatChip(Icons.Outlined.Schedule, "$inProgress in progress", STATUS_COLORS.getValue("in_progress"))
                    StatChip(Icons.Outlined.ErrorOutline, "$inReview in review", STATUS_COLORS.getValue("in_review"))
                }
                Text(
                    if (collapsed) "Show tasks ▸" else "Hide tasks ▾",
                    color = colors.primary, fontSize = 0.78.rem,
                    modifier = Modifier.clickable { collapsed = !collapsed }.padding(vertical = 2.dp),
                )
                if (!collapsed) {
                    STATUS_LABELS.forEach { (status, label) ->
                        val group = data.tasks.filter { it.str("status") == status }
                        if (group.isNotEmpty()) {
                            Text("$label (${group.size})", color = STATUS_COLORS.getValue(status), fontWeight = FontWeight.SemiBold, fontSize = 0.75.rem)
                            group.forEach { task ->
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
                                    Box(Modifier.size(6.dp).background(STATUS_COLORS.getValue(status), CircleShape))
                                    Text(task.str("title").orEmpty(), color = colors.text, fontSize = 0.82.rem, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 8.dp).weight(1f))
                                    task.str("assignee_name")?.let { Text(it, color = colors.textMuted, fontSize = 0.72.rem) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(11.dp))
        Text(text, color = tint, fontSize = 0.72.rem, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
internal fun TimeTrackingCard(viewModel: NotesViewModel, modifier: Modifier) {
    val colors = LocalWebColors.current
    val state by produceState<Result<TimeSummary>?>(null) { value = viewModel.timeSummary() }
    val clock = remember { NotesClock() }
    EmbedCard(modifier) {
        val data = state?.getOrNull()
        when {
            state == null -> Text("Loading time data…", color = colors.textMuted, fontSize = 0.85.rem)
            data == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
                Text("No time data for today", color = colors.textMuted, fontSize = 0.85.rem, modifier = Modifier.padding(start = 6.dp))
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = colors.primary, modifier = Modifier.size(14.dp))
                    Text("Today's Time", color = colors.text, fontWeight = FontWeight.SemiBold, fontSize = 0.9.rem, modifier = Modifier.padding(start = 6.dp).weight(1f))
                    if (data.isActive) Badge(Icons.Outlined.PlayArrow, "Active", colors.success)
                    else if (data.lastClockOut != null) Badge(Icons.Outlined.Stop, "Done", colors.textMuted)
                }
                Row(Modifier.fillMaxWidth()) {
                    Metric("${jsNumber(data.hoursWorked)}h", "Worked", Modifier.weight(1f))
                    Metric("${jsNumber(data.breakHours)}h", "Break", Modifier.weight(1f), Icons.Outlined.LocalCafe)
                    Metric(data.firstClockIn?.let(clock::time) ?: "—", "Clock In", Modifier.weight(1f))
                    Metric(data.lastClockOut?.let(clock::time) ?: "—", "Clock Out", Modifier.weight(1f))
                }
                data.workMode?.let { mode ->
                    val label = when (mode) {
                        "office" -> "🏢 Office"
                        "remote" -> "🏠 Remote"
                        "hybrid" -> "🔄 Hybrid"
                        else -> mode
                    }
                    StatChip(Icons.Outlined.Place, label, colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(
        Modifier.background(tint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(9.dp))
        Text(text, color = tint, fontSize = 0.68.rem, modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
private fun Metric(value: String, label: String, modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    val colors = LocalWebColors.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.text, fontWeight = FontWeight.Bold, fontSize = 0.95.rem)
        Row(verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, tint = colors.textMuted, modifier = Modifier.size(10.dp)) }
            Text(label, color = colors.textMuted, fontSize = 0.68.rem)
        }
    }
}
