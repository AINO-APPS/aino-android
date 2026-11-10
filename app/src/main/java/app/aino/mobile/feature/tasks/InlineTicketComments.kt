package app.aino.mobile.feature.tasks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

/** The inline route shares Detail's comment thread, not the shell's state. */
@Composable
internal fun InlineTicketComments(ticketId: Long, session: TicketSession, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(session.key, ticketId) { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    val detail = remember(store) {
        ViewModelProvider(store, TicketDetailViewModel.factory(context, ticketId, session))[TicketDetailViewModel::class.java]
    }
    val ui by detail.ui.collectAsStateWithLifecycle()
    LaunchedEffect(detail) { detail.loadComments() }
    LaunchedEffect(ui.closed) { if (ui.closed) onDismiss() }
    val colors = LocalWebColors.current
    TaskSheetScaffold("Comments", onDismiss, HeroIcons.ChatBubbleOvalLeft) {
        ui.detail?.let {
            Text(it.title, color = colors.textSecondary, fontSize = 0.84.rem, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 10.dp))
        }
        ui.error?.let { ErrorMsg(it) }
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            CommentSection(
                taskId = ticketId,
                comments = ui.detailComments,
                loading = ui.commentsLoading,
                currentUserId = ui.userId,
                users = ui.assignableUsers,
                viewModel = detail,
                placeholder = "Write a comment…",
                canComment = TaskCapability.Comment in ui.availableActions,
            )
        }
    }
    ui.confirm?.let { TaskConfirmDialog(it, detail::acceptConfirm, detail::dismissConfirm) }
}
