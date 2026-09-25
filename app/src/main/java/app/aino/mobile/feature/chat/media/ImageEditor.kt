package app.aino.mobile.feature.chat.media

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val SliderStops = listOf(
    Color.White, Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color.Black,
)

private fun sliderColor(t: Float): Color {
    val seg = t.coerceIn(0f, 1f) * (SliderStops.size - 1)
    val i = seg.toInt().coerceAtMost(SliderStops.size - 2)
    return lerp(SliderStops[i], SliderStops[i + 1], seg - i)
}

private val Aspects = listOf<Pair<Float?, String>>(null to "Free", 1f to "1:1", 4f / 3f to "4:3", 16f / 9f to "16:9")

private val Emoji = (
    "😀 😃 😄 😁 😆 😅 😂 🤣 😊 😇 🙂 😉 😍 🥰 😘 😋 😜 🤪 😎 🤩 🥳 😏 😒 😞 😢 😭 😤 😡 🤯 😳 🥺 😱 " +
        "🤔 🤫 🤭 😴 🤢 🤮 🤧 😷 🤠 🤡 👻 💀 👽 🤖 💩 😺 🙈 🙉 🙊 ❤️ 🧡 💛 💚 💙 💜 🖤 💔 💯 💥 " +
        "🔥 ✨ ⭐ 🌈 ☀️ 🌙 ⚡ 🎉 🎈 🎁 🏆 👍 👎 👏 🙌 🙏 💪 👀 👋 ✌️ 🤞 👌 🐶 🐱 🦄 🍕 🍔 ☕ 🍺 🎂"
    ).split(' ')

private data class Geo(val ow: Int, val oh: Int, val crop: NRect, val vp: Viewport)

