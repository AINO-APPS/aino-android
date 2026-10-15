package app.aino.mobile.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aino.mobile.core.designsystem.AinoEmptyState
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.share.IncomingShare
import app.aino.mobile.feature.chat.media.MediaSendItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** WhatsApp caps a share at 5 chats; Signal allows a handful too. */
const val MAX_SHARE_TARGETS = 5

/** Recent chats first (the list is already server-ordered), archived and read-only chats left out. */
fun shareDestinations(conversations: List<ChatConversation>, query: String): List<ChatConversation> =
    forwardDestinations(conversations, query).filterNot { it.isBlocked || (it.isGroup && it.postPolicy == "admins" && it.myRole == "member") }

/**
 * Share-sheet flow (Signal / WhatsApp): pick chats, then
 * - photos/videos → the regular media send screen (caption, HD, edit),
 * - text, links and other files → a short preview with an editable message.
 */
@Composable
fun ShareTargetScreen(
    share: IncomingShare,
    viewModel: ChatViewModel,
    onClose: () -> Unit,
    onSent: (conversationIds: List<Long>) -> Unit,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val signal = signalColors
    val selected = remember { mutableStateListOf<Long>().apply { share.conversationId?.let(::add) } }
    var query by remember { mutableStateOf("") }
    // A Direct Share target skips the picker.
    var step by remember { mutableStateOf(if (share.conversationId != null) ShareStep.Compose else ShareStep.Pick) }
    val titleOf = { id: Long -> ui.conversations.firstOrNull { it.id == id }?.title() ?: "Chat" }

    // The card is fetched once for the whole share and reused for every target.
    var preview by remember(share) { mutableStateOf<LinkPreview?>(null) }
    var previewLoading by remember(share) { mutableStateOf(false) }
    var sending by remember(share) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val previewsOn = app.aino.mobile.core.settings.ChatPreferences.state.collectAsStateWithLifecycle().value.linkPreviews
    androidx.compose.runtime.LaunchedEffect(share, previewsOn) {
        val url = share.link?.takeIf { previewsOn } ?: return@LaunchedEffect
        previewLoading = true
        try {
            preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.fetchLinkPreview(url) }
        } finally {
            previewLoading = false
        }
    }

    fun send(caption: String, viewOnce: Boolean = false, highQuality: Boolean = false, items: List<MediaSendItem>? = null) {
        if (sending) return
        sending = true
        scope.launch {
            // Signal waits for the link card before sending (a re-hosted preview can take several seconds).
            if (previewLoading) withTimeoutOrNull(ChatViewModel.SHARED_PREVIEW_TIMEOUT_MS) { snapshotFlow { previewLoading }.first { !it } }
            val targets = selected.toList()
            val plan = sharePlan(share, caption)
            targets.forEach { id ->
                val media = if (!plan.sendMedia) emptyList()
                else items?.map { MediaUploadSpec(it.uri, it.mimeType, it.width, it.height) }
                    ?: share.items.map { MediaUploadSpec(it.uri, it.mimeType) }
                if (media.isNotEmpty()) viewModel.sendSharedMedia(id, media, plan.mediaCaption, viewOnce, highQuality)
                plan.text?.let { viewModel.sendSharedText(id, it, preview) }
            }
            onSent(targets)
        }
    }

    BackHandler {
        if (step == ShareStep.Compose && share.conversationId == null) step = ShareStep.Pick else onClose()
    }

    if (step == ShareStep.Compose && share.isMediaOnly && !share.hasLinkWithMedia) {
        app.aino.mobile.feature.chat.media.MediaSendScreen(
            initial = remember(share) { share.items.map { MediaSendItem(it.uri, it.mimeType) } },
            recipientName = selected.joinToString(", ") { titleOf(it) },
            onAddMore = {},
            onClose = { if (share.conversationId == null) step = ShareStep.Pick else onClose() },
            onSend = { finalItems, caption, viewOnce, highQuality -> send(caption, viewOnce, highQuality, finalItems) },
            initialHighQuality = app.aino.mobile.core.settings.ChatPreferences.current.highQualityMedia,
        )
        return
    }

    Column(Modifier.fillMaxSize().background(signal.background).statusBarsPadding()
        .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step == ShareStep.Compose && share.conversationId == null) step = ShareStep.Pick else onClose() }) {
                Icon(if (step == ShareStep.Pick) HeroIcons.XMark else HeroIcons.ArrowLeft, "Back", tint = signal.text)
            }
            Text(
                if (step == ShareStep.Pick) "Send to…" else selected.joinToString(", ") { titleOf(it) },
                color = signal.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
        }
        when (step) {
            ShareStep.Pick -> PickStep(
                ui = ui, query = query, onQuery = { query = it }, selected = selected,
                onToggle = { id -> if (id in selected) selected.remove(id) else if (selected.size < MAX_SHARE_TARGETS) selected.add(id) },
                modifier = Modifier.weight(1f),
            )
            ShareStep.Compose -> ComposeStep(share, preview, previewLoading, sending, Modifier.weight(1f), onSend = {
                send(it, highQuality = app.aino.mobile.core.settings.ChatPreferences.current.highQualityMedia)
            })
        }
        if (step == ShareStep.Pick && selected.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(signal.surface).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selected.joinToString(", ") { titleOf(it) }, color = signal.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(signal.primary).clickable { step = ShareStep.Compose },
                    contentAlignment = Alignment.Center,
                ) { Icon(HeroIcons.PaperAirplane, "Next", tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
        }
    }
}

