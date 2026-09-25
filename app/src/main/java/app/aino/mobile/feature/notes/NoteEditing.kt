package app.aino.mobile.feature.notes

// ── Inline content operations ───────────────────────────────────────────────

private val MarkType.extendsAtEnd: Boolean get() = this != MarkType.LINK

private fun String.countTokens(from: Int, to: Int): Int {
    var n = 0
    for (i in from until to) if (this[i] == TOKEN_CHAR) n++
    return n
}

/**
 * Applies a plain-text edit coming from the text field. Marks are shifted
 * (typing inside or at the end of bold continues bold, like Quill), tokens
 * whose placeholder was deleted are dropped, and stray placeholder characters
 * pasted by the IME are stripped. [cursor] (post-edit caret) disambiguates
 * repeated characters. [pending] applies toolbar toggles made with a collapsed
 * selection to the inserted text.
 */
fun InlineContent.edit(
    newTextRaw: String,
    cursor: Int = -1,
    pending: Map<MarkType, Boolean> = emptyMap(),
): InlineContent {
    val oldText = text
    if (newTextRaw == oldText) return this
    val oldLen = oldText.length
    val newLen = newTextRaw.length
    var p = -1
    var oldEnd = 0
    var newEnd = 0
    val delta = newLen - oldLen
    if (delta > 0 && cursor in delta..newLen) {
        val ins = cursor - delta
        if (oldText.regionMatches(0, newTextRaw, 0, ins) && oldText.regionMatches(ins, newTextRaw, cursor, oldLen - ins)) {
            p = ins
            oldEnd = ins
            newEnd = cursor
        }
    }
    if (p < 0) {
        var prefix = 0
        val max = minOf(oldLen, newLen)
        while (prefix < max && oldText[prefix] == newTextRaw[prefix]) prefix++
        var suffix = 0
        while (suffix < max - prefix && oldText[oldLen - 1 - suffix] == newTextRaw[newLen - 1 - suffix]) suffix++
        p = prefix
        oldEnd = oldLen - suffix
        newEnd = newLen - suffix
    }
    val inserted = newTextRaw.substring(p, newEnd).replace(TOKEN_CHAR.toString(), "")
    val delLen = oldEnd - p
    val insLen = inserted.length
    val before = oldText.countTokens(0, p)
    val removed = oldText.countTokens(p, oldEnd)
    val newTokens = tokens.take(before) + tokens.drop(before + removed)
    val newMarks = marks.mapNotNull { m ->
        fun mapDel(x: Int) = when {
            x <= p -> x
            x >= oldEnd -> x - delLen
            else -> p
        }
        var s = mapDel(m.start)
        var e = mapDel(m.end)
        if (insLen > 0) {
            val inherit = when {
                delLen > 0 && m.start <= p && m.end >= oldEnd -> true
                s < p && p < e -> true
                e == p && s < p -> m.type.extendsAtEnd
                p == 0 && s == 0 && e > 0 -> m.type.extendsAtEnd
                else -> false
            }
            if (inherit) e += insLen
            else if (s >= p) {
                s += insLen
                e += insLen
            }
        }
        if (e <= s) null else m.copy(start = s, end = e)
    }
    var result = InlineContent(oldText.substring(0, p) + inserted + oldText.substring(oldEnd), newMarks, newTokens)
    if (insLen > 0) pending.forEach { (type, on) ->
        result = if (on) result.addMark(p, p + insLen, type) else result.removeMark(p, p + insLen, type)
    }
    return result
}

/** True when the whole range (or, collapsed, the character before the caret) has [type]. */
fun InlineContent.hasMark(start: Int, end: Int, type: MarkType): Boolean {
    val typed = marks.filter { it.type == type }
    if (start >= end) {
        val pos = start
        return typed.any { (it.start < pos && pos <= it.end) || (pos == 0 && it.start == 0 && it.end > 0) }
    }
    for (i in start until end) if (typed.none { it.start <= i && i < it.end }) return false
    return true
}

fun InlineContent.markAt(pos: Int, type: MarkType): Mark? =
    marks.firstOrNull { it.type == type && it.start <= pos && pos < it.end }
        ?: marks.firstOrNull { it.type == type && it.start < pos && pos <= it.end }

fun InlineContent.removeMark(start: Int, end: Int, type: MarkType): InlineContent {
    if (start >= end) return this
    val out = mutableListOf<Mark>()
    for (m in marks) {
        if (m.type != type || m.end <= start || m.start >= end) {
            out += m
            continue
        }
        if (m.start < start) out += m.copy(end = start)
        if (m.end > end) out += m.copy(start = end)
    }
    return copy(marks = out)
}

