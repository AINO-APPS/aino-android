package app.aino.mobile.feature.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.RemoveModerator
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons

/** Pages reachable from group settings; the back arrow and system Back walk up to [Main]. */
internal enum class GroupPage(val title: String) {
    Main("Group settings"),
    Edit("Edit group"),
    Members("Members"),
    AddMembers("Add members"),
    Link("Group link"),
    Requests("Requests & invites"),
    Permissions("Permissions"),
}

/**
 * Group settings with Signal's layout and flows (re-implemented for AINO):
 * header with the group photo, quick actions, the member list with roles,
 * add/remove, admin management, group link, requests and permissions.
 * [content] hosts the non-group info pages (media, pinned, saved, search).
 */
@Composable
internal fun GroupSettingsScreen(
    ui: ChatUiState,
    chat: ChatViewModel,
    onOpenAllMedia: () -> Unit,
    onOpenInfoPage: (InfoPage) -> Unit,
) {
    val conversation = ui.selectedConversation ?: return
    val context = LocalContext.current
    val vm: GroupSettingsViewModel = viewModel(key = "group-${conversation.id}", factory = GroupSettingsViewModel.factory(context))
    val state by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(conversation.id) {
        vm.bind(conversation.id, ui.currentUserId, ui.members, onGroupChanged = chat::onGroupChanged, onLeft = { chat.onLeftGroup(conversation.id) })
    }
    var page by remember { mutableStateOf(GroupPage.Main) }
    var memberSheet by remember { mutableStateOf<ConversationMember?>(null) }
    val perms = GroupPermissions.of(conversation, state.members, ui.currentUserId)
    // The main page shows the link's on/off state and the pending-request count.
    LaunchedEffect(conversation.id, perms.canManageLink) { if (perms.canManageLink) vm.loadInviteLink() }
    BackHandler(enabled = page != GroupPage.Main) { page = GroupPage.Main }
    val signal = signalColors
    val snackbar = state.message ?: state.error

    Box(Modifier.fillMaxSize().background(signal.background)) {
        Column(Modifier.fillMaxSize()) {
            GroupTopBar(page.title, onBack = { if (page == GroupPage.Main) chat.closeInfo() else page = GroupPage.Main })
            when (page) {
                GroupPage.Main -> GroupMainPage(
                    ui, conversation, state, perms, chat,
                    onOpenAllMedia = onOpenAllMedia,
                    onOpenInfoPage = onOpenInfoPage,
                    onPage = { page = it },
                    onMember = { memberSheet = it },
                    onLeave = vm::leave,
                )
                GroupPage.Edit -> EditGroupPage(conversation, state, perms, vm, onDone = { page = GroupPage.Main })
                GroupPage.Members -> MemberListPage(state.members, ui.currentUserId, perms, onAdd = { page = GroupPage.AddMembers }) { memberSheet = it }
                GroupPage.AddMembers -> AddMembersPage(conversation, state, vm, onDone = { page = GroupPage.Main })
                GroupPage.Link -> GroupLinkPage(state, perms, vm, onRequests = { page = GroupPage.Requests })
                GroupPage.Requests -> JoinRequestsPage(state, vm)
                GroupPage.Permissions -> PermissionsPage(conversation, perms, vm)
            }
        }
        if (snackbar != null) {
            LaunchedEffect(snackbar) { kotlinx.coroutines.delay(2_800); vm.consumeMessage() }
            Text(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)
                    .clip(RoundedCornerShape(10.dp)).background(signal.text).padding(horizontal = 16.dp, vertical = 12.dp),
                color = signal.background, fontSize = 14.sp,
            )
        }
    }
    memberSheet?.let { member ->
        GroupMemberSheet(member, conversation, perms, ui, chat, vm) { memberSheet = null }
    }
}

