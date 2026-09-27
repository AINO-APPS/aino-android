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

/**
 * Server media-job states. The pipeline only fingerprints a file that is already
 * stored and servable, so these never gate display (Signal shows media as soon as
 * it exists); only a failed job surfaces a retry.
 */
fun ChatMessage.mediaInFlight(): Boolean =
    mediaState?.lowercase()?.let { it !in setOf("ready", "completed", "failed", "cancelled") } == true

fun ChatMessage.mediaFailed(): Boolean = mediaState?.lowercase() in setOf("failed", "cancelled")

/**
 * Session memory for chat media. Files this device just sent map their server URL
 * to the local copy, so the delivered bubble keeps showing the already-decoded
 * image/voice note (no refetch blink). Decoded aspect ratios are kept per model so
 * a bubble that remounts never starts at the 4:3 fallback and resizes.
 */
object ChatMediaMemory {
    private val localByUrl = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val aspects = java.util.concurrent.ConcurrentHashMap<String, Float>()

    fun rememberSent(fileUrl: String?, local: android.net.Uri, aspect: Float?) {
        if (fileUrl.isNullOrBlank()) return
        val key = local.toString()
        localByUrl[fileUrl] = key
        (aspect ?: aspects[key])?.let { putAspect(fileUrl, it) }
    }

    fun localFor(fileUrl: String?): String? = fileUrl?.let(localByUrl::get)
    fun aspect(key: Any?): Float? = key?.toString()?.let(aspects::get)
    fun putAspect(key: Any?, aspect: Float) {
        if (key != null && aspect > 0f) aspects[key.toString()] = aspect
    }
}

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
    // Sent from this device this session: keep showing the local copy (no refetch).
    val local = ChatMediaMemory.localFor(rawUrl)
    val visual = message.isImageAttachment() || message.isVideoAttachment()
    Column(modifier.widthIn(max = SignalDimens.mediaMaxWidth + 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            visual -> Box {
                ChatThumbnail(
                    local ?: url, message.fileName, video = message.isVideoAttachment(), shape = shape,
                    initialAspect = ChatMediaMemory.aspect(rawUrl) ?: message.mediaAspect(), onLongClick = onLongPress,
                ) { onOpenMedia(message) }
                if (message.mediaFailed()) TransferRetry(
                    onRetry = { onRetryProcessing(message) },
                    modifier = Modifier.matchParentSize().clip(shape),
                )
            }
            message.fileType?.startsWith("audio/") == true -> ChatVoicePlayer(local ?: url, Modifier.fillMaxWidth(), outgoing = outgoing, waveSeed = message.fileName ?: url)
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
    var aspect by remember(url) { mutableFloatStateOf(initialAspect?.takeIf { it > 0f } ?: ChatMediaMemory.aspect(url) ?: (4f / 3f)) }
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
                if (image.width > 0 && image.height > 0) {
                    aspect = image.width.toFloat() / image.height
                    ChatMediaMemory.putAspect(url, aspect)
                }
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
    // The file is already servable while the job runs; only a failure needs UI.
    if (state !in setOf("failed", "cancelled")) return
    val label = if (state == "failed") "Couldn't send" else "Cancelled"
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { onRetry(message) }, Modifier.size(32.dp)) {
                Icon(HeroIcons.ArrowPath, "Retry", Modifier.size(18.dp))
            }
        }
        message.mediaFailureReason?.takeIf(String::isNotBlank)?.let { Text(it, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.danger, style = MaterialTheme.typography.labelSmall) }
    }
}