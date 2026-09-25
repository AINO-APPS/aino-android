package app.aino.mobile.feature.chat.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class EditorTool { None, Crop, Draw, Text, Sticker, Blur }

internal enum class BrushType(val widthMul: Float, val alpha: Float) { Pen(1f, 1f), Marker(2.5f, 0.7f), Highlighter(5f, 0.4f) }

/** Points are normalized source-bitmap coordinates; width is a fraction of the bitmap's short edge. */
internal data class EditStroke(val points: List<Vec2>, val color: Int, val widthFrac: Float, val brush: BrushType)

internal data class EditText(
    val id: Long,
    val text: String,
    val center: Vec2,
    val sizeFrac: Float,
    val rotation: Float = 0f,
    val color: Int = Color.WHITE,
    val highlighted: Boolean = false,
    val sticker: Boolean = false,
)

/** One immutable snapshot of every edit; [EditorHistory] of these gives undo/redo. */
internal data class EditorState(
    val turns: Int = 0,
    val flipH: Boolean = false,
    val crop: NRect = NRect.Full,
    val strokes: List<EditStroke> = emptyList(),
    val blurStrokes: List<EditStroke> = emptyList(),
    val faceRects: List<NRect> = emptyList(),
    val texts: List<EditText> = emptyList(),
)

/** Source-bitmap px → target px: flip, quarter turns, crop offset, then viewport scale/offset. */
internal fun buildMatrix(w: Int, h: Int, st: EditorState, crop: NRect, vp: Viewport): Matrix {
    val m = Matrix()
    if (st.flipH) { m.postScale(-1f, 1f); m.postTranslate(w.toFloat(), 0f) }
    var cw = w.toFloat()
    var ch = h.toFloat()
    repeat(Math.floorMod(st.turns, 4)) {
        m.postRotate(90f)
        m.postTranslate(ch, 0f)
        val t = cw; cw = ch; ch = t
    }
    m.postTranslate(-crop.left * cw, -crop.top * ch)
    m.postScale(vp.scale, vp.scale)
    m.postTranslate(vp.offsetX, vp.offsetY)
    return m
}

private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

internal fun drawEdits(c: Canvas, src: Bitmap, mosaic: Bitmap, st: EditorState, m: Matrix, clip: RectF, scale: Float) {
    val w = src.width.toFloat()
    val h = src.height.toFloat()
    val minDim = min(w, h)
    c.save()
    c.clipRect(clip)
    c.save()
    c.concat(m)
    c.drawBitmap(src, 0f, 0f, bitmapPaint)
    if (st.blurStrokes.isNotEmpty() || st.faceRects.isNotEmpty()) {
        val layer = c.saveLayer(0f, 0f, w, h, null)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        st.faceRects.forEach { c.drawRect(it.left * w, it.top * h, it.right * w, it.bottom * h, mask) }
        st.blurStrokes.forEach { drawStroke(c, it, w, h, minDim, Color.BLACK, 1f) }
        val mosaicPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN); isFilterBitmap = false }
        c.drawBitmap(mosaic, null, RectF(0f, 0f, w, h), mosaicPaint)
        c.restoreToCount(layer)
    }
    st.strokes.forEach { drawStroke(c, it, w, h, minDim, it.color, it.brush.alpha) }
    c.restore()
    st.texts.forEach { drawText(c, it, m, w, h, minDim * scale) }
    c.restore()
}

private fun drawStroke(c: Canvas, s: EditStroke, w: Float, h: Float, minDim: Float, color: Int, alpha: Float) {
    if (s.points.isEmpty()) return
    val path = Path()
    path.moveTo(s.points[0].x * w, s.points[0].y * h)
    if (s.points.size == 1) path.lineTo(s.points[0].x * w + 0.1f, s.points[0].y * h)
    for (i in 1 until s.points.size) path.lineTo(s.points[i].x * w, s.points[i].y * h)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = s.widthFrac * minDim * s.brush.widthMul
        this.color = color
        this.alpha = (alpha * 255).roundToInt()
    }
    c.drawPath(path, paint)
}

private class TextLayout(val lines: List<String>, val paint: Paint, val halfW: Float, val halfH: Float, val lineH: Float)

private fun layoutText(t: EditText, sizePx: Float): TextLayout {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        textAlign = Paint.Align.CENTER
        typeface = if (t.sticker) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
    }
    val lines = t.text.split('\n')
    val lineH = sizePx * 1.2f
    val halfW = (lines.maxOfOrNull { paint.measureText(it) } ?: 0f) / 2f
    return TextLayout(lines, paint, halfW, lines.size * lineH / 2f, lineH)
}

private fun isDark(color: Int) = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000 < 150

