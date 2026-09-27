package app.aino.mobile.feature.chat

import android.content.Context
import android.content.res.Configuration

/**
 * Last settled soft-keyboard height, per orientation, so the emoji and
 * attachment drawers open at exactly the IME's size (Signal
 * `InsetAwareConstraintLayout` + `SignalStore.misc.keyboard*Height`).
 * Heights are in dp and exclude the navigation bar.
 */
object KeyboardHeightStore {
    private const val FILE = "aino_keyboard"
    private const val PORTRAIT = "keyboard_height_portrait"
    private const val LANDSCAPE = "keyboard_height_landscape"

    /** Signal `default_custom_keyboard_size`. */
    const val DEFAULT_DP = 260f
    /** Signal `min_custom_keyboard_top_margin_portrait`: room kept above the drawer. */
    const val MIN_TOP_MARGIN_DP = 170f

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(context: Context) =
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) LANDSCAPE else PORTRAIT

    fun save(context: Context, heightDp: Float) {
        if (heightDp <= 0f) return
        prefs(context).edit().putFloat(key(context), heightDp).apply()
    }

    fun get(context: Context): Float {
        val landscape = key(context) == LANDSCAPE
        val screenDp = context.resources.configuration.screenHeightDp.toFloat()
        return resolveKeyboardHeight(prefs(context).getFloat(key(context), 0f), screenDp, landscape)
    }
}

/** Saved height, or the default when missing/too small; portrait keeps [KeyboardHeightStore.MIN_TOP_MARGIN_DP] free above. */
internal fun resolveKeyboardHeight(savedDp: Float, screenHeightDp: Float, landscape: Boolean): Float {
    val height = if (savedDp > KeyboardHeightStore.DEFAULT_DP) savedDp else KeyboardHeightStore.DEFAULT_DP
    if (landscape || screenHeightDp <= 0f) return height
    val max = screenHeightDp - KeyboardHeightStore.MIN_TOP_MARGIN_DP
    return if (max > 0f) height.coerceAtMost(max) else height
}
