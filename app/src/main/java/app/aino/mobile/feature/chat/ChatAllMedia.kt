package app.aino.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private enum class MediaTab(val label: String) { Media("Media"), Files("Files"), Audio("Audio"), Links("Links") }

internal fun SharedChatFile.isVisual() = fileType?.startsWith("image/") == true || fileType?.startsWith("video/") == true
internal fun SharedChatFile.isAudio() = fileType?.startsWith("audio/") == true

/** Adapts a shared-files row to the viewer's message model. */
internal fun SharedChatFile.asMessage(conversationId: Long): ChatMessage = ChatMessage(
    id = id, conversationId = conversationId, senderId = 0, createdAt = "1970-01-01T00:00:00Z",
    fileUrl = fileUrl, fileName = fileName, fileType = fileType, fileSize = fileSize,
)

/**
 * Signal "All media" (overflow → View all media): tabbed grid of photos and
 * videos, files, audio and links. Slides in from the end like Signal's pages.
 */
@Composable
internal fun AllMediaScreen(ui: ChatUiState, conversation: ChatConversation, viewModel: ChatViewModel, onClose: () -> Unit) {
    val signal = signalColors
    var tab by rememberSaveable { mutableStateOf(MediaTab.Media) }
    var viewing by remember { mutableStateOf<Long?>(null) }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(conversation.id) { visible = true; viewModel.loadSharedFiles() }
    val files = (ui.infoContent as? InfoContent.Files)?.files.orEmpty()
    val visual = remember(files) { files.filter { it.isVisual() } }
    val audio = remember(files) { files.filter { it.isAudio() } }
    val documents = remember(files) { files.filterNot { it.isVisual() || it.isAudio() } }
    // Links: the server has no shared-links endpoint, so use loaded thread rows.
    val links = remember(ui.messages) { ui.messages.filter { it.deletedAt == null && it.linkPreview != null }.asReversed() }

    AnimatedVisibility(visible = visible, enter = slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) {
        Column(Modifier.fillMaxSize().background(signal.background)) {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack, "Back",
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose).padding(12.dp), tint = signal.text,
                )
                Text(
                    conversation.title(), Modifier.weight(1f).padding(start = 8.dp),
                    color = signal.text, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            TabRow(
                selectedTabIndex = tab.ordinal,
                containerColor = signal.background,
                contentColor = signal.primary,
                indicator = { positions ->
                    TabRowDefaults.PrimaryIndicator(Modifier.tabIndicatorOffset(positions[tab.ordinal]), color = signal.primary, width = 48.dp)
                },
            ) {
                MediaTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry, onClick = { tab = entry },
                        text = { Text(entry.label, color = if (tab == entry) signal.primary else signal.textSecondary, fontSize = 14.sp) },
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ui.infoLoading && files.isEmpty() && tab != MediaTab.Links ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center), color = signal.primary)
                    tab == MediaTab.Media -> MediaGrid(visual) { viewing = it.id }
                    tab == MediaTab.Files -> FileList(documents, Icons.Outlined.Description, "No files")
                    tab == MediaTab.Audio -> AudioList(audio)
                    else -> LinkList(links) { viewModel.closeInfo(); onClose(); viewModel.jumpToMessage(it) }
                }
            }
        }
    }
    viewing?.let { id -> ChatMediaViewer(visual.map { it.asMessage(conversation.id) }, id) { viewing = null } }
}


@Composable
private fun EmptyTab(icon: ImageVector, text: String) {
    val signal = signalColors
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = signal.textSecondary)
        Text(text, Modifier.padding(top = 12.dp), color = signal.textSecondary, fontSize = 15.sp)
    }
}

/** Signal 3-column square thumbnail grid (2dp gutters, video play badge). */
@Composable
private fun MediaGrid(items: List<SharedChatFile>, onOpen: (SharedChatFile) -> Unit) {
    if (items.isEmpty()) { EmptyTab(Icons.Outlined.PhotoLibrary, "No media"); return }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { "media-${it.id}" }) { file ->
            val video = file.fileType?.startsWith("video/") == true
            Box(Modifier.aspectRatio(1f)) {
                ChatThumbnail(
                    resolveChatMediaUrl(file.fileUrl), file.fileName, video = false,
                    shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxSize(),
                ) { onOpen(file) }
                if (video) Icon(
                    Icons.Outlined.PlayArrow, "Video",
                    Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp)
                        .background(Color.Black.copy(alpha = .5f), CircleShape).padding(2.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun FileList(items: List<SharedChatFile>, icon: ImageVector, empty: String) {
    val signal = signalColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberChatMediaSaver()
    if (items.isEmpty()) { EmptyTab(icon, empty); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(items, key = { "file-${it.id}" }) { file ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    scope.launch { openChatFile(context, resolveChatMediaUrl(file.fileUrl), file.fileName, file.fileType) }
                }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(signal.primary.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                    Text(
                        file.fileName?.substringAfterLast('.', "")?.take(4)?.uppercase()?.ifBlank { null } ?: "FILE",
                        color = signal.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(file.fileName ?: "File", color = signal.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(formatChatFileSize(file.fileSize), file.fileType?.substringAfter('/')).joinToString(" · "),
                        color = signal.textSecondary, fontSize = 13.sp, maxLines = 1,
                    )
                }
                Icon(
                    Icons.Outlined.Download, "Save to device",
                    Modifier.size(40.dp).clip(RoundedCornerShape(20.dp))
                        .clickable { save(resolveChatMediaUrl(file.fileUrl), file.fileName, file.fileType) }
                        .padding(8.dp),
                    tint = signal.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun AudioList(items: List<SharedChatFile>) {
    val signal = signalColors
    if (items.isEmpty()) { EmptyTab(Icons.Outlined.Description, "No audio"); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items, key = { "audio-${it.id}" }) { file ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(signal.incoming).padding(12.dp)) {
                ChatVoicePlayer(resolveChatMediaUrl(file.fileUrl), Modifier.fillMaxWidth())
                file.fileName?.let { Text(it, Modifier.padding(top = 4.dp), color = signal.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun LinkList(items: List<ChatMessage>, onOpen: (ChatMessage) -> Unit) {
    if (items.isEmpty()) { EmptyTab(Icons.Outlined.Link, "No links"); return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { "link-${it.id}" }) { message ->
            val preview = message.linkPreview ?: return@items
            LinkPreviewCard(preview, Modifier.fillMaxWidth().clickable { onOpen(message) })
        }
    }
}

