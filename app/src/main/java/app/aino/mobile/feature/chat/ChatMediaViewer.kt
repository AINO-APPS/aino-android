package app.aino.mobile.feature.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

fun ChatMessage.isImageAttachment() = fileUrl != null && fileType?.startsWith("image/") == true
fun ChatMessage.isVideoAttachment() = fileUrl != null && fileType?.startsWith("video/") == true
fun ChatMessage.isViewableMedia() = isImageAttachment() || isVideoAttachment()

/**
 * Full-screen media viewer (web `FullScreenImage` / full-screen `VideoPlayer`,
 * with Signal's swipe-between-media pager): pinch/double-tap zoom for images,
 * Media3 playback for video, share/open for both.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMediaViewer(media: List<ChatMessage>, startMessageId: Long, onClose: () -> Unit) {
    if (media.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(media.indexOfFirst { it.id == startMessageId }.coerceAtLeast(0)) { media.size }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 24.dp, key = { media[it].id }) { page ->
                val item = media[page]
                val url = resolveChatMediaUrl(item.fileUrl.orEmpty())
                if (item.isVideoAttachment()) VideoPage(url, active = pager.currentPage == page)
                else ZoomableImage(url, item.fileName)
            }
            val current = media[pager.currentPage]
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Close, "Close", Modifier.size(40.dp).clickable(onClick = onClose).padding(8.dp), tint = Color.White)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(current.senderName ?: current.senderUsername.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                    if (media.size > 1) Text("${pager.currentPage + 1} of ${media.size}", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                }
                Icon(
                    Icons.Outlined.Share, "Share",
                    Modifier.size(40.dp).clickable {
                        scope.launch { openChatFile(context, resolveChatMediaUrl(current.fileUrl.orEmpty()), current.fileName, current.fileType, share = true) }
                    }.padding(8.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String, label: String?) {
    val loader = AppContainer.get(LocalContext.current).imageLoader
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    AsyncImage(
        model = url,
        imageLoader = loader,
        contentDescription = label,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .pointerInput(url) {
                detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f })
            }
            .pointerInput(url) {
                // Only claim the gesture while zoomed or pinching so the pager still swipes at 1x.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + event.calculatePan()
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
    )
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(url: String, active: Boolean) {
    val context = LocalContext.current
    val container = AppContainer.get(context)
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(container.mediaHttp))))
            .build()
            .apply { setMediaItem(MediaItem.fromUri(url)); prepare() }
    }
    LaunchedEffect(active) {
        if (active) { container.audio.stop(); player.play() } else player.pause()
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize(),
    )
}

/**
 * Opens (ACTION_VIEW) or shares a chat file. Remote files are downloaded once
 * through the authenticated media client into the FileProvider `chat-media/`
 * cache, since other apps cannot send our bearer token.
 */
suspend fun openChatFile(context: Context, url: String, fileName: String?, mimeType: String?, share: Boolean = false) {
    val local: Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val parsed = Uri.parse(url)
            if (parsed.scheme == "content") return@runCatching parsed
            val directory = File(context.cacheDir, "chat-media/files").apply { mkdirs() }
            val safe = (fileName ?: parsed.lastPathSegment ?: "file").replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
            val target = File(directory, "${url.hashCode().toUInt()}-$safe")
            if (!target.exists() || target.length() == 0L) {
                AppContainer.get(context).mediaHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    target.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
        }.getOrNull()
    }
    if (local == null) {
        Toast.makeText(context, "Could not download file", Toast.LENGTH_SHORT).show()
        return
    }
    val type = mimeType ?: context.contentResolver.getType(local) ?: "*/*"
    val intent = if (share) {
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, local).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(local, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Web `MessageBubble` link card (and the `ChatInputBar` compose-time card when
 * [onRemove] is set): 52dp thumb, title, 2-line description, site name, primary left rule.
 */
@Composable
fun LinkPreviewCard(preview: LinkPreview, modifier: Modifier = Modifier, onRemove: (() -> Unit)? = null) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val loader = AppContainer.get(context).imageLoader
    Row(
        modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = .12f))
            .clickable {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(preview.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(60.dp).background(colors.primary))
        preview.image?.let { image ->
            AsyncImage(
                model = resolveChatMediaUrl(image), imageLoader = loader, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 8.dp).size(52.dp).clip(RoundedCornerShape(6.dp)),
            )
        }
        Column(Modifier.weight(1f, fill = false).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(preview.title.ifBlank { preview.url }, color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview.description.isNotBlank()) Text(preview.description, color = colors.text.copy(alpha = .75f), fontSize = 11.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (preview.siteName.isNotBlank()) Text(preview.siteName, color = colors.text.copy(alpha = .55f), fontSize = 11.sp, maxLines = 1)
        }
        onRemove?.let {
            Icon(Icons.Outlined.Close, "Remove preview", Modifier.padding(end = 8.dp).size(18.dp).clickable(onClick = it), tint = colors.textSecondary)
        }
    }
}

