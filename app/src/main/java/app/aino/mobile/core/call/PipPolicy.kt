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

    fun shouldEnterOnUserLeave(
        apiLevel: Int,
        deviceAdvertisesPip: Boolean,
        callActive: Boolean,
    ): Boolean = callActive && isSupported(apiLevel, deviceAdvertisesPip) &&
        !usesSystemAutoEnter(apiLevel)
}