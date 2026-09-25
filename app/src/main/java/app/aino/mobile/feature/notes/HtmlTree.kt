package app.aino.mobile.feature.notes

/**
 * A small, lenient HTML tree builder that keeps exact source offsets for every
 * node. The notes editor only regenerates blocks the user touched; everything
 * else is re-emitted from [HNode.start]..[HNode.end] byte-for-byte, so the parser
 * never has to be a perfect HTML5 implementation — only a faithful slicer.
 */
sealed class HNode {
    abstract val start: Int
    abstract val end: Int
}

class HText(val raw: String, override val start: Int, override val end: Int) : HNode() {
    val text: String by lazy { decodeEntities(raw) }
}

class HComment(override val start: Int, override val end: Int) : HNode()

class HElement(
    val name: String,
    val attrs: List<Pair<String, String>>,
    override val start: Int,
    /** Index just past the `>` of the opening tag. */
    val openEnd: Int,
    val selfClosing: Boolean,
) : HNode() {
    val children: MutableList<HNode> = mutableListOf()
    /** Index of `</name` (or [end] when the element was closed implicitly). */
    var closeStart: Int = openEnd
        internal set
    override var end: Int = openEnd
        internal set

    fun attr(name: String): String? = attrs.firstOrNull { it.first == name }?.second
    fun hasAttr(name: String): Boolean = attrs.any { it.first == name }
    fun classes(): List<String> = attr("class")?.split(Regex("\\s+"))?.filter(String::isNotEmpty).orEmpty()
    fun hasClass(name: String): Boolean = name in classes()
    fun elements(): List<HElement> = children.filterIsInstance<HElement>()

    fun openTag(source: String): String = source.substring(start, openEnd)
    fun outerHtml(source: String): String = source.substring(start, end)

    /** Concatenated decoded text of all descendants. */
    fun textContent(): String = buildString { appendText(this@HElement) }

    private fun StringBuilder.appendText(node: HNode) {
        when (node) {
            is HText -> append(node.text)
            is HElement -> if (node.name != "select") node.children.forEach { appendText(it) }
            is HComment -> Unit
        }
    }

    /** Depth-first search over descendant elements. */
    fun descendants(): Sequence<HElement> = sequence {
        for (child in children) if (child is HElement) {
            yield(child)
            yieldAll(child.descendants())
        }
    }
}

val VOID_ELEMENTS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr",
)
private val RAW_TEXT_ELEMENTS = setOf("script", "style", "textarea", "title", "xmp")

/** Block-level tags that implicitly close an open `<p>`. */
private val CLOSES_P = setOf(
    "address", "article", "aside", "blockquote", "div", "dl", "fieldset", "figure", "footer", "form",
    "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr", "main", "nav", "ol", "p", "pre", "section", "table", "ul",
)

/** Parses [source] into a list of top-level nodes with exact offsets. */
fun parseHtml(source: String): List<HNode> {
    val root = HElement("#root", emptyList(), 0, 0, false)
    val stack = ArrayDeque<HElement>().apply { addLast(root) }
    var i = 0
    val n = source.length

    fun closeTop(at: Int) {
        val el = stack.removeLast()
        el.closeStart = at
        el.end = at
    }

    fun addText(from: Int, to: Int) {
        if (to > from) stack.last().children += HText(source.substring(from, to), from, to)
    }

    while (i < n) {
        val c = source[i]
        if (c != '<') {
            val next = source.indexOf('<', i).let { if (it < 0) n else it }
            addText(i, next)
            i = next
            continue
        }
        when {
            source.startsWith("<!--", i) -> {
                val close = source.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                stack.last().children += HComment(i, end)
                i = end
            }
            source.startsWith("</", i) && i + 2 < n && source[i + 2].isLetter() -> {
                var j = i + 2
                while (j < n && (source[j].isLetterOrDigit() || source[j] == '-' || source[j] == ':')) j++
                val name = source.substring(i + 2, j).lowercase()
                val gt = source.indexOf('>', j)
                val end = if (gt < 0) n else gt + 1
                val index = stack.indexOfLast { it.name == name }
                if (index > 0) {
                    while (stack.size - 1 > index) closeTop(i)
                    val el = stack.removeLast()
                    el.closeStart = i
                    el.end = end
                }
                // A stray end tag without an open element is dropped from the tree
                // (its bytes survive only inside an untouched block's source slice).
                i = end
            }
            source.startsWith("<!", i) || source.startsWith("<?", i) -> {
                val gt = source.indexOf('>', i)
                val end = if (gt < 0) n else gt + 1
                stack.last().children += HComment(i, end)
                i = end
            }
            i + 1 < n && source[i + 1].isLetter() -> {
                val tag = readStartTag(source, i)
                if (tag == null) {
                    addText(i, i + 1)
                    i++
                    continue
                }
                val name = tag.name
                if (name in CLOSES_P && stack.last().name == "p") closeTop(i)
                if (name == "li") {
                    val liIndex = stack.indexOfLast { it.name == "li" || it.name == "ul" || it.name == "ol" }
                    if (liIndex > 0 && stack[liIndex].name == "li") while (stack.size > liIndex) closeTop(i)
                }
                val el = HElement(name, tag.attrs, i, tag.end, tag.selfClosing)
                stack.last().children += el
                when {
                    name in VOID_ELEMENTS || tag.selfClosing -> {
                        el.closeStart = tag.end
                        el.end = tag.end
                    }
                    name in RAW_TEXT_ELEMENTS -> {
                        val close = source.indexOf("</$name", tag.end, ignoreCase = true)
                        val closeAt = if (close < 0) n else close
                        if (closeAt > tag.end) el.children += HText(source.substring(tag.end, closeAt), tag.end, closeAt)
                        val gt = if (close < 0) -1 else source.indexOf('>', close)
                        el.closeStart = closeAt
                        el.end = if (gt < 0) n else gt + 1
                    }
                    else -> stack.addLast(el)
                }
                i = el.end.coerceAtLeast(tag.end)
            }
            else -> {
                addText(i, i + 1)
                i++
            }
        }
    }
    while (stack.size > 1) closeTop(n)
    return root.children
}