@Composable
private fun GroupTopBar(title: String, onBack: () -> Unit) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().background(signal.background).statusBarsPadding().height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
            Icon(HeroIcons.ArrowLeft, "Back", tint = signal.text)
        }
        Text(title, Modifier.padding(start = 8.dp), color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Main page ────────────────────────────────────────────────────────────

private const val MEMBER_PREVIEW = 5

@Composable
private fun GroupMainPage(
    ui: ChatUiState,
    conversation: ChatConversation,
    state: GroupSettingsUiState,
    perms: GroupPermissions,
    chat: ChatViewModel,
    onOpenAllMedia: () -> Unit,
    onOpenInfoPage: (InfoPage) -> Unit,
    onPage: (GroupPage) -> Unit,
    onMember: (ConversationMember) -> Unit,
    onLeave: () -> Unit,
) {
    val signal = signalColors
    val withCallPermissions = app.aino.mobile.core.call.rememberCallPermissions()
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var muteOpen by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(112.dp).clickable(enabled = perms.canEditInfo) { onPage(GroupPage.Edit) }) {
                    ConversationAvatar(conversation, null, 112.dp, members = state.members)
                    if (state.avatarUploading) Box(Modifier.size(112.dp).clip(CircleShape).background(Color.Black.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    if (perms.canEditInfo) Box(
                        Modifier.align(Alignment.BottomEnd).size(34.dp).clip(CircleShape).background(signal.surface).border(2.dp, signal.background, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.PhotoCamera, "Change group photo", Modifier.size(18.dp), tint = signal.text) }
                }
                Text(
                    conversation.title(), Modifier.padding(top = 16.dp),
                    color = signal.text, fontSize = 24.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val count = state.members.size.takeIf { it > 0 } ?: conversation.memberCount ?: 0
                Text("Group · $count ${if (count == 1) "member" else "members"}", Modifier.padding(top = 4.dp), color = signal.textSecondary, fontSize = 15.sp)
                val description = conversation.groupDescription?.takeIf(String::isNotBlank)
                if (description != null) {
                    Text(description, Modifier.padding(top = 10.dp), color = signal.text, fontSize = 15.sp, textAlign = TextAlign.Center)
                } else if (perms.canEditInfo) {
                    Text(
                        "Add group description…", Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).clickable { onPage(GroupPage.Edit) }.padding(6.dp),
                        color = signal.primary, fontSize = 15.sp,
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ui.callsEnabled) {
                    QuickAction(Icons.Outlined.Videocam, "Video", Modifier.weight(1f)) { withCallPermissions(true) { chat.closeInfo(); chat.startCall("video") } }
                    QuickAction(Icons.Outlined.Call, "Audio", Modifier.weight(1f)) { withCallPermissions(false) { chat.closeInfo(); chat.startCall("voice") } }
                }
                Box(Modifier.weight(1f)) {
                    QuickAction(
                        if (conversation.isMuted) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                        if (conversation.isMuted) "Muted" else "Mute", Modifier.fillMaxWidth(),
                    ) { if (conversation.isMuted) chat.muteFor(null) else muteOpen = true }
                    MuteDurationMenu(muteOpen, { muteOpen = false }) { chat.muteFor(it) }
                }
                QuickAction(Icons.Outlined.Search, "Search", Modifier.weight(1f)) { onOpenInfoPage(InfoPage.Search) }
            }
        }
        item { SectionDivider() }
        item { SettingsRow(HeroIcons.Photo, "All media, files & links", onClick = onOpenAllMedia) }
        item { SettingsRow(HeroIcons.PushPin, "Pinned messages") { onOpenInfoPage(InfoPage.Pinned) } }
        item { SettingsRow(HeroIcons.Star, "Saved messages") { onOpenInfoPage(InfoPage.Saved) } }
        item { SectionDivider() }

        item { SectionTitle("${state.members.size.takeIf { it > 0 } ?: conversation.memberCount ?: 0} members") }
        if (perms.canAddMembers) item { SettingsRow(Icons.Outlined.GroupAdd, "Add members", accent = true) { onPage(GroupPage.AddMembers) } }
        if (state.membersLoading && state.members.isEmpty()) item { Loading() }
        items(state.members.take(MEMBER_PREVIEW), key = { "m-${it.id}" }) { member -> MemberRow(member, ui.currentUserId) { onMember(member) } }
        if (state.members.size > MEMBER_PREVIEW) item { SettingsRow(HeroIcons.ChevronDown, "See all") { onPage(GroupPage.Members) } }
        item { SectionDivider() }

        if (perms.canManageLink) {
            item { SettingsRow(Icons.Outlined.Link, "Group link", trailing = if (state.inviteLink?.enabled == true) "On" else "Off") { onPage(GroupPage.Link) } }
            item {
                val pending = state.inviteLink?.pendingRequests ?: 0
                SettingsRow(Icons.Outlined.HowToReg, "Requests & invites", trailing = pending.takeIf { it > 0 }?.toString()) { onPage(GroupPage.Requests) }
            }
            item { SettingsRow(Icons.Outlined.Lock, "Permissions") { onPage(GroupPage.Permissions) } }
            item { SectionDivider() }
        }
        item { SettingsRow(Icons.AutoMirrored.Outlined.Logout, "Leave group", danger = true) { confirmLeave = true } }
        item { SettingsRow(HeroIcons.ArchiveBoxXMark, "Clear chat", danger = true) { confirmClear = true } }
    }
    if (confirmLeave) SignalConfirmDialog(
        title = "Leave group?",
        message = if (perms.isOwner) "You're the owner. Ownership passes to an admin (or the longest-standing member) when you leave."
        else "You will no longer be able to send or receive messages in this group.",
        confirmLabel = "Leave",
        onConfirm = onLeave,
        onDismiss = { confirmLeave = false },
    )
    if (confirmClear) ClearChatDialog(onConfirm = chat::clearChat) { confirmClear = false }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val signal = signalColors
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(signal.surface).clickable(onClick = onClick).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(24.dp), tint = signal.text)
        Text(label, Modifier.padding(top = 4.dp), color = signal.text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
internal fun SettingsRow(
    icon: ImageVector?,
    label: String,
    danger: Boolean = false,
    accent: Boolean = false,
    trailing: String? = null,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val signal = signalColors
    val tint = when { danger -> signal.danger; accent -> signal.primary; else -> signal.text }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).let { if (accent) it.clip(CircleShape).background(signal.primary.copy(alpha = .14f)) else it },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(24.dp), tint = tint) }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = tint, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = signal.textSecondary, fontSize = 13.sp)
        }
        if (trailing != null) Text(trailing, color = signal.textSecondary, fontSize = 15.sp)
    }
}

