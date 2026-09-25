package app.aino.mobile.feature.notes

import java.util.concurrent.atomic.AtomicLong

/** Stands in for an atomic inline token (mention, page link, image, …) inside [InlineContent.text]. */
const val TOKEN_CHAR = '\uFFFC'

private val blockIds = AtomicLong(1)
fun nextBlockId(): Long = blockIds.getAndIncrement()

/** Inline wrapper kinds. [rank] mirrors Quill's inline blot order (outermost first). */
enum class MarkType(val tag: String, val rank: Int) {
    OTHER("span", 0), LINK("a", 1), UNDERLINE("u", 2), STRIKE("s", 3), ITALIC("em", 4), BOLD("strong", 5), CODE("code", 6),
}

/**
 * An inline wrapper over `[start, end)`. [openTag] is the exact source of the
 * opening tag so unknown attributes/classes survive regeneration.
 */
data class Mark(
    val start: Int,
    val end: Int,
    val type: MarkType,
    val openTag: String = defaultOpenTag(type),
    val tag: String = type.tag,
    val href: String? = null,
    val color: String? = null,
    val background: String? = null,
)

fun defaultOpenTag(type: MarkType, href: String? = null): String = when (type) {
    MarkType.LINK -> "<a href=\"${escapeHtmlAttr(href.orEmpty())}\" rel=\"noopener noreferrer\" target=\"_blank\">"
    else -> "<${type.tag}>"
}

enum class TokenKind { MENTION, PAGE_LINK, DATE, IMAGE, BREAK, OTHER }

/** An atomic inline element kept as raw [html]; [label] is what the editor displays. */
data class InlineToken(
    val kind: TokenKind,
    val html: String,
    val label: String,
    val attrs: Map<String, String> = emptyMap(),
)

data class InlineContent(
    val text: String = "",
    val marks: List<Mark> = emptyList(),
    val tokens: List<InlineToken> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty()

    /** Display text: tokens expanded to their labels. */
    fun plainText(): String = buildString {
        var t = 0
        for (c in text) if (c == TOKEN_CHAR) append(tokens.getOrNull(t++)?.label.orEmpty()) else append(c)
    }
}

enum class BlockType { PARAGRAPH, HEADING, QUOTE, LIST_ITEM, CODE, DIVIDER, OPAQUE }

enum class ListType(val attr: String) {
    BULLET("bullet"), ORDERED("ordered"), CHECKED("checked"), UNCHECKED("unchecked");

    val isCheck: Boolean get() = this == CHECKED || this == UNCHECKED
}

enum class EmbedKind { SPRINT, TIME }

enum class OpaqueKind { IMAGE, CALLOUT, TOGGLE, TABLE, DRAWIO, MATH, AUDIO, VIDEO, OTHER }

data class NoteBlock(
    val id: Long = nextBlockId(),
    val type: BlockType,
    val content: InlineContent = InlineContent(),
    val level: Int = 0,
    val listType: ListType = ListType.BULLET,
    val indent: Int = 0,
    val language: String = "plain",
    /** Raw opening tag for p, h1-h6 and blockquote (keeps class/style/unknown attrs); null = default tag. */
    val openTag: String? = null,
    /** Extra `<li>` attributes beyond `data-list` / `ql-indent-*`. */
    val extraAttrs: List<Pair<String, String>> = emptyList(),
    /** Original HTML of this block (list items: inner inline HTML). Emitted verbatim while clean. */
    val source: String? = null,
    /** Original list container a list item came from. */
    val group: Int? = null,
    /** Source text between the previous block and this one (whitespace, comments, stray tags). */
    val leading: String = "",
    val dirty: Boolean = false,
    /** Inline content that was not wrapped in a block element. */
    val implicit: Boolean = false,
    val opaqueKind: OpaqueKind? = null,
) {
    val isEditable: Boolean get() = type != BlockType.DIVIDER && type != BlockType.OPAQUE && embed == null
    val embed: EmbedKind? get() = if (type == BlockType.PARAGRAPH) embedForText(content.text) else null
}

