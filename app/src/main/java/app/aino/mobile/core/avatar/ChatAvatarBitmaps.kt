package app.aino.mobile.core.avatar

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorPath
import app.aino.mobile.core.designsystem.icons.HeroIcons
import kotlin.math.max

/** Chat-specific rendering; call/reaction fallback colours and initials are unchanged. */
object ChatAvatarBitmaps {
    private const val SIZE = 256

    fun user(name: String?, primary: Int, photo: Bitmap? = null): Bitmap =
        photo?.let { circle(it) } ?: square().also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary })
            initials(canvas, avatarInitials(name), RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()), Color.WHITE, SIZE * .38f)
        }

    fun circle(photo: Bitmap): Bitmap = square().also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.clipPath(Path().apply { addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Path.Direction.CW) })
        drawPhoto(canvas, photo, RectF(0f, 0f, SIZE.toFloat(), SIZE.toFloat()))
    }

    fun group(title: String?, metadata: GroupAvatarMetadata, key: String, primary: Int, photos: Map<String, Bitmap>): Bitmap {
        if (!metadata.photoUrl.isNullOrBlank()) return user(title, primary, photos[metadata.photoUrl])
        val shown = collageMembers(metadata.members)
        val (bg, fg) = groupAvatarColors(key)
        return square().also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.clipPath(Path().apply { addCircle(SIZE / 2f, SIZE / 2f, SIZE / 2f, Path.Direction.CW) })
            canvas.drawColor(bg)
            if (shown.size < 2) {
                canvas.save()
                canvas.translate(SIZE * .25f, SIZE * .25f)
                canvas.scale(SIZE * .5f / 24f, SIZE * .5f / 24f)
                val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fg; style = Paint.Style.STROKE; strokeWidth = 1.5f
                    strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
                }
                val vector = HeroIcons.UserGroup
                for (i in 0 until vector.root.size) {
                    val path = vector.root[i] as VectorPath
                    canvas.drawPath(PathParser().addPathNodes(path.pathData).toPath().asAndroidPath(), stroke)
                }
                canvas.restore()
            } else {
                // Same normalized layout and divider as GroupAvatar at a 48dp list size.
                val gap = SIZE / 48f
                groupAvatarTiles(shown.size).zip(shown).forEachIndexed { index, (tile, member) ->
                    val left = SIZE * tile.x + if (tile.x > 0) gap / 2 else 0f
                    val top = SIZE * tile.y + if (tile.y > 0) gap / 2 else 0f
                    val rect = RectF(left, top,
                        left + SIZE * tile.width - if (tile.width < 1) gap / 2 else 0f,
                        top + SIZE * tile.height - if (tile.height < 1) gap / 2 else 0f)
                    val (tileBg, tileFg) = groupAvatarColors(member.name ?: "$key-$index")
                    canvas.drawRect(rect, Paint().apply { color = tileBg })
                    initials(canvas, avatarInitials(member.name).take(1), rect, tileFg, SIZE * minOf(tile.width, tile.height) * .42f)
                    photos[member.avatarUrl]?.let { drawPhoto(canvas, it, rect) }
                }
            }
        }
    }

    private fun square() = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

    private fun initials(canvas: Canvas, text: String, rect: RectF, foreground: Int, fontSize: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground; textAlign = Paint.Align.CENTER; textSize = fontSize
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText(text, rect.centerX(), rect.centerY() - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun drawPhoto(canvas: Canvas, photo: Bitmap, rect: RectF) {
        val scale = max(rect.width() / photo.width, rect.height() / photo.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(photo, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(rect.centerX() - photo.width * scale / 2f, rect.centerY() - photo.height * scale / 2f)
                })
            }
        }
        canvas.drawRect(rect, paint)
    }
}