/** Full-screen Signal-style image editor: crop/rotate, draw, text, stickers and blur, with undo. */
@Composable
internal fun ImageEditor(source: Uri, initialTool: EditorTool, onDone: (MediaSendItem) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(source) { mutableStateOf(false) }
    LaunchedEffect(source) {
        runCatching { loadEditorBitmap(context, source) }.onSuccess { bitmap = it }.onFailure { failed = true }
    }
    BackHandler(onBack = onCancel)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val src = bitmap
        if (src != null) {
            EditorContent(src, initialTool, onDone, onCancel)
        } else {
            IconButton(onClick = onCancel, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel editing", tint = Color.White)
            }
            if (failed) {
                Text("Couldn't open image", color = Color.White, modifier = Modifier.align(Alignment.Center))
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun EditorContent(src: Bitmap, initialTool: EditorTool, onDone: (MediaSendItem) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val mosaic = remember(src) { makeMosaic(src) }

    var history by remember { mutableStateOf(EditorHistory(EditorState())) }
    var state by remember { mutableStateOf(history.current) }
    var tool by remember { mutableStateOf(initialTool) }
    var brush by remember { mutableStateOf(BrushType.Pen) }
    var colorPos by remember { mutableFloatStateOf(0.15f) }
    var widthPos by remember { mutableFloatStateOf(0.3f) }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var editing by remember { mutableStateOf<EditText?>(null) }
    var showStickers by remember { mutableStateOf(initialTool == EditorTool.Sticker) }
    var detecting by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val padPx = with(density) { 24.dp.toPx() }
    val slopPx = with(density) { 12.dp.toPx() }
    val cornerTouchPx = with(density) { 40.dp.toPx() }

    LaunchedEffect(message) { if (message != null) { delay(2000); message = null } }

    fun commit() { history = history.push(state) }
    fun undo() { history = history.undo(); state = history.current; selectedId = null }
    fun geometry(): Geo {
        val (ow, oh) = orientedSize(src.width, src.height, state.turns)
        val crop = if (tool == EditorTool.Crop) NRect.Full else state.crop
        val vp = fitViewport(crop.width * ow, crop.height * oh, viewSize.width.toFloat(), viewSize.height.toFloat(), padPx)
        return Geo(ow, oh, crop, vp)
    }
    fun toSource(o: Offset): Vec2 = geometry().let { g ->
        screenToSource(Vec2(o.x, o.y), g.vp, g.crop, g.ow, g.oh, state.turns, state.flipH)
    }
    fun toScreen(p: Vec2): Offset = geometry().let { g ->
        sourceToScreen(p, g.vp, g.crop, g.ow, g.oh, state.turns, state.flipH).let { Offset(it.x, it.y) }
    }
    fun hitTest(o: Offset): Long? {
        val g = geometry()
        val m = buildMatrix(src.width, src.height, state, g.crop, g.vp)
        return state.texts.lastOrNull { hitText(it, o.x, o.y, m, src.width, src.height, g.vp.scale, slopPx) }?.id
    }
    fun updateText(id: Long, f: (EditText) -> EditText) {
        state = state.copy(texts = state.texts.map { if (it.id == id) f(it) else it })
    }
    fun finishEditing(text: String) {
        val et = editing ?: return
        editing = null
        val exists = state.texts.any { it.id == et.id }
        state = when {
            text.isBlank() -> state.copy(texts = state.texts.filter { it.id != et.id })
            exists -> state.copy(texts = state.texts.map { if (it.id == et.id) et.copy(text = text) else it })
            else -> state.copy(texts = state.texts + et.copy(text = text))
        }
        selectedId = if (text.isBlank()) null else et.id
        commit()
    }
    fun onColor(t: Float) {
        colorPos = t
        val c = sliderColor(t).toArgb()
        val e = editing
        if (e != null) editing = e.copy(color = c)
        else if (tool == EditorTool.Text) selectedId?.let { id -> updateText(id) { if (it.sticker) it else it.copy(color = c) } }
    }
    fun setFaceBlur(on: Boolean) {
        if (!on) { state = state.copy(faceRects = emptyList()); commit(); return }
        detecting = true
        scope.launch {
            val faces = detectFaces(src)
            detecting = false
            if (faces.isEmpty()) message = "No faces found" else { state = state.copy(faceRects = faces); commit() }
        }
    }
    fun applyAspect() {
        val a = Aspects[aspectIndex].first ?: return
        val (ow, oh) = orientedSize(src.width, src.height, state.turns)
        state = state.copy(crop = fitAspect(state.crop, a, ow.toFloat() / oh))
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, contentDescription = "Cancel editing", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = ::undo, enabled = history.canUndo) {
                Icon(
                    Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo",
                    tint = if (history.canUndo) Color.White else Color.White.copy(alpha = 0.3f),
                )
            }
            IconButton(
                onClick = {
                    if (saving) return@IconButton
                    editing?.let { finishEditing(it.text) }
                    saving = true
                    scope.launch {
                        runCatching { exportEdited(context, src, mosaic, state) }
                            .onSuccess(onDone)
                            .onFailure { saving = false; message = "Couldn't save image" }
                    }
                },
            ) {
                if (saving) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Outlined.Check, contentDescription = "Done", tint = Color.White)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewSize = it }
                .pointerInput(tool) {
                    when (tool) {
                        EditorTool.Draw, EditorTool.Blur -> detectDragGestures(
                            onDragStart = { o ->
                                val blur = tool == EditorTool.Blur
                                val s = EditStroke(
                                    listOf(toSource(o)),
                                    sliderColor(colorPos).toArgb(),
                                    if (blur) 0.03f + 0.1f * widthPos else 0.004f + 0.03f * widthPos,
                                    if (blur) BrushType.Pen else brush,
                                )
                                state = if (blur) state.copy(blurStrokes = state.blurStrokes + s) else state.copy(strokes = state.strokes + s)
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                val p = toSource(change.position)
                                state = if (tool == EditorTool.Blur) {
                                    state.copy(blurStrokes = state.blurStrokes.dropLast(1) + state.blurStrokes.last().let { it.copy(points = it.points + p) })
                                } else {
                                    state.copy(strokes = state.strokes.dropLast(1) + state.strokes.last().let { it.copy(points = it.points + p) })
                                }
                            },
                            onDragEnd = { commit() },
                            onDragCancel = { commit() },
                        )

                        EditorTool.Crop -> awaitEachGesture {
                            val down = awaitFirstDown()
                            val g = geometry()
                            val r = state.crop
                            val corners = mapOf(
                                Corner.TopLeft to Vec2(r.left, r.top), Corner.TopRight to Vec2(r.right, r.top),
                                Corner.BottomLeft to Vec2(r.left, r.bottom), Corner.BottomRight to Vec2(r.right, r.bottom),
                            ).mapValues { orientedToScreen(it.value, g.vp, NRect.Full, g.ow, g.oh).let { s -> Offset(s.x, s.y) } }
                            val corner = corners.entries.filter { (it.value - down.position).getDistance() < cornerTouchPx }
                                .minByOrNull { (it.value - down.position).getDistance() }?.key
                            val tl = corners.getValue(Corner.TopLeft)
                            val br = corners.getValue(Corner.BottomRight)
                            val inside = down.position.x in tl.x..br.x && down.position.y in tl.y..br.y
                            if (corner == null && !inside) return@awaitEachGesture
                            val aspect = Aspects[aspectIndex].first
                            drag(down.id) { change ->
                                val d = change.positionChange()
                                change.consume()
                                val dx = d.x / (g.ow * g.vp.scale)
                                val dy = d.y / (g.oh * g.vp.scale)
                                state = state.copy(
                                    crop = if (corner != null) {
                                        dragCorner(state.crop, corner, dx, dy, aspect, g.ow.toFloat() / g.oh)
                                    } else {
                                        moveRect(state.crop, dx, dy)
                                    },
                                )
                            }
                            commit()
                        }

                        EditorTool.Text, EditorTool.Sticker -> awaitEachGesture {
                            val down = awaitFirstDown()
                            val hit = hitTest(down.position)
                            var moved = false
                            var panTotal = Offset.Zero
                            do {
                                val event = awaitPointerEvent()
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()
                                val rotation = event.calculateRotation()
                                panTotal += pan
                                if (!moved && (panTotal.getDistance() > viewConfiguration.touchSlop || zoom != 1f || rotation != 0f)) moved = true
                                if (hit != null && moved) {
                                    updateText(hit) {
                                        it.copy(
                                            center = toSource(toScreen(it.center) + pan),
                                            sizeFrac = (it.sizeFrac * zoom).coerceIn(0.02f, 1.5f),
                                            rotation = it.rotation + rotation,
                                        )
                                    }
                                    event.changes.forEach { c -> if (c.positionChanged()) c.consume() }
                                }
                            } while (event.changes.any { it.pressed })
                            if (hit != null) selectedId = hit
                            if (moved) {
                                if (hit != null) commit()
                            } else if (tool == EditorTool.Text) {
                                val item = hit?.let { id -> state.texts.first { it.id == id } }
                                editing = when {
                                    item != null && !item.sticker -> item
                                    item == null -> EditText(
                                        id = System.nanoTime(), text = "", center = toSource(down.position),
                                        sizeFrac = 0.08f, color = sliderColor(colorPos).toArgb(),
                                    )
                                    else -> null
                                }
                            } else if (hit == null) {
                                showStickers = true
                            }
                        }

                        EditorTool.None -> Unit
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                if (viewSize == IntSize.Zero) return@Canvas
                val g = geometry()
                val shown = editing?.let { e -> state.copy(texts = state.texts.filter { it.id != e.id }) } ?: state
                val m = buildMatrix(src.width, src.height, shown, g.crop, g.vp)
                val clip = RectF(
                    g.vp.offsetX, g.vp.offsetY,
                    g.vp.offsetX + g.crop.width * g.ow * g.vp.scale, g.vp.offsetY + g.crop.height * g.oh * g.vp.scale,
                )
                drawIntoCanvas { drawEdits(it.nativeCanvas, src, mosaic, shown, m, clip, g.vp.scale) }
                if (tool == EditorTool.Crop) drawCropOverlay(g, state.crop)
            }
            if ((tool == EditorTool.Draw || tool == EditorTool.Text) && editing == null) {
                ColorSlider(
                    colorPos, ::onColor, onFinished = { if (editing == null) commit() },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                )
            }
            message?.let {
                Text(
                    it, color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .clip(RoundedCornerShape(50)).background(Color(0xCC303133)).padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (detecting) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            when (tool) {
                EditorTool.Crop -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = {
                        val (t, f, c) = rotateCw(state.turns, state.flipH, state.crop)
                        state = state.copy(turns = t, flipH = f, crop = c)
                        applyAspect()
                        commit()
                    }) { Icon(Icons.Outlined.RotateRight, contentDescription = "Rotate", tint = Color.White) }
                    IconButton(onClick = {
                        val (t, f, c) = flipDisplayedH(state.turns, state.flipH, state.crop)
                        state = state.copy(turns = t, flipH = f, crop = c)
                        commit()
                    }) { Icon(Icons.Outlined.Flip, contentDescription = "Flip horizontally", tint = Color.White) }
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).clickable {
                            aspectIndex = (aspectIndex + 1) % Aspects.size
                            applyAspect()
                            commit()
                        }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.AspectRatio, contentDescription = "Aspect ratio", tint = Color.White)
                        Spacer(Modifier.width(6.dp))
                        Text(Aspects[aspectIndex].second, color = Color.White, fontSize = 14.sp)
                    }
                    IconButton(onClick = {
                        aspectIndex = 0
                        state = state.copy(turns = 0, flipH = false, crop = NRect.Full)
                        commit()
                    }) { Icon(Icons.Outlined.RestartAlt, contentDescription = "Reset crop", tint = Color.White) }
                }

                EditorTool.Draw -> Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        BrushType.entries.forEach { b ->
                            val icon = when (b) {
                                BrushType.Pen -> Icons.Outlined.Create
                                BrushType.Marker -> Icons.Outlined.Brush
                                BrushType.Highlighter -> Icons.Outlined.BorderColor
                            }
                            ToolButton(icon, b.name, selected = brush == b) { brush = b }
                        }
                    }
                    WidthSlider(widthPos) { widthPos = it }
                }

                EditorTool.Blur -> Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Blur faces", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        Switch(checked = state.faceRects.isNotEmpty(), onCheckedChange = ::setFaceBlur, enabled = !detecting)
                    }
                    Text("Draw anywhere to blur", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    WidthSlider(widthPos) { widthPos = it }
                }

                EditorTool.Text, EditorTool.Sticker -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    val selected = state.texts.firstOrNull { it.id == selectedId }
                    if (tool == EditorTool.Text) {
                        Text("Tap to add text", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.align(Alignment.CenterVertically))
                        if (selected != null && !selected.sticker) {
                            ToolButton(Icons.Outlined.FontDownload, "Text style", selected = selected.highlighted) {
                                updateText(selected.id) { it.copy(highlighted = !it.highlighted) }
                                commit()
                            }
                        }
                    } else {
                        ToolButton(Icons.Outlined.EmojiEmotions, "Add sticker", selected = false) { showStickers = true }
                    }
                    if (selected != null) {
                        IconButton(onClick = {
                            state = state.copy(texts = state.texts.filter { it.id != selected.id })
                            selectedId = null
                            commit()
                        }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color.White) }
                    }
                }

                EditorTool.None -> Unit
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf(
                    EditorTool.Crop to (Icons.Outlined.Crop to "Crop and rotate"),
                    EditorTool.Draw to (Icons.Outlined.Brush to "Draw"),
                    EditorTool.Text to (Icons.Outlined.TextFields to "Add text"),
                    EditorTool.Sticker to (Icons.Outlined.EmojiEmotions to "Stickers"),
                    EditorTool.Blur to (Icons.Outlined.BlurOn to "Blur"),
                ).forEach { (t, v) ->
                    ToolButton(v.first, v.second, selected = tool == t) {
                        tool = if (tool == t) EditorTool.None else t
                        showStickers = tool == EditorTool.Sticker && state.texts.none { it.sticker }
                        selectedId = null
                    }
                }
            }
        }
    }

    editing?.let { et -> TextEditOverlay(et, onToggleStyle = { editing = et.copy(highlighted = !et.highlighted) }, onDone = ::finishEditing) }
    if (editing != null) {
        Box(Modifier.fillMaxSize()) {
            ColorSlider(colorPos, ::onColor, onFinished = {}, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))
        }
    }
    if (showStickers) {
        StickerPicker(onDismiss = { showStickers = false }) { emoji ->
            showStickers = false
            val center = toSource(Offset(viewSize.width / 2f, viewSize.height / 2f))
            val item = EditText(id = System.nanoTime(), text = emoji, center = center, sizeFrac = 0.2f, sticker = true)
            state = state.copy(texts = state.texts + item)
            selectedId = item.id
            commit()
        }
    }
}