const val SPRINT_EMBED_TEXT = " 📊 Sprint Board (live embed below) "
const val TIME_EMBED_TEXT = " ⏱ Time Tracking (live embed below) "

fun embedForText(text: String): EmbedKind? = when (text.replace('\u00A0', ' ').trim()) {
    SPRINT_EMBED_TEXT.trim() -> EmbedKind.SPRINT
    TIME_EMBED_TEXT.trim() -> EmbedKind.TIME
    else -> null
}

data class ListGroup(val source: String, val leading: String, val itemIds: List<Long>)

data class NoteDoc(
    val blocks: List<NoteBlock>,
    val original: String,
    val originalIds: List<Long>,
    val groups: Map<Int, ListGroup> = emptyMap(),
    val trailing: String = "",
) {
    val isModified: Boolean get() = blocks.any { it.dirty } || blocks.map { it.id } != originalIds

    fun toHtml(): String = if (!isModified) original else serializeBlocks()

    /** Always walks the blocks (used by tests to prove the regen path is lossless too). */
    fun serializeBlocks(): String {
        val out = StringBuilder()
        var i = 0
        while (i < blocks.size) {
            val block = blocks[i]
            if (block.type == BlockType.LIST_ITEM) {
                var j = i
                while (j < blocks.size && blocks[j].type == BlockType.LIST_ITEM) j++
                emitListRun(blocks.subList(i, j), out)
                i = j
                continue
            }
            out.append(block.leading).append(blockHtml(block))
            i++
        }
        out.append(trailing)
        return out.toString()
    }

    private fun emitListRun(run: List<NoteBlock>, out: StringBuilder) {
        var i = 0
        while (i < run.size) {
            val group = run[i].group
            var j = i
            while (j < run.size && run[j].group == group) j++
            val segment = run.subList(i, j)
            val original = group?.let(groups::get)
            if (original != null && segment.none { it.dirty } && segment.map { it.id } == original.itemIds) {
                out.append(original.leading).append(original.source)
            } else {
                if (original != null && segment.first().id == original.itemIds.firstOrNull()) out.append(original.leading)
                out.append(listHtml(segment))
            }
            i = j
        }
    }
}

fun blockHtml(block: NoteBlock): String {
    if (!block.dirty && block.source != null) return block.source
    return when (block.type) {
        BlockType.PARAGRAPH -> (block.openTag ?: "<p>") + inlineToHtml(block.content) + "</p>"
        BlockType.HEADING -> {
            val level = block.level.coerceIn(1, 6)
            (block.openTag ?: "<h$level>") + inlineToHtml(block.content) + "</h$level>"
        }
        BlockType.QUOTE -> (block.openTag ?: "<blockquote>") + inlineToHtml(block.content) + "</blockquote>"
        BlockType.CODE -> "<pre data-language=\"${escapeHtmlAttr(block.language)}\">\n${escapeHtmlText(block.content.plainText())}\n</pre>"
        BlockType.DIVIDER -> "<hr>"
        BlockType.LIST_ITEM -> listHtml(listOf(block))
        BlockType.OPAQUE -> block.source.orEmpty()
    }
}

