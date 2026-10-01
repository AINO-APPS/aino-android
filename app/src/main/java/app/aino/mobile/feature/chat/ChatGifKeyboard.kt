package app.aino.mobile.feature.chat

import android.content.Context
import android.net.Uri
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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

/**
 * Web `EmojiGifPicker` GIF / Sticker modes: server-proxied GIPHY search
 * (trending when empty); a pick is downloaded and sent as an animated image
 * upload, exactly like the web's `onSelectMediaFile`.
 */
@Composable
internal fun GifKeyboard(
    height: Dp,
    onPicked: (uri: Uri, mimeType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = signalColors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stickers by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GiphyMedia>?>(null) }
    var sending by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query, stickers) {
        delay(300) // debounce typing
        results = withContext(Dispatchers.IO) {
            runCatching {
                val body = AppContainer.get(context).api.execute(ApiRequest(path = giphySearchPath(query, stickers))).bodyAsString()
                giphyJson.decodeFromString<GiphyResults>(body).results
            }.getOrDefault(emptyList())
        }
    }

    Column(modifier.fillMaxWidth().height(height).background(colors.surface)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ModeChip("GIF", !stickers) { stickers = false }
            Spacer(Modifier.width(6.dp))
            ModeChip("Sticker", stickers) { stickers = true }
            Spacer(Modifier.width(10.dp))
            Row(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(18.dp)).background(colors.searchPill).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(HeroIcons.MagnifyingGlass, null, Modifier.size(18.dp), tint = colors.textSecondary)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text(if (stickers) "Search stickers..." else "Search GIFs...", color = colors.textSecondary, fontSize = 14.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it.take(100) },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
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
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(colors.searchPill)
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

@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = signalColors
    Text(
        label,
        color = if (active) colors.primary else colors.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(14.dp))
            .background(if (active) colors.searchPill else colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
