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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import coil3.compose.AsyncImage
import coil3.video.videoFrameMillis
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import app.aino.mobile.core.designsystem.icons.HeroIcons

/** Signal `MediaUtil.isImageType`: SVG is never rendered inline; it is a document. */
fun isInlineImageType(type: String?): Boolean {
    val mime = type?.lowercase() ?: return false
    return mime.startsWith("image/") && mime != "image/svg+xml"
}

/** Generic downloads are opened as whatever their extension says (an .svg opens in a browser, a .apk in the installer). */
internal fun openableMimeType(
    mimeType: String?,
    fileName: String?,
    forExtension: (String) -> String? = { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) },
): String? {
    if (mimeType != null && !mimeType.equals(GENERIC_FILE_MIME, ignoreCase = true)) return mimeType
    val ext = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf(String::isNotBlank)
    return ext?.let(forExtension) ?: mimeType
}

fun ChatMessage.isImageAttachment() = fileUrl != null && isInlineImageType(fileType)
fun ChatMessage.isVideoAttachment() = fileUrl != null && fileType?.startsWith("video/") == true
fun ChatMessage.isViewableMedia() = isImageAttachment() || isVideoAttachment()

/**
 * Full-screen media viewer (web `FullScreenImage` / full-screen `VideoPlayer`,
 * with Signal's swipe-between-media pager): pinch/double-tap zoom for images,
 * Media3 playback for video, share/open for both.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatMediaViewer(media: List<ChatMessage>, startMessageId: Long, secure: Boolean = false, onClose: () -> Unit) {
    if (media.isEmpty()) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(media.indexOfFirst { it.id == startMessageId }.coerceAtLeast(0)) { media.size }
    val save = rememberChatMediaSaver()
    // Immersive like Signal's media viewer: a tap toggles the top bar and the video controls together.
    var chromeVisible by remember { mutableStateOf(true) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Signal view-once: block screenshots/recents thumbnails while open.
        if (secure) app.aino.mobile.core.designsystem.SecureScreen()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = 24.dp, key = { media[it].id }) { page ->
                val item = media[page]
                val url = resolveChatMediaUrl(item.fileUrl.orEmpty())
                if (item.isVideoAttachment()) {
                    VideoPage(
                        url = url,
                        localUri = ChatMediaMemory.localFor(item.fileUrl),
                        label = item.fileName,
                        active = pager.currentPage == page,
                        onControlsVisible = { chromeVisible = it },
                    )
                } else {
                    ZoomableImage(url, item.fileName, onTap = { chromeVisible = !chromeVisible })
                }
            }
            val current = media[pager.currentPage]
            // A swipe to an image brings the bar back; a video page starts immersive.
            LaunchedEffect(pager.currentPage) { chromeVisible = !current.isVideoAttachment() }
            androidx.compose.animation.AnimatedVisibility(
                visible = chromeVisible,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
            ) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .35f)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.XMark, "Close", Modifier.size(40.dp).clickable(onClick = onClose).padding(8.dp), tint = Color.White)
                Column(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(current.senderName ?: current.senderUsername.orEmpty(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
                    if (media.size > 1) Text("${pager.currentPage + 1} of ${media.size}", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
                }
                // Signal media viewer: Save to device (never for view-once media).
                if (!secure) Icon(
                    HeroIcons.ArrowDownTray, "Save",
                    Modifier.size(40.dp).clickable {
                        save(resolveChatMediaUrl(current.fileUrl.orEmpty()), current.fileName, current.fileType)
                    }.padding(8.dp),
                    tint = Color.White,
                )
                if (!secure) Icon(
                    HeroIcons.Share, "Share",
                    Modifier.size(40.dp).clickable {
                        scope.launch { openChatFile(context, resolveChatMediaUrl(current.fileUrl.orEmpty()), current.fileName, current.fileType, share = true) }
                    }.padding(8.dp),
                    tint = Color.White,
                )
            }
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String, label: String?, onTap: () -> Unit) {
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
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { if (scale > 1f) { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f },
                )
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

/**
 * Signal-style viewer video: starts playing straight away with the controls
 * hidden (a tap shows them; they auto-hide while playing), the poster frame
 * from the bubble stays up until the first video frame renders, and the bytes
 * come from the closest copy: the file sent from this device, the full video
 * the bubble poster already downloaded, or the network through [VideoCache].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun VideoPage(url: String, localUri: String?, label: String?, active: Boolean, onControlsVisible: (Boolean) -> Unit) {
    val context = LocalContext.current
    val container = AppContainer.get(context)
    val activeState by rememberUpdatedState(active)
    val controlsCallback by rememberUpdatedState(onControlsVisible)
    // Coil keeps the whole video on disk for the bubble poster; hold the entry open while it plays.
    val snapshot = remember(url) { runCatching { container.imageLoader.diskCache?.openSnapshot(url) }.getOrNull() }
    val sources = remember(url, localUri) {
        listOfNotNull(
            localUri?.let(Uri::parse),
            snapshot?.data?.toFile()?.takeIf { it.length() > 0 }?.let(Uri::fromFile),
            Uri.parse(url),
        ).distinct()
    }
    var sourceIndex by remember(url) { mutableIntStateOf(0) }
    var firstFrameRendered by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.aino.mobile.core.media.VideoCache.dataSourceFactory(context, container.mediaHttp)))
            .setLoadControl(app.aino.mobile.core.media.VideoCache.quickStartLoadControl())
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(sources[0]))
                playWhenReady = active
                prepare()
            }
    }
    var view by remember(url) { mutableStateOf<PlayerView?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Finished: show the controls so the video can be replayed.
                if (playbackState == Player.STATE_ENDED && activeState) view?.showController()
            }

            override fun onPlayerError(error: PlaybackException) {
                // A stale local copy (revoked content:// grant, evicted cache file): try the next source.
                if (sourceIndex < sources.lastIndex) {
                    sourceIndex += 1
                    player.setMediaItem(MediaItem.fromUri(sources[sourceIndex]))
                    player.prepare()
                } else {
                    view?.showController()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            runCatching { snapshot?.close() }
        }
    }
    LaunchedEffect(active) {
        if (active) {
            container.audio.stop()
            player.play()
        } else {
            player.pause()
            view?.hideController()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    controllerAutoShow = false
                    controllerHideOnTouch = true
                    controllerShowTimeoutMs = CONTROLS_TIMEOUT_MS
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            if (activeState) controlsCallback(visibility == android.view.View.VISIBLE)
                        },
                    )
                    hideController()
                    view = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!firstFrameRendered) {
            var posterFailed by remember(url, localUri) { mutableStateOf(false) }
            val request = remember(url, localUri, posterFailed) {
                val source = localUri ?: url
                if (posterFailed) coil3.request.ImageRequest.Builder(context).data(source).videoFrameMillis(100).build()
                else coil3.request.ImageRequest.Builder(context).data(app.aino.mobile.core.media.VideoPoster(source.toString())).build()
            }
            AsyncImage(
                model = request,
                imageLoader = container.imageLoader,
                contentDescription = label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
                onError = { posterFailed = true },
            )
        }
    }
}

private const val CONTROLS_TIMEOUT_MS = 2_500

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
    val type = openableMimeType(mimeType, fileName) ?: context.contentResolver.getType(local) ?: "*/*"
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

