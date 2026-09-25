package app.aino.mobile.feature.chat.media

import kotlin.math.max
import kotlin.math.min

/* Pure, Android-free math for the camera and image editor (unit-tested). */

enum class ShutterAction { Photo, Video }

/** Signal-style shutter: a short tap takes a photo, holding past the threshold records video. */
fun shutterAction(pressDurationMs: Long, holdThresholdMs: Long = 400): ShutterAction =
    if (pressDurationMs >= holdThresholdMs) ShutterAction.Video else ShutterAction.Photo

data class Vec2(val x: Float, val y: Float)

/** Rectangle in normalized (0..1) coordinates. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        val Full = NRect(0f, 0f, 1f, 1f)
    }
}

enum class Corner { TopLeft, TopRight, BottomLeft, BottomRight }

private fun turns(q: Int) = Math.floorMod(q, 4)

/** Pixel size of a w×h image after [quarterTurns] clockwise quarter turns. */
fun orientedSize(width: Int, height: Int, quarterTurns: Int): Pair<Int, Int> =
    if (turns(quarterTurns) % 2 == 1) height to width else width to height

/** Normalized source point → normalized displayed point: horizontal flip first, then clockwise quarter turns. */
fun orientPoint(p: Vec2, quarterTurns: Int, flipH: Boolean): Vec2 {
    var x = if (flipH) 1f - p.x else p.x
    var y = p.y
    repeat(turns(quarterTurns)) { val nx = 1f - y; y = x; x = nx }
    return Vec2(x, y)
}

/** Inverse of [orientPoint]. */
fun unorientPoint(p: Vec2, quarterTurns: Int, flipH: Boolean): Vec2 {
    var x = p.x
    var y = p.y
    repeat(turns(quarterTurns)) { val nx = y; y = 1f - x; x = nx }
    return Vec2(if (flipH) 1f - x else x, y)
}

/** Orientation (quarterTurns, flipH) after rotating the displayed image 90° clockwise, with the crop following it. */
fun rotateCw(quarterTurns: Int, flipH: Boolean, crop: NRect): Triple<Int, Boolean, NRect> =
    Triple(turns(quarterTurns + 1), flipH, NRect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right))

/** Orientation after mirroring the *displayed* image horizontally (mirror ∘ rot^r = rot^-r ∘ mirror). */
fun flipDisplayedH(quarterTurns: Int, flipH: Boolean, crop: NRect): Triple<Int, Boolean, NRect> =
    Triple(turns(-quarterTurns), !flipH, NRect(1f - crop.right, crop.top, 1f - crop.left, crop.bottom))

/** Keeps [r] inside the unit square with at least [minSize] per side. */
fun clampRect(r: NRect, minSize: Float = 0.1f): NRect {
    val w = r.width.coerceIn(minSize, 1f)
    val h = r.height.coerceIn(minSize, 1f)
    val l = r.left.coerceIn(0f, 1f - w)
    val t = r.top.coerceIn(0f, 1f - h)
    return NRect(l, t, l + w, t + h)
}

/** Translates [r] by (dx, dy), stopping at the image edges. */
fun moveRect(r: NRect, dx: Float, dy: Float): NRect {
    val l = (r.left + dx).coerceIn(0f, 1f - r.width)
    val t = (r.top + dy).coerceIn(0f, 1f - r.height)
    return NRect(l, t, l + r.width, t + r.height)
}

/**
 * Drags [corner] of [r] by (dx, dy), keeping the opposite corner anchored.
 * [aspect] is the locked pixel aspect (w/h) or null for free; [pxAspect] is the image's pixel width/height.
 */
fun dragCorner(r: NRect, corner: Corner, dx: Float, dy: Float, aspect: Float?, pxAspect: Float, minSize: Float = 0.1f): NRect {
    val leftSide = corner == Corner.TopLeft || corner == Corner.BottomLeft
    val topSide = corner == Corner.TopLeft || corner == Corner.TopRight
    val ax = if (leftSide) r.right else r.left
    val ay = if (topSide) r.bottom else r.top
    val mx = (if (leftSide) r.left else r.right) + dx
    val my = (if (topSide) r.top else r.bottom) + dy
    val maxW = if (leftSide) ax else 1f - ax
    val maxH = if (topSide) ay else 1f - ay
    var w = (if (leftSide) ax - mx else mx - ax).coerceIn(min(minSize, maxW), maxW)
    var h = (if (topSide) ay - my else my - ay).coerceIn(min(minSize, maxH), maxH)
    if (aspect != null) {
        val k = aspect / pxAspect
        if (w / h > k) w = h * k else h = w / k
        if (w < minSize) { w = minSize; h = w / k }
        if (h < minSize) { h = minSize; w = h * k }
        if (w > maxW) { w = maxW; h = w / k }
        if (h > maxH) { h = maxH; w = h * k }
    }
    val l = if (leftSide) ax - w else ax
    val t = if (topSide) ay - h else ay
    return NRect(l, t, l + w, t + h)
}

/** Largest rect of pixel [aspect] centred inside [r]. */
fun fitAspect(r: NRect, aspect: Float, pxAspect: Float): NRect {
    val k = aspect / pxAspect
    val w: Float
    val h: Float
    if (r.width / r.height > k) { h = r.height; w = h * k } else { w = r.width; h = w / k }
    val cx = (r.left + r.right) / 2f
    val cy = (r.top + r.bottom) / 2f
    return NRect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
}

/** How content of [contentW]×[contentH] px is fitted (centred) into a view with [pad] px margins. */
data class Viewport(val scale: Float, val offsetX: Float, val offsetY: Float)

fun fitViewport(contentW: Float, contentH: Float, viewW: Float, viewH: Float, pad: Float = 0f): Viewport {
    val aw = max(1f, viewW - 2 * pad)
    val ah = max(1f, viewH - 2 * pad)
    val s = min(aw / contentW, ah / contentH)
    return Viewport(s, (viewW - contentW * s) / 2f, (viewH - contentH * s) / 2f)
}

/** Screen px → normalized source point. [ow]/[oh] are oriented pixel dims, [crop] the visible oriented region. */
fun screenToSource(p: Vec2, vp: Viewport, crop: NRect, ow: Int, oh: Int, quarterTurns: Int, flipH: Boolean): Vec2 {
    val o = Vec2(crop.left + (p.x - vp.offsetX) / (vp.scale * ow), crop.top + (p.y - vp.offsetY) / (vp.scale * oh))
    return unorientPoint(o, quarterTurns, flipH)
}

fun sourceToScreen(p: Vec2, vp: Viewport, crop: NRect, ow: Int, oh: Int, quarterTurns: Int, flipH: Boolean): Vec2 =
    orientedToScreen(orientPoint(p, quarterTurns, flipH), vp, crop, ow, oh)

fun orientedToScreen(o: Vec2, vp: Viewport, crop: NRect, ow: Int, oh: Int): Vec2 =
    Vec2(vp.offsetX + (o.x - crop.left) * ow * vp.scale, vp.offsetY + (o.y - crop.top) * oh * vp.scale)

/** Immutable undo/redo stack of editor snapshots. */
data class EditorHistory<T>(val current: T, val past: List<T> = emptyList(), val future: List<T> = emptyList()) {
    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun push(next: T): EditorHistory<T> = if (next == current) this else EditorHistory(next, past + current, emptyList())
    fun undo(): EditorHistory<T> = if (!canUndo) this else EditorHistory(past.last(), past.dropLast(1), listOf(current) + future)
    fun redo(): EditorHistory<T> = if (!canRedo) this else EditorHistory(future.first(), past + current, future.drop(1))
}
