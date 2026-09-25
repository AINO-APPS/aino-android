package app.aino.mobile.feature.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteHtmlRoundTripTest {
    private fun NoteDoc.dirtyAll() = copy(blocks = blocks.map { it.copy(dirty = true) })

    private fun assertLossless(html: String) {
        val doc = parseNoteDoc(html)
        assertEquals("toHtml of an untouched doc", html, doc.toHtml())
        assertEquals("regen path of an untouched doc", html, doc.serializeBlocks())
        assertFalse(doc.isModified)
    }

    /** Canonical Quill 2 `getSemanticHTML` output regenerates byte-identically even when every block is dirty. */
    private fun assertCanonical(html: String) {
        assertLossless(html)
        assertEquals("regenerated from the model", html, parseNoteDoc(html).dirtyAll().serializeBlocks())
    }

    @Test
    fun emptyContentStaysEmpty() {
        val doc = parseNoteDoc("")
        assertEquals(1, doc.blocks.size)
        assertEquals(BlockType.PARAGRAPH, doc.blocks[0].type)
        assertEquals("", doc.toHtml())
        assertEquals("", doc.serializeBlocks())
    }

    @Test
    fun canonicalQuillSemanticHtmlRegeneratesExactly() {
        assertCanonical(
            "<h1>Title</h1><p>Hello&nbsp;<strong>bold</strong>&nbsp;and&nbsp;<em>it</em></p>" +
                "<ul><li>a</li><li>b<ul><li>c</li></ul></li></ul><ol><li>one</li><li>two</li></ol>" +
                "<ul><li data-list=\"checked\">done</li></ul><ul><li data-list=\"unchecked\">todo</li></ul>" +
                "<blockquote>quote</blockquote><pre data-language=\"plain\">\nline1\nline2\n</pre><hr><p><br></p>",
        )
    }

    @Test
    fun parsesBlockStructure() {
        val doc = parseNoteDoc(
            "<h2>H</h2><ul><li>a</li><li>b<ul><li>c</li></ul></li></ul><ul><li data-list=\"checked\">x</li>" +
                "<li data-list=\"unchecked\">y</li></ul><pre data-language=\"python\">\nprint(1)\n</pre><hr>",
        )
        val types = doc.blocks.map { it.type }
        assertEquals(
            listOf(BlockType.HEADING, BlockType.LIST_ITEM, BlockType.LIST_ITEM, BlockType.LIST_ITEM, BlockType.LIST_ITEM, BlockType.LIST_ITEM, BlockType.CODE, BlockType.DIVIDER),
            types,
        )
        assertEquals(2, doc.blocks[0].level)
        assertEquals(listOf(0, 0, 1), doc.blocks.subList(1, 4).map { it.indent })
        assertEquals(ListType.CHECKED, doc.blocks[4].listType)
        assertEquals(ListType.UNCHECKED, doc.blocks[5].listType)
        assertEquals("print(1)", doc.blocks[6].content.text)
        assertEquals("python", doc.blocks[6].language)
    }

    @Test
    fun mentionsAndPageLinksAreAtomicAndRoundTrip() {
        val html = "<p>Hi&nbsp;<span class=\"ql-mention\" data-user-id=\"5\" data-user-name=\"Bob\" contenteditable=\"false\">\uFEFF" +
            "<span contenteditable=\"false\">@Bob</span>\uFEFF</span>&nbsp;see&nbsp;<a class=\"ql-pagelink\" data-page-id=\"p1\" " +
            "data-page-title=\"Plan\" href=\"#\" contenteditable=\"false\">Plan</a></p>"
        assertCanonical(html)
        val content = parseNoteDoc(html).blocks.single().content
        assertEquals("Hi $TOKEN_CHAR see $TOKEN_CHAR", content.text)
        assertEquals(listOf(TokenKind.MENTION, TokenKind.PAGE_LINK), content.tokens.map { it.kind })
        assertEquals("@Bob", content.tokens[0].label)
        assertEquals("5", content.tokens[0].attrs["id"])
        assertEquals("Plan", content.tokens[1].label)
        assertEquals("p1", content.tokens[1].attrs["id"])
        assertEquals("Hi @Bob see Plan", content.plainText())
    }

    @Test
    fun generatedMentionAndPageLinkMatchQuillBlots() {
        assertEquals(
            "<span class=\"ql-mention\" data-user-id=\"7\" data-user-name=\"Ann Lee\" data-user-avatar=\"/u/a.png\" contenteditable=\"false\">" +
                "\uFEFF<span contenteditable=\"false\">@Ann Lee</span>\uFEFF</span>",
            mentionToken("7", "Ann Lee", "/u/a.png").html,
        )
        assertEquals(
            "<a class=\"ql-pagelink\" data-page-id=\"x\" data-page-title=\"My &quot;Page&quot;\" href=\"#\" contenteditable=\"false\">My&nbsp;\"Page\"</a>",
            pageLinkToken("x", "My \"Page\"").html,
        )
    }

    @Test
    fun unknownBlocksArePreservedByteForByteWhenAnotherBlockIsEdited() {
        val rest = "<div class=\"ql-callout\" data-callout=\"info\"><p>x</p></div>" +
            "<table style=\"border: 1px solid #000;\"><tbody><tr><td>1</td></tr></tbody></table>" +
            "<div class=\"ql-drawio\" data-xml=\"%3Cmx%3E\" contenteditable=\"false\"><svg><path d=\"M0 0\"/></svg><div class=\"ql-drawio-overlay\">Click to edit</div></div>" +
            "<div class=\"ql-math\" data-tex=\"E=mc^2\">$$ E=mc^2 $$</div>" +
            "<div class=\"ql-audio\" data-src=\"data:audio/webm;base64,AAAA\" data-label=\"Rec\"><audio controls src=\"data:audio/webm;base64,AAAA\"></audio></div>" +
            "<iframe class=\"ql-video\" frameborder=\"0\" allowfullscreen=\"true\" src=\"https://www.youtube.com/embed/x\"></iframe>" +
            "<p><img src=\"data:image/png;base64,iVBOR\" width=\"120\"></p><p>b</p>"
        val html = "<p>a</p>$rest"
        val doc = parseNoteDoc(html)
        val kinds = doc.blocks.filter { it.type == BlockType.OPAQUE }.map { it.opaqueKind }
        assertEquals(
            listOf(OpaqueKind.CALLOUT, OpaqueKind.TABLE, OpaqueKind.DRAWIO, OpaqueKind.MATH, OpaqueKind.AUDIO, OpaqueKind.VIDEO, OpaqueKind.IMAGE),
            kinds,
        )
        val first = doc.blocks.first()
        val edited = doc.update(first.id) { it.copy(content = it.content.edit("a2", 2)) }
        assertEquals("<p>a2</p>$rest", edited.toHtml())
    }

    @Test
    fun unknownAttributesOnBlocksAndInlineWrappersSurviveEdits() {
        val html = "<p class=\"ql-align-center\" data-foo=\"1\">x<span style=\"color: rgb(230, 0, 0);\" data-x=\"y\">red</span></p>"
        val doc = parseNoteDoc(html)
        val block = doc.blocks.single()
        val edited = doc.update(block.id) { it.copy(content = it.content.edit("xredz", 5)) }
        assertEquals("<p class=\"ql-align-center\" data-foo=\"1\">x<span style=\"color: rgb(230, 0, 0);\" data-x=\"y\">redz</span></p>", edited.toHtml())
        assertEquals("rgb(230, 0, 0)", block.content.marks.single().color)
    }

    @Test
    fun linksRoundTrip() {
        assertCanonical("<p><a href=\"https://x.com/a?b=1&amp;c=2\" rel=\"noopener noreferrer\" target=\"_blank\">site</a></p>")
        val mark = parseNoteDoc("<p><a href=\"https://x.com/a?b=1&amp;c=2\">site</a></p>").blocks.single().content.marks.single()
        assertEquals(MarkType.LINK, mark.type)
        assertEquals("https://x.com/a?b=1&c=2", mark.href)
    }

    @Test
    fun internalInnerHtmlFormatListsAndCodeBlocksParse() {
        val html = "<ol><li data-list=\"bullet\"><span class=\"ql-ui\" contenteditable=\"false\"></span>a</li>" +
            "<li data-list=\"bullet\" class=\"ql-indent-1\"><span class=\"ql-ui\" contenteditable=\"false\"></span>b</li>" +
            "<li data-list=\"ordered\"><span class=\"ql-ui\" contenteditable=\"false\"></span>c</li></ol>" +
            "<div class=\"ql-code-block-container\" spellcheck=\"false\"><div class=\"ql-code-block\" data-language=\"plain\">x</div>" +
            "<div class=\"ql-code-block\" data-language=\"plain\">y</div></div>"
        assertLossless(html)
        val doc = parseNoteDoc(html)
        val items = doc.blocks.filter { it.type == BlockType.LIST_ITEM }
        assertEquals(listOf(ListType.BULLET, ListType.BULLET, ListType.ORDERED), items.map { it.listType })
        assertEquals(listOf(0, 1, 0), items.map { it.indent })
        assertEquals("a", items[0].content.text)
        val code = doc.blocks.last()
        assertEquals(BlockType.CODE, code.type)
        assertEquals("x\ny", code.content.text)
        // Editing regenerates Quill semantic HTML; untouched items keep their inner source.
        val edited = doc.update(items[0].id) { it.copy(content = it.content.edit("a!", 2)) }
        assertEquals(
            "<ul><li>a!<ul><li>b</li></ul></li></ul><ol><li>c</li></ol>" +
                "<div class=\"ql-code-block-container\" spellcheck=\"false\"><div class=\"ql-code-block\" data-language=\"plain\">x</div>" +
                "<div class=\"ql-code-block\" data-language=\"plain\">y</div></div>",
            edited.toHtml(),
        )
    }

    @Test
    fun indentJumpsUseEmptyWrapperItemsLikeQuill() {
        assertCanonical("<ul><li><ul><li>deep</li></ul></li></ul>")
        assertEquals(1, parseNoteDoc("<ul><li><ul><li>deep</li></ul></li></ul>").blocks.single().indent)
    }

    @Test
    fun mixedListTypesAtOneLevelRegenerateLikeQuill() {
        assertCanonical("<ol><li>1</li></ol><ul><li>b</li><li>c<ol><li>n</li></ol></li></ul><ul><li data-list=\"checked\">k</li></ul>")
    }

    @Test
    fun templateWhitespaceIsPreservedAroundEditedBlocks() {
        val html = noteTemplate("meeting").html(NotesClock(java.time.ZonedDateTime.parse("2026-09-25T10:00:00Z"), java.util.Locale.US))
        assertLossless(html)
        val doc = parseNoteDoc(html)
        val attendees = doc.blocks.first { it.content.plainText().startsWith("Attendees") }
        val edited = doc.update(attendees.id) { it.copy(content = it.content.edit("Attendees: Ann", 14)) }
        val out = edited.toHtml()
        assertTrue(out, out.contains("\n      <p><strong>Attendees:</strong>&nbsp;Ann</p>\n      <p><strong>Date / time:</strong>"))
        assertEquals(html.replace("<p><strong>Attendees:</strong> </p>", "<p><strong>Attendees:</strong>&nbsp;Ann</p>"), out)
    }

    @Test
    fun implicitTopLevelTextBecomesAParagraphOnlyWhenEdited() {
        assertLossless("plain <b>text</b>")
        val doc = parseNoteDoc("plain <b>text</b>")
        val block = doc.blocks.single()
        assertTrue(block.implicit)
        val edited = doc.update(block.id) { it.copy(content = it.content.edit("plain text!", 11)) }
        assertEquals("<p>plain&nbsp;<b>text!</b></p>", edited.toHtml())
    }

    @Test
    fun adjacentListGroupsKeepTheirSourceIndependently() {
        val html = "<ul><li>a</li></ul>\n<ul><li>b</li></ul>"
        val doc = parseNoteDoc(html)
        val b = doc.blocks[1]
        val edited = doc.update(b.id) { it.copy(content = it.content.edit("bb", 2)) }
        assertEquals("<ul><li>a</li></ul>\n<ul><li>bb</li></ul>", edited.toHtml())
    }

    @Test
    fun entitiesDecodeAndReencode() {
        val doc = parseNoteDoc("<p>a &amp; b &lt;c&gt; &quot;q&quot; &#128512; &unknown;</p>")
        val block = doc.blocks.single()
        assertEquals("a & b <c> \"q\" 😀 &unknown;", block.content.text)
        assertEquals("<p>a&nbsp;&amp;&nbsp;b&nbsp;&lt;c&gt;&nbsp;\"q\"&nbsp;😀&nbsp;&amp;unknown;</p>", doc.dirtyAll().serializeBlocks())
    }

    @Test
    fun malformedHtmlIsStillLossless() {
        assertLossless("<p>unclosed<p>next")
        assertLossless("<p>a</p></div><p>b</p>")
        assertLossless("<!-- note --><p>a</p> <p>b</p>\n")
        assertLossless("<p>a<b>bold</p><ul><li>x<li>y</ul>")
        assertLossless("<p>1 < 2 and 3 > 2</p>")
        val doc = parseNoteDoc("<!-- note --><p>a</p><p>b</p>")
        val last = doc.blocks.last()
        assertEquals("<!-- note --><p>a</p><p>bc</p>", doc.update(last.id) { it.copy(content = it.content.edit("bc", 2)) }.toHtml())
    }

    @Test
    fun listItemsWithBlockChildrenAreReadOnly() {
        val html = "<ul><li><p>para in li</p></li></ul>"
        val doc = parseNoteDoc(html)
        assertEquals(BlockType.OPAQUE, doc.blocks.single().type)
        assertLossless(html)
    }

    @Test
    fun imageOnlyParagraphIsAnImageBlockAndInlineImagesAreTokens() {
        val img = parseNoteDoc("<p><img src=\"data:image/png;base64,AA\"></p>").blocks.single()
        assertEquals(OpaqueKind.IMAGE, img.opaqueKind)
        val mixed = parseNoteDoc("<p>see <img src=\"/uploads/x.png\"> here</p>").blocks.single()
        assertEquals(BlockType.PARAGRAPH, mixed.type)
        assertEquals(TokenKind.IMAGE, mixed.content.tokens.single().kind)
        assertEquals("/uploads/x.png", mixed.content.tokens.single().attrs["src"])
    }

    @Test
    fun softBreaksAndDateChipsAreTokens() {
        val doc = parseNoteDoc("<p><strong>Sep 25</strong></p><p>line1<br>line2</p><p>due <span class=\"ql-datechip\" data-date=\"2026-09-25\" contenteditable=\"false\">&nbsp;</span></p>")
        assertEquals(TokenKind.BREAK, doc.blocks[1].content.tokens.single().kind)
        assertEquals("line1\nline2", doc.blocks[1].content.plainText())
        assertEquals(TokenKind.DATE, doc.blocks[2].content.tokens.single().kind)
        assertEquals("<p>line1<br>line2</p>", blockHtml(doc.blocks[1].copy(dirty = true)))
    }

    @Test
    fun embedMarkersAreDetected() {
        val doc = parseNoteDoc("<hr><p>&nbsp;📊&nbsp;Sprint&nbsp;Board&nbsp;(live&nbsp;embed&nbsp;below)&nbsp;</p><hr><p> ⏱ Time Tracking (live embed below) </p>")
        assertEquals(EmbedKind.SPRINT, doc.blocks[1].embed)
        assertEquals(EmbedKind.TIME, doc.blocks[3].embed)
        assertFalse(doc.blocks[1].isEditable)
        assertNull(parseNoteDoc("<p>hello</p>").blocks.single().embed)
    }

    @Test
    fun plainTextStripsTagsLikeStripHtml() {
        assertEquals("ab c", htmlToPlainText("<p>a</p><p>b&nbsp;c</p>").replace('\u00A0', ' '))
        assertEquals("", htmlToPlainText(null))
    }
}
