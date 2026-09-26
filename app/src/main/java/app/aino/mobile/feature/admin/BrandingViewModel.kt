package app.aino.mobile.feature.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Org Settings → Branding and Email templates (web `BrandingSection.tsx` +
 * `EmailTemplatesSection.tsx`, P10.4). [onBrandingChanged] pushes a saved
 * change app-wide (web `useBranding().refresh`).
 */
class BrandingViewModel(
    private val repository: BrandingAdminRepository,
    private val onBrandingChanged: () -> Unit = {},
    private val background: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(BrandingUiState())
    val ui: StateFlow<BrandingUiState> = _ui.asStateFlow()
    private var previewJob: Job? = null
    private var noticeJob: Job? = null

    fun bind(role: String) = _ui.update { it.copy(role = role) }

    // ── Branding ────────────────────────────────────────────────────────────

    fun loadBranding(force: Boolean = false) {
        val s = _ui.value
        if (s.branding.loading && !force) return
        if (s.branding.data != null && !force) return
        _ui.update { it.copy(branding = it.branding.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching { repository.branding() } }
            _ui.update { st ->
                result.fold(
                    // A refetch never discards staged, unsaved changes.
                    onSuccess = { b ->
                        val keep = st.branding.data?.let(st.draft::dirty) == true
                        st.copy(branding = Load(b), draft = if (keep) st.draft else BrandingDraft.from(b))
                    },
                    onFailure = { e -> st.copy(branding = st.branding.copy(loading = false, error = e.adminMessage("Failed to load branding"))) },
                )
            }
        }
    }

    fun setAccent(value: String) {
        if (!_ui.value.canEdit) return
        _ui.update { it.copy(draft = it.draft.copy(accent = sanitizeHexInput(value, it.draft.accent))) }
    }

    /** `stageFile`: validates, then stages the picked logo (clearing a pending removal). */
    fun stageLogo(logo: StagedLogo) {
        if (!_ui.value.canEdit) return
        validateLogo(logo.mimeType, logo.bytes.size.toLong())?.let { return notify(false, it) }
        _ui.update { it.copy(draft = it.draft.copy(logo = logo, removeLogo = false)) }
    }

    /** Reads a picked file off the main thread; a null read (unreadable / too large) reports the size error. */
    fun pickLogo(read: () -> StagedLogo?) {
        if (!_ui.value.canEdit) return
        viewModelScope.launch {
            val logo = withContext(background) { runCatching(read).getOrNull() }
            if (logo == null) notify(false, "Logo must be under 2 MB") else stageLogo(logo)
        }
    }

    fun stageRemoveLogo() {
        if (!_ui.value.canEdit) return
        _ui.update { it.copy(draft = it.draft.copy(logo = null, removeLogo = true)) }
    }

    fun cancelBranding() {
        if (_ui.value.saving) return
        _ui.update { s -> s.copy(draft = s.branding.data?.let(BrandingDraft::from) ?: BrandingDraft()) }
    }

    /** `save`: logo change first, then the accent, then push app-wide. */
    fun saveBranding() {
        val s = _ui.value
        val saved = s.branding.data ?: return
        val draft = s.draft
        if (!s.canEdit || !draft.dirty(saved) || s.saving) return
        if (!draft.accentValid) return notify(false, "Accent color must be a 6-digit hex (e.g. #2383e2)")
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withContext(background) {
                runCatching {
                    var next = saved
                    if (draft.logo != null) {
                        next = next.copy(logoUrl = repository.uploadLogo(draft.logo).logoUrl)
                    } else if (draft.removeLogo) {
                        repository.deleteLogo()
                        next = next.copy(logoUrl = null)
                    }
                    if (draft.accentDirty(saved)) next = next.copy(accentColor = repository.updateAccent(draft.accent.lowercase()).accentColor)
                    next
                }
            }
            _ui.update { it.copy(saving = false) }
            result.fold(
                onSuccess = { next ->
                    _ui.update { it.copy(branding = Load(next), draft = BrandingDraft.from(next)) }
                    onBrandingChanged()
                    notify(true, "Branding saved")
                },
                onFailure = { notify(false, it.adminMessage("Failed to save branding")) },
            )
        }
    }

    // ── Email templates ─────────────────────────────────────────────────────

    fun loadTemplates(force: Boolean = false) {
        val s = _ui.value
        if (s.templates.loading && !force) return
        if (s.templates.data != null && !force) return
        _ui.update { it.copy(templates = it.templates.copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching { repository.emailTemplates() } }
            result.fold(
                onSuccess = { list -> applyTemplates(list) },
                onFailure = { e -> _ui.update { it.copy(templates = it.templates.copy(loading = false, error = e.adminMessage("Failed to load email templates"))) } },
            )
        }
    }

    /** Keeps the selection (first template by default) and an unsaved draft of it. */
    private fun applyTemplates(list: List<EmailTemplate>) {
        val s = _ui.value
        val key = s.selectedKey?.takeIf { k -> list.any { it.templateKey == k } } ?: list.firstOrNull()?.templateKey
        val template = list.firstOrNull { it.templateKey == key }
        val old = s.templateDraft
        val keepDraft = old != null && old.key == key && s.selected?.let(old::dirty) == true
        val draft = if (keepDraft) old else template?.let(TemplateDraft::from)
        _ui.update { it.copy(templates = Load(list), selectedKey = key, templateDraft = draft) }
        if (draft != null && (draft != old || _ui.value.preview == null)) schedulePreview()
    }

    /** `key={current.template_key}`: switching templates remounts the editor (drops the draft). */
    fun selectTemplate(key: String) {
        val template = _ui.value.templates.data?.firstOrNull { it.templateKey == key } ?: return
        _ui.update { it.copy(selectedKey = key, templateDraft = TemplateDraft.from(template), preview = null) }
        schedulePreview()
    }

    fun editTemplate(transform: (TemplateDraft) -> TemplateDraft) {
        if (!_ui.value.canEdit) return
        val before = _ui.value.templateDraft ?: return
        val after = transform(before).let { it.copy(subject = it.subject.take(EMAIL_SUBJECT_MAX), body = it.body.take(EMAIL_BODY_MAX)) }
        _ui.update { it.copy(templateDraft = after) }
        if (after.subject != before.subject || after.body != before.body) schedulePreview()
    }

    fun insertBuiltin() {
        val template = _ui.value.selected ?: return
        editTemplate { it.withBuiltin(template) }
    }

    fun saveTemplate() {
        val s = _ui.value
        val template = s.selected ?: return
        val draft = s.templateDraft ?: return
        if (!s.canEdit || !draft.dirty(template) || s.saving) return
        if (draft.subject.isBlank()) return notify(false, "subject is required (max 200 chars)")
        if (draft.body.isBlank()) return notify(false, "body_html is required (max 30000 chars)")
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching { repository.saveTemplate(draft) } }
            _ui.update { it.copy(saving = false) }
            result.fold(
                onSuccess = {
                    // Mirror the saved values locally so the draft reads clean, then refetch.
                    markSaved(draft)
                    notify(true, "Template saved")
                    loadTemplates(force = true)
                },
                onFailure = { notify(false, it.adminMessage("Failed to save")) },
            )
        }
    }

    fun revertTemplate() {
        val s = _ui.value
        val template = s.selected ?: return
        if (!s.canEdit || !template.isOverridden || s.reverting) return
        _ui.update { it.copy(reverting = true) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching { repository.revertTemplate(template.templateKey) } }
            _ui.update { it.copy(reverting = false) }
            result.fold(
                onSuccess = {
                    // The reverted draft is replaced by the refetched built-in.
                    _ui.update { it.copy(templateDraft = null) }
                    notify(true, "Template reverted to the built-in")
                    loadTemplates(force = true)
                },
                onFailure = { notify(false, it.adminMessage("Failed to revert")) },
            )
        }
    }

    private fun markSaved(draft: TemplateDraft) = _ui.update { s ->
        val list = s.templates.data?.map {
            if (it.templateKey == draft.key) it.copy(subject = draft.subject, bodyHtml = draft.body, enabled = draft.enabled, isOverridden = true) else it
        }
        s.copy(templates = s.templates.copy(data = list))
    }

    /** Debounced live preview (web: 350 ms); a failure blanks the pane. */
    private fun schedulePreview() {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MS)
            val draft = _ui.value.templateDraft ?: return@launch
            _ui.update { it.copy(previewLoading = true) }
            val result = withContext(background) { runCatching { repository.preview(draft.key, draft.subject, draft.body) } }
            _ui.update { it.copy(previewLoading = false, preview = result.getOrElse { EmailPreview(draft.subject, "") }) }
        }
    }

    // ── Notices ─────────────────────────────────────────────────────────────

    fun dismissNotice() {
        noticeJob?.cancel()
        _ui.update { it.copy(notice = null) }
    }

    private fun notify(ok: Boolean, text: String) {
        _ui.update { it.copy(notice = AdminNotice(ok, text)) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_DISMISS_MS)
            _ui.update { it.copy(notice = null) }
        }
    }

    companion object {
        const val PREVIEW_DEBOUNCE_MS = 350L
        private const val NOTICE_DISMISS_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return BrandingViewModel(BrandingAdminRepository(container.api), onBrandingChanged = container.branding::refresh) as T
            }
        }
    }
}
