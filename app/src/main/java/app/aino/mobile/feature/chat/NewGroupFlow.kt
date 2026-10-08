package app.aino.mobile.feature.chat

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.rememberAinoHaptics
import coil3.compose.AsyncImage

private const val GROUP_NAME_MAX = 100

/** People from existing 1:1 chats, most recent first: the picker's "Recent chats" before any search. */
internal fun recentGroupContacts(conversations: List<ChatConversation>, currentUserId: Long?, limit: Int = 30): List<ChatUser> =
    conversations.asSequence()
        .filter { !it.isGroup && !it.isMeetingChat && !it.isSelfChat && !it.isBlocked && it.otherUserId != null && it.otherUserId != currentUserId }
        .sortedByDescending { it.lastMessageAt ?: it.updatedAt.orEmpty() }
        .map { ChatUser(id = it.otherUserId!!, username = it.otherUsername, fullName = it.otherFullName, avatar = it.otherAvatar) }
        .distinctBy { it.id }
        .take(limit)
        .toList()

/**
 * Signal's new-group flow as a full-screen surface: step 1 picks members
 * (recent chats + search, chips of the picked), step 2 names the group and
 * sets an optional photo, then creates it.
 */
@Composable
internal fun NewGroupFlow(ui: ChatUiState, viewModel: ChatViewModel, onClose: () -> Unit) {
    val picked = remember { mutableStateMapOf<Long, ChatUser>() }
    var order by remember { mutableStateOf(listOf<Long>()) }
    var step by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var submitted by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { viewModel.clearGroupCandidates() } }
    // Close once the create call finishes cleanly; on failure stay on the details step with the error.
    LaunchedEffect(ui.creatingGroup, submitted) {
        if (submitted && !ui.creatingGroup) {
            if (ui.error == null) onClose() else submitted = false
        }
    }
    val toggle: (ChatUser) -> Unit = { user ->
        if (user.id in picked) { picked.remove(user.id); order = order - user.id }
        else { picked[user.id] = user; order = order + user.id }
    }
    val back = { if (step == 1 && !ui.creatingGroup) step = 0 else if (step == 0) onClose() }
    Dialog(
        onDismissRequest = back,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnClickOutside = false),
    ) {
        BackHandler(onBack = back)
        // Make the dialog window truly full-screen and edge-to-edge so Compose insets
        // (status bar, nav bar, keyboard) are applied once and the bottom actions stay visible.
        val dialogView = LocalView.current
        DisposableEffect(dialogView) {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                @Suppress("DEPRECATION")
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            onDispose { }
        }
        val signal = signalColors
        Box(Modifier.fillMaxSize().background(signal.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "newGroupStep",
            ) { current ->
                if (current == 0) {
                    MemberPickStep(
                        ui = ui,
                        viewModel = viewModel,
                        picked = order.mapNotNull { picked[it] },
                        isPicked = { it in picked },
                        onToggle = toggle,
                        onBack = onClose,
                        onNext = { step = 1 },
                    )
                } else {
                    GroupDetailsStep(
                        members = order.mapNotNull { picked[it] },
                        name = name,
                        onName = { name = it.take(GROUP_NAME_MAX) },
                        photo = photo,
                        onPhoto = { photo = it },
                        creating = ui.creatingGroup,
                        error = ui.error.takeIf { !ui.creatingGroup && step == 1 },
                        onBack = { step = 0 },
                        onCreate = {
                            submitted = true
                            viewModel.createGroup(name, order.filter { it in picked }, photo)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MemberPickStep(
    ui: ChatUiState,
    viewModel: ChatViewModel,
    picked: List<ChatUser>,
    isPicked: (Long) -> Boolean,
    onToggle: (ChatUser) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val signal = signalColors
    val haptics = rememberAinoHaptics()
    val candidates by viewModel.groupCandidates.collectAsStateWithLifecycle()
    val recents = remember(ui.conversations, ui.currentUserId) { recentGroupContacts(ui.conversations, ui.currentUserId) }
    val query = candidates.query.trim()
    val searching = query.length >= 2
    val chipsState = rememberLazyListState()
    LaunchedEffect(picked.size) { if (picked.isNotEmpty()) chipsState.animateScrollToItem(picked.lastIndex) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FlowTopBar(
                title = "New group",
                subtitle = if (picked.isEmpty()) "Add members" else "${picked.size} member${if (picked.size == 1) "" else "s"} selected",
                onBack = onBack,
            )
            // Search field.
            val focus = remember { FocusRequester() }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(44.dp)
                    .clip(RoundedCornerShape(22.dp)).background(signal.searchPill).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(18.dp), tint = signal.textSecondary)
                BasicTextField(
                    candidates.query, { viewModel.searchGroupCandidates(it) },
                    Modifier.weight(1f).padding(start = 10.dp).focusRequester(focus), singleLine = true,
                    textStyle = TextStyle(color = signal.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(signal.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.searchGroupCandidates(candidates.query, debounceMs = 0) }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (candidates.query.isEmpty()) Text("Search name or email", color = signal.textSecondary, fontSize = 16.sp)
                            inner()
                        }
                    },
                )
                if (candidates.query.isNotEmpty()) {
                    Icon(
                        HeroIcons.XMark, "Clear search",
                        Modifier.size(24.dp).clip(CircleShape).clickable { viewModel.searchGroupCandidates("", debounceMs = 0) },
                        tint = signal.textSecondary,
                    )
                }
            }
            // Picked members as removable chips.
            AnimatedVisibility(picked.isNotEmpty()) {
                LazyRow(
                    state = chipsState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(picked, key = { "chip-${it.id}" }) { user ->
                        Row(
                            Modifier.animateItem().clip(RoundedCornerShape(20.dp)).background(signal.searchPill)
                                .clickable(onClickLabel = "Remove ${user.display()}") { haptics.toggle(); onToggle(user) }
                                .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            UserAvatar(user.display(), user.avatar, 28.dp)
                            Text(
                                user.display().substringBefore(' ').ifBlank { "User" },
                                Modifier.padding(start = 8.dp).widthIn(max = 120.dp),
                                color = signal.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Icon(HeroIcons.XMark, null, Modifier.padding(start = 6.dp).size(14.dp), tint = signal.textSecondary)
                        }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 96.dp)) {
                val rows = if (searching) candidates.results.filter { it.id != ui.currentUserId } else recents
                item(key = "section") {
                    Text(
                        if (searching) "Search results" else "Recent chats",
                        Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                        color = signal.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                when {
                    searching && candidates.searching && rows.isEmpty() -> item(key = "searching") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = signal.primary)
                        }
                    }
                    candidates.error != null && searching -> item(key = "error") { PickerHint(candidates.error.orEmpty(), signal.danger) }
                    query.length == 1 -> item(key = "short") { PickerHint("Type at least 2 characters", signal.textSecondary) }
                    rows.isEmpty() -> item(key = "empty") {
                        PickerHint(if (searching) "No people found" else "Search for people to add to the group", signal.textSecondary)
                    }
                }
                items(rows, key = { "pick-${it.id}" }) { user ->
                    MemberPickRow(user, isPicked(user.id)) { haptics.toggle(); onToggle(user) }
                }
            }
        }
        // Signal's round "Next" arrow once someone is picked.
        AnimatedVisibility(
            picked.isNotEmpty(),
            Modifier.align(Alignment.BottomEnd).padding(20.dp),
            enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(),
        ) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(signal.primary)
                    .clickable(onClickLabel = "Next", role = Role.Button) { haptics.tap(); onNext() },
                contentAlignment = Alignment.Center,
            ) { Icon(HeroIcons.ArrowRight, "Next", Modifier.size(26.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun MemberPickRow(user: ChatUser, picked: Boolean, onClick: () -> Unit) {
    val signal = signalColors
    val name = user.display().ifBlank { "Unknown user" }
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(name, user.avatar, SignalDimens.listAvatar)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(name, color = signal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            (user.username?.let { "@$it" } ?: user.email)?.takeIf(String::isNotBlank)?.let {
                Text(it, color = signal.textSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        RoundCheck(picked)
    }
}

/** Signal's round checkbox: outlined ring, filling with a check when picked. */
@Composable
private fun RoundCheck(checked: Boolean) {
    val signal = signalColors
    val fill by animateColorAsState(if (checked) signal.primary else Color.Transparent, label = "checkFill")
    val ring by animateColorAsState(if (checked) signal.primary else signal.textSecondary.copy(alpha = .6f), label = "checkRing")
    Box(Modifier.size(24.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
        AnimatedVisibility(checked, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(HeroIcons.Check, null, Modifier.size(14.dp), tint = Color.White)
        }
    }
}

@Composable
private fun PickerHint(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp), color = color, fontSize = 14.sp)
}

@Composable
private fun GroupDetailsStep(
    members: List<ChatUser>,
    name: String,
    onName: (String) -> Unit,
    photo: Uri?,
    onPhoto: (Uri?) -> Unit,
    creating: Boolean,
    error: String?,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    val signal = signalColors
    val haptics = rememberAinoHaptics()
    val context = LocalContext.current
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onPhoto(uri) }
    val canCreate = name.isNotBlank() && members.isNotEmpty() && !creating
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(error) { if (error != null) haptics.reject() }
    Column(Modifier.fillMaxSize()) {
        FlowTopBar(title = "Name this group", subtitle = null, onBack = onBack, enabled = !creating)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    // The camera badge overlaps the circle's edge, so only the photo circle is clipped.
                    Box(Modifier.size(112.dp)) {
                        Box(
                            Modifier.fillMaxSize().clip(CircleShape).background(signal.primary.copy(alpha = .14f))
                                .clickable(enabled = !creating, onClickLabel = if (photo == null) "Add group photo" else "Change group photo") {
                                    haptics.tap()
                                    pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (photo != null) {
                                AsyncImage(
                                    model = photo,
                                    imageLoader = AppContainer.get(context).imageLoader,
                                    contentDescription = "Group photo",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Icon(HeroIcons.UserGroup, null, Modifier.size(48.dp), tint = signal.primary)
                            }
                        }
                        Box(
                            Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(38.dp)
                                .clip(CircleShape).background(signal.background).padding(3.dp)
                                .clip(CircleShape).background(signal.primary),
                            contentAlignment = Alignment.Center,
                        ) { Icon(HeroIcons.Camera, null, Modifier.size(18.dp), tint = Color.White) }
                    }
                    if (photo != null) {
                        Text(
                            "Remove photo",
                            Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).clickable(enabled = !creating) { haptics.tap(); onPhoto(null) }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            color = signal.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                    // Group name field.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(signal.searchPill).padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                name, onName,
                                Modifier.weight(1f).focusRequester(focus), singleLine = true, enabled = !creating,
                                textStyle = TextStyle(color = signal.text, fontSize = 17.sp),
                                cursorBrush = SolidColor(signal.primary),
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (canCreate) { haptics.confirm(); onCreate() } }),
                                decorationBox = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (name.isEmpty()) Text("Group name (required)", color = signal.textSecondary, fontSize = 17.sp)
                                        inner()
                                    }
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                            error?.let { Text(it, Modifier.weight(1f), color = signal.danger, fontSize = 13.sp) } ?: Spacer(Modifier.weight(1f))
                            Text("${name.length}/$GROUP_NAME_MAX", color = signal.textSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            item(key = "members-title") {
                Text(
                    "Members · ${members.size + 1}",
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    color = signal.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            item(key = "me") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(signal.searchPill, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(HeroIcons.User, null, Modifier.size(20.dp), tint = signal.textSecondary)
                    }
                    Text("You", Modifier.padding(start = 14.dp), color = signal.text, fontSize = 16.sp)
                }
            }
            items(members, key = { "member-${it.id}" }) { user ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(user.display(), user.avatar, 40.dp)
                    Text(
                        user.display().ifBlank { "Unknown user" }, Modifier.padding(start = 14.dp),
                        color = signal.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Create button.
        val bg by animateColorAsState(if (canCreate || creating) signal.primary else signal.primary.copy(alpha = .4f), label = "createBg")
        Box(
            Modifier.fillMaxWidth().padding(16.dp).height(52.dp).clip(RoundedCornerShape(26.dp)).background(bg)
                .clickable(enabled = canCreate, role = Role.Button, onClickLabel = "Create group") { haptics.confirm(); onCreate() },
            contentAlignment = Alignment.Center,
        ) {
            if (creating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                    Text("Creating…", Modifier.padding(start = 10.dp), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text("Create", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FlowTopBar(title: String, subtitle: String?, onBack: () -> Unit, enabled: Boolean = true) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HeroIcons.ArrowLeft, "Back",
            Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onBack).padding(12.dp),
            tint = signal.text,
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                androidx.compose.animation.AnimatedContent(it, label = "subtitle") { text ->
                    Text(text, color = signal.textSecondary, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}
