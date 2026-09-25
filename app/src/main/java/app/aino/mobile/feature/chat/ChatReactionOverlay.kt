package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp

// Verbatim parity with aino-platform's `ReactionPicker.tsx` quick-reaction row.
val DefaultQuickReactions = listOf("👍", "❤️", "😂", "😮", "😢", "🔥", "👏", "🎉", "👎", "💯")

data class EmojiCategory(val name: String, val emojis: List<String>)

val DefaultEmojiCategories = listOf(
    EmojiCategory("Smileys", listOf("😀","😃","😄","😁","😆","😅","😂","🤣","😊","😇","🙂","🙃","😉","😍","🥰","😘","😋","😎","🤩","🥳","😏","😢","😭","😡","🤯","😱","😮","🤔","🫡","🤗")),
    EmojiCategory("Gestures", listOf("👍","👎","👌","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","👇","☝️","✋","🤚","🖐️","🖖","👋","👏","🙌","🫶","🙏","💪")),
    EmojiCategory("Hearts", listOf("❤️","🩷","🧡","💛","💚","💙","🩵","💜","🤎","🖤","🩶","🤍","💔","❣️","💕","💞","💓","💗","💖","💘","💝")),
    EmojiCategory("People", listOf("👶","🧒","👦","👧","🧑","👩","👨","🧓","👴","👵","👮","👷","💂","🕵️","👩‍⚕️","👨‍🍳","👩‍🎓","👨‍💻","🧑‍🚀")),
    EmojiCategory("Animals", listOf("🐶","🐱","🐭","🐹","🐰","🦊","🐻","🐼","🐨","🐯","🦁","🐮","🐷","🐸","🐵","🐔","🐧","🐦","🦄","🐝","🦋")),
    EmojiCategory("Food", listOf("🍏","🍎","🍐","🍊","🍋","🍌","🍉","🍇","🍓","🫐","🍒","🥝","🍅","🥑","🍕","🍔","🌮","🍿","☕","🎂")),
    EmojiCategory("Activities", listOf("⚽","🏀","🏈","⚾","🎾","🏐","🎱","🏓","🏸","🥅","⛳","🎣","🎮","🎲","🎨","🎸","🎯","🏆")),
    EmojiCategory("Travel", listOf("🚗","🚕","🚌","🏎️","🚓","🚑","🚒","🚲","✈️","🚀","🚁","⛵","🚢","🏠","🏖️","🏔️","🌋","🗺️")),
    EmojiCategory("Objects", listOf("⌚","📱","💻","⌨️","📷","💡","📚","✏️","📌","🔒","🔑","🔨","🧲","🎁","🎈","🔔","💎")),
    EmojiCategory("Symbols", listOf("✅","❌","❓","❗","💯","🔥","✨","⭐","🌟","💥","💫","💤","🎉","🎊","🚩","⚠️","♻️")),
)

data class ReactionActions(
    val onReply: () -> Unit,
    val onForward: () -> Unit,
    val onEdit: (() -> Unit)? = null,
    val onStar: () -> Unit,
    val onPin: () -> Unit,
    val onDelete: (() -> Unit)? = null,
    val onCopy: (() -> Unit)? = null,
    val pinned: Boolean = false,
    val starred: Boolean = false,
    val onSelect: (() -> Unit)? = null,
)

/** Signal's default reaction scrubber set. */
val SignalQuickReactions = listOf("❤️", "👍", "👎", "😂", "😮", "😢")

/**
 * Signal long-press overlay: dimmed scrim, the lifted message, a 320dp reaction
 * scrubber (drag across to pick, haptic tick per emoji) and a context menu card.
 */
