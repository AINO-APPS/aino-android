package app.aino.mobile.feature.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AllInclusive
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.feature.chat.media.MediaSendItem
import coil3.compose.AsyncImage
import coil3.video.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

// ---------------------------------------------------------------------------
// Receipts: Signal's circle-check glyphs, drawn in Compose (no Signal assets).
// ---------------------------------------------------------------------------

/** Sending = clock, Sent = one check in a ring, Delivered = two rings, Read = two filled rings. */
@Composable
internal fun SignalReceiptIcon(tick: DeliveryTick, tint: Color, modifier: Modifier = Modifier) {
    val label = when (tick) {
        DeliveryTick.Sending -> "Sending"
        DeliveryTick.Sent -> "Sent"
        DeliveryTick.Delivered -> "Delivered"
        DeliveryTick.Read -> "Read"
    }
    val double = tick == DeliveryTick.Delivered || tick == DeliveryTick.Read
    Canvas(modifier.size(width = if (double) 19.dp else 12.dp, height = 12.dp).semantics { contentDescription = label }) {
        val r = size.height / 2 - 0.6.dp.toPx()
        val stroke = 1.2.dp.toPx()
        fun ring(cx: Float, filled: Boolean) {
            val center = Offset(cx, size.height / 2)
            if (filled) drawCircle(tint, r + stroke / 2, center) else drawCircle(tint, r, center, style = Stroke(stroke))
            if (tick == DeliveryTick.Sending) {
                drawLine(tint, center, center.copy(y = center.y - r * .6f), stroke, StrokeCap.Round)
                drawLine(tint, center, center.copy(x = center.x + r * .5f), stroke, StrokeCap.Round)
                return
            }
            val c = if (filled) tintOnFill(tint) else tint
            drawLine(c, Offset(center.x - r * .45f, center.y + r * .02f), Offset(center.x - r * .1f, center.y + r * .38f), stroke, StrokeCap.Round)
            drawLine(c, Offset(center.x - r * .1f, center.y + r * .38f), Offset(center.x + r * .48f, center.y - r * .32f), stroke, StrokeCap.Round)
        }
        if (double) {
            ring(size.width - size.height / 2, tick == DeliveryTick.Read)
            ring(size.height / 2, tick == DeliveryTick.Read)
        } else ring(size.width / 2, false)
    }
}

/** Check colour inside a filled (read) ring: the bubble colour punches through. */
private fun tintOnFill(tint: Color): Color = if (tint.red + tint.green + tint.blue > 2.2f) Color(0xFF2C6BED) else Color.White

// ---------------------------------------------------------------------------
// Bubble text + inline footer (time/receipt share the last line when it fits).
// ---------------------------------------------------------------------------

@Composable
fun BubbleTextWithFooter(
    text: AnnotatedString,
    color: Color,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        content = {
            Text(
                text,
                color = color,
                style = TextStyle(fontSize = SignalDimens.bodyText, lineHeight = SignalDimens.bodyLine),
                onTextLayout = { layout = it },
            )
            footer()
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val textPlaceable = measurables[0].measure(constraints.copy(minWidth = 0))
        val footerPlaceable = measurables[1].measure(constraints.copy(minWidth = 0))
        val gap = 8.dp.roundToPx()
        val lastLineRight = layout?.let { it.getLineRight(it.lineCount - 1).toInt() } ?: textPlaceable.width
        val inline = lastLineRight + gap + footerPlaceable.width <= constraints.maxWidth
        val width = if (inline) max(textPlaceable.width, lastLineRight + gap + footerPlaceable.width) else max(textPlaceable.width, footerPlaceable.width)
        val height = if (inline) max(textPlaceable.height, footerPlaceable.height) else textPlaceable.height + footerPlaceable.height
        layout(width.coerceAtMost(constraints.maxWidth), height) {
            textPlaceable.place(0, 0)
            footerPlaceable.place(width - footerPlaceable.width, height - footerPlaceable.height)
        }
    }
}

/** Case-insensitive highlight of [term] (Signal in-chat search). */
fun highlightTerm(text: String, term: String?, background: Color): AnnotatedString {
    val needle = term?.trim().orEmpty()
    if (needle.length < 2) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val hit = text.indexOf(needle, from, ignoreCase = true)
            if (hit < 0) { append(text.substring(from)); break }
            append(text.substring(from, hit))
            pushStyle(SpanStyle(background = background, fontWeight = FontWeight.SemiBold))
            append(text.substring(hit, hit + needle.length))
            pop()
            from = hit + needle.length
        }
    }
}

// ---------------------------------------------------------------------------
// Delete dialog (Signal: "Delete for me" / "Delete for everyone").
// ---------------------------------------------------------------------------

