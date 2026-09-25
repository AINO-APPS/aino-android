package app.aino.mobile.feature.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteEditingTest {
    private fun bold(start: Int, end: Int) = Mark(start, end, MarkType.BOLD)

    @Test
    fun typingInsideOrAtEndOfBoldContinuesBold() {
        val c = InlineContent("ab cd", listOf(bold(0, 2)))
        assertEquals(listOf(bold(0, 3)), c.edit("abX cd", 3).marks)
        assertEquals(listOf(bold(0, 3)), c.edit("aXb cd", 2).marks)
        assertEquals(listOf(bold(0, 2)), c.edit("ab Xcd", 4).marks)
    }

    @Test
    fun typingAtBlockStartInheritsTheFirstCharacterFormat() {
        val c = InlineContent("ab", listOf(bold(0, 2)))
        assertEquals(listOf(bold(0, 3)), c.edit("Xab", 1).marks)
    }

    @Test
    fun linksDoNotGrowWhenTypingAfterThem() {
        val link = Mark(0, 4, MarkType.LINK, href = "https://a")
        val c = InlineContent("site", listOf(link))
        assertEquals(listOf(link), c.edit("site!", 5).marks)
    }

    @Test
    fun repeatedCharactersUseTheCaretToPlaceTheInsertion() {
        val c = InlineContent("aa", listOf(bold(0, 1)))
        // Typing an "a" right after the bold "a" (caret 2) extends bold rather than shifting it.
        assertEquals(listOf(bold(0, 2)), c.edit("aaa", 2).marks)
    }

    @Test
    fun deletingATokenPlaceholderDropsTheToken() {
        val t1 = mentionToken("1", "A", null)
        val t2 = pageLinkToken("p", "P")
        val c = InlineContent("x${TOKEN_CHAR}y$TOKEN_CHAR", emptyList(), listOf(t1, t2))
        val after = c.edit("xy$TOKEN_CHAR", 1)
        assertEquals(listOf(t2), after.tokens)
        assertEquals("<p>xy${t2.html}</p>", blockHtml(NoteBlock(type = BlockType.PARAGRAPH, content = after, dirty = true)))
    }

    @Test
    fun strayPlaceholderCharactersFromTheImeAreStripped() {
        val after = InlineContent("ab").edit("a${TOKEN_CHAR}b", 2)
        assertEquals("ab", after.text)
        assertTrue(after.tokens.isEmpty())
    }

    @Test
    fun deletingASelectionClipsMarks() {
        val c = InlineContent("hello world", listOf(bold(0, 5), Mark(6, 11, MarkType.ITALIC)))
        val after = c.edit("helrld", 3)
        assertEquals("helrld", after.text)
        assertEquals(listOf(bold(0, 3), Mark(3, 6, MarkType.ITALIC)), after.marks)
    }

    @Test
    fun pendingTogglesApplyToTypedText() {
        val after = InlineContent("ab").edit("abc", 3, mapOf(MarkType.BOLD to true))
        assertEquals(listOf(Mark(2, 3, MarkType.BOLD)), after.marks)
        assertEquals("ab<strong>c</strong>", inlineToHtml(after))
    }

    @Test
    fun toggleMarkAddsMergesAndRemoves() {
        val c = InlineContent("hello world")
        val bolded = c.toggleMark(0, 5, MarkType.BOLD)
        assertTrue(bolded.hasMark(0, 5, MarkType.BOLD))
        assertEquals("<strong>hello</strong>&nbsp;world", inlineToHtml(bolded))
        val merged = bolded.toggleMark(3, 8, MarkType.BOLD)
        assertEquals(listOf(Mark(0, 8, MarkType.BOLD)), merged.marks)
        val removed = merged.toggleMark(2, 4, MarkType.BOLD)
        assertEquals("<strong>he</strong>ll<strong>o&nbsp;wo</strong>rld", inlineToHtml(removed))
    }

    @Test
    fun overlappingMarksNestLikeQuill() {
        val c = InlineContent("abc").addMark(0, 3, MarkType.ITALIC).addMark(1, 2, MarkType.BOLD).setLink(0, 3, "https://x")
        assertEquals(
            "<a href=\"https://x\" rel=\"noopener noreferrer\" target=\"_blank\"><em>a<strong>b</strong>c</em></a>",
            inlineToHtml(c),
        )
    }

    @Test
    fun enterSplitsBlocksWithQuillFormatCarryOver() {
        val doc = parseNoteDoc("<h2>Title here</h2><ul><li data-list=\"checked\">done item</li></ul><blockquote>q1q2</blockquote>")
        val heading = doc.blocks[0]
        val (d1, f1) = doc.splitAt(heading.id, 5)
        assertEquals("<h2>Title</h2><p>&nbsp;here</p>", d1.toHtml().substringBefore("<ul>"))
        assertEquals(0, f1.position)
        val item = doc.blocks[1]
        val (d2, f2) = doc.splitAt(item.id, 4)
        val newItem = d2.block(f2.blockId)!!
        assertEquals(ListType.UNCHECKED, newItem.listType)
        assertTrue(d2.toHtml().contains("<ul><li data-list=\"checked\">done</li></ul><ul><li data-list=\"unchecked\">&nbsp;item</li></ul>"))
        val quote = doc.blocks[2]
        val (d3, _) = doc.splitAt(quote.id, 2)
        assertTrue(d3.toHtml().endsWith("<blockquote>q1</blockquote><blockquote>q2</blockquote>"))
    }

    @Test
    fun enterOnAnEmptyListItemLeavesTheList() {
        val doc = parseNoteDoc("<ul><li>a</li><li><br></li></ul>")
        val empty = doc.blocks[1]
        val (next, focus) = doc.splitAt(empty.id, 0)
        assertEquals(empty.id, focus.blockId)
        assertEquals("<ul><li>a</li></ul><p><br></p>", next.toHtml())
    }

    @Test
    fun backspaceAtStartMergesAndOutdents() {
        val doc = parseNoteDoc("<p><strong>ab</strong></p><p>c<em>d</em></p><ul><li>x<ul><li>y</li></ul></li></ul><hr><p>z</p>")
        val (merged, focus) = doc.backspaceAtStart(doc.blocks[1].id)
        assertEquals(FocusTarget(doc.blocks[0].id, 2), focus)
        assertTrue(merged.toHtml().startsWith("<p><strong>ab</strong>c<em>d</em></p>"))
        val y = doc.blocks[3]
        val (outdented, _) = doc.backspaceAtStart(y.id)
        assertEquals(0, outdented.block(y.id)!!.indent)
        val (unlisted, _) = doc.backspaceAtStart(doc.blocks[2].id)
        assertEquals(BlockType.PARAGRAPH, unlisted.block(doc.blocks[2].id)!!.type)
        val z = doc.blocks.last()
        val (noDivider, _) = doc.backspaceAtStart(z.id)
        assertFalse(noDivider.blocks.any { it.type == BlockType.DIVIDER })
    }

    @Test
    fun backspaceNeverDeletesAReadOnlyBlock() {
        val doc = parseNoteDoc("<table><tr><td>1</td></tr></table><p>after</p>")
        val (same, focus) = doc.backspaceAtStart(doc.blocks[1].id)
        assertNull(focus)
        assertEquals(doc.toHtml(), same.toHtml())
    }

    @Test
    fun setTypeConvertsBetweenFormats() {
        val doc = parseNoteDoc("<p>a <strong>b</strong></p>")
        val id = doc.blocks[0].id
        assertEquals("<h1>a&nbsp;<strong>b</strong></h1>", doc.setType(id, BlockType.HEADING, 1).toHtml())
        assertEquals("<ol><li>a&nbsp;<strong>b</strong></li></ol>", doc.setType(id, BlockType.LIST_ITEM, listType = ListType.ORDERED).toHtml())
        assertEquals("<ul><li data-list=\"unchecked\">a&nbsp;<strong>b</strong></li></ul>", doc.setType(id, BlockType.LIST_ITEM, listType = ListType.UNCHECKED).toHtml())
        val code = doc.setType(id, BlockType.CODE, language = "python")
        assertEquals("<pre data-language=\"python\">\na b\n</pre>", code.toHtml())
        val codeDoc = parseNoteDoc("<pre data-language=\"plain\">\nx\ny\n</pre>")
        assertEquals("<p>x</p><p>y</p>", codeDoc.setType(codeDoc.blocks[0].id, BlockType.PARAGRAPH).toHtml())
    }

    @Test
    fun toggleCheckAndIndent() {
        val doc = parseNoteDoc("<ul><li data-list=\"unchecked\">t</li></ul>")
        val id = doc.blocks[0].id
        assertEquals("<ul><li data-list=\"checked\">t</li></ul>", doc.toggleCheck(id).toHtml())
        assertEquals("<ul><li><ul><li data-list=\"unchecked\">t</li></ul></li></ul>", doc.indent(id, 1).toHtml())
    }

    @Test
    fun embedsTocAndRemoval() {
        val doc = parseNoteDoc("<h1>A</h1><h2>B</h2><p>x</p>")
        val inserted = doc.insertAfter(doc.blocks.last().id, embedBlocks(EmbedKind.SPRINT))
        assertEquals(
            "<h1>A</h1><h2>B</h2><p>x</p><hr><p>&nbsp;📊&nbsp;Sprint&nbsp;Board&nbsp;(live&nbsp;embed&nbsp;below)&nbsp;</p><hr>",
            inserted.toHtml(),
        )
        val marker = inserted.blocks.first { it.embed == EmbedKind.SPRINT }
        assertEquals(doc.toHtml(), inserted.removeEmbed(marker.id).serializeBlocks().let { it })
        val toc = tocBlocks(doc)!!
        assertEquals(
            "<h3>Table&nbsp;of&nbsp;contents</h3><ul><li>A</li><li>&nbsp;&nbsp;B</li></ul>",
            NoteDoc(toc.map { it.copy(dirty = true) }, "", emptyList()).toHtml(),
        )
        assertNull(tocBlocks(parseNoteDoc("<p>no headings</p>")))
    }

    @Test
    fun replaceRangeInsertsTokensWithMarksShifted() {
        val c = InlineContent("hi @bo", listOf(bold(0, 2)))
        val token = mentionToken("9", "Bob", null)
        val out = c.replaceRange(3, 6, tokenContent(token) + InlineContent(" "))
        assertEquals("hi $TOKEN_CHAR ", out.text)
        assertEquals(listOf(bold(0, 2)), out.marks)
        assertEquals(listOf(token), out.tokens)
    }

    @Test
    fun wordCountMatchesTheWeb() {
        assertEquals(3 to 13, wordCount("<p>one two</p><p> three</p>"))
        assertEquals(0 to 0, wordCount(""))
    }
}
