package app.aino.mobile.feature.chat

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.icons.HeroIcons
import app.aino.mobile.core.network.ApiRequest
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request

/** `GET /api/giphy/search` item (web `GiphyMedia`). */
@Serializable
data class GiphyMedia(val id: String, val previewUrl: String, val mediaUrl: String)

@Serializable
private data class GiphyResults(val results: List<GiphyMedia> = emptyList())

private val giphyJson = Json { ignoreUnknownKeys = true }

/** Web `searchGiphy`: an empty query is trending on the server. */
// @api GET giphy/search
internal fun giphySearchPath(query: String, stickers: Boolean): String =
    "giphy/search?q=" + java.net.URLEncoder.encode(query.trim().take(100), "UTF-8") + "&type=" + if (stickers) "stickers" else "gifs"

/** Signal `KeyboardPagerFragment` pages, in tab order. */
enum class KeyboardPage { Emoji, Sticker, Gif }

/** Signal reopens the media keyboard on the last used page. */
object KeyboardPageStore {
    private const val FILE = "aino_keyboard"
    private const val KEY = "keyboard_page"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context): KeyboardPage =
        KeyboardPage.entries.firstOrNull { it.name == prefs(context).getString(KEY, null) } ?: KeyboardPage.Emoji

    fun save(context: Context, page: KeyboardPage) {
        prefs(context).edit().putString(KEY, page.name).apply()
    }
}

private val MediaKeyboardTabHeight = 44.dp

/**
 * Signal media keyboard: Emoji | Sticker | GIF pages behind a bottom tab strip,
 * sized to the IME height. [giphy] is false on the Emoji page; the Sticker/GIF
 * choice is kept here and in [KeyboardPageStore].
 */
@Composable
internal fun MediaKeyboard(
    giphy: Boolean,
    height: Dp,
    onShowGiphy: (Boolean) -> Unit,
    onEmoji: (String) -> Unit,
    onBackspace: () -> Unit,
    onOpenEmojiSearch: () -> Unit,
    onGiphyPicked: (uri: Uri, mimeType: String) -> Unit,
    modifier: Modifier = Modifier,
    showTabs: Boolean = true,
) {
    val colors = signalColors
    val context = LocalContext.current
    var giphyPage by remember { mutableStateOf(KeyboardPageStore.get(context).takeIf { it != KeyboardPage.Emoji } ?: KeyboardPage.Gif) }
    val page = if (giphy) giphyPage else KeyboardPage.Emoji
    Column(modifier.fillMaxWidth().height(height).background(colors.surface)) {
        AnimatedContent(
            targetState = page,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(180)) { it / 8 * dir } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 8 * dir } + fadeOut(tween(120)))
            },
            label = "keyboardPage",
        ) { current ->
            when (current) {
                KeyboardPage.Emoji -> SignalEmojiKeyboard(onEmoji, onBackspace, Modifier.fillMaxSize(), onOpenSearch = onOpenEmojiSearch)
                KeyboardPage.Sticker -> GiphyGrid(stickers = true, onPicked = onGiphyPicked)
                KeyboardPage.Gif -> GiphyGrid(stickers = false, onPicked = onGiphyPicked)
            }
        }
        if (showTabs) KeyboardPageTabs(page) { next ->
            KeyboardPageStore.save(context, next)
            if (next != KeyboardPage.Emoji) giphyPage = next
            onShowGiphy(next != KeyboardPage.Emoji)
        }
    }
}