@Composable
fun SignalDeleteDialog(
    count: Int,
    canDeleteForEveryone: Boolean,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val signal = signalColors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = signal.surface,
        title = { Text(if (count == 1) "Delete message?" else "Delete $count messages?", color = signal.text) },
        text = {
            Text(
                if (canDeleteForEveryone) "You can delete for yourself or for everyone in this chat."
                else "This will delete ${if (count == 1) "this message" else "these messages"} from this device.",
                color = signal.textSecondary,
            )
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onDeleteForMe(); onDismiss() }) { Text("Delete for me", color = signal.danger) }
                if (canDeleteForEveryone) TextButton(onClick = { onDeleteForEveryone(); onDismiss() }) { Text("Delete for everyone", color = signal.danger) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = signal.primary) }
            }
        },
    )
}

// ---------------------------------------------------------------------------
// In-chat search (toolbar field + bottom "x of y" bar).
// ---------------------------------------------------------------------------

@Composable
fun ThreadSearchToolbar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val signal = signalColors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().background(signal.background).statusBarsPadding().height(SignalDimens.toolbarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close search", Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose).padding(12.dp), tint = signal.text)
        BasicTextField(
            query, onQuery,
            Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            textStyle = TextStyle(color = signal.text, fontSize = 17.sp),
            cursorBrush = SolidColor(signal.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search", color = signal.textSecondary, fontSize = 17.sp); inner() } },
        )
        if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Clear", Modifier.size(48.dp).clip(CircleShape).clickable { onQuery("") }.padding(12.dp), tint = signal.textSecondary)
    }
}

@Composable
fun ThreadSearchBottomBar(index: Int, count: Int, searching: Boolean, onOlder: () -> Unit, onNewer: () -> Unit) {
    val signal = signalColors
    Row(
        Modifier.fillMaxWidth().background(signal.surface).height(52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when {
                searching -> "Searching…"
                count == 0 -> "No results"
                else -> "${index + 1} of $count"
            },
            Modifier.weight(1f), color = signal.textSecondary, fontSize = 15.sp,
        )
        Icon(Icons.Outlined.KeyboardArrowUp, "Older match", Modifier.size(44.dp).clip(CircleShape).clickable(enabled = index < count - 1, onClick = onOlder).padding(10.dp), tint = if (index < count - 1) signal.text else signal.textSecondary.copy(alpha = .4f))
        Icon(Icons.Outlined.KeyboardArrowDown, "Newer match", Modifier.size(44.dp).clip(CircleShape).clickable(enabled = index > 0, onClick = onNewer).padding(10.dp), tint = if (index > 0) signal.text else signal.textSecondary.copy(alpha = .4f))
    }
}

// ---------------------------------------------------------------------------
// View-once bubble (Signal "Photo"/"Video"/"Viewed" pill).
// ---------------------------------------------------------------------------

