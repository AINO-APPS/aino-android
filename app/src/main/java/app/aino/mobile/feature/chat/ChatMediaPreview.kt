package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.network.NetworkConfig
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import kotlinx.coroutines.launch
import app.aino.mobile.core.designsystem.icons.HeroIcons

/** Attachment URLs resolve exactly like every other server upload path. */
fun resolveChatMediaUrl(value: String, origin: String = NetworkConfig.serverOrigin): String =
    app.aino.mobile.core.media.resolveServerMediaUrl(value, origin)

fun formatChatFileSize(bytes: Long?): String? = bytes?.takeIf { it >= 0 }?.let {
    when {
        it >= 1024L * 1024 -> "%.1f MB".format(it / (1024.0 * 1024.0))
        it >= 1024 -> "%.1f KB".format(it / 1024.0)
        else -> "$it B"
    }
}

/** Server media-job states that still need Signal's transfer ring on the thumbnail. */
fun ChatMessage.mediaInFlight(): Boolean =
    mediaState?.lowercase()?.let { it !in setOf("ready", "completed", "failed", "cancelled") } == true

fun ChatMessage.mediaFailed(): Boolean = mediaState?.lowercase() in setOf("failed", "cancelled")

@Composable
fun ChatMediaPreview(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onOpenMedia: (ChatMessage) -> Unit = {},
    onCancelProcessing: (ChatMessage) -> Unit = {},
    onRetryProcessing: (ChatMessage) -> Unit = {},
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    outgoing: Boolean = false,
    /** Long-press must reach the bubble's action overlay; media handles its own clicks. */
    onLongPress: () -> Unit = {},
) {
    val rawUrl = message.fileUrl ?: return
    val url = resolveChatMediaUrl(rawUrl)
    val visual = message.isImageAttachment() || message.isVideoAttachment()
    Column(modifier.widthIn(max = SignalDimens.mediaMaxWidth + 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            visual -> Box {
                ChatThumbnail(url, message.fileName, video = message.isVideoAttachment(), shape = shape, onLongClick = onLongPress) {
                    if (!message.mediaInFlight()) onOpenMedia(message)
                }
                // Signal TransferControls: ring over the dimmed thumbnail while the server transcodes.
                when {
                    message.mediaInFlight() -> TransferRing(
                        progress = message.mediaProgress?.takeIf { it > 0 }?.let { it / 100f },
                        onCancel = { onCancelProcessing(message) },
                        modifier = Modifier.matchParentSize().clip(shape),
                    )
                    message.mediaFailed() -> TransferRetry(
                        onRetry = { onRetryProcessing(message) },
                        modifier = Modifier.matchParentSize().clip(shape),
                    )
                }
            }
            message.fileType?.startsWith("audio/") == true -> ChatVoicePlayer(url, Modifier.fillMaxWidth(), outgoing = outgoing, waveSeed = message.fileName ?: url)
            else -> AttachmentCard(message, url, onLongPress)
        }
        if (!visual) MediaProcessingState(message, onCancelProcessing, onRetryProcessing)
        message.mediaFailureReason?.takeIf { visual && message.mediaFailed() && it.isNotBlank() }?.let {
            Text(it, color = LocalWebColors.current.danger, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Signal's transfer control: a 48dp translucent disc with a determinate ring
 * (indeterminate spinner before bytes/progress are known) and an ✕ to cancel.
 * Fades in/out so the swap to the finished media is smooth.
 */
@Composable
fun TransferRing(progress: Float?, onCancel: (() -> Unit)?, modifier: Modifier = Modifier) {
    val animated by androidx.compose.animation.core.animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = androidx.compose.animation.core.tween(250),
        label = "transferProgress",
    )
    Box(modifier.background(Color.Black.copy(alpha = .28f)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f))
                .then(if (onCancel != null) Modifier.clickable(onClickLabel = "Cancel", onClick = onCancel) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (progress == null) CircularProgressIndicator(Modifier.size(40.dp), color = Color.White, strokeWidth = 2.5.dp)
            else CircularProgressIndicator(progress = { animated }, modifier = Modifier.size(40.dp), color = Color.White, strokeWidth = 2.5.dp, trackColor = Color.White.copy(alpha = .25f))
            if (onCancel != null) Icon(HeroIcons.XMark, "Cancel", Modifier.size(20.dp), tint = Color.White)
        }
    }
}

/** Signal failed-transfer state: tap the disc to retry. */
@Composable
fun TransferRetry(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = .28f)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)).clickable(onClickLabel = "Retry", onClick = onRetry),
            contentAlignment = Alignment.Center,
        ) { Icon(HeroIcons.ArrowPath, "Retry", Modifier.size(24.dp), tint = Color.White) }
    }
}