fun InlineContent.addMark(start: Int, end: Int, type: MarkType, href: String? = null): InlineContent {
    if (start >= end) return this
    val cleared = removeMark(start, end, type)
    val openTag = defaultOpenTag(type, href)
    var s = start
    var e = end
    val kept = mutableListOf<Mark>()
    for (m in cleared.marks) {
        if (m.type == type && m.openTag == openTag && m.end >= s && m.start <= e) {
            s = minOf(s, m.start)
            e = maxOf(e, m.end)
        } else {
            kept += m
        }
    }
    kept += Mark(s, e, type, openTag = openTag, href = href)
    return cleared.copy(marks = kept)
}

fun InlineContent.toggleMark(start: Int, end: Int, type: MarkType): InlineContent =
    if (hasMark(start, end, type)) removeMark(start, end, type) else addMark(start, end, type)

fun InlineContent.setLink(start: Int, end: Int, href: String?): InlineContent {
    val cleared = removeMark(start, end, MarkType.LINK)
    return if (href.isNullOrBlank()) cleared else cleared.addMark(start, end, MarkType.LINK, href.trim())
}

fun InlineContent.slice(start: Int, end: Int): InlineContent {
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(s, text.length)
    val skip = text.countTokens(0, s)
    val take = text.countTokens(s, e)
    return InlineContent(
        text.substring(s, e),
        marks.mapNotNull { m ->
            val ms = maxOf(m.start, s)
            val me = minOf(m.end, e)
            if (me > ms) m.copy(start = ms - s, end = me - s) else null
        },
        tokens.drop(skip).take(take),
    )
}

operator fun InlineContent.plus(other: InlineContent): InlineContent {
    val offset = text.length
    val joined = marks + other.marks.map { it.copy(start = it.start + offset, end = it.end + offset) }
    return InlineContent(text + other.text, joined, tokens + other.tokens)
}

fun InlineContent.split(pos: Int): Pair<InlineContent, InlineContent> = slice(0, pos) to slice(pos, text.length)

fun InlineContent.replaceRange(start: Int, end: Int, replacement: InlineContent): InlineContent =
    slice(0, start) + replacement + slice(end, text.length)

fun tokenContent(token: InlineToken): InlineContent = InlineContent(TOKEN_CHAR.toString(), emptyList(), listOf(token))

// ── Token factories (exact Quill blot markup) ──────────────────────────────

/** `MentionBlot` (InlineEmbed): guard characters around a non-editable inner span. */
fun mentionToken(userId: String, name: String, avatar: String?): InlineToken {
    val avatarAttr = avatar?.takeIf { it.isNotBlank() }?.let { " data-user-avatar=\"${escapeHtmlAttr(it)}\"" }.orEmpty()
    val html = "<span class=\"ql-mention\" data-user-id=\"${escapeHtmlAttr(userId)}\" data-user-name=\"${escapeHtmlAttr(name)}\"" +
        "$avatarAttr contenteditable=\"false\">\uFEFF<span contenteditable=\"false\">@${escapeHtmlText(name)}</span>\uFEFF</span>"
    return InlineToken(TokenKind.MENTION, html, "@$name", mapOf("id" to userId, "name" to name))
}

/** `PageLinkBlot`: `<a class="ql-pagelink" data-page-id data-page-title href="#" contenteditable="false">`. */
fun pageLinkToken(pageId: String, title: String): InlineToken {
    val html = "<a class=\"ql-pagelink\" data-page-id=\"${escapeHtmlAttr(pageId)}\" data-page-title=\"${escapeHtmlAttr(title)}\"" +
        " href=\"#\" contenteditable=\"false\">${escapeHtmlText(title).replace(" ", "&nbsp;")}</a>"
    return InlineToken(TokenKind.PAGE_LINK, html, title, mapOf("id" to pageId, "title" to title))
}

/** Slash `/today`: a `datechip` formatted space. */
fun dateChipToken(isoDate: String): InlineToken {
    val html = "<span class=\"ql-datechip\" data-date=\"${escapeHtmlAttr(isoDate)}\" contenteditable=\"false\">&nbsp;</span>"
    return InlineToken(TokenKind.DATE, html, "📅 $isoDate", mapOf("date" to isoDate))
}

// ── Document operations ────────────────────────────────────────────────────

data class FocusTarget(val blockId: Long, val position: Int)