/** Port of Quill 2's `convertListHTML` (semantic HTML: nested lists, `data-list` only for checkboxes). */
fun listHtml(items: List<NoteBlock>): String {
    val sb = StringBuilder()
    val types = ArrayDeque<ListType>()
    var lastIndent = -1
    var index = 0
    fun tag(type: ListType) = if (type == ListType.ORDERED) "ol" else "ul"
    fun attrs(item: NoteBlock): String = buildString {
        if (item.listType.isCheck) append(" data-list=\"${item.listType.attr}\"")
        item.extraAttrs.forEach { (name, value) -> append(" ").append(name).append("=\"").append(escapeHtmlAttr(value)).append('"') }
    }
    fun inner(item: NoteBlock): String =
        if (!item.dirty && item.source != null) item.source else inlineToHtml(item.content)
    while (true) {
        if (index == items.size) {
            while (types.isNotEmpty()) sb.append("</li></").append(tag(types.removeLast())).append('>')
            break
        }
        val item = items[index]
        val indent = item.indent.coerceAtLeast(0)
        if (indent > lastIndent) {
            types.addLast(item.listType)
            if (indent == lastIndent + 1) {
                sb.append('<').append(tag(item.listType)).append("><li").append(attrs(item)).append('>').append(inner(item))
                index++
            } else {
                sb.append('<').append(tag(item.listType)).append("><li>")
            }
            lastIndent++
            continue
        }
        val previous = types.last()
        if (indent == lastIndent && tagKey(item.listType) == tagKey(previous) && item.listType == previous) {
            sb.append("</li><li").append(attrs(item)).append('>').append(inner(item))
            index++
            continue
        }
        sb.append("</li></").append(tag(types.removeLast())).append('>')
        lastIndent--
    }
    return sb.toString()
}

private fun tagKey(type: ListType) = if (type == ListType.ORDERED) "ol" else "ul"

/** Serializes inline content the way Quill's `getSemanticHTML` does (spaces become `&nbsp;`). */
fun inlineToHtml(content: InlineContent): String {
    val text = content.text
    if (text.isEmpty()) return "<br>"
    val marks = content.marks.filter { it.end > it.start && it.start >= 0 && it.end <= text.length }
    val boundaries = sortedSetOf(0, text.length)
    marks.forEach { boundaries += it.start; boundaries += it.end }
    val stack = mutableListOf<Int>()
    val sb = StringBuilder()
    var token = 0
    val points = boundaries.toList()
    for (k in 0 until points.size - 1) {
        val a = points[k]
        val b = points[k + 1]
        if (a >= b) continue
        val active = marks.indices.filter { marks[it].start <= a && marks[it].end >= b }.toSet()
        val firstBad = stack.indexOfFirst { it !in active }
        if (firstBad >= 0) {
            for (s in stack.lastIndex downTo firstBad) sb.append("</").append(marks[stack[s]].tag).append('>')
            while (stack.size > firstBad) stack.removeAt(stack.lastIndex)
        }
        active.filter { it !in stack }
            .sortedWith(compareByDescending<Int> { marks[it].end }.thenBy { marks[it].start }.thenBy { marks[it].type.rank })
            .forEach { sb.append(marks[it].openTag); stack += it }
        for (i in a until b) {
            when (val c = text[i]) {
                TOKEN_CHAR -> sb.append(content.tokens.getOrNull(token++)?.html.orEmpty())
                ' ', '\u00A0' -> sb.append("&nbsp;")
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                else -> sb.append(c)
            }
        }
    }
    for (s in stack.lastIndex downTo 0) sb.append("</").append(marks[stack[s]].tag).append('>')
    return sb.toString()
}

// ── Parsing ─────────────────────────────────────────────────────────────────

private val INLINE_TAGS = setOf(
    "a", "abbr", "b", "big", "br", "cite", "code", "del", "em", "font", "i", "img", "ins", "kbd", "label", "mark",
    "q", "s", "small", "span", "strike", "strong", "sub", "sup", "time", "u", "wbr",
)
private val BLOCKISH_IN_INLINE = setOf(
    "p", "div", "table", "ul", "ol", "pre", "blockquote", "h1", "h2", "h3", "h4", "h5", "h6", "figure", "section",
    "iframe", "video", "audio", "hr", "li", "dl", "form", "article", "aside", "header", "footer", "nav",
)

/** Parses Quill HTML into editable/preserved blocks. Never throws: failures yield one read-only block. */
fun parseNoteDoc(html: String): NoteDoc = try {
    NoteDocParser(html).parse()
} catch (_: Throwable) {
    val block = NoteBlock(type = BlockType.OPAQUE, source = html, opaqueKind = OpaqueKind.OTHER)
    NoteDoc(listOf(block), html, listOf(block.id))
}