@Composable
private fun SectionDivider() = HorizontalDivider(Modifier.padding(vertical = 8.dp), color = signalColors.divider, thickness = 0.5.dp)

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp), color = signalColors.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = signalColors.primary, strokeWidth = 2.dp)
    }
}

@Composable
internal fun MemberRow(member: ConversationMember, currentUserId: Long?, onClick: (() -> Unit)?) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(member.display(), member.avatar, 40.dp)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(if (member.id == currentUserId) "You" else member.display(), color = signal.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            member.username?.takeIf(String::isNotBlank)?.let { Text("@$it", color = signal.textSecondary, fontSize = 13.sp, maxLines = 1) }
        }
        roleBadge(member.role)?.let { Text(it, color = signal.textSecondary, fontSize = 13.sp) }
    }
}

// ── Members ──────────────────────────────────────────────────────────────

@Composable
private fun MemberListPage(
    members: List<ConversationMember>,
    currentUserId: Long?,
    perms: GroupPermissions,
    onAdd: () -> Unit,
    onMember: (ConversationMember) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (perms.canAddMembers) item { SettingsRow(Icons.Outlined.GroupAdd, "Add members", accent = true, onClick = onAdd) }
        items(members, key = { "all-${it.id}" }) { member -> MemberRow(member, currentUserId) { onMember(member) } }
    }
}

