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
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.media.VideoPoster
import app.aino.mobile.core.media.isRemoteMediaUrl
import app.aino.mobile.core.media.videoPosterCacheKey
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.network.NetworkConfig
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Memory for chat media. Files this device sent map their server URL to the local
 * copy, so the delivered bubble keeps showing the already-decoded image/voice note
 * (no refetch blink). Pixel dimensions are kept per model so a bubble that remounts
 * is sized before decode and never resizes. Remote dims (and mappings to app-owned
 * local files) survive restarts in a small store, bounded to [PERSISTED_MAX] entries.
 */
object ChatMediaMemory {
    private val localByUrl = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sizes = java.util.concurrent.ConcurrentHashMap<String, IntSize>()
    private const val PERSISTED_MAX = 500
    private const val STORE_NAME = "chat-media-memory.tsv"
    private val persisted = object : LinkedHashMap<String, Pair<IntSize?, String?>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<IntSize?, String?>>) = size > PERSISTED_MAX
    }
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "chat-media-memory").apply { isDaemon = true } }
    private val savePending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var store: java.io.File? = null
    @Volatile private var appPackage: String? = null

    /** Loads the persisted store off the main thread; later writes are coalesced on the same thread. */
    fun attach(context: android.content.Context) {
        if (store != null) return
        val file = java.io.File(context.filesDir, STORE_NAME)
        store = file
        appPackage = context.packageName
        writer.execute {
            val lines = runCatching { if (file.exists()) file.readLines() else emptyList() }.getOrDefault(emptyList())
            synchronized(persisted) {
                lines.mapNotNull(::parseMediaMemoryLine).forEach { (key, dims, local) ->
                    val usable = local?.takeIf { isLocalUsable(context, it) }
                    dims?.let { sizes.putIfAbsent(key, it) }
                    usable?.let { localByUrl.putIfAbsent(key, it) }
                    if (!persisted.containsKey(key)) persisted[key] = dims to usable
                }
            }
        }
    }

    /** Sign-out: the previous account's media must not be remembered. */
    fun clear() {
        localByUrl.clear()
        sizes.clear()
        synchronized(persisted) { persisted.clear() }
        store?.let { file -> writer.execute { file.delete() } }
    }

    fun rememberSent(fileUrl: String?, local: android.net.Uri, aspect: Float?) {
        if (fileUrl.isNullOrBlank()) return
        val key = local.toString()
        localByUrl[fileUrl] = key
        if (isAppOwned(local)) remember(fileUrl) { (dims, _) -> dims to key }
        (sizes[key] ?: aspect?.let(::aspectDims))?.let { putDims(fileUrl, it) }
    }

    fun localFor(fileUrl: String?): String? = fileUrl?.let(localByUrl::get)
    fun dims(key: Any?): IntSize? = key?.toString()?.let(sizes::get)
    fun putDims(key: Any?, size: IntSize) {
        if (key == null || size.width <= 0 || size.height <= 0) return
        val name = key.toString()
        if (sizes.putIfAbsent(name, size) == null && isPersistableKey(name)) remember(name) { (_, local) -> size to local }
    }

    private fun remember(key: String, update: (Pair<IntSize?, String?>) -> Pair<IntSize?, String?>) {
        if (store == null) return
        synchronized(persisted) { persisted[key] = update(persisted[key] ?: (null to null)) }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        writer.execute {
            Thread.sleep(500)
            savePending.set(false)
            val file = store ?: return@execute
            val text = synchronized(persisted) {
                persisted.entries.joinToString("\n") { (key, value) -> formatMediaMemoryLine(key, value.first, value.second) }
            }
            runCatching {
                val tmp = java.io.File(file.parentFile, "$STORE_NAME.tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
            }
        }
    }

    /** Server paths and remote URLs; local URIs are session-specific. */
    private fun isPersistableKey(key: String): Boolean = !key.startsWith("content:") && !key.startsWith("file:")

    private fun isAppOwned(uri: android.net.Uri): Boolean = when (uri.scheme) {
        "file" -> appPackage?.let { uri.path?.contains("/$it/") } == true
        "content" -> appPackage?.let { uri.authority == "$it.fileprovider" } == true
        else -> false
    }

    /** Staged shares / camera captures may have been pruned since; a missing copy falls back to the server URL. */
    private fun isLocalUsable(context: android.content.Context, value: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(value)
        if (uri.scheme == "file") java.io.File(uri.path.orEmpty()).exists()
        else context.contentResolver.openFileDescriptor(uri, "r")?.use { true } == true
    }.getOrDefault(false)
}

internal fun formatMediaMemoryLine(key: String, dims: IntSize?, local: String?): String =
    listOf(key, dims?.width?.toString().orEmpty(), dims?.height?.toString().orEmpty(), local.orEmpty()).joinToString("\t")