fun NoteDoc.block(id: Long): NoteBlock? = blocks.firstOrNull { it.id == id }
fun NoteDoc.indexOf(id: Long): Int = blocks.indexOfFirst { it.id == id }

fun NoteDoc.update(id: Long, transform: (NoteBlock) -> NoteBlock): NoteDoc =
    copy(blocks = blocks.map { if (it.id == id) transform(it).copy(dirty = true) else it })

fun NoteDoc.insertAfter(id: Long?, newBlocks: List<NoteBlock>): NoteDoc {
    val index = id?.let(::indexOf) ?: -1
    val at = if (index < 0) blocks.size else index + 1
    val list = blocks.toMutableList()
    list.addAll(at, newBlocks.map { it.copy(dirty = true) })
    return copy(blocks = list)
}

fun NoteDoc.remove(id: Long): NoteDoc {
    val remaining = blocks.filterNot { it.id == id }
    return copy(blocks = remaining.ifEmpty { listOf(NoteBlock(type = BlockType.PARAGRAPH, dirty = true)) })
}

/** Enter key. Returns the new document and where the caret goes. */
fun NoteDoc.splitAt(id: Long, pos: Int): Pair<NoteDoc, FocusTarget> {
    val b = block(id) ?: return this to FocusTarget(id, pos)
    if (b.type == BlockType.LIST_ITEM && b.content.isEmpty) {
        val next = if (b.indent > 0) b.copy(indent = b.indent - 1) else b.toParagraph()
        return update(id) { next } to FocusTarget(id, 0)
    }
    val (left, right) = b.content.split(pos.coerceIn(0, b.content.text.length))
    val rightBlock = when (b.type) {
        BlockType.HEADING -> NoteBlock(type = BlockType.PARAGRAPH, content = right)
        BlockType.LIST_ITEM -> NoteBlock(
            type = BlockType.LIST_ITEM,
            listType = if (b.listType == ListType.CHECKED) ListType.UNCHECKED else b.listType,
            indent = b.indent,
            group = b.group,
            content = right,
        )
        BlockType.QUOTE -> NoteBlock(type = BlockType.QUOTE, openTag = b.openTag, content = right)
        else -> NoteBlock(type = BlockType.PARAGRAPH, openTag = if (b.implicit) null else b.openTag, content = right)
    }
    val doc = update(id) { it.copy(content = left) }.insertAfter(id, listOf(rightBlock))
    return doc to FocusTarget(rightBlock.id, 0)
}

private fun NoteBlock.toParagraph(): NoteBlock =
    copy(type = BlockType.PARAGRAPH, openTag = null, extraAttrs = emptyList(), indent = 0, group = null, level = 0, implicit = false)

/** Backspace with the caret at the start of a block. Null focus = nothing happened. */
fun NoteDoc.backspaceAtStart(id: Long): Pair<NoteDoc, FocusTarget?> {
    val index = indexOf(id)
    if (index < 0) return this to null
    val b = blocks[index]
    if (b.type == BlockType.LIST_ITEM) {
        val next = if (b.indent > 0) b.copy(indent = b.indent - 1) else b.toParagraph()
        return update(id) { next } to FocusTarget(id, 0)
    }
    if (b.type == BlockType.CODE && b.content.isEmpty) return update(id) { it.toParagraph() } to FocusTarget(id, 0)
    if (b.type == BlockType.CODE) return this to null
    if (index == 0) {
        return if (b.type == BlockType.HEADING || b.type == BlockType.QUOTE) update(id) { it.toParagraph() } to FocusTarget(id, 0)
        else this to null
    }
    val prev = blocks[index - 1]
    return when {
        prev.type == BlockType.DIVIDER -> remove(prev.id) to FocusTarget(id, 0)
        !prev.isEditable -> {
            if (!b.content.isEmpty) return this to null
            val target = blocks.subList(0, index).lastOrNull { it.isEditable }
            remove(id) to target?.let { FocusTarget(it.id, it.content.text.length) }
        }
        prev.type == BlockType.CODE -> {
            val at = prev.content.text.length
            update(prev.id) { it.copy(content = InlineContent(it.content.text + b.content.plainText())) }.remove(id) to FocusTarget(prev.id, at)
        }
        else -> {
            val at = prev.content.text.length
            update(prev.id) { it.copy(content = it.content + b.content) }.remove(id) to FocusTarget(prev.id, at)
        }
    }
}

