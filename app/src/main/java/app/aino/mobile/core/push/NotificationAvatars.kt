package app.aino.mobile.core.push

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs

/**
 * Signal `FallbackAvatar` for notifications: initials on a per-contact colour.
 * Notifications never fall back to the launcher logo, which One UI renders as
 * an oversized square large icon.
 */
object NotificationAvatars {
    private const val SIZE_PX = 256

    /** Signal `AvatarColor` background / foreground pairs (A100–A210). */
    private val palette = listOf(
        0xFFE3E3FE to 0xFF3838F5, 0xFFDDE7FC to 0xFF1251D3, 0xFFD8E8F0 to 0xFF086DA0,
        0xFFCDE4CD to 0xFF067906, 0xFFEAE0F8 to 0xFF661AFF, 0xFFF5E3FE to 0xFF9F00F0,
        0xFFF6D8EC to 0xFFB8057C, 0xFFF5D7D7 to 0xFFBE0404, 0xFFFEF5D0 to 0xFF836B01,
        0xFFEAE6D5 to 0xFF7D6F40, 0xFFD2D2DC to 0xFF4F4F6D, 0xFFD7D7D9 to 0xFF5C5C5C,
    ).map { (bg, fg) -> bg.toInt() to fg.toInt() }

    private val cache = object : LinkedHashMap<String, Bitmap>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 48
    }

    /**
     * Circular initials avatar for [name]; [key] (sender id / conversation id)
     * keeps the colour stable across renames. With [adaptive] the square is
     * filled edge-to-edge and the glyph kept inside the adaptive-icon safe zone.
     */
    @Synchronized
    fun fallback(name: String, key: String = name, adaptive: Boolean = false): Bitmap =
        cache.getOrPut("$key|$name|$adaptive") { render(name, key, adaptive) }

    /** Circle-crops any bitmap (e.g. the app logo) so it never renders as a full-bleed square. */
    fun circle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(android.graphics.Matrix().apply {
                    setTranslate(-(src.width - size) / 2f, -(src.height - size) / 2f)
                })
            }
        }
        Canvas(out).drawOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        return out
    }

    private fun render(name: String, key: String, adaptive: Boolean): Bitmap {
        val (bg, fg) = colorsFor(key)
        val out = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val s = SIZE_PX.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg }
        if (adaptive) canvas.drawRect(0f, 0f, s, s, fill) else canvas.drawOval(RectF(0f, 0f, s, s), fill)
        // Adaptive masks show only the inner 72/108 of the square.
        val scale = if (adaptive) 72f / 108f else 1f
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg }
        val initials = initials(name)
        if (initials.isEmpty()) {
            val r = s * scale
            canvas.drawCircle(s / 2, s / 2 - r * .12f, r * .16f, glyph)
            canvas.drawOval(RectF(s / 2 - r * .28f, s / 2 + r * .1f, s / 2 + r * .28f, s / 2 + r * .5f), glyph)
        } else {
            glyph.textAlign = Paint.Align.CENTER
            glyph.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            glyph.textSize = s * scale * (if (initials.length > 1) .36f else .42f)
            val y = s / 2 - (glyph.descent() + glyph.ascent()) / 2
            canvas.drawText(initials, s / 2, y, glyph)
        }
        return out
    }

    internal fun colorsFor(key: String): Pair<Int, Int> = palette[abs(key.hashCode() % palette.size)]

    /** Signal `NameUtil.getAbbreviation`: first letters of the first and last words. */
    internal fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter(String::isNotEmpty).mapNotNull { word ->
            val cp = word.codePointAt(0)
            if (Character.isLetterOrDigit(cp)) String(Character.toChars(cp)).uppercase() else null
        }
        return when {
            words.isEmpty() -> ""
            words.size == 1 -> words.first()
            else -> words.first() + words.last()
        }
    }
}