internal fun parseMediaMemoryLine(line: String): Triple<String, IntSize?, String?>? {
    val parts = line.split('\t')
    if (parts.size != 4 || parts[0].isBlank()) return null
    val width = parts[1].toIntOrNull()
    val height = parts[2].toIntOrNull()
    val dims = if (width != null && height != null && width > 0 && height > 0) IntSize(width, height) else null
    val local = parts[3].ifBlank { null }
    if (dims == null && local == null) return null
    return Triple(parts[0], dims, local)
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
    /** Off for a sending video: Signal shows only the transfer ring over the poster. */
    showPlay: Boolean = true,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val loader = AppContainer.get(context).imageLoader
    val known = remember(url, naturalSize) { naturalSize?.takeIf { it.width > 0 && it.height > 0 }?.also { ChatMediaMemory.putDims(url, it) } ?: ChatMediaMemory.dims(url) }
    var decoded by remember(url) { mutableStateOf<IntSize?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    // A retriever poster reads only the first frame; a failure (or a 401 the retriever can't refresh)
    // falls back to Coil's VideoFrameDecoder, which downloads the whole file through the authenticated client.
    var posterFailed by remember(url) { mutableStateOf(false) }
    val request = remember(url, video, posterFailed) {
        ImageRequest.Builder(context).data(if (video && !posterFailed) VideoPoster(url.toString()) else url)
            .apply { if (video && posterFailed) videoFrameMillis(100) }.build()
    }
    val density = LocalDensity.current.density
    val (width, height) = signalMediaSize(known ?: decoded, density, withContent)
    // Signal media auto-download: remote media not allowed on this network waits for a tap.
    val prefs by app.aino.mobile.core.settings.ChatPreferences.state.collectAsState()
    var tapped by remember(url) { mutableStateOf(false) }
    val remote = url is String && isRemoteMediaUrl(url)
    val allowed = remember(prefs, video) {
        prefs.autoDownload(
            if (video) app.aino.mobile.core.settings.AutoDownloadKind.Videos else app.aino.mobile.core.settings.AutoDownloadKind.Photos,
            app.aino.mobile.core.settings.ChatPreferences.network(context),
        )
    }
    // Only media held back by auto-download needs the (disk) cache check; null until it ran.
    val cached by produceState<Boolean?>(if (!remote || allowed) true else null, url, remote, allowed) {
        if (!remote || allowed) { value = true; return@produceState }
        value = withContext(Dispatchers.IO) {
            val cache = loader.diskCache ?: return@withContext false
            listOfNotNull(url as String, videoPosterCacheKey(url).takeIf { video }).any { key -> cache.openSnapshot(key)?.use { true } == true }
        }
    }
    val placeholder = LocalWebColors.current.textMuted.copy(alpha = .12f)
    if (remote && cached != true && !tapped) {
        Box(
            (modifier ?: Modifier.size(width.dp, height.dp))
                .clip(shape).background(placeholder)
                .combinedClickable(onClickLabel = "Download media", onLongClickLabel = "Message actions", onClick = { tapped = true }, onLongClick = onLongClick),
            contentAlignment = Alignment.Center,
        ) {
            if (cached == false) Box(Modifier.size(48.dp).background(Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.ArrowDownTray, "Download", Modifier.size(24.dp), tint = Color.White)
            }
        }
        return
    }
    Box(
        (modifier ?: Modifier.size(width.dp, height.dp))
            .clip(shape).background(placeholder)
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
            onError = { if (video && !posterFailed) posterFailed = true else failed = true },
        )
        when {
            failed -> Icon(HeroIcons.Photo, "Media unavailable", tint = LocalWebColors.current.textMuted)
            video && showPlay -> Box(Modifier.size(46.dp).background(Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(HeroIcons.Play, "Play video", Modifier.size(28.dp), tint = Color.White)
            }
        }
    }
}

/** Long side used when only an aspect ratio is known, so it sizes like a camera photo. */
private const val ASPECT_ONLY_LONG_SIDE_PX = 4000
private const val PREFETCH_COUNT = 20
private const val PREFETCH_SIZE_PX = 720

/** The newest photos/videos of a thread worth warming: resolved URL + whether it is a video, newest first. */
internal fun chatMediaPrefetchTargets(messages: List<ChatMessage>, limit: Int = PREFETCH_COUNT): List<Pair<String, Boolean>> =
    messages.asReversed().asSequence()
        .filter { it.deletedAt == null && !it.isViewOnce() && (it.isImageAttachment() || it.isVideoAttachment()) }
        .filter { ChatMediaMemory.localFor(it.fileUrl) == null }
        .take(limit)
        .map { resolveChatMediaUrl(it.fileUrl.orEmpty()) to it.isVideoAttachment() }
        .filter { (url, _) -> isRemoteMediaUrl(url) }
        .toList()

/** Enqueues [targets] allowed by the auto-download settings; Coil skips what is already cached. */
fun prefetchChatMedia(context: android.content.Context, targets: List<Pair<String, Boolean>>) {
    if (targets.isEmpty()) return
    val prefs = app.aino.mobile.core.settings.ChatPreferences.current
    val network = app.aino.mobile.core.settings.ChatPreferences.network(context)
    val photos = prefs.autoDownload(app.aino.mobile.core.settings.AutoDownloadKind.Photos, network)
    val videos = prefs.autoDownload(app.aino.mobile.core.settings.AutoDownloadKind.Videos, network)
    val loader = AppContainer.get(context).imageLoader
    targets.forEach { (url, video) ->
        if (if (video) !videos else !photos) return@forEach
        loader.enqueue(ImageRequest.Builder(context).data(if (video) VideoPoster(url) else url).size(PREFETCH_SIZE_PX).build())
    }
}

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

/** "Processing 40%" while the server pipeline runs (live via `chat_media_job`). */
fun mediaProcessingLabel(message: ChatMessage): String? {
    if (!message.mediaInFlight()) return null
    val stage = (message.mediaStage ?: message.mediaState).orEmpty().lowercase()
    val verb = if (stage == "queued") "Queued" else "Processing"
    return message.mediaProgress?.takeIf { it in 1..99 }?.let { "$verb $it%" } ?: verb
}

@Composable
fun MediaProcessingState(message: ChatMessage, onCancel: (ChatMessage) -> Unit, onRetry: (ChatMessage) -> Unit, modifier: Modifier = Modifier) {
    val state = message.mediaState?.lowercase() ?: return
    if (message.mediaInFlight()) {
        mediaProcessingLabel(message)?.let { Text(it, modifier, style = MaterialTheme.typography.labelSmall) }
        return
    }
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