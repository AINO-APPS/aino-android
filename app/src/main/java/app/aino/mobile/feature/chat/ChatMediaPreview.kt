package app.aino.mobile.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
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

@Composable
fun ChatMediaPreview(
    message: ChatMessage,
    modifier: Modifier = Modifier,
    onOpenMedia: (ChatMessage) -> Unit = {},
    onCancelProcessing: (ChatMessage) -> Unit = {},
    onRetryProcessing: (ChatMessage) -> Unit = {},
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(12.dp),
    outgoing: Boolean = false,
) {
    val rawUrl = message.fileUrl ?: return
    val url = resolveChatMediaUrl(rawUrl)
    Column(modifier.widthIn(max = SignalDimens.mediaMaxWidth + 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            message.isImageAttachment() -> ChatThumbnail(url, message.fileName, video = false, shape = shape) { onOpenMedia(message) }
            message.isVideoAttachment() -> ChatThumbnail(url, message.fileName, video = true, shape = shape) { onOpenMedia(message) }
            message.fileType?.startsWith("audio/") == true -> ChatVoicePlayer(url, Modifier.fillMaxWidth(), outgoing = outgoing)
            else -> AttachmentCard(message, url)
        }
        MediaProcessingState(message, onCancelProcessing, onRetryProcessing)
    }
}

/**
 * Image / video-poster thumbnail via the shared Coil loader (auth headers,
 * memory+disk cache, downsampled decode). Aspect ratio follows the decoded
 * image, like the web's `--img-aspect`, falling back to 4:3.
 */
@Composable
private fun ChatThumbnail(url: String, label: String?, video: Boolean, shape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit) {
    val context = LocalContext.current
    val loader = AppContainer.get(context).imageLoader
    var aspect by remember(url) { mutableFloatStateOf(4f / 3f) }
    var failed by remember(url) { mutableStateOf(false) }
    // ponytail: Coil's VideoFrameDecoder needs the file on disk, so a poster downloads the whole video once (then disk-cached); add server-side posters if videos get large.
    val request = remember(url, video) {
        ImageRequest.Builder(context).data(url).apply { if (video) videoFrameMillis(100) }.build()
    }
    val (width, height) = signalMediaSize(aspect)
    Box(
        Modifier.size(width.dp, height.dp)
            .clip(shape).background(LocalWebColors.current.surface).clickable(onClick = onClick),
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
            failed -> Icon(Icons.Outlined.BrokenImage, "Media unavailable", tint = LocalWebColors.current.textMuted)
            video -> Box(Modifier.size(46.dp).background(Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, "Play video", Modifier.size(28.dp), tint = Color.White)
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

@Composable
private fun AttachmentCard(message: ChatMessage, url: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember(url) { mutableStateOf(false) }
    Surface(
        Modifier.fillMaxWidth().clickable(enabled = !opening) {
            opening = true
            scope.launch { openChatFile(context, url, message.fileName, message.fileType); opening = false }
        },
        shape = RoundedCornerShape(12.dp),
        color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.surface,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary.copy(alpha = .12f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Description, "Document", tint = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.primary)
            }
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text(message.fileName ?: "File", fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(message.fileType, formatChatFileSize(message.fileSize)).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.textSecondary)
            }
            if (opening) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.OpenInNew, "Open", Modifier.size(19.dp))
        }
    }
}

@Composable
fun MediaProcessingState(message: ChatMessage, onCancel: (ChatMessage) -> Unit, onRetry: (ChatMessage) -> Unit, modifier: Modifier = Modifier) {
    val state = message.mediaState?.lowercase() ?: return
    if (state in setOf("ready", "completed")) return
    val progress = (message.mediaProgress ?: 0).coerceIn(0, 100)
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(message.mediaStage ?: state, message.mediaProgress?.let { "$progress%" }).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            IconButton(onClick = { if (state in setOf("failed", "cancelled")) onRetry(message) else onCancel(message) }, Modifier.size(32.dp)) {
                Icon(if (state in setOf("failed", "cancelled")) Icons.Outlined.Replay else Icons.Outlined.Close, if (state in setOf("failed", "cancelled")) "Retry" else "Cancel", Modifier.size(18.dp))
            }
        }
        if (state !in setOf("failed", "cancelled")) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth())
        message.mediaFailureReason?.takeIf(String::isNotBlank)?.let { Text(it, color = app.aino.mobile.core.designsystem.tokens.LocalWebColors.current.danger, style = MaterialTheme.typography.labelSmall) }
    }
}