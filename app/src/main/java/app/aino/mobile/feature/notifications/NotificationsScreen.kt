package app.aino.mobile.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.component.AinoFullPage
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** NotificationBell's dropdown as a full page (product decision: the bell opens a page). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val colors = LocalWebColors.current
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    // `handleOpen`: refresh whenever the bell is opened.
    LaunchedEffect(Unit) { viewModel.refresh() }

    AinoFullPage(
        title = "Notifications",
        onBack = onBack,
        scrollable = false,
        actions = {
            if (ui.unread > 0) {
                TextButton(onClick = viewModel::markAllRead) {
                    Text("Mark all read", color = colors.primaryLight, fontSize = 0.78.rem)
                }
            }
        },
    ) {
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = viewModel::pullRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                !ui.loaded && ui.error == null && ui.notifications.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = colors.primary)
                }
                ui.notifications.isEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text(
                        if (!ui.loaded) ui.error.orEmpty() else "No notifications yet",
                        color = if (!ui.loaded) colors.danger else colors.textMuted,
                        fontSize = 0.85.rem,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.notifications, key = { it.id }) { item ->
                        DismissibleNotificationRow(
                            item = item,
                            onClick = { viewModel.open(item)?.let(onOpenLink) },
                            onDelete = { viewModel.delete(item.id) },
                        )
                        HorizontalDivider(color = colors.glassBorder)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DismissibleNotificationRow(item: NotificationItem, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalWebColors.current
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(colors.danger).padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color.White)
            }
        },
    ) {
        NotificationRow(item, onClick, onDelete)
    }
}

@Composable
private fun NotificationRow(item: NotificationItem, onClick: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalWebColors.current
    val unreadBg = colors.primary.copy(alpha = 0.06f).compositeOver(colors.bg)
    Box(
        Modifier.fillMaxWidth()
            .background(if (item.isRead) colors.bg else unreadBg)
            .clickable(onClick = onClick),
    ) {
        if (!item.isRead) {
            Box(
                Modifier.align(Alignment.CenterStart).width(3.dp).fillMaxHeight(0.6f)
                    .background(colors.primary, RoundedCornerShape(topEnd = 2.dp, bottomEnd = 2.dp)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 11.dp, bottom = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                notificationIconVector(notificationIcon(item.type)),
                contentDescription = null,
                tint = colors.text,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    item.title.orEmpty(), color = colors.text, fontSize = 0.84.rem, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (!item.body.isNullOrEmpty()) {
                    Text(
                        item.body, color = colors.textSecondary, fontSize = 0.78.rem,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    notificationTimeAgo(item.createdAt), color = colors.textMuted, fontSize = 0.72.rem,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Dismiss notification", tint = colors.textMuted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun notificationIconVector(icon: NotificationIcon): ImageVector = when (icon) {
    NotificationIcon.Mention -> Icons.Outlined.AlternateEmail
    NotificationIcon.Leave -> Icons.AutoMirrored.Outlined.Assignment
    NotificationIcon.Task -> Icons.Outlined.Description
    NotificationIcon.Approval -> Icons.Outlined.CheckCircle
    NotificationIcon.MeetingInvite -> Icons.Outlined.Videocam
    NotificationIcon.Default -> Icons.Outlined.Notifications
}
