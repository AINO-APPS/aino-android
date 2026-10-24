package app.aino.mobile.core.call

/** A platform-free ratio representation so PiP validation is unit-testable. */
data class PipRatio(val width: Int, val height: Int)

object PipPolicy {
    const val MIN_API = 26
    const val AUTO_ENTER_MIN_API = 31

    private const val MAX_RATIO = 2.39
    private const val MAX_WIDTH = 239
    private const val MAX_HEIGHT = 100

    /**
     * Android rejects PiP aspect ratios outside approximately 1:2.39–2.39:1.
     * Invalid dimensions intentionally become 1:1, matching the legacy module.
     */
    fun safeRatio(width: Int, height: Int): PipRatio {
        val safeWidth = if (width > 0) width else 1
        val safeHeight = if (height > 0) height else 1
        val ratio = safeWidth.toDouble() / safeHeight.toDouble()
        return when {
            ratio > MAX_RATIO -> PipRatio(MAX_WIDTH, MAX_HEIGHT)
            ratio < 1.0 / MAX_RATIO -> PipRatio(MAX_HEIGHT, MAX_WIDTH)
            else -> PipRatio(safeWidth, safeHeight)
        }
    }

    fun isSupported(apiLevel: Int, deviceAdvertisesPip: Boolean): Boolean =
        apiLevel >= MIN_API && deviceAdvertisesPip

    fun usesSystemAutoEnter(apiLevel: Int): Boolean = apiLevel >= AUTO_ENTER_MIN_API

        /**
     * Leaving the app with a live call must always ask for PiP, on every
     * supported API - system auto-enter is not a substitute.
     *
     * `setAutoEnterEnabled(true)` only runs on the transition to background. A
     * device that is otherwise already backgrounded when the call connects (a
     * call answered from the notification shade, a call screen drawn as an
     * overlay, or a PiP window that was dismissed) never gets auto-enter, so
     * gating this off on API 31+ left those users with no PiP window at all.
     * Reproduced on Android 15 by pressing Home during a live call:
     * `mLastReportedPictureInPictureMode=false`.
     */
    fun shouldEnterOnUserLeave(
        apiLevel: Int,
        deviceAdvertisesPip: Boolean,
        callActive: Boolean,
    ): Boolean = callActive && isSupported(apiLevel, deviceAdvertisesPip)
}