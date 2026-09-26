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
 * The P10.1 Admin panel sections (web `pages/admin`). One instance is shared
 * by every `admin/...` sub-page; each page loads its own queries on entry.
 */
class AdminViewModel(
    private val repository: AdminRepository,
    private val background: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(AdminUiState())
    val ui: StateFlow<AdminUiState> = _ui.asStateFlow()
    private var noticeJob: Job? = null

    /** Signed-in user; a different user resets every cached section. */
    fun bind(role: String, userId: Long, orgId: Long?) {
        val s = _ui.value
        if (s.userId != 0L && (s.userId != userId || s.orgId != orgId)) _ui.value = AdminUiState()
        _ui.update { it.copy(role = role, userId = userId, orgId = orgId) }
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    fun loadStats(force: Boolean = false) =
        query({ it.stats }, { s, v -> s.copy(stats = v) }, "Failed to load stats", force) { repository.stats() }

    /**
     * Loads the queries a section page renders (web: each page's `useQuery` on
     * mount). [force] refetches, used by pull-to-refresh / resume.
     */
    fun loadSection(key: String, force: Boolean = false) {
        when (key) {
            AdminSectionKeys.HOME -> { loadStats(force); loadHome(force) }
            AdminSectionKeys.USERS -> { loadUsers(force); if (_ui.value.roleRequestStatus == "pending") loadRoleRequests(force) }
            AdminSectionKeys.ADD -> { loadPickers(force = force); loadOrganizations(force) }
            AdminSectionKeys.ROLE_REQUESTS -> loadRoleRequests(force)
            AdminSectionKeys.PAYROLL -> loadPayPeriods(force)
            AdminSectionKeys.AUDIT -> { loadAudit(force); loadPickers() }
            AdminSectionKeys.ORG_SETTINGS -> loadSettingsTab(_ui.value.settingsTab, force)
            AdminSectionKeys.ORGANIZATIONS -> loadOrganizations(force)
            AdminSectionKeys.TASK_LABELS -> loadTaskLabels(force)
            AdminSectionKeys.REGISTRATION -> loadRegistration(force)
            AdminSectionKeys.ANNOUNCEMENTS -> loadAnnouncements(force)
        }
    }

    /** `UserDrawer` data: the user plus assignment pickers (and orgs for platform admins). */
    fun loadUserDetail(id: Long) {
        loadUser(id)
        loadPickers()
        loadOrganizations()
    }

    /** `AdminHome.tsx` setup checklist + pending role-request count; best-effort, never surfaces errors. */
    fun loadHome(force: Boolean = false) {
        if (_ui.value.home.loading && !force) return
        _ui.update { it.copy(home = it.home.copy(loading = true)) }
        viewModelScope.launch {
            val summary = withContext(background) { repository.homeSummary(hasOrg = _ui.value.orgId != null) }
            _ui.update { it.copy(home = Load(summary)) }
        }
    }

    fun loadUsers(force: Boolean = false) {
        val s = _ui.value
        query({ it.users }, { st, v -> st.copy(users = v) }, "Failed to load users", force) {
            repository.users(s.userFilters, s.userPage, USERS_PAGE_SIZE)
        }
    }

    fun setUserFilters(filters: UserFilters) {
        _ui.update { it.copy(userFilters = filters, userPage = 1) }
        loadUsers(force = true)
    }

    fun setUserPage(page: Int) {
        _ui.update { it.copy(userPage = page.coerceAtLeast(1)) }
        loadUsers(force = true)
    }

    fun loadUser(id: Long) {
        if (_ui.value.user.data?.id != id) _ui.update { it.copy(user = Load()) }
        query({ it.user }, { s, v -> s.copy(user = v) }, "Failed to load user", force = true) { repository.user(id) }
    }

    /** Department / team / member pickers for [orgId] (defaults to the caller's org). */
    fun loadPickers(orgId: Long? = _ui.value.orgId, force: Boolean = false) {
        query({ it.departments }, { s, v -> s.copy(departments = v) }, "Failed to load departments", force) { repository.departments(orgId) }
        query({ it.teams }, { s, v -> s.copy(teams = v) }, "Failed to load teams", force) { repository.teams(orgId) }
        query({ it.members }, { s, v -> s.copy(members = v) }, "Failed to load members", force) { repository.members(orgId) }
    }

    /** `admin/organizations` is platform_admin only (server `requireRole('platform_admin')`). */
    fun loadOrganizations(force: Boolean = false) {
        if (!_ui.value.isPlatformAdmin) return
        query({ it.organizations }, { s, v -> s.copy(organizations = v) }, "Failed to load organizations", force) { repository.organizations() }
    }

    fun loadRoleRequests(force: Boolean = false) {
        val status = _ui.value.roleRequestStatus
        query({ it.roleRequests }, { s, v -> s.copy(roleRequests = v) }, "Failed to load role requests", force) {
            repository.roleRequests(status)
        }
    }

    fun setRoleRequestStatus(status: String) {
        _ui.update { it.copy(roleRequestStatus = status) }
        loadRoleRequests(force = true)
    }

    fun loadAudit(force: Boolean = false) {
        val s = _ui.value
        query({ it.audit }, { st, v -> st.copy(audit = v) }, "Failed to load audit logs", force) {
            repository.auditLogs(s.auditFilters, AUDIT_PAGE_SIZE, s.auditOffset)
        }
    }

    fun setAuditFilters(filters: AuditFilters) {
        _ui.update { it.copy(auditFilters = filters, auditOffset = 0) }
        loadAudit(force = true)
    }

    fun setAuditOffset(offset: Int) {
        _ui.update { it.copy(auditOffset = offset.coerceAtLeast(0)) }
        loadAudit(force = true)
    }

    fun loadPayPeriods(force: Boolean = false) =
        query({ it.payPeriods }, { s, v -> s.copy(payPeriods = v) }, "Failed to load pay periods", force) { repository.payPeriods() }

    fun loadTaskLabels(force: Boolean = false) =
        query({ it.taskLabels }, { s, v -> s.copy(taskLabels = v) }, "Failed to load labels", force) { repository.taskLabels() }

    /** `admin/announcements` is super_admin+. */
    fun loadAnnouncements(force: Boolean = false) {
        if (!_ui.value.isSuperOrAbove) return
        query({ it.announcements }, { s, v -> s.copy(announcements = v) }, "Failed to load announcements", force) { repository.announcements() }
    }

    fun loadOrgSettings(force: Boolean = false) = query({ it.orgSettings }, { s, v -> s.copy(orgSettings = v) }, "Failed to load settings", force) {
        repository.orgSettings() ?: throw IllegalStateException("No organization")
    }

    fun loadOrgRoles(force: Boolean = false) =
        query({ it.orgRoles }, { s, v -> s.copy(orgRoles = v) }, "Failed to load roles", force) { repository.orgRoles() }

    fun loadRegistration(force: Boolean = false) {
        query({ it.registration }, { s, v -> s.copy(registration = v) }, "Failed to load registration settings", force) { repository.registrationSettings() }
        query({ it.inviteCodes }, { s, v -> s.copy(inviteCodes = v) }, "Failed to load invite codes", force) { repository.inviteCodes() }
    }

    fun selectSettingsTab(tab: OrgSettingsTab) {
        _ui.update { it.copy(settingsTab = tab) }
        loadSettingsTab(tab, force = false)
    }

    /** Branding / Email templates load in their own ViewModel; a forced reload signals them via [AdminUiState.settingsRefresh]. */
    private fun loadSettingsTab(tab: OrgSettingsTab, force: Boolean) = when {
        tab == OrgSettingsTab.Roles -> loadOrgRoles(force)
        tab.usesOrgSettings -> loadOrgSettings(force)
        force -> _ui.update { it.copy(settingsRefresh = it.settingsRefresh + 1) }
        else -> Unit
    }

    fun clearResults() = _ui.update { it.copy(createdUser = null, importResult = null, createdInvite = null) }

    // ── Users (UserDrawer / CreateUser / AddPeopleWizard) ─────────────────────

    fun createUser(draft: NewUserDraft, onDone: () -> Unit = {}) = mutation(
        action = { repository.createUser(draft) },
        fallback = "Failed to create user",
    ) { created ->
        _ui.update { it.copy(createdUser = created) }
        notify(true, created.message ?: "User created")
        loadUsers(force = true)
        onDone()
    }

    fun importUsers(rows: List<ImportRow>, orgId: Long?) {
        if (rows.isEmpty()) return notify(false, "No valid rows to import.")
        mutation(action = { repository.importUsers(rows, orgId) }, fallback = "Import failed") { result ->
            _ui.update { it.copy(importResult = result) }
            notify(result.failed.isEmpty(), "${result.imported} imported" + if (result.failed.isNotEmpty()) ", ${result.failed.size} failed" else "")
            loadUsers(force = true)
        }
    }

    /** super/platform apply immediately ("Role updated"); others queue a request with [reason]. */
    fun changeRole(userId: Long, role: String, reason: String?) = mutation(
        action = { repository.changeRole(userId, role, reason?.trim()?.ifEmpty { null }) },
        fallback = "Action failed",
    ) { r ->
        val fallback = if (r.immediate == false || r.pending == true) "Role change request submitted" else "Role updated"
        notify(true, r.message ?: fallback)
        refreshUser(userId)
    }

    fun updateAssignment(userId: Long, orgId: Long?, departmentId: Long?, teamId: Long?, managerId: Long?) = mutation(
        action = { repository.updateAssignment(userId, orgId, departmentId, teamId, managerId) },
        fallback = "Action failed",
    ) { r ->
        notify(true, r.message ?: "Assignment updated")
        refreshUser(userId)
    }

    fun toggleActive(userId: Long) = mutation(action = { repository.toggleActive(userId) }, fallback = "Action failed") { r ->
        notify(true, r.message ?: if (r.isActive == true) "User activated" else "User deactivated")
        refreshUser(userId)
    }

    fun resetPassword(userId: Long, password: String) {
        if (password.length < 8) return notify(false, "Minimum 8 characters")
        mutation(action = { repository.resetPassword(userId, password) }, fallback = "Action failed") { r ->
            notify(true, r.message ?: "Password reset")
        }
    }

    fun resetFaceEnrollment(userId: Long) = mutation(action = { repository.resetFaceEnrollment(userId) }, fallback = "Action failed") { r ->
        notify(true, r.message ?: "Face enrollment reset")
    }

    fun deleteUser(userId: Long, onDeleted: () -> Unit) = mutation(action = { repository.deleteUser(userId) }, fallback = "Action failed") { r ->
        notify(true, r.message ?: "User deleted")
        _ui.update { it.copy(user = Load()) }
        loadUsers(force = true)
        onDeleted()
    }

    fun inviteToOrg(userId: Long, role: String, departmentId: Long?, teamId: Long?) = mutation(
        action = { repository.inviteToOrg(userId, role, departmentId, teamId) },
        fallback = "Action failed",
    ) { r ->
        notify(true, r.message ?: "Added to the organization")
        refreshUser(userId)
    }

    fun removeFromOrg(userId: Long) = mutation(action = { repository.removeFromOrg(userId) }, fallback = "Action failed") { r ->
        notify(true, r.message ?: "Removed from the organization")
        refreshUser(userId)
    }

    private fun refreshUser(userId: Long) {
        if (_ui.value.user.data?.id == userId) loadUser(userId)
        loadUsers(force = true)
    }

    // ── Role requests ────────────────────────────────────────────────────────

    fun approveRoleRequest(id: Long) = mutation(action = { repository.approveRoleRequest(id) }, fallback = "Failed") { r ->
        notify(true, r.message ?: "Approved")
        loadRoleRequests(force = true)
    }

    fun rejectRoleRequest(id: Long, reason: String) = mutation(
        action = { repository.rejectRoleRequest(id, reason.trim().ifEmpty { null }) },
        fallback = "Failed",
    ) { r ->
        notify(true, r.message ?: "Rejected")
        loadRoleRequests(force = true)
    }

    fun cancelRoleRequest(id: Long) = mutation(action = { repository.cancelRoleRequest(id) }, fallback = "Failed") { r ->
        notify(true, r.message ?: "Cancelled")
        loadRoleRequests(force = true)
    }

    // ── Pay periods ──────────────────────────────────────────────────────────

    fun lockPayPeriod(label: String, start: String, end: String, onDone: () -> Unit) {
        validatePayPeriod(label, start, end)?.let { return notify(false, it) }
        mutation(action = { repository.lockPayPeriod(label.trim(), start, end) }, fallback = "Failed to lock pay period") {
            notify(true, "Pay period locked")
            loadPayPeriods(force = true)
            onDone()
        }
    }

    fun unlockPayPeriod(id: Long) = mutation(action = { repository.unlockPayPeriod(id) }, fallback = "Failed to unlock pay period") { r ->
        notify(true, r.message ?: "Pay period unlocked")
        loadPayPeriods(force = true)
    }

    // ── Task labels / announcements (Android-only) ────────────────────────────

    fun saveTaskLabel(id: Long?, name: String, color: String, onDone: () -> Unit) {
        if (name.isBlank()) return notify(false, "Name is required")
        if (!isHexColor(color)) return notify(false, "Color must be a hex value like #3b82f6")
        mutation(
            action = { if (id == null) repository.createTaskLabel(name.trim(), color) else repository.updateTaskLabel(id, name.trim(), color) },
            fallback = "Failed to save label",
        ) {
            notify(true, if (id == null) "Label created" else "Label updated")
            loadTaskLabels(force = true)
            onDone()
        }
    }

    fun deleteTaskLabel(id: Long) = mutation(action = { repository.deleteTaskLabel(id) }, fallback = "Failed to delete label") {
        notify(true, "Label deleted")
        loadTaskLabels(force = true)
    }

    fun saveAnnouncement(id: Long?, message: String, type: String, durationHours: String, onDone: () -> Unit) {
        if (message.isBlank()) return notify(false, "Message is required")
        mutation(
            action = {
                if (id == null) repository.createAnnouncement(message, type, durationHours)
                else repository.updateAnnouncement(id, message = message, type = type, durationHours = durationHours)
            },
            fallback = "Failed to save announcement",
        ) {
            notify(true, if (id == null) "Announcement posted" else "Announcement updated")
            loadAnnouncements(force = true)
            onDone()
        }
    }

    fun setAnnouncementActive(id: Long, active: Boolean) = mutation(
        action = { repository.updateAnnouncement(id, isActive = active) },
        fallback = "Failed to update announcement",
    ) {
        notify(true, if (active) "Announcement activated" else "Announcement paused")
        loadAnnouncements(force = true)
    }

    fun deleteAnnouncement(id: Long) = mutation(action = { repository.deleteAnnouncement(id) }, fallback = "Failed to delete announcement") {
        notify(true, "Announcement deleted")
        loadAnnouncements(force = true)
    }

    // ── Org settings / attendance / roles ──────────────────────────────────────

    fun saveGeneralSettings(draft: GeneralSettingsDraft) {
        val canEditAll = _ui.value.isSuperOrAbove
        if (canEditAll && draft.name.isBlank()) return notify(false, "Organization name is required")
        if (canEditAll && draft.workDays.isEmpty()) return notify(false, "Pick at least one working day")
        mutation(action = { repository.saveGeneralSettings(draft, canEditAll) }, fallback = "Failed to save settings") { saved ->
            _ui.update { it.copy(orgSettings = Load(saved)) }
            notify(true, "Settings saved")
        }
    }

    fun saveAttendanceSettings(draft: AttendanceSettingsDraft) {
        if (draft.verifyOn && (draft.latitude.toDoubleOrNull() == null || draft.longitude.toDoubleOrNull() == null)) {
            return notify(false, "Set the office latitude and longitude to enable location verification")
        }
        if (draft.wifiOn && draft.wifi.none { it.bssid.isNotBlank() }) {
            return notify(false, "Add at least one Wi-Fi BSSID to enable Wi-Fi verification")
        }
        mutation(action = { repository.saveAttendanceSettings(draft) }, fallback = "Failed to save settings") { saved ->
            _ui.update { it.copy(orgSettings = Load(saved)) }
            notify(true, "Attendance settings saved")
        }
    }

    fun saveOrgRole(draft: RoleDraft, onDone: () -> Unit) {
        if (draft.label.isBlank()) return notify(false, "Label is required")
        if (draft.isNew && !Regex("^[a-z][a-z0-9_]{1,39}$").matches(draft.roleKey)) {
            return notify(false, "Key must be lowercase letters, numbers and underscores")
        }
        mutation(
            action = { if (draft.isNew) repository.createOrgRole(draft) else repository.updateOrgRole(draft) },
            fallback = "Failed to save role",
        ) { roles ->
            _ui.update { it.copy(orgRoles = Load(roles)) }
            notify(true, if (draft.isNew) "Role created" else "Role updated")
            onDone()
        }
    }

    fun deleteOrgRole(roleKey: String) = mutation(action = { repository.deleteOrgRole(roleKey) }, fallback = "Failed to delete role") { roles ->
        _ui.update { it.copy(orgRoles = Load(roles)) }
        notify(true, "Role removed")
    }

    // ── Organizations (platform_admin) ─────────────────────────────────────────

    fun saveOrganization(draft: OrganizationDraft, onDone: () -> Unit) {
        if (draft.name.isBlank()) return notify(false, "Name is required")
        mutation(
            action = { if (draft.id == null) repository.createOrganization(draft).message else { repository.updateOrganization(draft); null } },
            fallback = "Failed to save organization",
        ) { msg ->
            notify(true, msg ?: if (draft.id == null) "Organization created" else "Organization updated")
            loadOrganizations(force = true)
            onDone()
        }
    }

    fun deleteOrganization(id: Long) = mutation(action = { repository.deleteOrganization(id) }, fallback = "Failed to delete organization") { r ->
        notify(true, r.message ?: "Organization deleted")
        loadOrganizations(force = true)
    }

    // ── Registration + invite codes ───────────────────────────────────────────

    fun setRegistrationMode(mode: String) = mutation(action = { repository.updateRegistrationMode(mode) }, fallback = "Failed to update registration") { s ->
        _ui.update { it.copy(registration = Load(s)) }
        notify(true, s.message ?: "Registration mode updated")
    }

    fun createInviteCode(role: String, maxUses: String, expiresDays: String) {
        val uses = maxUses.trim().ifEmpty { "1" }.toIntOrNull()?.takeIf { it >= 0 } ?: return notify(false, "Max uses must be 0 or more")
        val days = expiresDays.trim().ifEmpty { null }?.let { it.toIntOrNull()?.takeIf { d -> d > 0 } ?: return notify(false, "Expiry must be a positive number of days") }
        mutation(action = { repository.createInviteCode(role, uses, days) }, fallback = "Failed to create invite code") { created ->
            _ui.update { it.copy(createdInvite = created) }
            notify(true, created.message ?: "Invite code created")
            query({ it.inviteCodes }, { s, v -> s.copy(inviteCodes = v) }, "Failed to load invite codes", force = true) { repository.inviteCodes() }
        }
    }

    fun deactivateInviteCode(id: Long) = mutation(action = { repository.deactivateInviteCode(id) }, fallback = "Failed to deactivate code") { r ->
        notify(true, r.message ?: "Invite code deactivated")
        query({ it.inviteCodes }, { s, v -> s.copy(inviteCodes = v) }, "Failed to load invite codes", force = true) { repository.inviteCodes() }
    }

    // ── Plumbing ───────────────────────────────────────────────────────────────

    /**
     * Runs [fetch] unless one is already in flight ([force] restarts it). Keeps the
     * previous data on failure so a refetch error doesn't blank the list.
     */
    private fun <T> query(
        get: (AdminUiState) -> Load<T>,
        set: (AdminUiState, Load<T>) -> AdminUiState,
        fallback: String,
        force: Boolean = false,
        fetch: () -> T,
    ) {
        if (get(_ui.value).loading && !force) return
        _ui.update { set(it, get(it).copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching(fetch) }
            _ui.update { s ->
                result.fold(
                    onSuccess = { set(s, Load(it)) },
                    onFailure = { e -> set(s, get(s).copy(loading = false, error = e.adminMessage(fallback))) },
                )
            }
        }
    }

    private fun <T> mutation(action: () -> T, fallback: String, onSuccess: (T) -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching(action) }
            _ui.update { it.copy(busy = false) }
            result.fold(onSuccess = onSuccess, onFailure = { notify(false, it.adminMessage(fallback)) })
        }
    }

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
        /** `useAutoDismiss` default delay. */
        private const val NOTICE_DISMISS_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return AdminViewModel(AdminRepository(container.api)) as T
            }
        }
    }
}
