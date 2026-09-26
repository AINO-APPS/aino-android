package app.aino.mobile.feature.admin

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.compose.AsyncImage

/**
 * A branding logo: staged bytes, or the saved `/uploads/...` path loaded
 * through the shared authenticated image loader (uploads sit behind auth).
 */
@Composable
internal fun BrandLogoImage(logo: Any, height: Dp, maxWidth: Dp, contentDescription: String) {
    val context = LocalContext.current
    val model = remember(logo) { if (logo is String) resolveServerMediaUrl(logo) else logo }
    AsyncImage(
        model = model,
        imageLoader = AppContainer.get(context).imageLoader,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(height).widthIn(max = maxWidth),
    )
}

/** `.logoPreview`: the effective logo or "No logo set". */
@Composable
internal fun LogoPreview(logo: Any?) {
    val colors = LocalWebColors.current
    Box(
        Modifier.fillMaxWidth().height(72.dp).background(colors.surfaceHover, RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp)).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (logo == null) Text("No logo set", color = colors.textMuted, fontSize = 0.82.rem)
        else BrandLogoImage(logo, 56.dp, 220.dp, "Org logo")
    }
}

/** Reads a picked image; null when it is unreadable or over the 2 MB limit (checked before buffering). */
internal fun readStagedLogo(context: Context, uri: Uri): StagedLogo? {
    val resolver = context.contentResolver
    var name = "logo"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size > MAX_LOGO_BYTES) return null
    val bytes = resolver.openInputStream(uri)?.use { it.readAtMost(MAX_LOGO_BYTES + 1) } ?: return null
    if (bytes.size > MAX_LOGO_BYTES) return null
    return StagedLogo(name, resolver.getType(uri) ?: "image/png", bytes)
}

/** Reads at most [limit] bytes. */
internal fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(16 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(chunk, 0, minOf(chunk.size, limit - total))
        if (n < 0) break
        out.write(chunk, 0, n)
        total += n
    }
    return out.toByteArray()
}
