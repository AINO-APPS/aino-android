package app.aino.mobile.feature.notes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookMergeTest {
    private fun nb(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.page(id: String) = pages().first { it.id == id }

    private val server = nb(
        """{"pages":[
            {"id":"a","title":"A","content":"<p>a</p>","future":{"x":1}},
            {"id":"b","title":"B","content":"<p>b</p>"}
          ],
          "folders":[{"id":"f1","name":"Work","parentId":null,"sortOrder":1}],
          "todos":[{"id":"t1","text":"keep me"}],
          "activePageId":"a","sortBy":"modified","somethingNew":true}""",
    )

    @Test
    fun onlyLocallyChangedFieldsAreAppliedOntoTheFreshServerCopy() {
        val local = nb(server.toString()).let { l ->
            l.with("pages" to JsonArray(l.arr("pages")!!.map { p ->
                val o = p.jsonObject
                if (o.str("id") == "a") o.with("content" to JsonPrimitive("<p>a local</p>")) else o
            }))
        }
        // Meanwhile the web edited page B and page A's title.
        val fresh = server.with("pages" to JsonArray(listOf(
            server.arr("pages")!![0].jsonObject.with("title" to JsonPrimitive("A (web)")),
            server.arr("pages")!![1].jsonObject.with("content" to JsonPrimitive("<p>b web</p>")),
        )))
        val pending = PendingChanges().touchPage("a", 1, "content")
        val merged = mergeNotebook(fresh, local, pending)
        assertEquals("<p>a local</p>", merged.page("a").content)
        assertEquals("A (web)", merged.page("a").title)
        assertEquals("<p>b web</p>", merged.page("b").content)
        assertEquals(JsonObject(mapOf("x" to JsonPrimitive(1))), merged.page("a").raw["future"])
        assertEquals(server["todos"], merged["todos"])
        assertEquals(JsonPrimitive(true), merged["somethingNew"])
    }

    @Test
    fun createdPagesAreAppendedAndDeletedPagesRemoved() {
        val created = newPageJson("New", id = "n", nowMillis = 1_000)
        val local = server.with("pages" to JsonArray(listOf(server.arr("pages")!![1], created)))
        val pending = PendingChanges().createPage("n", 1).deletePage("a", 2)
        val fresh = server.with("pages" to JsonArray(server.arr("pages")!! + newPageJson("Web page", id = "w")))
        val merged = mergeNotebook(fresh, local, pending)
        assertEquals(listOf("b", "w", "n"), merged.pages().map { it.id })
    }

    @Test
    fun pageEditedLocallyButDeletedRemotelyIsKept() {
        val local = server
        val fresh = server.with("pages" to JsonArray(listOf(server.arr("pages")!![1])))
        val merged = mergeNotebook(fresh, local, PendingChanges().touchPage("a", 1, "title"))
        assertEquals(listOf("b", "a"), merged.pages().map { it.id })
    }

    @Test
    fun foldersAndTopLevelKeysMerge() {
        val folder = newFolderJson("Journal", id = "f2", nowMillis = 5)
        val local = server.with(
            "folders" to JsonArray(server.arr("folders")!! + folder),
            "activePageId" to JsonPrimitive("b"),
        )
        val fresh = server.with("sortBy" to JsonPrimitive("title"))
        val pending = PendingChanges().createFolder("f2", 1).touchTop("activePageId", 2)
        val merged = mergeNotebook(fresh, local, pending)
        assertEquals(listOf("f1", "f2"), merged.folders().map { it.id })
        assertEquals(JsonPrimitive("b"), merged["activePageId"])
        assertEquals(JsonPrimitive("title"), merged["sortBy"])
    }

    @Test
    fun noServerNotebookMeansLocalWinsAndNoPendingMeansServerWins() {
        val local = nb("""{"pages":[{"id":"x"}]}""")
        assertEquals(local, mergeNotebook(null, local, PendingChanges().createPage("x", 1)))
        assertEquals(server, mergeNotebook(server, local, PendingChanges()))
    }

    @Test
    fun unknownNonObjectEntriesInPagesSurvive() {
        val fresh = nb("""{"pages":[{"id":"a","title":"A"},"weird",42]}""")
        val local = nb("""{"pages":[{"id":"a","title":"A2"}]}""")
        val merged = mergeNotebook(fresh, local, PendingChanges().touchPage("a", 1, "title"))
        assertEquals("""[{"id":"a","title":"A2"},"weird",42]""", merged["pages"].toString())
    }

    @Test
    fun savedChangesAreClearedOnlyWhenUnchangedSinceTheSnapshot() {
        val snapshot = PendingChanges().touchPage("a", 1, "content").touchPage("b", 2, "title").touchTop("activePageId", 3)
        val later = snapshot.touchPage("a", 4, "content")
        val remaining = later.minus(snapshot)
        assertEquals(mapOf("content" to 4L), remaining.pages["a"]!!.fields)
        assertNull(remaining.pages["b"])
        assertTrue(remaining.top.isEmpty())
        assertTrue(snapshot.minus(snapshot).isEmpty)
    }

    @Test
    fun pendingChangesSerializeForTheDraftStore() {
        val pending = PendingChanges().createPage("n", 1).touchPage("a", 2, "content", "updatedAt").deletePage("z", 3)
            .createFolder("f", 4).touchTop("activePageId", 5)
        assertEquals(pending, PendingChanges.fromJson(pending.toJson()))
        val subset = pendingSubset(server, PendingChanges().touchPage("a", 1, "content").touchTop("sortBy", 2))
        assertEquals(listOf("a"), subset.pages().map { it.id })
        assertEquals(JsonPrimitive("modified"), subset["sortBy"])
    }

    @Test
    fun newPageMirrorsNotesUtilsNewPage() {
        val page = newPageJson("T", "f", "p", userId = 7, nowMillis = 1_758_780_057_237, id = "id1")
        assertEquals(
            listOf("id", "title", "content", "createdAt", "updatedAt", "createdBy", "lastEditedBy", "pinned", "tags", "folderId",
                "parentPageId", "archived", "sortOrder", "icon", "coverColor", "readOnly", "properties", "reactions"),
            page.keys.toList(),
        )
        assertEquals("2025-09-25T06:00:57.237Z", page.str("createdAt"))
        assertEquals(7L, page.long("createdBy"))
        assertEquals(1_758_780_057_237L, page.long("sortOrder"))
        val folder = newFolderJson("Journal", nowMillis = 3, id = "f")
        assertEquals("""{"id":"f","name":"Journal","parentId":null,"sortOrder":3}""", folder.toString())
    }

    @Test
    fun treeHelpers() {
        val pages = listOf(
            NotePage(nb("""{"id":"r","title":"Root","content":"<a class=\"ql-pagelink\" data-page-id=\"c\">x</a>"}""")),
            NotePage(nb("""{"id":"c","title":"Child","parentPageId":"r"}""")),
            NotePage(nb("""{"id":"g","title":"Grand","parentPageId":"c"}""")),
            NotePage(nb("""{"id":"loop","parentPageId":"loop"}""")),
        )
        assertEquals(listOf("c", "g"), descendantPageIds("r", pages))
        assertEquals(listOf("r", "c"), pageAncestors("g", pages).map { it.id })
        assertEquals(listOf("r"), backlinks("c", pages).map { it.id })
        assertEquals("Untitled", pages[3].title)
        assertTrue(pageAncestors("loop", pages).isEmpty())
        val folders = listOf(NoteFolder(nb("""{"id":"1","name":"Work"}""")), NoteFolder(nb("""{"id":"2","name":"Q1","parentId":"1"}""")))
        assertEquals("Work / Q1", folderPath("2", folders))
        assertEquals(listOf("2"), descendantFolderIds("1", folders))
    }

    @Test
    fun relativeDatesMatchTheWeb() {
        val now = parseIsoMillis("2026-09-25T12:00:00.000Z")!!
        assertEquals("just now", relativeFromNow("2026-09-25T11:59:50.000Z", now))
        assertEquals("5 min ago", relativeFromNow("2026-09-25T11:55:00.000Z", now))
        assertEquals("3 hr ago", relativeFromNow("2026-09-25T09:00:00.000Z", now))
        assertEquals("2d ago", relativeFromNow("2026-09-23T12:00:00.000Z", now))
        assertEquals("Today", formatNoteDate("2026-09-25T01:00:00.000Z", now))
        assertEquals("Yesterday", formatNoteDate("2026-09-24T01:00:00.000Z", now))
    }
}