/**
 * Image / video-poster thumbnail via the shared Coil loader (auth headers,
 * memory+disk cache, downsampled decode). Aspect ratio follows the decoded
 * image, like the web's `--img-aspect`, falling back to 4:3.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatThumbnail(
    url: Any,
    label: String?,
    video: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    initialAspect: Float? = null,
    onLongClick: (() -> Unit)? = null,
    /** When set, replaces Signal's aspect-fitted bubble box (e.g. square grid cells). */
    modifier: Modifier? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val loader = AppContainer.get(context).imageLoader
    var aspect by remember(url) { mutableFloatStateOf(initialAspect?.takeIf { it > 0f } ?: (4f / 3f)) }
    var failed by remember(url) { mutableStateOf(false) }
    // ponytail: Coil's VideoFrameDecoder needs the file on disk, so a poster downloads the whole video once (then disk-cached); add server-side posters if videos get large.
    val request = remember(url, video) {
        ImageRequest.Builder(context).data(url).apply { if (video) videoFrameMillis(100) }.build()
    }
    val (width, height) = signalMediaSize(aspect)
    Box(
        (modifier ?: Modifier.size(width.dp, height.dp))
            .clip(shape).background(LocalWebColors.current.surface)
            .combinedClickable(onClickLabel = "Open media", onLongClickLabel = "Message actions", onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request,
            imageLoader = loader,
            contentDescription = label,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            onSuccess = { state ->
                val image = state.result.image
                if (image.width > 0 && image.height > 0) aspect = image.width.toFloat() / image.height
            },
            onError = { failed = true },
        )
        when {
            failed -> Icon(HeroIcons.Photo, "Media unavailable", tint = LocalWebColors.current.textMuted)
            video -> Box(Modifier.size(46.dp).background(Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.Play, "Play video", Modifier.size(28.dp), tint = Color.White)
            }
        }
    }
}

/** Signal media bubble box: 240dp wide, 100–320dp tall; tall images narrow down to 150dp. */
fun signalMediaSize(aspect: Float): Pair<Float, Float> {
    val ratio = aspect.takeIf { it > 0f } ?: (4f / 3f)
    var width = 240f
    var height = width / ratio
    if (height > 320f) { height = 320f; width = (height * ratio).coerceIn(150f, 240f) }
    return width to height.coerceAtLeast(100f)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun AttachmentCard(message: ChatMessage, url: String, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(url) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
            enabled = !opening,
            onLongClick = onLongPress,
        ) {
            opening = true
            scope.launch { openChatFile(context, url, message.fileName, message.fileType); opening = false }
        },
        shape = RoundedCornerShape(12.dp),
        color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.surface,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.DocumentText, "Document", tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(message.fileName ?: "File", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(message.fileType, formatChatFileSize(message.fileSize)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary)
            }
            if (opening) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(HeroIcons.ArrowTopRightOnSquare, "Open", Modifier.size(19.dp))
        }
    }
}

@Composable
fun MediaProcessingState(message: ChatMessage, onCancel: (ChatMessage) -> Unit, onRetry: (ChatMessage) -> Unit, modifier: Modifier = Modifier) {
    val state = message.mediaState?.lowercase() ?: return
    if (state in setOf("ready", "completed")) return
    val progress = (message.mediaProgress ?: 0).coerceIn(0, 100)
    // Human wording instead of raw job states ("queued · 0%"), like Signal's "Processing…".
    val label = when (state) {
        "failed" -> "Couldn't send"
        "cancelled" -> "Cancelled"
        else -> if (progress > 0) "Processing… $progress%" else "Processing…"
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { if (state in setOf("failed", "cancelled")) onRetry(message) else onCancel(message) }, Modifier.size(32.dp)) {
                Icon(if (state in setOf("failed", "cancelled")) HeroIcons.ArrowUturnLeft else HeroIcons.XMark, if (state in setOf("failed", "cancelled")) "Retry" else "Cancel", Modifier.size(18.dp))
            }
        }
        if (state !in setOf("failed", "cancelled")) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        message.mediaFailureReason?.takeIf(String::isNotBlank)?.let { Text(it, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.danger, style = MaterialTheme.typography.labelSmall) }
    }
}