private class NoteDocParser(private val src: String) {
    private val blocks = mutableListOf<NoteBlock>()
    private val groups = mutableMapOf<Int, ListGroup>()
    private var cursor = 0
    private var nextGroup = 1

    fun parse(): NoteDoc {
        val nodes = parseHtml(src)
        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            when {
                node is HComment || (node is HText && node.raw.isBlank()) -> i++
                node is HElement && node.name !in INLINE_TAGS -> {
                    addElement(node)
                    i++
                }
                else -> {
                    var j = i
                    var lastContent = i
                    while (j < nodes.size) {
                        val n = nodes[j]
                        if (n is HElement && n.name !in INLINE_TAGS) break
                        if (!(n is HComment || (n is HText && n.raw.isBlank()))) lastContent = j
                        j++
                    }
                    addInlineRun(nodes.subList(i, lastContent + 1))
                    i = lastContent + 1
                }
            }
        }
        val trailing = src.substring(cursor)
        if (blocks.isEmpty()) {
            val empty = NoteBlock(type = BlockType.PARAGRAPH, source = "", leading = "")
            return NoteDoc(listOf(empty), src, listOf(empty.id), groups, trailing)
        }
        return NoteDoc(blocks.toList(), src, blocks.map { it.id }, groups, trailing)
    }

    private fun leadingUntil(start: Int): String = src.substring(cursor, start).also { cursor = start }

    private fun add(block: NoteBlock, end: Int) {
        blocks += block
        cursor = end
    }

    private fun addInlineRun(nodes: List<HNode>) {
        val start = nodes.first().start
        val end = nodes.last().end
        val leading = leadingUntil(start)
        val source = src.substring(start, end)
        if (onlyImages(nodes)) {
            add(NoteBlock(type = BlockType.OPAQUE, source = source, leading = leading, opaqueKind = OpaqueKind.IMAGE), end)
            return
        }
        val content = inlineContent(nodes)
        add(NoteBlock(type = BlockType.PARAGRAPH, content = content, source = source, leading = leading, implicit = true), end)
    }

    private fun addElement(el: HElement) {
        val leading = leadingUntil(el.start)
        val source = el.outerHtml(src)
        val name = el.name
        val block: NoteBlock? = when {
            name == "p" -> textBlock(el, BlockType.PARAGRAPH, 0)
            name.length == 2 && name[0] == 'h' && name[1] in '1'..'6' -> textBlock(el, BlockType.HEADING, name[1] - '0')
            name == "blockquote" -> textBlock(el, BlockType.QUOTE, 0)
            name == "pre" -> codeBlock(el.textContent().removePrefix("\n").removeSuffix("\n"), el.attr("data-language"))
            name == "div" && el.hasClass("ql-code-block-container") -> {
                val lines = el.elements().filter { it.hasClass("ql-code-block") }
                codeBlock(lines.joinToString("\n") { it.textContent() }, lines.firstOrNull()?.attr("data-language"))
            }
            name == "div" && el.hasClass("ql-code-block") -> codeBlock(el.textContent(), el.attr("data-language"))
            name == "hr" -> NoteBlock(type = BlockType.DIVIDER)
            name == "ul" || name == "ol" -> {
                val items = listItems(el)
                if (items != null && items.isNotEmpty()) {
                    val group = nextGroup++
                    val tagged = items.map { it.copy(group = group) }
                    groups[group] = ListGroup(source, leading, tagged.map { it.id })
                    blocks += tagged
                    cursor = el.end
                    return
                }
                null
            }
            else -> null
        }
        val finalBlock = (block ?: NoteBlock(type = BlockType.OPAQUE, opaqueKind = opaqueKind(el)))
            .copy(source = source, leading = leading)
        add(finalBlock, el.end)
    }

    private fun codeBlock(text: String, language: String?): NoteBlock =
        NoteBlock(type = BlockType.CODE, content = InlineContent(text.replace(TOKEN_CHAR, ' ')), language = language?.ifBlank { null } ?: "plain")

    /** p / h* / blockquote with inline children; block children make it read-only. */
    private fun textBlock(el: HElement, type: BlockType, level: Int): NoteBlock? {
        val children = el.children
        if (children.any { it is HElement && it.name in BLOCKISH_IN_INLINE }) return null
        if (type != BlockType.QUOTE && children.isNotEmpty() && onlyImages(children)) {
            return NoteBlock(type = BlockType.OPAQUE, opaqueKind = OpaqueKind.IMAGE)
        }
        return NoteBlock(type = type, level = level, content = inlineContent(children), openTag = el.openTag(src))
    }

    private fun onlyImages(nodes: List<HNode>): Boolean {
        var images = 0
        for (n in nodes) when {
            n is HElement && n.name == "img" -> images++
            n is HElement && n.name == "br" -> Unit
            n is HComment -> Unit
            n is HText && n.raw.isBlank() -> Unit
            else -> return false
        }
        return images > 0
    }

    private fun listItems(list: HElement): List<NoteBlock>? {
        val out = mutableListOf<NoteBlock>()
        fun walk(container: HElement, depth: Int): Boolean {
            for (child in container.children) {
                when {
                    child is HComment -> Unit
                    child is HText -> if (child.raw.isNotBlank()) return false
                    child is HElement && child.name == "li" -> if (!item(child, container, depth, out, ::walk)) return false
                    else -> return false
                }
            }
            return true
        }
        return if (walk(list, 0)) out else null
    }

    private fun item(
        li: HElement,
        container: HElement,
        depth: Int,
        out: MutableList<NoteBlock>,
        walk: (HElement, Int) -> Boolean,
    ): Boolean {
        val nested = li.elements().filter { it.name == "ul" || it.name == "ol" }
        val inline = li.children.filter { !(it is HElement && (it.name == "ul" || it.name == "ol" || it.hasClass("ql-ui"))) }
        if (inline.any { it is HElement && it.name in BLOCKISH_IN_INLINE }) return false
        val ownContent = inline.any { !(it is HComment || (it is HText && it.raw.isBlank())) }
        if (ownContent || nested.isEmpty()) {
            val type = when (li.attr("data-list")) {
                "bullet" -> ListType.BULLET
                "ordered" -> ListType.ORDERED
                "checked" -> ListType.CHECKED
                "unchecked", "check" -> ListType.UNCHECKED
                else -> if (container.name == "ol") ListType.ORDERED else ListType.BULLET
            }
            val classIndent = li.classes().firstNotNullOfOrNull { Regex("^ql-indent-(\\d+)$").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
            val extra = li.attrs.mapNotNull { (name, value) ->
                when (name) {
                    "data-list" -> null
                    "class" -> value.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.startsWith("ql-indent-") }
                        .takeIf { it.isNotEmpty() }?.let { "class" to it.joinToString(" ") }
                    else -> name to value
                }
            }
            val innerSource = inline.joinToString("") { src.substring(it.start, it.end) }
            out += NoteBlock(
                type = BlockType.LIST_ITEM,
                listType = type,
                indent = depth + classIndent,
                content = inlineContent(inline),
                extraAttrs = extra,
                source = innerSource,
            )
        }
        return nested.all { walk(it, depth + 1) }
    }

    private fun inlineContent(nodes: List<HNode>): InlineContent {
        val builder = InlineBuilder(src)
        builder.addAll(nodes)
        return builder.build()
    }
}