/** Opens a chat link: a shared group link opens the in-app join preview instead of the browser. */
fun openChatLink(context: Context, url: String) {
    val invite = app.aino.mobile.core.navigation.webGroupInviteToken(url)
    if (invite != null) app.aino.mobile.core.navigation.PendingGroupInvite.set(invite)
    else runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/**
 * Signal `LinkPreviewView` (small layout) in a bubble or, with [onRemove], the composer:
 * 72dp thumbnail on the left, title, 2-line description and the link's domain on a
 * translucent [contentColor] tint, so it reads on outgoing and incoming bubbles alike.
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    contentColor: Color = LocalWebColors.current.text,
) {
    val context = LocalContext.current
    val loader = AppContainer.get(context).imageLoader
    val domain = remember(preview.url, preview.siteName) { linkPreviewDomain(preview) }
    Row(
        modifier.widthIn(max = 340.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(contentColor.copy(alpha = .1f))
            .clickable { openChatLink(context, preview.url) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        preview.image?.let { image ->
            AsyncImage(
                model = resolveChatMediaUrl(image), imageLoader = loader, contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 6.dp).size(72.dp).clip(RoundedCornerShape(8.dp))
                    .background(contentColor.copy(alpha = .08f)),
            )
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(preview.title.ifBlank { domain.ifBlank { preview.url } }, color = contentColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (preview.description.isNotBlank()) Text(preview.description, color = contentColor.copy(alpha = .8f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (domain.isNotBlank()) Text(domain, color = contentColor.copy(alpha = .6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        onRemove?.let {
            Icon(HeroIcons.XMark, "Remove preview", Modifier.padding(end = 10.dp).size(18.dp).clickable(onClick = it), tint = contentColor.copy(alpha = .6f))
        }
    }
}
