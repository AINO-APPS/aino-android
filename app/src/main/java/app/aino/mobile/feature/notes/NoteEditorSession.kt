package app.aino.mobile.feature.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Zero-width sentinel at the start of every block field: deleting it is how a
 * soft keyboard's backspace-at-start (which sends no key event) is detected.
 */
const val FIELD_SENTINEL = '\u200B'

enum class TriggerKind { SLASH, MENTION }

/** An open `/command` or `@mention` query in [blockId], spanning `[start, end)` of the model text. */
data class EditorTrigger(val kind: TriggerKind, val blockId: Long, val start: Int, val end: Int, val query: String)

private val SLASH_RE = Regex("(^|\\s)/([^\\s/]*)$")
private val MENTION_RE = Regex("(^|\\s)@([^\\s@]*)$")

/**
 * Editing state for one open page, held as Compose snapshot state so text
 * fields update synchronously (no IME desync). Every content change calls
 * [onChanged]; the ViewModel serializes and persists.
 */
class NoteEditorSession(
    val pageId: String,
    initial: NoteDoc,
    private val onChanged: () -> Unit,
) {
    var doc by mutableStateOf(initial)
        private set
    private val fields = mutableStateMapOf<Long, TextFieldValue>()
    var focusedBlockId by mutableStateOf<Long?>(null)
    var focusRequest by mutableStateOf<FocusTarget?>(null)
        private set
    var trigger by mutableStateOf<EditorTrigger?>(null)
        private set
    /** Toolbar toggles made with a collapsed selection, applied to the next typed text. */
    var pendingMarks by mutableStateOf<Map<MarkType, Boolean>>(emptyMap())
        private set
    private var dismissedTrigger: Pair<Long, Int>? = null

    val html: String get() = doc.toHtml()

    fun fieldValue(block: NoteBlock): TextFieldValue {
        val expected = FIELD_SENTINEL + block.content.text
        val current = fields[block.id]
        return if (current != null && current.text == expected) current else TextFieldValue(expected, TextRange(expected.length))
    }

    fun focusedBlock(): NoteBlock? = focusedBlockId?.let(doc::block)

    /** Model-coordinate selection of a block (caret at end when unknown). */
    fun selection(blockId: Long): TextRange {
        val block = doc.block(blockId) ?: return TextRange.Zero
        val value = fieldValue(block)
        val len = block.content.text.length
        val s = (value.selection.min - 1).coerceIn(0, len)
        val e = (value.selection.max - 1).coerceIn(0, len)
        return TextRange(s, e)
    }

    fun onFocusChanged(blockId: Long, focused: Boolean) {
        if (focused) {
            if (focusedBlockId != blockId) pendingMarks = emptyMap()
            focusedBlockId = blockId
            hasFocus = true
        } else if (focusedBlockId == blockId) {
            // Keep focusedBlockId: dialogs/sheets opened from the toolbar still act on it.
            hasFocus = false
            trigger = null
        }
    }

    /** True while one of the block fields holds input focus (toolbar visibility). */
    var hasFocus by mutableStateOf(false)
        private set

    fun consumeFocusRequest(target: FocusTarget) {
        val block = doc.block(target.blockId)
        if (block != null) {
            val text = FIELD_SENTINEL + block.content.text
            val pos = (target.position + 1).coerceIn(1, text.length)
            fields[block.id] = TextFieldValue(text, TextRange(pos))
        }
        if (focusRequest == target) focusRequest = null
    }

    fun requestFocus(blockId: Long, position: Int? = null) {
        val block = doc.block(blockId) ?: return
        focusRequest = FocusTarget(blockId, position ?: block.content.text.length)
    }

    fun onValueChange(blockId: Long, value: TextFieldValue) {
        val block = doc.block(blockId) ?: return
        if (!block.isEditable) return
        val old = fieldValue(block)
        if (!value.text.startsWith(FIELD_SENTINEL)) {
            if (old.selection.collapsed && old.selection.start <= 1 && value.text == old.text.drop(1)) {
                backspaceAtStart(blockId)
                return
            }
        }
        val raw = value.text.removePrefix(FIELD_SENTINEL.toString()).replace(FIELD_SENTINEL.toString(), "")
        val cursor = (value.selection.end - 1).coerceIn(0, raw.length)
        if (block.type != BlockType.CODE && '\n' in raw) {
            enterWithNewlines(block, raw, cursor)
            return
        }
        if (raw == block.content.text) {
            val sel = clampSelection(value)
            if (sel.selection != old.selection) pendingMarks = emptyMap()
            fields[blockId] = sel
            updateTrigger(blockId, block.content, sel.selection)
            return
        }
        val content = block.content.edit(raw, cursor, if (block.type == BlockType.CODE) emptyMap() else pendingMarks)
        val finalValue = if (content.text == raw) clampSelection(value) else {
            val pos = (cursor - (raw.length - content.text.length)).coerceIn(0, content.text.length)
            TextFieldValue(FIELD_SENTINEL + content.text, TextRange(pos + 1))
        }
        doc = doc.update(blockId) { it.copy(content = content) }
        fields[blockId] = finalValue
        updateTrigger(blockId, content, finalValue.selection)
        onChanged()
    }

    private fun clampSelection(value: TextFieldValue): TextFieldValue {
        val s = value.selection.start.coerceAtLeast(1)
        val e = value.selection.end.coerceAtLeast(1)
        val fixed = TextRange(s, e)
        return if (fixed == value.selection) value else value.copy(selection = fixed)
    }

    private fun enterWithNewlines(block: NoteBlock, raw: String, cursor: Int) {
        val full = block.content.edit(raw, cursor, pendingMarks)
        doc = doc.update(block.id) { it.copy(content = full) }
        val caret = cursor.coerceIn(0, full.text.length)
        val newlinesBeforeCaret = full.text.substring(0, caret).count { it == '\n' }
        val lastNl = if (newlinesBeforeCaret > 0) full.text.lastIndexOf('\n', caret - 1) else -1
        val visited = mutableListOf(block.id)
        var currentId = block.id
        var focus = FocusTarget(block.id, caret)
        while (true) {
            val current = doc.block(currentId) ?: break
            val k = current.content.text.indexOf('\n')
            if (k < 0) break
            val without = current.content.replaceRange(k, k + 1, InlineContent())
            doc = doc.update(currentId) { it.copy(content = without) }
            val (next, f) = doc.splitAt(currentId, k)
            doc = next
            if (f.blockId == currentId) {
                focus = f
                continue
            }
            currentId = f.blockId
            visited += currentId
        }
        if (newlinesBeforeCaret > 0 && visited.size > 1) {
            val target = visited.getOrNull(newlinesBeforeCaret) ?: visited.last()
            focus = FocusTarget(target, (caret - lastNl - 1).coerceAtLeast(0))
        }
        trigger = null
        visited.forEach { fields.remove(it) }
        focusRequest = focus
        onChanged()
    }

    private fun backspaceAtStart(blockId: Long) {
        val (next, focus) = doc.backspaceAtStart(blockId)
        if (focus == null) {
            // Nothing to merge into: keep the field intact (re-add the sentinel).
            fields[blockId] = TextFieldValue(FIELD_SENTINEL + (doc.block(blockId)?.content?.text ?: ""), TextRange(1))
            return
        }
        doc = next
        fields.remove(blockId)
        fields.remove(focus.blockId)
        trigger = null
        focusRequest = focus
        onChanged()
    }

    private fun updateTrigger(blockId: Long, content: InlineContent, selection: TextRange) {
        val block = doc.block(blockId)
        if (block == null || block.type == BlockType.CODE || !selection.collapsed) {
            trigger = null
            return
        }
        val cursor = (selection.start - 1).coerceIn(0, content.text.length)
        val before = content.text.substring(0, cursor)
        val slash = SLASH_RE.find(before)
        val mention = MENTION_RE.find(before)
        val found = when {
            slash != null -> EditorTrigger(TriggerKind.SLASH, blockId, slash.range.first + slash.groupValues[1].length, cursor, slash.groupValues[2])
            mention != null -> EditorTrigger(TriggerKind.MENTION, blockId, mention.range.first + mention.groupValues[1].length, cursor, mention.groupValues[2])
            else -> null
        }
        trigger = found?.takeIf { dismissedTrigger != (it.blockId to it.start) }
    }

    fun dismissTrigger() {
        trigger?.let { dismissedTrigger = it.blockId to it.start }
        trigger = null
    }

    /** Removes the typed `/query` or `@query` and returns the block + caret where it was. */
    fun consumeTrigger(): FocusTarget? {
        val t = trigger ?: return null
        trigger = null
        val block = doc.block(t.blockId) ?: return null
        val end = t.end.coerceAtMost(block.content.text.length)
        val content = block.content.replaceRange(t.start, end, InlineContent())
        doc = doc.update(t.blockId) { it.copy(content = content) }
        fields[t.blockId] = TextFieldValue(FIELD_SENTINEL + content.text, TextRange(t.start + 1))
        onChanged()
        return FocusTarget(t.blockId, t.start)
    }

    // ── Toolbar actions ────────────────────────────────────────────────────

    fun isMarkActive(type: MarkType): Boolean {
        val block = focusedBlock() ?: return false
        val sel = selection(block.id)
        pendingMarks[type]?.let { if (sel.collapsed) return it }
        return block.content.hasMark(sel.min, sel.max, type)
    }

    fun toggleMark(type: MarkType) {
        val block = focusedBlock()?.takeIf { it.isEditable && it.type != BlockType.CODE } ?: return
        val sel = selection(block.id)
        if (sel.collapsed) {
            pendingMarks = pendingMarks + (type to !isMarkActive(type))
            return
        }
        setContent(block.id, block.content.toggleMark(sel.min, sel.max, type))
    }

    fun linkAtSelection(): String? {
        val block = focusedBlock() ?: return null
        val sel = selection(block.id)
        return block.content.markAt(sel.min, MarkType.LINK)?.href
    }

    /** Applies (or clears) a link over the selection; with a collapsed caret the URL is inserted as linked text. */
    fun setLink(href: String?) {
        val block = focusedBlock()?.takeIf { it.isEditable && it.type != BlockType.CODE } ?: return
        val sel = selection(block.id)
        if (sel.collapsed) {
            val existing = block.content.marks.firstOrNull { it.type == MarkType.LINK && it.start < sel.start && sel.start <= it.end }
            if (existing != null) {
                setContent(block.id, block.content.setLink(existing.start, existing.end, href))
                return
            }
            if (href.isNullOrBlank()) return
            val text = href.trim()
            val inserted = InlineContent(text).addMark(0, text.length, MarkType.LINK, text)
            setContent(block.id, block.content.replaceRange(sel.start, sel.start, inserted), sel.start + text.length)
            return
        }
        setContent(block.id, block.content.setLink(sel.min, sel.max, href))
    }

    fun setBlockType(
        type: BlockType,
        level: Int = 0,
        listType: ListType = ListType.BULLET,
        language: String = "plain",
        toggle: Boolean = true,
    ) {
        val block = focusedBlock()?.takeIf { it.isEditable } ?: return
        val same = toggle && block.type == type && when (type) {
            BlockType.HEADING -> block.level == level
            BlockType.LIST_ITEM -> block.listType == listType || (block.listType.isCheck && listType.isCheck)
            BlockType.CODE -> block.language == language
            else -> true
        }
        val pos = selection(block.id).start
        doc = if (same && type != BlockType.PARAGRAPH) doc.setType(block.id, BlockType.PARAGRAPH) else doc.setType(block.id, type, level, listType, language)
        fields.remove(block.id)
        focusRequest = FocusTarget(block.id, pos.coerceAtMost(doc.block(block.id)?.content?.text?.length ?: 0))
        onChanged()
    }

    fun toggleCheck(blockId: Long) {
        doc = doc.toggleCheck(blockId)
        onChanged()
    }

    fun indent(delta: Int) {
        val block = focusedBlock() ?: return
        val next = doc.indent(block.id, delta)
        if (next !== doc) {
            doc = next
            onChanged()
        }
    }

    /** Inserts an atomic token (mention, page link, date chip) at the caret, followed by a space. */
    fun insertToken(token: InlineToken, at: FocusTarget? = null) {
        val target = at ?: focusedBlock()?.let { FocusTarget(it.id, selection(it.id).start) } ?: return
        val block = doc.block(target.blockId)?.takeIf { it.isEditable && it.type != BlockType.CODE } ?: return
        val pos = target.position.coerceIn(0, block.content.text.length)
        val inserted = tokenContent(token) + InlineContent(" ")
        setContent(block.id, block.content.replaceRange(pos, pos, inserted), pos + 2)
        focusRequest = FocusTarget(block.id, pos + 2)
    }

    fun insertText(text: String, at: FocusTarget? = null) {
        val target = at ?: focusedBlock()?.let { FocusTarget(it.id, selection(it.id).start) } ?: return
        val block = doc.block(target.blockId)?.takeIf { it.isEditable } ?: return
        val pos = target.position.coerceIn(0, block.content.text.length)
        setContent(block.id, block.content.replaceRange(pos, pos, InlineContent(text)), pos + text.length)
        focusRequest = FocusTarget(block.id, pos + text.length)
    }

    /**
     * Inserts blocks after [afterId] (or the focused block / the end) and focuses
     * the last editable one. An empty paragraph anchor (the line the slash
     * command was typed on) is replaced, like Quill inserting on that line.
     */
    fun insertBlocks(blocks: List<NoteBlock>, afterId: Long? = focusedBlockId) {
        if (blocks.isEmpty()) return
        val anchorBlock = afterId?.let(doc::block) ?: doc.blocks.lastOrNull()
        doc = doc.insertAfter(anchorBlock?.id, blocks)
        if (anchorBlock != null && anchorBlock.type == BlockType.PARAGRAPH && anchorBlock.content.isEmpty && anchorBlock.embed == null) {
            doc = doc.remove(anchorBlock.id)
        }
        val lastInserted = blocks.last()
        val focusBlock = if (lastInserted.type == BlockType.DIVIDER || lastInserted.embed != null) {
            val trailing = NoteBlock(type = BlockType.PARAGRAPH)
            doc = doc.insertAfter(lastInserted.id, listOf(trailing))
            trailing
        } else lastInserted
        if (focusBlock.isEditable) focusRequest = FocusTarget(focusBlock.id, focusBlock.content.text.length)
        onChanged()
    }

    fun removeBlock(blockId: Long) {
        doc = doc.remove(blockId)
        onChanged()
    }

    fun removeEmbed(blockId: Long) {
        doc = doc.removeEmbed(blockId)
        onChanged()
    }

    /** Convert-to-task result: the line becomes a checked item with `→ Task #id` in green italics. */
    fun markConverted(blockId: Long, taskId: Long) {
        val block = doc.block(blockId) ?: return
        val suffix = " → Task #$taskId"
        val start = block.content.text.length
        val tail = InlineContent(suffix, listOf(
            Mark(0, suffix.length, MarkType.OTHER, openTag = "<span style=\"color: rgb(16, 185, 129);\">", tag = "span", color = "rgb(16, 185, 129)"),
            Mark(0, suffix.length, MarkType.ITALIC),
        ))
        doc = doc.update(blockId) { it.copy(content = it.content + tail) }
        doc = doc.setType(blockId, BlockType.LIST_ITEM, listType = ListType.CHECKED)
        fields.remove(blockId)
        focusRequest = FocusTarget(blockId, start + suffix.length)
        onChanged()
    }

    private fun setContent(blockId: Long, content: InlineContent, caret: Int? = null) {
        doc = doc.update(blockId) { it.copy(content = content) }
        val current = fields[blockId]
        val sel = caret?.let { TextRange(it + 1) } ?: current?.selection ?: TextRange(content.text.length + 1)
        val len = content.text.length + 1
        fields[blockId] = TextFieldValue(FIELD_SENTINEL + content.text, TextRange(sel.start.coerceIn(1, len), sel.end.coerceIn(1, len)))
        onChanged()
    }
}