@Composable
fun ChatReactionOverlay(
    onDismiss: () -> Unit,
    onReaction: (String) -> Unit,
    actions: ReactionActions,
    modifier: Modifier = Modifier,
    quickReactions: List<String> = SignalQuickReactions,
    myReactions: Set<String> = emptySet(),
    alignEnd: Boolean = false,
    messagePreview: (@Composable () -> Unit)? = null,
) {
    val signal = signalColors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var showPicker by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(-1) }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val lift by androidx.compose.animation.core.animateFloatAsState(if (appeared) 1f else 0f, label = "lift")
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(signal.scrim.copy(alpha = signal.scrim.alpha * lift)).clickable(onClick = onDismiss)) {
            Column(
                modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 16.dp).clickable(enabled = false) {},
                horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Scrubber: 320dp pill, emoji cells scale up while hovered.
                var cellWidthPx by remember { mutableStateOf(1f) }
                Row(
                    Modifier
                        .graphicsLayer { translationY = (1f - lift) * 25.dp.toPx(); alpha = lift }
                        .width(SignalDimens.reactionScrubberWidth)
                        .clip(RoundedCornerShape(28.dp)).background(signal.surface)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .pointerInput(quickReactions) {
                            detectDragGestures(
                                onDragEnd = { quickReactions.getOrNull(hovered)?.let { onReaction(it); onDismiss() }; hovered = -1 },
                                onDragCancel = { hovered = -1 },
                            ) { change, _ ->
                                val index = (change.position.x / cellWidthPx).toInt()
                                if (index != hovered && index in quickReactions.indices) {
                                    hovered = index
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    quickReactions.forEachIndexed { index, emoji ->
                        val scale by androidx.compose.animation.core.animateFloatAsState(if (hovered == index) 1.45f else 1f, label = "emoji")
                        Box(
                            Modifier.weight(1f).height(48.dp)
                                .onSizeChanged { cellWidthPx = it.width.toFloat() }
                                .clip(CircleShape)
                                .background(if (emoji in myReactions) signal.primary.copy(alpha = .18f) else Color.Transparent)
                                .clickable(role = Role.Button) { onReaction(emoji); onDismiss() }
                                .semantics { contentDescription = "React with $emoji" },
                            contentAlignment = Alignment.Center,
                        ) { Text(emoji, Modifier.graphicsLayer { scaleX = scale; scaleY = scale }, fontSize = 28.sp) }
                    }
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(signal.background).clickable { showPicker = true },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, "More reactions", tint = signal.text) }
                }
                Box(Modifier.graphicsLayer { scaleX = 0.96f + .04f * lift; scaleY = 0.96f + .04f * lift }) { messagePreview?.invoke() }
                // Signal context menu card.
                Column(
                    Modifier.graphicsLayer { alpha = lift }.width(220.dp).clip(RoundedCornerShape(18.dp)).background(signal.surface).padding(vertical = 6.dp),
                ) {
                    ReactionAction("Reply", Icons.AutoMirrored.Outlined.Reply) { actions.onReply(); onDismiss() }
                    actions.onEdit?.let { ReactionAction("Edit", Icons.Outlined.Edit) { it(); onDismiss() } }
                    ReactionAction("Forward", Icons.AutoMirrored.Outlined.Forward) { actions.onForward(); onDismiss() }
                    actions.onCopy?.let { ReactionAction("Copy", Icons.Outlined.ContentCopy) { it(); onDismiss() } }
                    actions.onSelect?.let { ReactionAction("Select", Icons.Outlined.CheckCircleOutline) { it(); onDismiss() } }
                    ReactionAction(if (actions.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { actions.onPin(); onDismiss() }
                    ReactionAction(if (actions.starred) "Unsave" else "Save", if (actions.starred) Icons.Outlined.Star else Icons.Outlined.StarOutline) { actions.onStar(); onDismiss() }
                    actions.onDelete?.let { ReactionAction("Delete", Icons.Outlined.Delete, destructive = true) { it(); onDismiss() } }
                }
            }
        }
        if (showPicker) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .3f)).clickable { showPicker = false }) {
                SignalEmojiKeyboard(
                    onEmoji = { onReaction(it); onDismiss() },
                    onBackspace = {},
                    modifier = Modifier.align(Alignment.BottomCenter).clickable(enabled = false) {},
                    height = 380.dp,
                )
            }
        }
    }
}