private enum class ShareStep { Pick, Compose }

/** What a share sends: media (optionally captioned) and/or a separate text message. */
internal data class SharePlan(val mediaCaption: String?, val text: String?, val sendMedia: Boolean = true)

/**
 * A message that carries a link is sent as its own text (with its preview card) after the
 * media, so the link is never lost to a caption-less image. When the images are only the
 * link's thumbnail (Google Maps / Chrome), Signal sends just the link with its card.
 */
internal fun sharePlan(share: IncomingShare, message: String): SharePlan {
    val text = message.trim().ifEmpty { null }
    val hasLink = text != null && firstLinkIn(text) != null
    return when {
        share.items.isEmpty() -> SharePlan(null, text)
        hasLink && share.isLinkThumbnail -> SharePlan(null, text, sendMedia = false)
        hasLink -> SharePlan(null, text)
        else -> SharePlan(text, null)
    }
}

@Composable
private fun PickStep(
    ui: ChatUiState,
    query: String,
    onQuery: (String) -> Unit,
    selected: List<Long>,
    onToggle: (Long) -> Unit,
    modifier: Modifier,
) {
    val signal = signalColors
    val choices = remember(ui.conversations, query) { shareDestinations(ui.conversations, query) }
    Column(modifier) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            cursorBrush = SolidColor(signal.primary),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = signal.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                .background(signal.searchPill, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(HeroIcons.MagnifyingGlass, null, tint = signal.textSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Box { if (query.isEmpty()) Text("Search", color = signal.textSecondary); inner() }
                }
            },
        )
        Text(
            if (selected.size >= MAX_SHARE_TARGETS) "You can share with up to $MAX_SHARE_TARGETS chats" else "Recent chats",
            color = signal.textSecondary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (choices.isEmpty()) item {
                AinoEmptyState(HeroIcons.ChatBubbleOvalLeft, if (ui.conversations.isEmpty()) "Loading chats…" else "No matching chats")
            }
            items(choices, key = ChatConversation::id) { conversation ->
                val isSelected = conversation.id in selected
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = isSelected || selected.size < MAX_SHARE_TARGETS) { onToggle(conversation.id) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ConversationAvatar(conversation, ui.presence[conversation.otherUserId], 48.dp)
                    Text(
                        conversation.title(), color = signal.text, style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 14.dp).weight(1f),
                    )
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .background(if (isSelected) signal.primary else Color.Transparent)
                            .then(if (isSelected) Modifier else Modifier.background(signal.divider.copy(alpha = .35f), CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (isSelected) Icon(HeroIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ComposeStep(
    share: IncomingShare,
    preview: LinkPreview?,
    previewLoading: Boolean,
    sending: Boolean,
    modifier: Modifier,
    onSend: (String) -> Unit,
) {
    val signal = signalColors
    var message by remember(share) { mutableStateOf(share.text.orEmpty()) }
    val link = firstLinkIn(message)
    // The link's thumbnail image is replaced by its card (see [sharePlan]).
    val items = if (share.isLinkThumbnail && link != null) emptyList() else share.items
    Column(modifier.padding(horizontal = 16.dp)) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview != null && link == preview.url) item(key = "link-preview") {
                LinkPreviewCard(preview, Modifier.fillMaxWidth())
            } else if (previewLoading && link == share.link) item(key = "link-preview-loading") {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 84.dp).clip(RoundedCornerShape(10.dp))
                        .background(signal.text.copy(alpha = .1f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(Modifier.size(24.dp), color = signal.primary, strokeWidth = 2.5.dp) }
            }
            items(items, key = { it.uri.toString() }) { item ->
                Row(
                    Modifier.fillMaxWidth().background(signal.surface, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (item.isVisualMedia) {
                        coil3.compose.AsyncImage(
                            model = item.uri, contentDescription = item.fileName,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Icon(HeroIcons.DocumentText, null, tint = signal.primary, modifier = Modifier.size(32.dp))
                    }
                    Text(
                        item.fileName, color = signal.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = message,
                onValueChange = { message = it.take(5_000) },
                cursorBrush = SolidColor(signal.primary),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = signal.text),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    .background(signal.searchPill, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 13.dp),
                decorationBox = { inner ->
                    Box { if (message.isEmpty()) Text(if (share.items.isEmpty()) "Message" else "Add a message", color = signal.textSecondary); inner() }
                },
            )
            Spacer(Modifier.width(8.dp))
            val canSend = (share.items.isNotEmpty() || message.isNotBlank()) && !sending
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(if (canSend || sending) signal.primary else signal.divider)
                    .clickable(enabled = canSend) { onSend(message) },
                contentAlignment = Alignment.Center,
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(HeroIcons.PaperAirplane, "Send", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
