package app.aino.mobile.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/** A ready-to-upload avatar: always a centre-cropped square JPEG. */
data class PreparedAvatar(val fileName: String, val mimeType: String, val bytes: ByteArray)

/** Largest edge sent for a group photo; avatars render at most ~120 dp. */
const val GROUP_AVATAR_EDGE_PX = 640

/** Side and offsets of the centred square crop of a [width]×[height] image. */
internal fun centreSquare(width: Int, height: Int): Triple<Int, Int, Int> {
    val side = minOf(width, height)
    return Triple(side, (width - side) / 2, (height - side) / 2)
}

/** The rotate/flip that displays a bitmap with EXIF [orientation] upright. */
internal fun exifMatrix(orientation: Int): Matrix = Matrix().apply {
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
    }
}

/** Power-of-two decode sample keeping the short edge at or above [edge]. */
internal fun avatarSampleSize(width: Int, height: Int, edge: Int): Int {
    var sample = 1
    while (minOf(width, height) / (sample * 2) >= edge) sample *= 2
    return sample
}

/**
 * Decodes [uri] (any format the platform reads, including HEIC), centre-crops
 * it to a square, scales it to at most [edge] px and encodes JPEG. Returns null
 * when the image cannot be read. Blocking; call off the main thread.
 */
fun prepareSquareAvatar(context: Context, uri: Uri, edge: Int = GROUP_AVATAR_EDGE_PX): PreparedAvatar? {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val raw = resolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = avatarSampleSize(bounds.outWidth, bounds.outHeight, edge) })
    } ?: return null
    // Camera JPEGs store pixels sideways and rely on the EXIF tag, which re-encoding drops.
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = exifMatrix(orientation)
    val decoded = if (matrix.isIdentity) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
    val (side, x, y) = centreSquare(decoded.width, decoded.height)
    val square = Bitmap.createBitmap(decoded, x, y, side, side)
    val scaled = if (side > edge) Bitmap.createScaledBitmap(square, edge, edge, true) else square
    val out = ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
    return PreparedAvatar("group.jpg", "image/jpeg", out.toByteArray())
}