/** Changes a block's format (slash menu / toolbar). Code blocks split back into lines. */
fun NoteDoc.setType(
    id: Long,
    type: BlockType,
    level: Int = 0,
    listType: ListType = ListType.BULLET,
    language: String = "plain",
): NoteDoc {
    val b = block(id) ?: return this
    if (!b.isEditable) return this
    if (b.type == BlockType.CODE && type != BlockType.CODE) {
        val lines = b.content.text.split('\n')
        val first = retype(b.copy(content = InlineContent(lines.first())), type, level, listType, language)
        val rest = lines.drop(1).map { retype(NoteBlock(type = BlockType.PARAGRAPH, content = InlineContent(it)), type, level, listType, language) }
        return update(id) { first }.insertAfter(id, rest)
    }
    return update(id) { retype(it, type, level, listType, language) }
}

private fun retype(b: NoteBlock, type: BlockType, level: Int, listType: ListType, language: String): NoteBlock {
    val sameTag = b.type == type && (type != BlockType.HEADING || b.level == level)
    val content = if (type == BlockType.CODE && b.type != BlockType.CODE) InlineContent(b.content.plainText()) else b.content
    return b.copy(
        type = type,
        level = if (type == BlockType.HEADING) level else 0,
        listType = if (type == BlockType.LIST_ITEM) listType else b.listType,
        indent = if (type == BlockType.LIST_ITEM && b.type == BlockType.LIST_ITEM) b.indent else 0,
        group = if (type == BlockType.LIST_ITEM && b.type == BlockType.LIST_ITEM) b.group else null,
        extraAttrs = if (type == BlockType.LIST_ITEM && b.type == BlockType.LIST_ITEM) b.extraAttrs else emptyList(),
        language = if (type == BlockType.CODE) language else b.language,
        openTag = if (sameTag && type != BlockType.LIST_ITEM) b.openTag else null,
        implicit = false,
        content = content,
    )
}

fun NoteDoc.toggleCheck(id: Long): NoteDoc = update(id) {
    it.copy(listType = if (it.listType == ListType.CHECKED) ListType.UNCHECKED else ListType.CHECKED)
}

fun NoteDoc.indent(id: Long, delta: Int): NoteDoc {
    val b = block(id) ?: return this
    if (b.type != BlockType.LIST_ITEM) return this
    val next = (b.indent + delta).coerceIn(0, 8)
    return if (next == b.indent) this else update(id) { it.copy(indent = next) }
}

/** Removes a live-embed marker paragraph together with the dividers the web inserted around it. */
fun NoteDoc.removeEmbed(id: Long): NoteDoc {
    val index = indexOf(id)
    if (index < 0) return this
    var doc = this
    blocks.getOrNull(index + 1)?.takeIf { it.type == BlockType.DIVIDER }?.let { doc = doc.remove(it.id) }
    doc = doc.remove(id)
    blocks.getOrNull(index - 1)?.takeIf { it.type == BlockType.DIVIDER }?.let { doc = doc.remove(it.id) }
    return doc
}

/** Web `handleInsertSprintEmbed` / `handleInsertTimeBlock`: divider, marker line, divider. */
fun embedBlocks(kind: EmbedKind): List<NoteBlock> = listOf(
    NoteBlock(type = BlockType.DIVIDER),
    NoteBlock(type = BlockType.PARAGRAPH, content = InlineContent(if (kind == EmbedKind.SPRINT) SPRINT_EMBED_TEXT else TIME_EMBED_TEXT)),
    NoteBlock(type = BlockType.DIVIDER),
)

/** `insertTocIntoEditor`: an H3 plus one bullet per H1–H3 (indented with spaces). Null when there are no headings. */
fun tocBlocks(doc: NoteDoc): List<NoteBlock>? {
    val headings = doc.blocks.filter { it.type == BlockType.HEADING && it.level in 1..3 && it.content.plainText().isNotBlank() }
    if (headings.isEmpty()) return null
    return listOf(NoteBlock(type = BlockType.HEADING, level = 3, content = InlineContent("Table of contents"))) +
        headings.map {
            NoteBlock(
                type = BlockType.LIST_ITEM,
                listType = ListType.BULLET,
                content = InlineContent("  ".repeat((it.level - 1).coerceAtLeast(0)) + it.content.plainText().trim()),
            )
        }
}

/** Word/char counts like `getWordCount` (text of the whole page). */
fun wordCount(html: String?): Pair<Int, Int> {
    val text = htmlToPlainText(html).trim()
    if (text.isEmpty()) return 0 to 0
    return text.split(Regex("\\s+")).size to text.length
}