@Composable
fun ViewOnceContent(message: ChatMessage, state: ViewOnceState, outgoing: Boolean, loading: Boolean, onOpen: () -> Unit) {
    val signal = signalColors
    val fg = if (outgoing) signal.onOutgoing else signal.onIncoming
    val video = message.fileType?.startsWith("video/") == true
    val openable = state == ViewOnceState.Unopened || state == ViewOnceState.SentUnviewed || state == ViewOnceState.SentViewed
    val label = when (state) {
        ViewOnceState.Unopened, ViewOnceState.SentUnviewed -> if (video) "Video" else "Photo"
        ViewOnceState.Viewed, ViewOnceState.SentViewed -> "Viewed"
    }
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = openable && !loading, onClick = onOpen).padding(vertical = 4.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(28.dp).border(1.5.dp, fg.copy(alpha = if (state == ViewOnceState.Viewed) .4f else 1f), CircleShape), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator(Modifier.size(14.dp), color = fg, strokeWidth = 2.dp)
            else if (state == ViewOnceState.Viewed || state == ViewOnceState.SentViewed) Icon(Icons.Outlined.AllInclusive, null, Modifier.size(16.dp), tint = fg.copy(alpha = .6f))
            else Text("1", color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = fg.copy(alpha = if (state == ViewOnceState.Viewed) .6f else 1f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Attachment keyboard: recent media grid + Gallery / File / Poll / Camera.
// ---------------------------------------------------------------------------

data class RecentMedia(val uri: Uri, val mimeType: String, val width: Int?, val height: Int?) {
    fun toSendItem() = MediaSendItem(uri, mimeType, width, height)
}

fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Full access, Android 14 "selected photos" partial access, or none. */
fun hasMediaAccess(context: Context): Boolean = mediaPermissions().any {
    context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
}

/** Newest-first images and videos from MediaStore (whatever the user granted). */
suspend fun loadRecentMedia(context: Context, limit: Int = 90): List<RecentMedia> = withContext(Dispatchers.IO) {
    if (!hasMediaAccess(context)) return@withContext emptyList()
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.WIDTH, MediaStore.Files.FileColumns.HEIGHT,
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
    val out = mutableListOf<RecentMedia>()
    runCatching {
        context.contentResolver.query(collection, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val mime = c.getString(2) ?: if (video) "video/mp4" else "image/jpeg"
                if (mime !in CHAT_ALLOWED_MIME_TYPES) continue
                val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                out += RecentMedia(ContentUris.withAppendedId(base, id), mime, c.getInt(3).takeIf { it > 0 }, c.getInt(4).takeIf { it > 0 })
            }
        }
    }
    out
}

@Composable
fun AttachmentKeyboard(
    height: Dp,
    showPoll: Boolean,
    onSendMedia: (List<MediaSendItem>) -> Unit,
    onGallery: () -> Unit,
    onFile: () -> Unit,
    onPoll: () -> Unit,
    onCamera: () -> Unit,
) {
    val signal = signalColors
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasMediaAccess(context)) }
    var reload by remember { mutableIntStateOf(0) }
    val recent = remember { mutableStateListOf<RecentMedia>() }
    val selected = remember { mutableStateListOf<RecentMedia>() }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasMediaAccess(context); reload++
    }
    LaunchedEffect(granted, reload) { recent.clear(); recent.addAll(loadRecentMedia(context)) }
    val partial = Build.VERSION.SDK_INT >= 34 && granted &&
        context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED
    Column(Modifier.fillMaxWidth().height(height).background(signal.surface)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!granted) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Allow access to your photos and videos to send them quickly.", color = signal.textSecondary, fontSize = 14.sp)
                    TextButton(onClick = { permission.launch(mediaPermissions()) }) { Text("Allow access", color = signal.primary) }
                }
            } else {
                val loader = AppContainer.get(context).imageLoader
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(2.dp)) {
                    if (partial) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("You've given access to selected photos", Modifier.weight(1f), color = signal.textSecondary, fontSize = 13.sp)
                            TextButton(onClick = { permission.launch(mediaPermissions()) }) { Text("Manage", color = signal.primary) }
                        }
                    }
                    items(recent, key = { it.uri.toString() }) { media ->
                        val order = selected.indexOf(media)
                        Box(
                            Modifier.aspectRatio(1f).padding(1.dp).clickable {
                                if (order >= 0) selected.remove(media) else if (selected.size < 32) selected.add(media)
                            },
                        ) {
                            AsyncImage(
                                model = coil3.request.ImageRequest.Builder(context).data(media.uri).apply {
                                    if (media.mimeType.startsWith("video/")) videoFrameMillis(0)
                                }.build(),
                                imageLoader = loader,
                                contentDescription = if (media.mimeType.startsWith("video/")) "Video" else "Photo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().background(signal.divider),
                            )
                            if (media.mimeType.startsWith("video/")) Icon(Icons.Outlined.PlayArrow, null, Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp), tint = Color.White)
                            Box(
                                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                                    .background(if (order >= 0) signal.primary else Color.Black.copy(alpha = .25f), CircleShape)
                                    .border(1.5.dp, Color.White, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { if (order >= 0) Text("${order + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            if (selected.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.BottomEnd).padding(12.dp).clip(RoundedCornerShape(24.dp)).background(signal.primary)
                        .clickable { onSendMedia(selected.map(RecentMedia::toSendItem)); selected.clear() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("Next (${selected.size})", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            AttachButton(Icons.Outlined.PhotoLibrary, "Gallery", onGallery)
            AttachButton(Icons.Outlined.CameraAlt, "Camera", onCamera)
            AttachButton(Icons.Outlined.Description, "File", onFile)
            if (showPoll) AttachButton(Icons.Outlined.Poll, "Poll", onPoll)
        }
    }
}

@Composable
private fun AttachButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val signal = signalColors
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).background(signal.background, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(24.dp), tint = signal.text)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = signal.text, fontSize = 12.sp)
    }
}

/** Signal unread badge: blue pill with 12.5dp radius and a bold count. */
@Composable
fun SignalUnreadBadge(count: Int, muted: Boolean = false, modifier: Modifier = Modifier) {
    val signal = signalColors
    Box(
        modifier.height(22.dp).widthIn(min = 22.dp)
            .background(if (muted) signal.textSecondary else signal.primary, RoundedCornerShape(12.5.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (count > 999) "999+" else count.toString(), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
}