private class StartTag(val name: String, val attrs: List<Pair<String, String>>, val end: Int, val selfClosing: Boolean)

private fun readStartTag(s: String, from: Int): StartTag? {
    val n = s.length
    var j = from + 1
    while (j < n && (s[j].isLetterOrDigit() || s[j] == '-' || s[j] == ':' || s[j] == '_')) j++
    val name = s.substring(from + 1, j).lowercase()
    val attrs = mutableListOf<Pair<String, String>>()
    var selfClosing = false
    while (j < n) {
        while (j < n && s[j].isWhitespace()) j++
        if (j >= n) return null
        if (s[j] == '>') return StartTag(name, attrs, j + 1, selfClosing)
        if (s[j] == '/') {
            selfClosing = true
            j++
            continue
        }
        selfClosing = false
        val nameStart = j
        while (j < n && !s[j].isWhitespace() && s[j] != '=' && s[j] != '>' && !(s[j] == '/' && j + 1 < n && s[j + 1] == '>')) j++
        if (j == nameStart) {
            j++
            continue
        }
        val attrName = s.substring(nameStart, j).lowercase()
        var k = j
        while (k < n && s[k].isWhitespace()) k++
        var value = ""
        if (k < n && s[k] == '=') {
            j = k + 1
            while (j < n && s[j].isWhitespace()) j++
            if (j < n && (s[j] == '"' || s[j] == '\'')) {
                val quote = s[j]
                val close = s.indexOf(quote, j + 1)
                if (close < 0) return null
                value = s.substring(j + 1, close)
                j = close + 1
            } else {
                val vStart = j
                while (j < n && !s[j].isWhitespace() && s[j] != '>') j++
                value = s.substring(vStart, j)
            }
        }
        attrs += attrName to decodeEntities(value)
    }
    return null
}

private val NAMED_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
    "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026", "rsquo" to "\u2019", "lsquo" to "\u2018",
    "ldquo" to "\u201C", "rdquo" to "\u201D", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
    "bull" to "\u2022", "middot" to "\u00B7", "laquo" to "\u00AB", "raquo" to "\u00BB", "times" to "\u00D7",
    "deg" to "\u00B0", "euro" to "\u20AC", "pound" to "\u00A3", "rarr" to "\u2192", "larr" to "\u2190",
    "zwj" to "\u200D", "zwnj" to "\u200C", "shy" to "\u00AD", "ensp" to "\u2002", "emsp" to "\u2003",
    "thinsp" to "\u2009", "iexcl" to "\u00A1", "iquest" to "\u00BF", "sect" to "\u00A7", "para" to "\u00B6",
)

fun decodeEntities(raw: String): String {
    if ('&' !in raw) return raw
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '&') {
            val semi = raw.indexOf(';', i + 1)
            if (semi > i + 1 && semi - i <= 12) {
                val body = raw.substring(i + 1, semi)
                val decoded = when {
                    body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePointString)
                    body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePointString)
                    else -> NAMED_ENTITIES[body]
                }
                if (decoded != null) {
                    out.append(decoded)
                    i = semi + 1
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

private fun codePointString(cp: Int): String? =
    if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

/** Quill's `escapeText`: `&`, `<`, `>` only. */
fun escapeHtmlText(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        else -> append(c)
    }
}

fun escapeHtmlAttr(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '&' -> append("&amp;")
        '"' -> append("&quot;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        else -> append(c)
    }
}

/** Plain text of an HTML fragment (`stripHtml` in notesUtils): tags dropped, entities decoded. */
fun htmlToPlainText(html: String?): String {
    if (html.isNullOrEmpty()) return ""
    val sb = StringBuilder()
    fun walk(nodes: List<HNode>) {
        for (node in nodes) when (node) {
            is HText -> sb.append(node.text)
            is HElement -> if (node.name != "script" && node.name != "style" && node.name != "select") walk(node.children)
            is HComment -> Unit
        }
    }
    walk(parseHtml(html))
    return sb.toString().replace("\uFEFF", "")
}