@Composable
private fun KeyboardPageTabs(page: KeyboardPage, onSelect: (KeyboardPage) -> Unit) {
    val colors = signalColors
    Row(
        Modifier.fillMaxWidth().height(MediaKeyboardTabHeight).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.clip(RoundedCornerShape(18.dp)).background(colors.searchPill).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            KeyboardPage.entries.forEach { tab ->
                val active = tab == page
                val background by animateColorAsState(if (active) colors.surface else Color.Transparent, tween(150), label = "pageTab")
                val tint = if (active) colors.primary else colors.textSecondary
                Box(
                    Modifier.height(30.dp).widthIn(min = 56.dp).clip(RoundedCornerShape(15.dp)).background(background)
                        .selectable(selected = active, role = Role.Tab) { onSelect(tab) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (tab) {
                        KeyboardPage.Emoji -> Icon(HeroIcons.FaceSmile, "Emoji", Modifier.size(20.dp), tint = tint)
                        KeyboardPage.Sticker -> Icon(HeroIcons.Sparkles, "Stickers", Modifier.size(20.dp), tint = tint)
                        KeyboardPage.Gif -> Text("GIF", color = tint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Web `EmojiGifPicker` GIF / Sticker grid: server-proxied GIPHY search
 * (trending when empty); a pick is downloaded and sent as an animated image
 * upload, exactly like the web's `onSelectMediaFile`.
 */
@Composable
internal fun GiphyGrid(
    stickers: Boolean,
    onPicked: (uri: Uri, mimeType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = signalColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(stickers) { mutableStateOf("") }
    var results by remember(stickers) { mutableStateOf<List<GiphyMedia>?>(null) }
    var sending by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, stickers) {
        if (query.isNotEmpty()) delay(300) // debounce typing
        results = withContext(Dispatchers.IO) {
            runCatching {
                val body = AppContainer.get(context).api.execute(ApiRequest(path = giphySearchPath(query, stickers))).bodyAsString()
                giphyJson.decodeFromString<GiphyResults>(body).results
            }.getOrDefault(emptyList())
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(40.dp)
                .clip(RoundedCornerShape(20.dp)).background(colors.searchPill).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(20.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text(if (stickers) "Search stickers" else "Search GIFs", color = colors.textSecondary, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.take(100) },
                    singleLine = true,
                    textStyle = TextStyle(color = colors.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) Icon(
                HeroIcons.XMark, "Clear search",
                Modifier.size(20.dp).clip(RoundedCornerShape(10.dp)).clickable { query = "" },
                tint = colors.textSecondary,
            )
        }
        val items = results
        when {
            items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = colors.primary, strokeWidth = 2.dp)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results", color = colors.textSecondary, fontSize = 14.sp)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(if (stickers) 4 else 3),
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .background(if (stickers) Color.Transparent else colors.searchPill)
                            .clickable(enabled = sending == null) {
                                sending = item.id
                                scope.launch {
                                    val picked = withContext(Dispatchers.IO) { runCatching { download(context, item, stickers) }.getOrNull() }
                                    sending = null
                                    picked?.let { (uri, mime) -> onPicked(uri, mime) }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        AsyncImage(
                            model = item.previewUrl,
                            imageLoader = AppContainer.get(context).imageLoader,
                            contentDescription = if (stickers) "Sticker" else "GIF",
                            contentScale = if (stickers) ContentScale.Fit else ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (sending == item.id) CircularProgressIndicator(Modifier.size(22.dp), color = colors.primary, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}
/**
 * Downloads the full-size media from GIPHY (plain client: no session headers
 * to a third party) into the cache, typed like the web: stickers are WebP,
 * GIFs are GIF.
 */
private fun download(context: Context, item: GiphyMedia, sticker: Boolean): Pair<Uri, String> {
    val http = AppContainer.get(context).http
    val bytes = http.newCall(Request.Builder().url(item.mediaUrl).build()).execute().use { response ->
        check(response.isSuccessful) { "GIPHY download failed: ${response.code}" }
        response.body!!.bytes()
    }
    val webp = sticker || item.mediaUrl.substringBefore('?').endsWith(".webp")
    val ext = if (webp) "webp" else "gif"
    val dir = context.cacheDir.resolve("giphy").apply { mkdirs() }
    val file = dir.resolve("${if (sticker) "sticker" else "gif"}-${System.currentTimeMillis()}.$ext")
    file.writeBytes(bytes)
    return Uri.fromFile(file) to if (webp) "image/webp" else "image/gif"
}