@Composable
private fun AddMembersPage(conversation: ChatConversation, state: GroupSettingsUiState, vm: GroupSettingsViewModel, onDone: () -> Unit) {
    val signal = signalColors
    var picked by remember { mutableStateOf<Map<Long, ChatUser>>(emptyMap()) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(44.dp).clip(RoundedCornerShape(22.dp)).background(signal.searchPill).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(18.dp), tint = signal.textSecondary)
            BasicTextField(
                state.candidateQuery, { vm.searchCandidates(it) }, Modifier.weight(1f).padding(start = 10.dp), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = signal.text),
                decorationBox = { inner -> if (state.candidateQuery.isEmpty()) Text("Search people in your organization", color = signal.textSecondary); inner() },
            )
        }
        if (picked.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(picked.values.toList(), key = { "chip-${it.id}" }) { user ->
                    Row(
                        Modifier.clip(RoundedCornerShape(16.dp)).background(signal.surface).clickable { picked = picked - user.id }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UserAvatar(user.fullName ?: user.username, user.avatar, 24.dp)
                        Text(user.fullName ?: user.username.orEmpty(), Modifier.padding(start = 6.dp), color = signal.text, fontSize = 14.sp, maxLines = 1)
                        Icon(HeroIcons.XMark, "Remove", Modifier.padding(start = 4.dp).size(14.dp), tint = signal.textSecondary)
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f)) {
            when {
                state.searching -> item { Loading() }
                state.candidateQuery.trim().length < 2 -> item {
                    Text("Type at least 2 characters to find people.", Modifier.padding(24.dp), color = signal.textSecondary, fontSize = 14.sp)
                }
                state.candidates.isEmpty() -> item {
                    Text("No one new found. People already in the group aren't shown.", Modifier.padding(24.dp), color = signal.textSecondary, fontSize = 14.sp)
                }
            }
            items(state.candidates, key = { "c-${it.id}" }) { user ->
                val selected = user.id in picked
                Row(
                    Modifier.fillMaxWidth().clickable { picked = if (selected) picked - user.id else picked + (user.id to user) }.padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    UserAvatar(user.fullName ?: user.username, user.avatar, 40.dp)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(user.fullName ?: user.username.orEmpty(), color = signal.text, fontSize = 16.sp, maxLines = 1)
                        user.username?.let { Text("@$it", color = signal.textSecondary, fontSize = 13.sp, maxLines = 1) }
                    }
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(if (selected) signal.primary else Color.Transparent)
                            .border(2.dp, if (selected) signal.primary else signal.textSecondary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (selected) Icon(HeroIcons.Check, null, Modifier.size(14.dp), tint = Color.White) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { confirm = true }, enabled = picked.isNotEmpty() && !state.busy) {
                Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}", fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
    if (confirm) {
        val names = picked.values.map { it.fullName ?: it.username.orEmpty() }
        SignalConfirmDialog(
            title = "Add ${if (names.size == 1) names.first() else "${names.size} people"} to \"${conversation.title()}\"?",
            message = "They'll see messages sent from now on.",
            confirmLabel = "Add",
            danger = false,
            onConfirm = { vm.addMembers(picked.keys, onDone = { picked = emptyMap(); onDone() }) },
            onDismiss = { confirm = false },
        )
    }
}

// ── Member sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupMemberSheet(
    member: ConversationMember,
    conversation: ChatConversation,
    perms: GroupPermissions,
    ui: ChatUiState,
    chat: ChatViewModel,
    vm: GroupSettingsViewModel,
    onDismiss: () -> Unit,
) {
    val signal = signalColors
    var confirm by remember { mutableStateOf<String?>(null) }
    val isSelf = member.id == ui.currentUserId
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = signal.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UserAvatar(member.display(), member.avatar, 80.dp)
            Text(if (isSelf) "You" else member.display(), Modifier.padding(top = 12.dp), color = signal.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            member.username?.takeIf(String::isNotBlank)?.let { Text("@$it", color = signal.textSecondary, fontSize = 15.sp) }
            roleBadge(member.role)?.let { Text("Group $it".lowercase().replaceFirstChar(Char::uppercase), Modifier.padding(top = 4.dp), color = signal.primary, fontSize = 14.sp) }
        }
        Spacer(Modifier.height(12.dp))
        if (!isSelf) {
            SettingsRow(HeroIcons.ChatBubbleOvalLeft, "Message") {
                onDismiss(); chat.closeInfo()
                chat.startDirect(ChatUser(id = member.id, username = member.username, fullName = member.fullName, avatar = member.avatar))
            }
            if (perms.canChangeRoles && member.role != "owner") {
                if (member.role == "admin") SettingsRow(Icons.Outlined.RemoveModerator, "Remove as admin") { onDismiss(); vm.setAdmin(member, false) }
                else SettingsRow(Icons.Outlined.AdminPanelSettings, "Make group admin") { onDismiss(); vm.setAdmin(member, true) }
            }
            if (perms.canTransferOwnership && member.role != "owner") {
                SettingsRow(Icons.Outlined.WorkspacePremium, "Make group owner") { confirm = "owner" }
            }
            if (perms.canRemove(member, ui.currentUserId)) {
                SettingsRow(Icons.Outlined.PersonRemove, "Remove from group", danger = true) { confirm = "remove" }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    when (confirm) {
        "remove" -> SignalConfirmDialog(
            title = "Remove ${member.display()} from \"${conversation.title()}\"?",
            message = "They won't be able to see new messages in this group.",
            confirmLabel = "Remove",
            onConfirm = { vm.removeMember(member); onDismiss() },
            onDismiss = { confirm = null },
        )
        "owner" -> SignalConfirmDialog(
            title = "Make ${member.display()} the group owner?",
            message = "You'll become an admin. Only the owner can change roles, permissions and ownership.",
            confirmLabel = "Make owner",
            onConfirm = { vm.transferOwnership(member); onDismiss() },
            onDismiss = { confirm = null },
        )
    }
}

// ── Edit group ───────────────────────────────────────────────────────────

@Composable
private fun EditGroupPage(conversation: ChatConversation, state: GroupSettingsUiState, perms: GroupPermissions, vm: GroupSettingsViewModel, onDone: () -> Unit) {
    val signal = signalColors
    var name by remember(conversation.id) { mutableStateOf(conversation.groupName.orEmpty()) }
    var description by remember(conversation.id) { mutableStateOf(conversation.groupDescription.orEmpty()) }
    var photoMenu by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? -> uri?.let(vm::uploadAvatar) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 16.dp)) {
            Box(Modifier.size(112.dp).clip(CircleShape).clickable(enabled = perms.canEditInfo) { photoMenu = true }) {
                ConversationAvatar(conversation, null, 112.dp, members = state.members)
                if (state.avatarUploading) Box(Modifier.size(112.dp).background(Color.Black.copy(alpha = .4f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
            SignalDropdownMenu(expanded = photoMenu, onDismiss = { photoMenu = false }) {
                SignalMenuItem("Choose photo", HeroIcons.Photo) {
                    photoMenu = false
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                if (!conversation.groupAvatar.isNullOrBlank()) SignalMenuItem("Remove photo", HeroIcons.Trash, danger = true) { photoMenu = false; vm.removeAvatar() }
            }
        }
        TextButton(onClick = { photoMenu = true }, enabled = perms.canEditInfo && !state.avatarUploading) {
            Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Text("  Edit photo")
        }
        Text("Without a photo, the group shows its members' pictures.", color = signal.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        EditField(name, { name = it.take(100) }, "Group name (required)", maxLength = 100)
        Spacer(Modifier.height(12.dp))
        EditField(description, { description = it.take(500) }, "Description", maxLength = 500, singleLine = false)
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text("Cancel") }
            TextButton(onClick = { vm.saveInfo(name, description, conversation, onDone) }, enabled = name.isNotBlank() && !state.busy) {
                Text("Save", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun EditField(value: String, onValue: (String) -> Unit, hint: String, maxLength: Int, singleLine: Boolean = true) {
    val signal = signalColors
    Column(Modifier.fillMaxWidth()) {
        BasicTextField(
            value, onValue,
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(signal.surface).padding(16.dp),
            singleLine = singleLine, minLines = if (singleLine) 1 else 3,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = signal.text),
            decorationBox = { inner -> if (value.isEmpty()) Text(hint, color = signal.textSecondary); inner() },
        )
        Text("${value.length}/$maxLength", Modifier.align(Alignment.End).padding(top = 4.dp), color = signal.textSecondary, fontSize = 12.sp)
    }
}

// ── Group link ───────────────────────────────────────────────────────────

@Composable
private fun GroupLinkPage(state: GroupSettingsUiState, perms: GroupPermissions, vm: GroupSettingsViewModel, onRequests: () -> Unit) {
    val signal = signalColors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadInviteLink() }
    val link = state.inviteLink
    val url = link?.token?.let { groupInviteUrl(app.aino.mobile.core.network.NetworkConfig.serverOrigin, it) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Group link", color = signal.text, fontSize = 16.sp)
                    Text("Share with people in your organization so they can join.", color = signal.textSecondary, fontSize = 13.sp)
                }
                Switch(
                    checked = link?.enabled == true, onCheckedChange = vm::setInviteEnabled, enabled = link != null && !state.busy && perms.canManageLink,
                    colors = SwitchDefaults.colors(checkedTrackColor = signal.primary),
                )
            }
        }
        if (state.inviteLoading && link == null) item { Loading() }
        if (url != null) {
            item {
                Text(
                    url, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp).clip(RoundedCornerShape(12.dp)).background(signal.surface).padding(16.dp),
                    color = signal.primary, fontSize = 14.sp,
                )
            }
            item {
                SettingsRow(Icons.Outlined.Share, "Share") {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
                    context.startActivity(Intent.createChooser(send, "Share group link"))
                }
            }
            item { SettingsRow(Icons.Outlined.ContentCopy, "Copy") { clipboard.setText(AnnotatedString(url)) } }
            item { SettingsRow(Icons.Outlined.Sync, "Reset link") { confirmReset = true } }
            item { SectionDivider() }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Approve new members", color = signal.text, fontSize = 16.sp)
                        Text("Require an admin to approve people who join with the link.", color = signal.textSecondary, fontSize = 13.sp)
                    }
                    Switch(
                        checked = link.requiresApproval, onCheckedChange = vm::setRequiresApproval, enabled = !state.busy,
                        colors = SwitchDefaults.colors(checkedTrackColor = signal.primary),
                    )
                }
            }
            if (link.requiresApproval) item {
                SettingsRow(Icons.Outlined.HowToReg, "Requests & invites", trailing = link.pendingRequests.takeIf { it > 0 }?.toString(), onClick = onRequests)
            }
        } else if (link != null) {
            item {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AddLink, null, Modifier.size(24.dp), tint = signal.textSecondary)
                    Text("  Turn on the group link to invite people without adding them one by one.", color = signal.textSecondary, fontSize = 14.sp)
                }
            }
        }
    }
    if (confirmReset) SignalConfirmDialog(
        title = "Reset link?",
        message = "People won't be able to join with the current link anymore. You'll get a new link to share.",
        confirmLabel = "Reset link",
        onConfirm = vm::resetInviteLink,
        onDismiss = { confirmReset = false },
    )
}

