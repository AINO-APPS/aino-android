package app.aino.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.rememberAinoHaptics

internal data class ChatListTabItem(val tab: ChatListTab, val label: String, val icon: ImageVector, val badge: Int)

/** One overflow-menu entry; [receipt] draws the double read-receipt glyph instead of [icon]. */
internal data class ChatMenuItem(
    val label: String,
    val icon: ImageVector? = null,
    val receipt: Boolean = false,
    val count: Int = 0,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** Borderless tabs on the page background: only the active one gets a soft tinted pill. */
@Composable
internal fun ChatListTabs(tabs: List<ChatListTabItem>, activeTab: ChatListTab, onTab: (ChatListTab) -> Unit, modifier: Modifier = Modifier) {
    val signal = signalColors
    val haptics = rememberAinoHaptics()
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { item ->
            val active = item.tab == activeTab
            val tint by animateColorAsState(if (active) signal.primary else signal.textSecondary, label = "tabTint")
            val pill by animateColorAsState(
                if (active) signal.primary.copy(alpha = if (signal.isDark) .18f else .12f) else Color.Transparent,
                label = "tabPill",
            )
            Row(
                Modifier.height(38.dp).clip(RoundedCornerShape(19.dp)).background(pill)
                    .semantics { selected = active }
                    .clickable(role = Role.Tab) { if (!active) { haptics.tick(); onTab(item.tab) } }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(item.icon, null, Modifier.size(18.dp), tint = tint)
                Text(
                    item.label, Modifier.padding(start = 6.dp), color = tint, fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                )
                if (item.badge > 0) SignalUnreadBadge(item.badge, modifier = Modifier.padding(start = 6.dp).height(18.dp))
            }
        }
    }
}

/**
 * Swipe right to pin / unpin, swipe left to archive / unarchive. The row always
 * springs back; the action runs on release past the threshold.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableConversation(
    conversation: ChatConversation,
    enabled: Boolean,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val signal = signalColors
    val haptics = rememberAinoHaptics()
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { haptics.confirm(); onPin() }
                SwipeToDismissBoxValue.EndToStart -> { haptics.confirm(); onArchive() }
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
        positionalThreshold = { distance -> distance * .35f },
    )
    LaunchedEffect(state.targetValue) {
        if (state.targetValue != SwipeToDismissBoxValue.Settled) haptics.tick()
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val direction = state.dismissDirection
            val pin = direction == SwipeToDismissBoxValue.StartToEnd
            val armed = state.targetValue != SwipeToDismissBoxValue.Settled
            val base = if (pin) signal.primary else Color(0xFF5B6B7F)
            val bg by animateColorAsState(if (armed) base else base.copy(alpha = .75f), label = "swipeBg")
            if (direction != SwipeToDismissBoxValue.Settled) {
                Row(
                    Modifier.fillMaxSize().background(bg).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = if (pin) Arrangement.Start else Arrangement.End,
                ) {
                    val label = when {
                        pin -> if (conversation.isPinned) "Unpin" else "Pin"
                        else -> if (conversation.isArchived) "Unarchive" else "Archive"
                    }
                    val icon = when {
                        pin -> HeroIcons.PushPin
                        conversation.isArchived -> HeroIcons.ArchiveBoxArrowDown
                        else -> HeroIcons.ArchiveBox
                    }
                    val iconScale by animateDpAsState(if (armed) 24.dp else 20.dp, label = "swipeIcon")
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(icon, null, Modifier.size(iconScale), tint = Color.White)
                        Text(label, Modifier.padding(top = 2.dp), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { content() }
}

/** Empty list: a tinted icon disc, a title, a hint and an optional action. */
@Composable
internal fun ChatListEmpty(icon: ImageVector, title: String, subtitle: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val signal = signalColors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(88.dp).background(signal.primary.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = signal.primary)
        }
        Text(title, Modifier.padding(top = 20.dp), color = signal.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            subtitle, Modifier.padding(top = 6.dp), color = signal.textSecondary, fontSize = 14.sp, lineHeight = 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                Modifier.padding(top = 20.dp).clip(RoundedCornerShape(20.dp)).background(signal.primary)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** "Archived chats" entry at the bottom of the list. */
@Composable
internal fun ArchivedChatsRow(count: Int, onClick: () -> Unit) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp))
            .background(signal.searchPill.copy(alpha = .6f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(signal.textSecondary.copy(alpha = .14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(HeroIcons.ArchiveBox, null, Modifier.size(20.dp), tint = signal.textSecondary)
        }
        Text("Archived chats", Modifier.weight(1f).padding(start = 14.dp), color = signal.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        SignalUnreadBadge(count, muted = true)
        Icon(HeroIcons.ChevronRight, null, Modifier.padding(start = 8.dp).size(18.dp), tint = signal.textSecondary)
    }
}

/** Round icon-only compose FAB (new chat → people search). */
@Composable
internal fun NewChatFab(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val signal = signalColors
    val haptics = rememberAinoHaptics()
    AnimatedVisibility(visible, modifier, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        FloatingActionButton(
            onClick = { haptics.tap(); onClick() },
            shape = RoundedCornerShape(18.dp),
            containerColor = signal.primary,
            contentColor = Color.White,
        ) { Icon(HeroIcons.PencilSquare, "New chat") }
    }
}