private fun DrawScope.drawCropOverlay(g: Geo, crop: NRect) {
    val tl = orientedToScreen(Vec2(crop.left, crop.top), g.vp, NRect.Full, g.ow, g.oh)
    val br = orientedToScreen(Vec2(crop.right, crop.bottom), g.vp, NRect.Full, g.ow, g.oh)
    val dim = Color(0x99000000)
    drawRect(dim, Offset.Zero, Size(size.width, tl.y))
    drawRect(dim, Offset(0f, br.y), Size(size.width, size.height - br.y))
    drawRect(dim, Offset(0f, tl.y), Size(tl.x, br.y - tl.y))
    drawRect(dim, Offset(br.x, tl.y), Size(size.width - br.x, br.y - tl.y))
    val w = br.x - tl.x
    val h = br.y - tl.y
    drawRect(Color.White, Offset(tl.x, tl.y), Size(w, h), style = Stroke(1.dp.toPx()))
    for (i in 1..2) {
        drawLine(Color.White.copy(alpha = 0.5f), Offset(tl.x + w * i / 3, tl.y), Offset(tl.x + w * i / 3, br.y), 1f)
        drawLine(Color.White.copy(alpha = 0.5f), Offset(tl.x, tl.y + h * i / 3), Offset(br.x, tl.y + h * i / 3), 1f)
    }
    val len = 20.dp.toPx()
    val sw = 4.dp.toPx()
    listOf(tl.x to tl.y, br.x to tl.y, tl.x to br.y, br.x to br.y).forEach { (x, y) ->
        val sx = if (x == tl.x) 1f else -1f
        val sy = if (y == tl.y) 1f else -1f
        drawLine(Color.White, Offset(x, y), Offset(x + sx * len, y), sw)
        drawLine(Color.White, Offset(x, y), Offset(x, y + sy * len), sw)
    }
}

