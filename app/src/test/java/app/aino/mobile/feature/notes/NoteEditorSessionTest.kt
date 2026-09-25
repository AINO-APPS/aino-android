package app.aino.mobile.feature.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditorSessionTest {
    private var changes = 0
    private fun session(html: String) = NoteEditorSession("p", parseNoteDoc(html)) { changes++ }

    /** Simulates the text field: types [text] at the caret of [blockId]. */
    private fun NoteEditorSession.type(blockId: Long, text: String) {
        val block = doc.block(blockId)!!
        val value = fieldValue(block)
        val caret = value.selection.end
        val next = value.text.substring(0, caret) + text + value.text.substring(caret)
        onValueChange(blockId, TextFieldValue(next, TextRange(caret + text.length)))
    }

    private fun NoteEditorSession.caret(blockId: Long, modelPos: Int) {
        val block = doc.block(blockId)!!
        onValueChange(blockId, fieldValue(block).copy(selection = TextRange(modelPos + 1)))
    }

    private fun NoteEditorSession.backspaceAtStart(blockId: Long) {
        val block = doc.block(blockId)!!
        caret(blockId, 0)
        val value = fieldValue(block)
        onValueChange(blockId, TextFieldValue(value.text.drop(1), TextRange(0)))
    }

    @Test
    fun typingMarksOnlyThatBlockDirtyAndKeepsTheRestVerbatim() {
        val s = session("<p>a</p><table><tr><td>x</td></tr></table>")
        val id = s.doc.blocks[0].id
        s.type(id, "b")
        assertEquals("<p>ab</p><table><tr><td>x</td></tr></table>", s.html)
        assertTrue(changes > 0)
    }

    @Test
    fun untouchedSessionReturnsTheOriginalHtml() {
        val html = "<h1>T</h1><p>x&nbsp;y</p>\n<ul><li>q</li></ul>"
        val s = session(html)
        s.caret(s.doc.blocks[1].id, 1)
        assertEquals(html, s.html)
    }

    @Test
    fun enterSplitsAndFocusesTheNewBlock() {
        val s = session("<p>hello</p>")
        val id = s.doc.blocks[0].id
        s.caret(id, 2)
        s.type(id, "\n")
        assertEquals("<p>he</p><p>llo</p>", s.html)
        val focus = s.focusRequest!!
        assertEquals(s.doc.blocks[1].id, focus.blockId)
        assertEquals(0, focus.position)
    }

    @Test
    fun pastingMultipleLinesCreatesBlocks() {
        val s = session("<ul><li>a</li></ul>")
        val id = s.doc.blocks[0].id
        s.type(id, "1\n2\n3")
        assertEquals("<ul><li>a1</li><li>2</li><li>3</li></ul>", s.html)
        assertEquals(FocusTarget(s.doc.blocks[2].id, 1), s.focusRequest)
    }

    @Test
    fun deletingTheSentinelMergesWithThePreviousBlock() {
        val s = session("<p>ab</p><p>cd</p>")
        val second = s.doc.blocks[1].id
        s.backspaceAtStart(second)
        assertEquals("<p>abcd</p>", s.html)
        assertEquals(FocusTarget(s.doc.blocks[0].id, 2), s.focusRequest)
    }

    @Test
    fun slashTriggerIsDetectedAndConsumed() {
        val s = session("<p>x</p>")
        val id = s.doc.blocks[0].id
        s.type(id, " /hea")
        val trigger = s.trigger!!
        assertEquals(TriggerKind.SLASH, trigger.kind)
        assertEquals("hea", trigger.query)
        assertEquals(listOf("h1", "h2", "h3"), filterSlashCommands(trigger.query).map { it.id })
        val at = s.consumeTrigger()!!
        assertEquals(FocusTarget(id, 2), at)
        assertEquals("x ", s.doc.block(id)!!.content.text)
        assertNull(s.trigger)
    }

    @Test
    fun slashNeedsLineStartOrWhitespace() {
        val s = session("<p>a</p>")
        val id = s.doc.blocks[0].id
        s.type(id, "/b")
        assertNull(s.trigger)
    }

    @Test
    fun mentionTriggerAndInsertion() {
        val s = session("<p>hi</p>")
        val id = s.doc.blocks[0].id
        s.type(id, " @an")
        assertEquals(TriggerKind.MENTION, s.trigger!!.kind)
        val users = listOf(MentionUser(1, "Ann Lee", "ann", null), MentionUser(2, "Bob", "bob", null))
        assertEquals(listOf(1L), filterMentionUsers(users, s.trigger!!.query).map { it.id })
        val at = s.consumeTrigger()
        s.insertToken(mentionToken("1", "Ann Lee", null), at)
        assertEquals(
            "<p>hi&nbsp;<span class=\"ql-mention\" data-user-id=\"1\" data-user-name=\"Ann Lee\" contenteditable=\"false\">\uFEFF" +
                "<span contenteditable=\"false\">@Ann Lee</span>\uFEFF</span>&nbsp;</p>",
            s.html,
        )
    }

    @Test
    fun pendingBoldAppliesToTheNextTypedText() {
        val s = session("<p>a</p>")
        val id = s.doc.blocks[0].id
        s.onFocusChanged(id, true)
        s.caret(id, 1)
        s.toggleMark(MarkType.BOLD)
        assertTrue(s.isMarkActive(MarkType.BOLD))
        s.type(id, "bc")
        assertEquals("<p>a<strong>bc</strong></p>", s.html)
    }

    @Test
    fun selectionFormattingAndLinks() {
        val s = session("<p>hello world</p>")
        val id = s.doc.blocks[0].id
        s.onFocusChanged(id, true)
        s.onValueChange(id, s.fieldValue(s.doc.block(id)!!).copy(selection = TextRange(1, 6)))
        s.toggleMark(MarkType.ITALIC)
        s.setLink("https://aino.app")
        assertEquals(
            "<p><a href=\"https://aino.app\" rel=\"noopener noreferrer\" target=\"_blank\"><em>hello</em></a>&nbsp;world</p>",
            s.html,
        )
        assertEquals("https://aino.app", s.linkAtSelection())
    }

    @Test
    fun blockTypeTogglesAndSlashSetsWithoutToggling() {
        val s = session("<p>item</p>")
        val id = s.doc.blocks[0].id
        s.onFocusChanged(id, true)
        s.setBlockType(BlockType.LIST_ITEM, listType = ListType.UNCHECKED)
        assertEquals("<ul><li data-list=\"unchecked\">item</li></ul>", s.html)
        s.setBlockType(BlockType.LIST_ITEM, listType = ListType.UNCHECKED)
        assertEquals("<p>item</p>", s.html)
        s.setBlockType(BlockType.HEADING, 2, toggle = false)
        s.setBlockType(BlockType.HEADING, 2, toggle = false)
        assertEquals("<h2>item</h2>", s.html)
    }

    @Test
    fun embedsReplaceAnEmptySlashLine() {
        val s = session("<p>top</p><p><br></p>")
        val empty = s.doc.blocks[1].id
        s.onFocusChanged(empty, true)
        s.insertBlocks(embedBlocks(EmbedKind.TIME))
        assertEquals("<p>top</p><hr><p>&nbsp;⏱&nbsp;Time&nbsp;Tracking&nbsp;(live&nbsp;embed&nbsp;below)&nbsp;</p><hr><p><br></p>", s.html)
    }

    @Test
    fun convertToTaskChecksTheLineAndAppendsTheTaskNumber() {
        val s = session("<p>Call vendor</p>")
        val id = s.doc.blocks[0].id
        s.markConverted(id, 42)
        assertEquals(
            "<ul><li data-list=\"checked\">Call&nbsp;vendor<span style=\"color: rgb(16, 185, 129);\"><em>&nbsp;→&nbsp;Task&nbsp;#42</em></span></li></ul>",
            s.html,
        )
    }
}