@Composable private fun ReactionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) {
    val signal = signalColors
    val color = if (destructive) signal.danger else signal.text
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(role = Role.Button, onClick = action).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = color); Spacer(Modifier.width(16.dp)); Text(label, color = color, fontSize = 16.sp)
    }
}

/** Signal reactions sheet: "All" tab plus one per emoji, listing who reacted. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReactionsSheet(reactions: List<ChatReaction>, currentUserId: Long?, onRemoveMine: (String) -> Unit, onDismiss: () -> Unit) {
    val signal = signalColors
    val groups = remember(reactions) { reactions.groupBy { it.emoji } }
    var tab by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = signal.surface) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReactionTab("All ${reactions.size}", tab == null) { tab = null }
            groups.forEach { (emoji, list) -> ReactionTab("$emoji ${list.size}", tab == emoji) { tab = emoji } }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).padding(top = 8.dp)) {
            items(if (tab == null) reactions else groups[tab].orEmpty(), key = { "${it.userId}-${it.emoji}" }) { r ->
                val mine = r.userId == currentUserId
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = mine) { onRemoveMine(r.emoji); onDismiss() }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    app.aino.mobile.core.designsystem.component.UserAvatar(r.fullName.ifBlank { "?" }, null, 36.dp)
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(if (mine) "You" else r.fullName.ifBlank { "Someone" }, color = signal.text, fontSize = 16.sp)
                        if (mine) Text("Tap to remove", color = signal.textSecondary, fontSize = 13.sp)
                    }
                    Text(r.emoji, fontSize = 24.sp)
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable private fun ReactionTab(label: String, active: Boolean, onClick: () -> Unit) {
    val signal = signalColors
    Text(
        label,
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (active) signal.primary.copy(alpha = .18f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
        color = if (active) signal.primary else signal.textSecondary, fontSize = 15.sp,
    )
}
/** Searchable and categorized emoji browser. Search matches category names and emoji glyphs. */
@Composable
fun EmojiPicker(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    categories: List<EmojiCategory> = DefaultEmojiCategories,
) {
    var query by remember { mutableStateOf("") }
    // `.align(Alignment.BottomCenter)` requires a `BoxScope` receiver; this
    // composable is invoked as a bare sibling (e.g. from inside a `Dialog`),
    // so it must provide its own `Box` rather than assume the caller's.
    Box(Modifier.fillMaxSize()) {
    Surface(modifier.fillMaxWidth().fillMaxHeight(.78f).align(Alignment.BottomCenter), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 12.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose a reaction", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close emoji picker") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search emoji or category") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
            Spacer(Modifier.height(8.dp))
            val visible = remember(query, categories) { if (query.isBlank()) categories else categories.mapNotNull { c ->
                if (c.name.contains(query, true)) c else c.copy(emojis = c.emojis.filter { it.contains(query) }).takeIf { it.emojis.isNotEmpty() }
            } }
            if (visible.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No emoji found") }
            else LazyColumn {
                items(visible, key = { it.name }) { category ->
                    Text(category.name, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.labelLarge, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
                    LazyVerticalGrid(GridCells.Adaptive(44.dp), Modifier.fillMaxWidth().height(((category.emojis.size + 7) / 8 * 48).coerceAtLeast(48).dp), userScrollEnabled = false) {
                        items(category.emojis.size) { index -> val emoji = category.emojis[index]; Text(emoji, Modifier.size(44.dp).semantics { contentDescription = "React with $emoji" }.clickable(role = Role.Button) { onEmojiSelected(emoji) }.wrapContentSize(), style = MaterialTheme.typography.headlineSmall) }
                    }
                }
            }
        }
    }
    }
}