@Composable
private fun ToolButton(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.clip(CircleShape).background(if (selected) Color.White else Color.Transparent),
    ) { Icon(icon, contentDescription = description, tint = if (selected) Color.Black else Color.White) }
}

@Composable
private fun WidthSlider(value: Float, onChange: (Float) -> Unit) {
    Slider(
        value = value, onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF)),
    )
}

/** Signal-like vertical colour picker: white → hues → black. */
@Composable
private fun ColorSlider(pos: Float, onChange: (Float) -> Unit, onFinished: () -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    Box(
        modifier.width(36.dp).height(240.dp).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                change((down.position.y / size.height).coerceIn(0f, 1f))
                drag(down.id) { c ->
                    c.consume()
                    change((c.position.y / size.height).coerceIn(0f, 1f))
                }
                finished()
            }
        },
    ) {
        Box(
            Modifier.align(Alignment.Center).width(8.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(SliderStops)),
        )
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, (pos * 240.dp.toPx() - 12.dp.toPx()).roundToInt()) }
                .size(24.dp).clip(CircleShape).background(sliderColor(pos)).border(2.dp, Color.White, CircleShape),
        )
    }
}

@Composable
private fun TextEditOverlay(item: EditText, onToggleStyle: () -> Unit, onDone: (String) -> Unit) {
    var value by remember(item.id) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(item.id) { focus.requestFocus() }
    BackHandler { onDone(value.text) }
    val color = Color(item.color)
    val textColor = if (item.highlighted) (if (color.isDarkish()) Color.White else Color.Black) else color
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = { onDone(value.text) }, indication = null, interactionSource = null)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolButton(Icons.Outlined.FontDownload, "Text style", selected = item.highlighted, onClick = onToggleStyle)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onDone(value.text) }) { Icon(Icons.Outlined.Check, contentDescription = "Done", tint = Color.White) }
        }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            textStyle = TextStyle(color = textColor, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Color.White),
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp).focusRequester(focus)
                .then(if (item.highlighted) Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 8.dp) else Modifier),
        )
    }
}

private fun Color.isDarkish() = (red * 0.299f + green * 0.587f + blue * 0.114f) < 0.6f

@Composable
private fun StickerPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onDismiss, indication = null, interactionSource = null)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(52.dp),
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 360.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Color(0xFF1B1B1D))
                .navigationBarsPadding().padding(8.dp),
        ) {
            items(Emoji) { e ->
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).clickable { onPick(e) },
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 30.sp) }
            }
        }
    }
}
