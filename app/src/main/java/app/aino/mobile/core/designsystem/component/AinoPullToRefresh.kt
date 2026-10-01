package app.aino.mobile.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import kotlinx.coroutines.delay

/**
 * Pull-to-refresh whose indicator shows only while a refresh the *user*
 * pulled is running. Automatic reloads (open, resume, realtime, reconnect
 * catch-up) refresh silently over the content already on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AinoPullToRefreshBox(
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    var sawLoading by remember { mutableStateOf(false) }
    LaunchedEffect(loading, pulled) {
        if (!pulled) return@LaunchedEffect
        if (loading) sawLoading = true
        else if (sawLoading) { pulled = false; sawLoading = false }
        else { delay(PULL_START_GRACE_MS); if (!sawLoading) pulled = false }
    }
    PullToRefreshBox(
        isRefreshing = pulled && loading,
        onRefresh = { pulled = true; sawLoading = loading; onRefresh() },
        modifier = modifier,
        content = content,
    )
}

/** The only loading UI a screen shows: a small native spinner while nothing has loaded yet. */
@Composable
fun FirstLoadSpinner(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), color = LocalWebColors.current.primary, strokeWidth = 2.5.dp)
    }
}

private const val PULL_START_GRACE_MS = 1_500L
