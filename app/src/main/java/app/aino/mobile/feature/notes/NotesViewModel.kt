package app.aino.mobile.feature.notes

import android.content.Context
import app.aino.mobile.core.AppContainer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class SaveStatus { Idle, Pending, Saving, Saved, Failed }

data class NotesUiState(
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Shown only while nothing has loaded yet. */
    val error: String? = null,
    val pages: List<NotePage> = emptyList(),
    val folders: List<NoteFolder> = emptyList(),
    val save: SaveStatus = SaveStatus.Idle,
    val saveError: String? = null,
    val mentionableUsers: List<MentionUser> = emptyList(),
    /** Transient feedback (toast/snackbar); cleared by [NotesViewModel.consumeMessage]. */
    val message: String? = null,
    val busy: Boolean = false,
) {
    val activePages: List<NotePage> get() = pages.filter { !it.archived }
    fun page(id: String): NotePage? = pages.firstOrNull { it.id == id }
}

/**
 * Notes state (port of `useNotesStore` + `useNotesPersistence`), Activity-scoped.
 *
 * The notebook is one JSON blob per user that `PUT /notes` replaces wholesale.
 * It is kept as raw JSON; every local edit is recorded in [PendingChanges] and
 * each save re-fetches the server copy and replays only those entities/fields
 * onto it ([mergeNotebook]), so edits made on the web to other pages survive.
 * Content edits save after the web's 10 s debounce; structural edits at once.
 */
