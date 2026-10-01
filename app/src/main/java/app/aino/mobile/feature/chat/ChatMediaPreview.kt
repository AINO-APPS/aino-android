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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.network.NetworkConfig
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
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
 * image/voice note (no refetch blink). Pixel dimensions are kept per model so a
 * bubble that remounts is sized before decode and never resizes.
 */
object ChatMediaMemory {
    private val localByUrl = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, IntSize>()

    fun rememberSent(fileUrl: String?, local: android.net.Uri, aspect: Float?) {
        if (fileUrl.isNullOrBlank()) return
        val key = local.toString()
        localByUrl[fileUrl] = key
        (sizes[key] ?: aspect?.let(::aspectDims))?.let { putDims(fileUrl, it) }
    }

    fun localFor(fileUrl: String?): String? = fileUrl?.let(localByUrl::get)
    fun dims(key: Any?): IntSize? = key?.toString()?.let(sizes::get)
    fun putDims(key: Any?, size: IntSize) {
        if (key != null && size.width > 0 && size.height > 0) sizes.putIfAbsent(key.toString(), size)
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
    /** Caption/text below the media: Signal widens the minimum to the full bubble width. */
    withContent: Boolean = false,
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
                    naturalSize = message.mediaDims() ?: ChatMediaMemory.dims(rawUrl), withContent = withContent,
                    onLongClick = onLongPress,
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
 * memory+disk cache, downsampled decode). The box follows Signal's
 * `ThumbnailView.fillTargetDimensions` from the known pixel size; the decoded
 * size is only used when nothing was known beforehand.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChatThumbnail(
    url: Any,
    label: String?,
    video: Boolean,
    shape: androidx.compose.ui.graphics.Shape,
    naturalSize: IntSize? = null,
    withContent: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    /** When set, replaces Signal's fitted bubble box (e.g. square grid cells). */
    modifier: Modifier? = null,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val loader = AppContainer.get(context).imageLoader
    val known = remember(url, naturalSize) { naturalSize?.takeIf { it.width > 0 && it.height > 0 }?.also { ChatMediaMemory.putDims(url, it) } ?: ChatMediaMemory.dims(url) }
    var decoded by remember(url) { mutableStateOf<IntSize?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    // ponytail: Coil's VideoFrameDecoder needs the file on disk, so a poster downloads the whole video once (then disk-cached); add server-side posters if videos get large.
    val request = remember(url, video) {
        ImageRequest.Builder(context).data(url).apply { if (video) videoFrameMillis(100) }.build()
    }
    val density = LocalDensity.current.density
    val (width, height) = signalMediaSize(known ?: decoded, density, withContent)
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
                if (known == null && image.width > 0 && image.height > 0) {
                    // Coil downsamples to the placeholder box, so only the decoded aspect is trustworthy.
                    aspectDims(image.width.toFloat() / image.height)?.let { size ->
                        decoded = size
                        ChatMediaMemory.putDims(url, size)
                    }
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

/** Long side used when only an aspect ratio is known, so it sizes like a camera photo. */
private const val ASPECT_ONLY_LONG_SIDE_PX = 4000

fun aspectDims(aspect: Float): IntSize? {
    if (!(aspect > 0f) || aspect.isInfinite()) return null
    return if (aspect >= 1f) IntSize(ASPECT_ONLY_LONG_SIDE_PX, (ASPECT_ONLY_LONG_SIDE_PX / aspect).roundToInt().coerceAtLeast(1))
    else IntSize((ASPECT_ONLY_LONG_SIDE_PX * aspect).roundToInt().coerceAtLeast(1), ASPECT_ONLY_LONG_SIDE_PX)
}

/**
 * Signal media bubble box in dp for an attachment of [natural] pixel size:
 * bounds are `[min width (150dp solo / 240dp with caption), 240dp] x [100dp, 320dp]`,
 * unknown size falls back to Signal's 210dp square.
 */
fun signalMediaSize(natural: IntSize?, density: Float, withContent: Boolean = false): Pair<Float, Float> {
    val minWidth = (if (withContent) SignalDimens.mediaMinWidthWithContent else SignalDimens.mediaMinWidthSolo).value
    val (w, h) = natural?.takeIf { it.width > 0 && it.height > 0 && density > 0f }
        ?.let { it.width / density to it.height / density }
        ?: (SignalDimens.mediaDefault.value to SignalDimens.mediaDefault.value)
    return fillTargetDimensions(
        w, h, minWidth, SignalDimens.mediaMaxWidth.value, SignalDimens.mediaMinHeight.value, SignalDimens.mediaMaxHeight.value,
    )
}

/** Signal `ThumbnailView.fillTargetDimensions`. */
fun fillTargetDimensions(
    naturalWidth: Float,
    naturalHeight: Float,
    minWidth: Float,
    maxWidth: Float,
    minHeight: Float,
    maxHeight: Float,
): Pair<Float, Float> {
    var width = naturalWidth
    var height = naturalHeight
    val widthInBounds = width in minWidth..maxWidth
    val heightInBounds = height in minHeight..maxHeight
    if (widthInBounds && heightInBounds) return width to height
    val minWidthRatio = naturalWidth / minWidth
    val maxWidthRatio = naturalWidth / maxWidth
    val minHeightRatio = naturalHeight / minHeight
    val maxHeightRatio = naturalHeight / maxHeight
    if (maxWidthRatio > 1f || maxHeightRatio > 1f) {
        val ratio = if (maxWidthRatio >= maxHeightRatio) maxWidthRatio else maxHeightRatio
        width = (width / ratio).coerceAtLeast(minWidth)
        height = (height / ratio).coerceAtLeast(minHeight)
    } else if (minWidthRatio < 1f || minHeightRatio < 1f) {
        val ratio = if (minWidthRatio <= minHeightRatio) minWidthRatio else minHeightRatio
        width = (width / ratio).coerceAtMost(maxWidth)
        height = (height / ratio).coerceAtMost(maxHeight)
    }
    return width to height
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