@Composable
private fun JoinRequestsPage(state: GroupSettingsUiState, vm: GroupSettingsViewModel) {
    val signal = signalColors
    LaunchedEffect(Unit) { vm.loadJoinRequests() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Text(
                "People who asked to join with the group link. Approved people can see new messages.",
                Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = signal.textSecondary, fontSize = 14.sp,
            )
        }
        if (state.requestsLoading && state.joinRequests.isEmpty()) item { Loading() }
        else if (state.joinRequests.isEmpty()) item {
            Text("No pending requests.", Modifier.padding(24.dp), color = signal.textSecondary, fontSize = 15.sp)
        }
        items(state.joinRequests, key = { "r-${it.id}" }) { request ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                UserAvatar(request.display(), request.avatar, 40.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(request.display(), color = signal.text, fontSize = 16.sp, maxLines = 1)
                    request.username?.let { Text("@$it", color = signal.textSecondary, fontSize = 13.sp, maxLines = 1) }
                }
                TextButton(onClick = { vm.resolveRequest(request, approve = false) }, enabled = !state.busy) { Text("Deny", color = signal.danger) }
                TextButton(onClick = { vm.resolveRequest(request, approve = true) }, enabled = !state.busy) { Text("Approve", fontWeight = FontWeight.Medium) }
            }
        }
    }
}

