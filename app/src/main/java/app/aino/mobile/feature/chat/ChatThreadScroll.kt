package app.aino.mobile.feature.chat

/**
 * Scroll decisions for the bottom-anchored (reversed) thread list, kept free
 * of Compose so they can be unit tested. Index 0 is the newest row.
 */
sealed interface ThreadScroll {
    data object None : ThreadScroll

    /** Show the newest row; animate only a single live arrival. */
    data class ToNewest(val animate: Boolean) : ThreadScroll

    /** Bring [index] (the first unread row) to the top of the viewport. */
    data class ToUnread(val index: Int) : ThreadScroll
}

/** The newest row is on screen and the list rests at (or within [slopPx] of) the bottom. */
fun isAtThreadBottom(firstVisibleIndex: Int, firstVisibleOffsetPx: Int, slopPx: Int): Boolean =
    firstVisibleIndex == 0 && firstVisibleOffsetPx <= slopPx

/** Rows that appeared at the newest end since [previousNewestKey] was the newest. */
fun addedAtNewest(newestFirstKeys: List<String>, previousNewestKey: String?): Int {
    if (previousNewestKey == null) return newestFirstKeys.size
    val index = newestFirstKeys.indexOf(previousNewestKey)
    // The previous newest row is gone (a pending bubble swapped for its server row): one arrival.
    return if (index < 0) 1 else index
}

/**
 * Signal stick-to-bottom: new rows follow the conversation only while the
 * reader was at the bottom *before* they arrived (or sent them); otherwise
 * the reader's position is kept and the rows count as unseen.
 */
fun scrollOnNewItems(wasAtBottom: Boolean, newestIsMine: Boolean, added: Int): ThreadScroll = when {
    added <= 0 -> ThreadScroll.None
    wasAtBottom || newestIsMine -> ThreadScroll.ToNewest(animate = added == 1)
    else -> ThreadScroll.None
}

/**
 * Where a thread opens: a pending jump (pinned/search hit) wins and is handled
 * by its own effect; once the reader has scrolled nothing moves; otherwise the
 * first unread row, or the bottom when everything was read.
 */
fun initialThreadScroll(
    newestFirstKeys: List<String>,
    dividerKey: String?,
    jumpPending: Boolean,
    userScrolled: Boolean,
): ThreadScroll {
    if (newestFirstKeys.isEmpty() || jumpPending || userScrolled) return ThreadScroll.None
    val unread = dividerKey?.let(newestFirstKeys::indexOf) ?: -1
    return if (unread > 0) ThreadScroll.ToUnread(unread) else ThreadScroll.ToNewest(animate = false)
}