private fun drawText(c: Canvas, t: EditText, m: Matrix, w: Float, h: Float, minDimPx: Float) {
    val pt = floatArrayOf(t.center.x * w, t.center.y * h)
    m.mapPoints(pt)
    val size = t.sizeFrac * minDimPx
    val l = layoutText(t, size)
    c.save()
    c.translate(pt[0], pt[1])
    c.rotate(t.rotation)
    if (t.highlighted && !t.sticker) {
        val pad = size * 0.3f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = t.color }
        c.drawRoundRect(-l.halfW - pad, -l.halfH - pad / 2, l.halfW + pad, l.halfH + pad / 2, pad, pad, bg)
        l.paint.color = if (isDark(t.color)) Color.WHITE else Color.BLACK
    } else {
        l.paint.color = t.color
        if (!t.sticker) l.paint.setShadowLayer(size * 0.08f, 0f, 0f, 0x66000000)
    }
    val fm = l.paint.fontMetrics
    l.lines.forEachIndexed { i, line ->
        val lineCenter = -l.halfH + l.lineH * (i + 0.5f)
        c.drawText(line, 0f, lineCenter - (fm.ascent + fm.descent) / 2f, l.paint)
    }
    c.restore()
}

/** Whether screen point ([px],[py]) falls on text/sticker [t] as drawn with [m]. */
internal fun hitText(t: EditText, px: Float, py: Float, m: Matrix, w: Int, h: Int, scale: Float, slop: Float): Boolean {
    val pt = floatArrayOf(t.center.x * w, t.center.y * h)
    m.mapPoints(pt)
    val l = layoutText(t, t.sizeFrac * min(w, h) * scale)
    val a = Math.toRadians(-t.rotation.toDouble())
    val dx = px - pt[0]
    val dy = py - pt[1]
    val rx = dx * cos(a) - dy * sin(a)
    val ry = dx * sin(a) + dy * cos(a)
    return kotlin.math.abs(rx) <= l.halfW + slop && kotlin.math.abs(ry) <= l.halfH + slop
}

internal fun makeMosaic(src: Bitmap): Bitmap =
    Bitmap.createScaledBitmap(src, max(1, src.width / 24), max(1, src.height / 24), true)

/** Decodes [uri] upright (EXIF-aware) with the long edge capped at [maxEdge]. */
internal suspend fun loadEditorBitmap(context: Context, uri: Uri, maxEdge: Int = 2048): Bitmap = withContext(Dispatchers.IO) {
    val cr = context.contentResolver
    val bmp = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val long = max(w, h)
            if (long > maxEdge) {
                val s = maxEdge.toFloat() / long
                decoder.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Unable to decode $uri")
        val orientation = runCatching {
            cr.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
        }
        val long = max(decoded.width, decoded.height)
        if (long > maxEdge) m.postScale(maxEdge.toFloat() / long, maxEdge.toFloat() / long)
        if (m.isIdentity) decoded else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
    }
    if (bmp.config != Bitmap.Config.ARGB_8888) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
}

/** Renders every edit at full (loaded) resolution and writes a JPEG into the chat-media cache. */
internal suspend fun exportEdited(context: Context, src: Bitmap, mosaic: Bitmap, st: EditorState): MediaSendItem =
    withContext(Dispatchers.Default) {
        val (ow, oh) = orientedSize(src.width, src.height, st.turns)
        val outW = max(1, (st.crop.width * ow).roundToInt())
        val outH = max(1, (st.crop.height * oh).roundToInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val m = buildMatrix(src.width, src.height, st, st.crop, Viewport(1f, 0f, 0f))
        drawEdits(canvas, src, mosaic, st, m, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), 1f)
        val file = newChatMediaFile(context, "edited", "jpg")
        withContext(Dispatchers.IO) { file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
        out.recycle()
        MediaSendItem(chatMediaUri(context, file), "image/jpeg", outW, outH)
    }

/** ML Kit face boxes as normalized source rects (padded a little so the mosaic covers hair/chin). */
internal suspend fun detectFaces(bitmap: Bitmap): List<NRect> = suspendCancellableCoroutine { cont ->
    val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build(),
    )
    val w = bitmap.width.toFloat()
    val h = bitmap.height.toFloat()
    detector.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { faces ->
            cont.resume(
                faces.map { f ->
                    val b = f.boundingBox
                    val px = b.width() * 0.1f
                    val py = b.height() * 0.1f
                    NRect(
                        ((b.left - px) / w).coerceIn(0f, 1f), ((b.top - py) / h).coerceIn(0f, 1f),
                        ((b.right + px) / w).coerceIn(0f, 1f), ((b.bottom + py) / h).coerceIn(0f, 1f),
                    )
                },
            )
        }
        .addOnFailureListener { cont.resume(emptyList()) }
        .addOnCompleteListener { detector.close() }
}