private class InlineBuilder(private val src: String) {
    private val sb = StringBuilder()
    private val marks = mutableListOf<Mark>()
    private val tokens = mutableListOf<InlineToken>()

    fun addAll(nodes: List<HNode>) = nodes.forEach(::add)

    fun build(): InlineContent {
        if (tokens.size == 1 && sb.length == 1 && tokens[0].kind == TokenKind.BREAK) return InlineContent()
        if (tokens.isEmpty() && sb.isBlank()) return InlineContent()
        return InlineContent(sb.toString(), marks.toList(), tokens.toList())
    }

    private fun token(kind: TokenKind, html: String, label: String, attrs: Map<String, String> = emptyMap()) {
        sb.append(TOKEN_CHAR)
        tokens += InlineToken(kind, html, label, attrs)
    }

    private fun add(node: HNode) {
        when (node) {
            is HComment -> Unit
            is HText -> {
                val t = node.text.replace("\uFEFF", "").replace(TOKEN_CHAR, ' ')
                    .replace(Regex("[\\r\\n\\t]"), " ").replace(Regex(" {2,}"), " ").replace('\u00A0', ' ')
                sb.append(t)
            }
            is HElement -> element(node)
        }
    }

    private fun element(el: HElement) {
        val html = el.outerHtml(src)
        val text = el.textContent().replace("\uFEFF", "").replace('\u00A0', ' ')
        when {
            el.name == "br" -> token(TokenKind.BREAK, html, "\n")
            el.name == "img" -> token(TokenKind.IMAGE, html, "🖼", mapOf("src" to el.attr("src").orEmpty()))
            el.name == "span" && el.hasClass("ql-mention") -> {
                val name = el.attr("data-user-name")?.ifBlank { null } ?: text.trim().removePrefix("@")
                token(TokenKind.MENTION, html, "@$name", mapOf("id" to el.attr("data-user-id").orEmpty(), "name" to name))
            }
            el.name == "a" && el.hasClass("ql-pagelink") -> {
                val title = el.attr("data-page-title")?.ifBlank { null }
                val label = text.trim().ifBlank { title ?: "Untitled" }
                token(TokenKind.PAGE_LINK, html, label, mapOf("id" to el.attr("data-page-id").orEmpty(), "title" to (title ?: label)))
            }
            el.name == "span" && el.hasClass("ql-datechip") -> {
                val date = el.attr("data-date").orEmpty()
                token(TokenKind.DATE, html, "📅 " + text.trim().ifBlank { date }, mapOf("date" to date))
            }
            el.attr("contenteditable") == "false" || el.hasClass("ql-formula") || el.name !in INLINE_TAGS -> {
                token(TokenKind.OTHER, html, text.trim().take(60).ifBlank { "⟨${el.name}⟩" })
            }
            else -> {
                val start = sb.length
                el.children.forEach(::add)
                val end = sb.length
                if (end > start) {
                    val type = when (el.name) {
                        "strong", "b" -> MarkType.BOLD
                        "em", "i" -> MarkType.ITALIC
                        "u" -> MarkType.UNDERLINE
                        "s", "strike", "del" -> MarkType.STRIKE
                        "code" -> MarkType.CODE
                        "a" -> MarkType.LINK
                        else -> MarkType.OTHER
                    }
                    val style = el.attr("style").orEmpty()
                    marks += Mark(
                        start, end, type,
                        openTag = el.openTag(src),
                        tag = el.name,
                        href = if (type == MarkType.LINK) el.attr("href") else null,
                        color = cssProperty(style, "color"),
                        background = cssProperty(style, "background-color") ?: cssProperty(style, "background"),
                    )
                }
            }
        }
    }
}

fun cssProperty(style: String, name: String): String? = style.split(';').firstNotNullOfOrNull { decl ->
    val key = decl.substringBefore(':', "").trim().lowercase()
    if (key == name) decl.substringAfter(':').trim().ifBlank { null } else null
}

fun opaqueKind(el: HElement): OpaqueKind = when {
    el.hasClass("ql-callout") || el.hasAttr("data-callout") -> OpaqueKind.CALLOUT
    el.hasClass("ql-toggle") -> OpaqueKind.TOGGLE
    el.name == "table" || el.hasClass("ql-simpletable") -> OpaqueKind.TABLE
    el.hasClass("ql-drawio") -> OpaqueKind.DRAWIO
    el.hasClass("ql-math") -> OpaqueKind.MATH
    el.hasClass("ql-audio") -> OpaqueKind.AUDIO
    el.name == "iframe" || el.name == "video" || el.hasClass("ql-video") -> OpaqueKind.VIDEO
    el.name == "img" -> OpaqueKind.IMAGE
    else -> OpaqueKind.OTHER
}
