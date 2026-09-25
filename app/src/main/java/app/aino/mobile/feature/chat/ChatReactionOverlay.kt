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

/** Signal-like modal surface. [messagePreview] should render the existing message bubble. */
@Composable
fun ChatReactionOverlay(
    onDismiss: () -> Unit,
    onReaction: (String) -> Unit,
    actions: ReactionActions,
    modifier: Modifier = Modifier,
    quickReactions: List<String> = DefaultQuickReactions,
    messagePreview: (@Composable () -> Unit)? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .58f)).clickable(onClick = onDismiss)) {
            Column(modifier.align(Alignment.Center).padding(20.dp).widthIn(max = 440.dp).clickable(enabled = false) {}, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                messagePreview?.invoke()
                Surface(modifier, shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) {
                    Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        quickReactions.forEach { emoji ->
                            Text(emoji, Modifier.size(44.dp).clickable(role = Role.Button) { onReaction(emoji); onDismiss() }.wrapContentSize(), style = MaterialTheme.typography.headlineSmall)
                        }
                        IconButton(onClick = { showPicker = true }) { Icon(Icons.Outlined.Add, "More reactions") }
                    }
                }
                Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp) {
                    Column {
                        // Order and labels follow web `MessageBubble.tsx` menuItems.
                        actions.onSelect?.let { ReactionAction("Select", Icons.Outlined.CheckBox) { it(); onDismiss() } }
                        ReactionAction("Reply", Icons.AutoMirrored.Outlined.Reply) { actions.onReply(); onDismiss() }
                        actions.onEdit?.let { ReactionAction("Edit", Icons.Outlined.Edit) { it(); onDismiss() } }
                        actions.onCopy?.let { ReactionAction("Copy", Icons.Outlined.ContentCopy) { it(); onDismiss() } }
                        ReactionAction(if (actions.pinned) "Unpin" else "Pin", Icons.Outlined.PushPin) { actions.onPin(); onDismiss() }
                        ReactionAction(if (actions.starred) "Unsave" else "Save", Icons.Outlined.Star) { actions.onStar(); onDismiss() }
                        ReactionAction("Forward", Icons.AutoMirrored.Outlined.Forward) { actions.onForward(); onDismiss() }
                        actions.onDelete?.let { ReactionAction("Delete", Icons.Outlined.Delete, destructive = true) { it(); onDismiss() } }
                    }
                }
            }
        }
        if (showPicker) EmojiPicker(onEmojiSelected = { onReaction(it); onDismiss() }, onDismiss = { showPicker = false })
    }
}

@Composable private fun ReactionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, destructive: Boolean = false, action: () -> Unit) {
    val color = if (destructive) app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.danger else app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.text
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = action).padding(horizontal = 18.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color); Spacer(Modifier.width(16.dp)); Text(label, color = color)
    }
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