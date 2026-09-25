package app.aino.mobile.feature.chat.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.aino.mobile.feature.chat.SignalDimens
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val SendBlue = Color(0xFF2C6BED)
private val PillGray = Color(0xFF303133)

/** Signal media review/send screen shown after camera, gallery or the attachment tray. */
@Composable
fun MediaSendScreen(
    initial: List<MediaSendItem>,
    recipientName: String,
    onAddMore: () -> Unit,
    onClose: () -> Unit,
    onSend: (items: List<MediaSendItem>, caption: String, viewOnce: Boolean, highQuality: Boolean) -> Unit,
) {
    val items = remember { mutableStateListOf<MediaSendItem>() }
    // Uris ever merged from `initial`, so removed items are not re-added when the caller appends more.
    val seen = remember { mutableSetOf<Uri>() }
    LaunchedEffect(initial) {
        initial.filter { seen.add(it.uri) }.let { fresh -> if (fresh.isNotEmpty()) items.addAll(fresh) }
    }
    var caption by rememberSaveable { mutableStateOf("") }
    var viewOnce by rememberSaveable { mutableStateOf(false) }
    var highQuality by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<Int, EditorTool>?>(null) }
    val pager = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    val railState = rememberLazyListState()

    fun remove(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        if (items.isEmpty()) onClose()
    }

    LaunchedEffect(pager.currentPage, items.size) {
        if (items.isNotEmpty()) railState.animateScrollToItem(pager.currentPage.coerceAtMost(items.lastIndex))
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (items.isNotEmpty()) {
            HorizontalPager(pager, modifier = Modifier.fillMaxSize(), key = { items.getOrNull(it)?.uri?.toString() ?: it }) { page ->
                val item = items.getOrNull(page) ?: return@HorizontalPager
                if (item.isVideo) VideoPage(item.uri, active = pager.currentPage == page)
                else AsyncImage(model = item.uri, contentDescription = "Media ${page + 1}", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }

        val current = items.getOrNull(pager.currentPage)
        Row(
            Modifier.fillMaxWidth().background(Color(0x66000000)).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            if (current != null && !current.isVideo) {
                listOf(
                    EditorTool.Crop to (Icons.Outlined.Crop to "Crop and rotate"),
                    EditorTool.Draw to (Icons.Outlined.Brush to "Draw"),
                    EditorTool.Text to (Icons.Outlined.TextFields to "Add text"),
                    EditorTool.Sticker to (Icons.Outlined.EmojiEmotions to "Add sticker"),
                    EditorTool.Blur to (Icons.Outlined.BlurOn to "Blur"),
                ).forEach { (tool, v) -> TopTool(v.first, v.second) { editing = pager.currentPage to tool } }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000))
                .navigationBarsPadding().imePadding().padding(vertical = 8.dp),
        ) {
            LazyRow(
                state = railState,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = SignalDimens.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                itemsIndexed(items, key = { _, it -> it.uri.toString() }) { index, item ->
                    RailThumb(
                        item,
                        selected = index == pager.currentPage,
                        onClick = { scope.launch { pager.animateScrollToPage(index) } },
                        onRemove = { remove(index) },
                    )
                }
                item {
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(PillGray).clickable(onClick = onAddMore),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Add, contentDescription = "Add more media", tint = Color.White) }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SignalDimens.gutter, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewOnceToggle(viewOnce) { viewOnce = !viewOnce }
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier.weight(1f).heightIn(min = SignalDimens.composeHeight).clip(RoundedCornerShape(50))
                        .background(PillGray).padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    val hint = if (viewOnce) "View-once media can't have a caption" else "Add a message"
                    if (caption.isEmpty() || viewOnce) Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = SignalDimens.bodyText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!viewOnce) {
                        BasicTextField(
                            value = caption,
                            onValueChange = { caption = it },
                            textStyle = TextStyle(color = Color.White, fontSize = SignalDimens.bodyText),
                            cursorBrush = SolidColor(Color.White),
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).border(1.5.dp, Color.White, RoundedCornerShape(50))
                        .clickable { highQuality = !highQuality }.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        if (highQuality) "HD" else "SD", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,

                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = SignalDimens.gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    recipientName,
                    color = Color.White,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp).clip(RoundedCornerShape(50)).background(PillGray)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(SendBlue).clickable(enabled = items.isNotEmpty()) {
                        onSend(items.toList(), if (viewOnce) "" else caption.trim(), viewOnce, highQuality)
                    },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White) }
            }
        }

        editing?.let { (index, tool) ->
            val item = items.getOrNull(index)
            if (item == null) {
                editing = null
            } else {
                ImageEditor(
                    source = item.uri,
                    initialTool = tool,
                    onDone = { edited ->
                        seen.add(edited.uri)
                        if (index in items.indices) items[index] = edited
                        editing = null
                    },
                    onCancel = { editing = null },
                )
            }
        }
    }
}

@Composable
private fun TopTool(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = description, tint = Color.White) }
}

@Composable
private fun ViewOnceToggle(on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.size(SignalDimens.composeHeight).clip(CircleShape).background(PillGray)
            .toggleable(value = on, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = if (on) "View once on" else "View once off" },
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Box(Modifier.size(24.dp).border(2.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text("1", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Icon(Icons.Outlined.AllInclusive, contentDescription = null, tint = Color.White)
        }
        // Single description for both states so TalkBack announces the toggle.
        Box(Modifier.size(1.dp).then(Modifier)) {}
    }.also { }
}

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailThumb(item: MediaSendItem, selected: Boolean, onClick: () -> Unit, onRemove: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onRemove, onClickLabel = "Select", onLongClickLabel = "Remove"),
    ) {
        AsyncImage(model = item.uri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (selected) 2.dp else 0.dp).clip(RoundedCornerShape(6.dp)))
        if (item.isVideo) Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(20.dp))
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(2.dp).size(18.dp).clip(CircleShape).background(Color(0xCC000000)).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp)) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(uri: Uri, active: Boolean) {
    if (!active) {
        Box(Modifier.fillMaxSize()) {
            AsyncImage(model = uri, contentDescription = "Video", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }
        return
    }
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