// ── Permissions ──────────────────────────────────────────────────────────

@Composable
private fun PermissionsPage(conversation: ChatConversation, perms: GroupPermissions, vm: GroupSettingsViewModel) {
    val signal = signalColors
    var picking by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        if (!perms.canChangePolicies) item {
            Text("Only the group owner can change these.", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = signal.textSecondary, fontSize = 14.sp)
        }
        item {
            SettingsRow(null, "Add members", subtitle = policyLabel(perms.addPolicy), onClick = if (perms.canChangePolicies) ({ picking = "add" }) else null)
        }
        item {
            SettingsRow(null, "Send messages", subtitle = policyLabel(perms.postPolicy), onClick = if (perms.canChangePolicies) ({ picking = "post" }) else null)
        }
        item { SettingsRow(null, "Edit group info", subtitle = "Only admins") }
    }
    picking?.let { which ->
        val current = if (which == "add") perms.addPolicy else perms.postPolicy
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { picking = null },
            containerColor = signal.surface,
            title = { Text(if (which == "add") "Who can add members?" else "Who can send messages?", color = signal.text) },
            text = {
                Column {
                    listOf("all" to "All members", "admins" to "Only admins").forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable {
                                picking = null
                                if (value != current) {
                                    if (which == "add") vm.setPolicies(addPolicy = value) else vm.setPolicies(postPolicy = value)
                                }
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.RadioButton(selected = value == current, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp), color = signal.text, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
        )
    }
}

private fun policyLabel(policy: String): String = if (policy == "admins") "Only admins" else "All members"