/** Pre-send preview for picked/captured media (web `MediaEditor`/`VideoPreview`, without editing tools). */
@Composable
fun AttachmentPreviewDialog(pending: PendingAttachment, initialCaption: String, onSend: (String) -> Unit, onCancel: () -> Unit) {
    val colors = LocalWebColors.current
    val loader = AppContainer.get(LocalContext.current).imageLoader
    var caption by remember(pending.uri) { mutableStateOf(initialCaption) }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Close, "Cancel", Modifier.size(40.dp).clickable(onClick = onCancel).padding(8.dp), tint = Color.White)
                Text(pending.fileName, Modifier.weight(1f), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    pending.isImage || pending.isVideo -> AsyncImage(
                        model = pending.uri, imageLoader = loader, contentDescription = pending.fileName,
                        contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                    )
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Description, null, Modifier.size(64.dp), tint = Color.White)
                        Text(pending.fileName, color = Color.White, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BasicTextField(
                    caption, { caption = it },
                    Modifier.weight(1f).background(Color.White.copy(alpha = .12f), CircleShape).padding(horizontal = 16.dp, vertical = 12.dp),
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    decorationBox = { inner -> if (caption.isEmpty()) Text("Add a caption...", color = Color.White.copy(alpha = .5f), fontSize = 16.sp); inner() },
                )
                Box(Modifier.size(48.dp).background(colors.primary, CircleShape).clickable { onSend(caption) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = colors.onAccent)
                }
            }
        }
    }
}

/** Port of web `PollCreator.tsx` (2–10 options, 500/200 char limits, multi-select). */
@Composable
fun PollCreatorDialog(onSubmit: (String, List<String>, Boolean) -> Unit, onClose: () -> Unit) {
    val colors = LocalWebColors.current
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var multi by remember { mutableStateOf(false) }
    val valid = question.isNotBlank() && options.count(String::isNotBlank) >= 2
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().background(colors.bgElevated, RoundedCornerShape(14.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Create Poll", Modifier.weight(1f), color = colors.text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Icon(Icons.Outlined.Close, "Close", Modifier.size(20.dp).clickable(onClick = onClose), tint = colors.textSecondary)
            }
            PollField(question, "Ask a question...", 500) { question = it }
            options.forEachIndexed { index, value ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PollField(value, "Option", 200) { options[index] = it } }
                    if (options.size > 2) Icon(Icons.Outlined.Close, "Remove option", Modifier.padding(start = 6.dp).size(18.dp).clickable { options.removeAt(index) }, tint = colors.textSecondary)
                }
            }
            if (options.size < 10) Text("+ Add option", Modifier.clickable { options.add("") }, color = colors.primary, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(multi, { multi = it })
                Text("Allow multiple selections", color = colors.text, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text("Cancel") }
                TextButton(onClick = { onSubmit(question, options.toList(), multi) }, enabled = valid) { Text("Send Poll") }
            }
        }
    }
}

@Composable
private fun PollField(value: String, placeholder: String, max: Int, onChange: (String) -> Unit) {
    val colors = LocalWebColors.current
    BasicTextField(
        value, { onChange(it.take(max)) },
        Modifier.fillMaxWidth().background(colors.inputBg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        singleLine = true,
        textStyle = TextStyle(color = colors.text, fontSize = 14.sp),
        decorationBox = { inner -> if (value.isEmpty()) Text(placeholder, color = colors.textMuted, fontSize = 14.sp); inner() },
    )
}