class NotesViewModel(
    private val repository: NotesRepository,
    private val drafts: NotesDraftStore? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> NotesClock = { NotesClock() },
) : ViewModel() {
    private val _ui = MutableStateFlow(NotesUiState())
    val ui: StateFlow<NotesUiState> = _ui.asStateFlow()

    /** The open editor (Compose snapshot state so text fields stay in sync with the IME). */
    var editor by mutableStateOf<NoteEditorSession?>(null)
        private set

    private var local: JsonObject = JsonObject(emptyMap())
    private var pending = PendingChanges()
    private var version = 0L
    private var userId: Long? = null
    private var generation = 0L

    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var saveDeadline = Long.MAX_VALUE
    private var commitJob: Job? = null
    private var flashJob: Job? = null
    private var retryDelayMs = RETRY_START_MS
    private val saveMutex = Mutex()
    private var mentionablesLoaded = false

    /** Screens pass the signed-in user; a different user resets and reloads. */
    fun bindUser(id: Long) {
        if (userId == id) {
            if (!_ui.value.loaded && loadJob?.isActive != true) refresh()
            return
        }
        if (userId != null) reset()
        userId = id
        drafts?.read(id)?.let { (draftPending, subset) ->
            pending = draftPending
            local = mergeNotebook(JsonObject(emptyMap()), subset, draftPending).let { if (it.isEmpty()) subset else it }
            version = draftPending.maxVersion()
        }
        refresh()
    }

    fun refresh() {
        if (loadJob?.isActive == true) return
        val gen = generation
        _ui.update { it.copy(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val fresh = withContext(io) { repository.load() }
                if (gen != generation) return@launch
                applyFresh(fresh)
                _ui.update { it.copy(loaded = true, error = null) }
                if (!pending.isEmpty) scheduleSave(immediate = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (gen == generation && !_ui.value.loaded) _ui.update { it.copy(error = error.message ?: "Failed to load notes") }
            } finally {
                if (gen == generation) _ui.update { it.copy(loading = false) }
            }
        }
        loadMentionables()
    }

    /** Sign-out: drop all in-memory state (unsynced edits stay in the per-user draft). */
    fun reset() {
        generation++
        loadJob?.cancel()
        saveJob?.cancel()
        commitJob?.cancel()
        flashJob?.cancel()
        loadJob = null
        saveJob = null
        saveDeadline = Long.MAX_VALUE
        local = JsonObject(emptyMap())
        pending = PendingChanges()
        version = 0
        userId = null
        editor = null
        mentionablesLoaded = false
        retryDelayMs = RETRY_START_MS
        _ui.value = NotesUiState()
    }

    /** Persists pending edits now (leaving the editor, app background). */
    fun flush() {
        commitJob?.cancel()
        commitEditor()
        persistDraft()
        if (pending.isEmpty) return
        saveJob?.cancel()
        saveDeadline = Long.MAX_VALUE
        saveJob = viewModelScope.launch { saveNow() }
    }

    /**
     * The web's notes store reacts to no realtime events (collaboration runs
     * over a separate Yjs socket; `note_mention` arrives as a generic
     * notification). Kept for the shell's uniform wiring; returns false.
     */
    @Suppress("UNUSED_PARAMETER")
    fun onRealtimeEvent(type: String, data: JsonElement?): Boolean = false

    fun consumeMessage() = _ui.update { it.copy(message = null) }

    private fun message(text: String) = _ui.update { it.copy(message = text) }

    // ── Loading / merging ─────────────────────────────────────────────────

    private fun applyFresh(fresh: JsonObject?) {
        commitEditor()
        local = mergeNotebook(fresh, local, pending)
        publish()
        val session = editor ?: return
        val page = _ui.value.page(session.pageId) ?: return
        val editedLocally = pending.pages[session.pageId]?.fields?.containsKey("content") == true
        if (!editedLocally && !session.doc.isModified && page.content != session.doc.original) {
            editor = newSession(page)
        }
    }

    private fun loadMentionables() {
        if (mentionablesLoaded) return
        mentionablesLoaded = true
        val gen = generation
        viewModelScope.launch {
            runCatching { withContext(io) { repository.mentionableUsers() } }
                .onSuccess { users -> if (gen == generation) _ui.update { it.copy(mentionableUsers = users) } }
                .onFailure { mentionablesLoaded = false }
        }
    }

    private fun publish() {
        _ui.update { it.copy(pages = local.pages(), folders = local.folders()) }
    }

    // ── Local mutations ───────────────────────────────────────────────────

    private fun pagesArray(): List<JsonElement> = local.arr("pages").orEmpty()
    private fun foldersArray(): List<JsonElement> = local.arr("folders").orEmpty()

    private fun setPages(list: List<JsonElement>) {
        local = local.with("pages" to JsonArray(list))
    }

    private fun setFolders(list: List<JsonElement>) {
        local = local.with("folders" to JsonArray(list))
    }

    private fun updatePage(id: String, fields: Map<String, JsonElement>, immediate: Boolean = true) {
        if (fields.isEmpty()) return
        val v = ++version
        setPages(pagesArray().map { el ->
            val obj = el as? JsonObject
            if (obj?.str("id") == id) JsonObject(obj + fields) else el
        })
        pending = pending.touchPage(id, v, *fields.keys.toTypedArray())
        publish()
        scheduleSave(immediate)
    }

    private fun addPage(page: JsonObject) {
        val v = ++version
        setPages(pagesArray() + page)
        pending = pending.createPage(page.str("id").orEmpty(), v)
        publish()
        scheduleSave(immediate = true)
    }

    private fun removePages(ids: Set<String>) {
        if (ids.isEmpty()) return
        val v = ++version
        setPages(pagesArray().filterNot { (it as? JsonObject)?.str("id") in ids })
        ids.forEach { pending = pending.deletePage(it, v) }
        publish()
        scheduleSave(immediate = true)
    }

    private fun addFolder(folder: JsonObject) {
        val v = ++version
        setFolders(foldersArray() + folder)
        pending = pending.createFolder(folder.str("id").orEmpty(), v)
    }

    private fun setTop(key: String, value: JsonElement) {
        if (local[key] == value) return
        local = local.with(key to value)
        pending = pending.touchTop(key, ++version)
    }

    private fun page(id: String): NotePage? = _ui.value.page(id)

    private fun now() = isoNow()

    private fun userJson(): JsonElement = jsonOf(userId)

    // ── Page operations (useNotesStore handlers) ───────────────────────────

    /** `handleNewPage` / `handleNewSubPage`. Returns the new page id. */
    fun createPage(title: String = "Untitled", folderId: String? = null, parentPageId: String? = null, content: String = ""): String {
        val page = newPageJson(title.trim().ifEmpty { "Untitled" }, folderId, parentPageId, userId, content)
        addPage(page)
        val id = page.str("id").orEmpty()
        setTop("activePageId", JsonPrimitive(id))
        return id
    }

    fun createSubPage(parentId: String): String = createPage("Untitled", page(parentId)?.folderId, parentId)

    /** `handleNewFromTemplate`: template folders are found (case-insensitively, top level) or created. */
    fun createFromTemplate(templateId: String): String {
        val c = clock()
        val tpl = noteTemplate(templateId)
        val folderId = tpl.folderName?.let(::findOrCreateRootFolder)
        return createPage(tpl.title(c).ifEmpty { "Untitled" }, folderId, null, tpl.html(c))
    }

    private fun findOrCreateRootFolder(name: String): String {
        local.folders().firstOrNull { it.name.equals(name, ignoreCase = true) && it.parentId == null }?.let { return it.id }
        val folder = newFolderJson(name, null)
        addFolder(folder)
        return folder.str("id").orEmpty()
    }

    /** `handleOpenTodayJournal`: reuse today's entry, else prefill from `GET notes/daily-prefill`. */
    fun openTodayJournal(onOpen: (String) -> Unit) {
        val c = clock()
        val tpl = noteTemplate("journal")
        val title = tpl.title(c)
        _ui.value.pages.firstOrNull { it.rawTitle == title && !it.archived }?.let { onOpen(it.id); return }
        val gen = generation
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val html = runCatching { withContext(io) { repository.dailyPrefill() } }
                .map { buildJournalPrefillHtml(it, c) }
                .getOrElse { tpl.html(c) }
            if (gen != generation) return@launch
            _ui.update { it.copy(busy = false) }
            _ui.value.pages.firstOrNull { it.rawTitle == title && !it.archived }?.let { onOpen(it.id); return@launch }
            val folderId = findOrCreateRootFolder(tpl.folderName ?: "Journal")
            onOpen(createPage(title, folderId, null, html))
        }
    }

    /** `handleNewOneOnOneWithPrefill` for a direct report. */
    fun newOneOnOne(reportId: Long, folderId: String?, onOpen: (String) -> Unit) {
        val c = clock()
        val gen = generation
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            var html = noteTemplate("oneonone").html(c)
            var reportName = "Team member"
            runCatching { withContext(io) { repository.oneOnOnePrefill(reportId) } }.onSuccess {
                reportName = it.reportName?.ifEmpty { null } ?: reportName
                html = buildOneOnOnePrefillHtml(it, c)
            }
            if (gen != generation) return@launch
            _ui.update { it.copy(busy = false) }
            val title = oneOnOneTitle(reportName, c)
            val existing = _ui.value.pages.firstOrNull { !it.archived && it.rawTitle == title }
            onOpen(existing?.id ?: createPage(title, folderId, null, html))
        }
    }

    /** `handleTitleChange` (debounced like content). */
    fun setTitle(pageId: String, title: String) {
        if (page(pageId)?.rawTitle == title) return
        updatePage(pageId, mapOf("title" to JsonPrimitive(title)), immediate = false)
    }

    fun togglePin(pageId: String) {
        val p = page(pageId) ?: return
        updatePage(pageId, mapOf("pinned" to JsonPrimitive(!p.pinned)))
    }

    fun toggleArchive(pageId: String) {
        val p = page(pageId) ?: return
        updatePage(pageId, mapOf("archived" to JsonPrimitive(!p.archived), "updatedAt" to JsonPrimitive(now())))
    }

    fun toggleReadOnly(pageId: String) {
        val p = page(pageId) ?: return
        commitEditor()
        updatePage(pageId, mapOf("readOnly" to JsonPrimitive(!p.readOnly), "updatedAt" to JsonPrimitive(now())))
    }

    /** `handleDuplicatePage`. */
    fun duplicate(pageId: String): String? {
        commitEditor()
        val source = page(pageId) ?: return null
        val copy = newPageJson("Copy of " + source.rawTitle, source.folderId, content = source.content).with(
            "tags" to JsonArray(source.tags.map(::JsonPrimitive)),
        )
        addPage(copy)
        return copy.str("id")
    }

    /** `handleConfirmDelete`: cascades to descendant sub-pages. */
    fun deletePage(pageId: String) {
        val pages = _ui.value.pages
        if (editor?.pageId == pageId) editor = null
        removePages(setOf(pageId) + descendantPageIds(pageId, pages))
    }

    fun moveToFolder(pageId: String, folderId: String?) {
        updatePage(pageId, mapOf("folderId" to jsonOf(folderId)))
    }

    fun addTag(pageId: String, tag: String) {
        val t = tag.trim().lowercase()
        val p = page(pageId) ?: return
        if (t.isEmpty() || t in p.tags) return
        updatePage(pageId, mapOf("tags" to JsonArray((p.tags + t).map(::JsonPrimitive))))
    }

    fun removeTag(pageId: String, tag: String) {
        val p = page(pageId) ?: return
        updatePage(pageId, mapOf("tags" to JsonArray(p.tags.filter { it != tag }.map(::JsonPrimitive))))
    }

    /** `handleSetPageIcon`. */
    fun setIcon(pageId: String, icon: String? = null, coverColor: String? = null) {
        val fields = buildMap<String, JsonElement> {
            icon?.let { put("icon", JsonPrimitive(it)) }
            coverColor?.let { put("coverColor", JsonPrimitive(it)) }
            put("updatedAt", JsonPrimitive(now()))
        }
        updatePage(pageId, fields)
    }

    /** `handleRestoreSnapshot`. */
    fun restoreSnapshot(pageId: String, content: String, title: String?) {
        if (editor?.pageId == pageId) editor = null
        val fields = buildMap<String, JsonElement> {
            put("content", JsonPrimitive(content))
            title?.takeIf { it.isNotEmpty() }?.let { put("title", JsonPrimitive(it)) }
            put("updatedAt", JsonPrimitive(now()))
        }
        updatePage(pageId, fields)
        page(pageId)?.let { if (editor == null) editor = newSession(it) }
        message("Saved")
    }

    /** Quick capture → `appendToInbox`. */
    fun appendToInbox(text: String) {
        val value = text.trim()
        if (value.isEmpty()) return
        commitEditor()
        var inbox = _ui.value.pages.firstOrNull { !it.archived && it.rawTitle.equals("Inbox", ignoreCase = true) }
        if (inbox == null) {
            val created = newPageJson("Inbox").with("pinned" to JsonPrimitive(true))
            addPage(created)
            inbox = NotePage(created)
        }
        val content = inbox.content + inboxCaptureHtml(value, clock())
        updatePage(inbox.id, mapOf("content" to JsonPrimitive(content), "updatedAt" to JsonPrimitive(now())))
        if (editor?.pageId == inbox.id) page(inbox.id)?.let { editor = newSession(it) }
    }

    // ── Folders ────────────────────────────────────────────────────────────

    fun newFolder(name: String, parentId: String? = null) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        addFolder(newFolderJson(clean, parentId))
        publish()
        scheduleSave(immediate = true)
    }

    fun renameFolder(folderId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val v = ++version
        setFolders(foldersArray().map { el ->
            val o = el as? JsonObject
            if (o?.str("id") == folderId) o.with("name" to JsonPrimitive(clean)) else el
        })
        pending = pending.touchFolder(folderId, v, "name")
        publish()
        scheduleSave(immediate = true)
    }

    /** `handleDeleteFolder`: sub-folders go too; their pages become uncategorized. */
    fun deleteFolder(folderId: String) {
        val removed = setOf(folderId) + descendantFolderIds(folderId, local.folders())
        val v = ++version
        setFolders(foldersArray().filterNot { (it as? JsonObject)?.str("id") in removed })
        removed.forEach { pending = pending.deleteFolder(it, v) }
        setPages(pagesArray().map { el ->
            val o = el as? JsonObject
            val pid = o?.str("id")
            if (o != null && pid != null && o.str("folderId") in removed) {
                pending = pending.touchPage(pid, v, "folderId")
                o.with("folderId" to JsonNull)
            } else el
        })
        publish()
        scheduleSave(immediate = true)
    }

    // ── Editor ─────────────────────────────────────────────────────────────

    private fun newSession(page: NotePage) = NoteEditorSession(page.id, parseNoteDoc(page.content)) { onEditorChanged() }

    /** Opens (or keeps) the editor session for [pageId]; returns false until the page is loaded. */
    fun openEditor(pageId: String): Boolean {
        if (editor?.pageId == pageId) return true
        val page = page(pageId) ?: return false
        commitEditor()
        editor = newSession(page)
        setTop("activePageId", JsonPrimitive(pageId))
        return true
    }

    fun closeEditor(pageId: String) {
        if (editor?.pageId != pageId) return
        flush()
        editor = null
    }

    private fun onEditorChanged() {
        _ui.update { if (it.save == SaveStatus.Saving) it else it.copy(save = SaveStatus.Pending) }
        commitJob?.cancel()
        commitJob = viewModelScope.launch {
            delay(COMMIT_DEBOUNCE_MS)
            commitEditor()
        }
    }

    /** Serializes the open document into the notebook (`handleContentChange`). */
    private fun commitEditor() {
        val session = editor ?: return
        val page = page(session.pageId) ?: return
        val html = session.html
        if (html == page.content) return
        updatePage(
            session.pageId,
            mapOf("content" to JsonPrimitive(html), "updatedAt" to JsonPrimitive(now()), "lastEditedBy" to userJson()),
            immediate = false,
        )
    }

    fun insertMention(user: MentionUser) {
        val session = editor ?: return
        val at = session.consumeTrigger()
        session.insertToken(mentionToken(user.id.toString(), user.display, user.avatar), at)
        val pageId = session.pageId
        val title = page(pageId)?.title ?: "Untitled"
        viewModelScope.launch { runCatching { withContext(io) { repository.sendMention(user.id, pageId, title) } } }
    }

    fun insertPageLink(target: NotePage, at: FocusTarget? = null) {
        editor?.insertToken(pageLinkToken(target.id, target.title), at)
    }

    /** `insertPageLinkForNew`: a new page in the current page's folder, then a link to it. */
    fun createPageAndLink(title: String, at: FocusTarget? = null) {
        val session = editor ?: return
        val folderId = page(session.pageId)?.folderId
        val created = newPageJson(title.trim(), folderId)
        addPage(created)
        session.insertToken(pageLinkToken(created.str("id").orEmpty(), title.trim()), at)
    }

    /** Slash `/promote-task`: `POST notes/convert-to-task`, then check the line and append `→ Task #id`. */
    fun convertToTask(blockId: Long) {
        val session = editor ?: return
        val text = session.doc.block(blockId)?.content?.plainText()?.trim().orEmpty()
        if (text.isEmpty()) return
        val pageId = session.pageId
        val title = page(pageId)?.title ?: "Untitled"
        viewModelScope.launch {
            runCatching { withContext(io) { repository.convertToTask(text, pageId, title) } }
                .onSuccess { task -> if (task != null && editor === session) session.markConverted(blockId, task.id) }
                .onFailure { message(it.message ?: "Failed to create task") }
        }
    }

    fun insertToc() {
        val session = editor ?: return
        val blocks = tocBlocks(session.doc)
        if (blocks == null) message("Add some H1 / H2 / H3 headings first.") else session.insertBlocks(blocks)
    }

    fun insertTimestamp() {
        editor?.insertText(clock().timestamp())
    }

    fun insertToday() {
        editor?.insertToken(dateChipToken(clock().todayIso()))
    }

    // ── Remote lookups used by sub-screens ─────────────────────────────────

    private suspend fun <T> remote(block: () -> T): Result<T> = try {
        Result.success(withContext(io) { block() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun history(pageId: String) = remote { repository.history(pageId) }
    suspend fun snapshot(id: Long) = remote { repository.snapshot(id) }
    suspend fun links(pageId: String) = remote { repository.links(pageId) }
    suspend fun addLink(pageId: String, type: String, id: Long) = remote { repository.addLink(pageId, type, id) }
    suspend fun removeLink(pageId: String, type: String, id: Long) = remote { repository.removeLink(pageId, type, id) }
    suspend fun directReports() = remote { repository.directReports() }
    suspend fun sprintEmbed() = remote { repository.sprintEmbed() }
    suspend fun timeSummary() = remote { repository.timeSummary() }
    suspend fun shareState(pageId: String) = remote { repository.share(pageId) }
    suspend fun revokeShare(pageId: String) = remote { repository.revokeShare(pageId) }

    suspend fun search(type: String, query: String) = remote {
        when (type) {
            "task" -> repository.searchTasks(query)
            "meeting" -> repository.searchMeetings(query)
            else -> repository.searchEvents(query)
        }
    }

    /** The server looks the page up in the saved notebook, so pending edits are flushed first. */
    suspend fun createShare(pageId: String): Result<ShareState> {
        commitJob?.cancel()
        commitEditor()
        if (!pending.isEmpty) {
            saveJob?.cancel()
            saveDeadline = Long.MAX_VALUE
            saveNow()
        }
        return remote { repository.createShare(pageId) }
    }

    // ── Saving ─────────────────────────────────────────────────────────────

    private fun scheduleSave(immediate: Boolean) {
        persistDraft()
        _ui.update { if (it.save == SaveStatus.Saving) it else it.copy(save = SaveStatus.Pending) }
        val delayMs = if (immediate) IMMEDIATE_SAVE_MS else CONTENT_SAVE_MS
        val deadline = System.currentTimeMillis() + delayMs
        if (saveJob?.isActive == true && saveDeadline <= deadline) return
        saveJob?.cancel()
        saveDeadline = deadline
        saveJob = viewModelScope.launch {
            delay(delayMs)
            saveDeadline = Long.MAX_VALUE
            saveNow()
        }
    }

    private suspend fun saveNow() {
        val gen = generation
        saveMutex.withLock {
            if (gen != generation) return
            commitEditor()
            val snapshot = pending
            if (snapshot.isEmpty) return
            _ui.update { it.copy(save = SaveStatus.Saving) }
            try {
                val merged = withContext(io) {
                    val fresh = repository.load()
                    val result = mergeNotebook(fresh, local, snapshot)
                    val size = Json.encodeToString(JsonElement.serializer(), result).length
                    if (size > NOTEBOOK_MAX_CHARS) throw NotesFailure(NOTEBOOK_TOO_LARGE, 400)
                    repository.save(result)
                    result
                }
                if (gen != generation) return
                pending = pending.minus(snapshot)
                local = mergeNotebook(merged, local, pending)
                publish()
                retryDelayMs = RETRY_START_MS
                if (pending.isEmpty) userId?.let { drafts?.clear(it) } else persistDraft()
                _ui.update { it.copy(save = if (pending.isEmpty) SaveStatus.Saved else SaveStatus.Pending, saveError = null) }
                flashJob?.cancel()
                flashJob = viewModelScope.launch {
                    delay(SAVED_FLASH_MS)
                    _ui.update { if (it.save == SaveStatus.Saved) it.copy(save = SaveStatus.Idle) else it }
                }
                if (!pending.isEmpty) scheduleSave(immediate = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (gen != generation) return
                persistDraft()
                val tooLarge = error is NotesFailure && error.statusCode == 400
                _ui.update { it.copy(save = SaveStatus.Failed, saveError = error.message ?: "Failed to save notes") }
                if (!tooLarge) scheduleRetry()
            }
        }
    }

    private fun scheduleRetry() {
        val wait = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(RETRY_MAX_MS)
        saveJob?.cancel()
        saveDeadline = System.currentTimeMillis() + wait
        saveJob = viewModelScope.launch {
            delay(wait)
            saveDeadline = Long.MAX_VALUE
            saveNow()
        }
    }

    private fun persistDraft(sync: Boolean = false) {
        val uid = userId ?: return
        val store = drafts ?: return
        val snapshotPending = pending
        val write: () -> Unit = if (snapshotPending.isEmpty) {
            { store.clear(uid) }
        } else {
            val subset = pendingSubset(local, snapshotPending)
            ({ store.write(uid, snapshotPending, subset) })
        }
        if (sync) write() else viewModelScope.launch(io) { write() }
    }

    override fun onCleared() {
        commitEditor()
        persistDraft(sync = true)
        super.onCleared()
    }

    companion object {
        private const val CONTENT_SAVE_MS = 10_000L
        private const val IMMEDIATE_SAVE_MS = 300L
        private const val COMMIT_DEBOUNCE_MS = 400L
        private const val SAVED_FLASH_MS = 2_000L
        private const val RETRY_START_MS = 15_000L
        private const val RETRY_MAX_MS = 300_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                return NotesViewModel(
                    NotesRepository(AppContainer.get(appContext).api),
                    FileNotesDraftStore(appContext.filesDir),
                ) as T
            }
        }
    }
}

private fun PendingChanges.maxVersion(): Long {
    val all = pages.values.flatMap { listOfNotNull(it.created, it.deleted) + it.fields.values } +
        folders.values.flatMap { listOfNotNull(it.created, it.deleted) + it.fields.values } + top.values
    return all.maxOrNull() ?: 0L
}
