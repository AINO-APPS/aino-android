package app.aino.mobile.feature.admin

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem

private const val EMAIL_PREVIEW_HEIGHT_DP = 420

/** `.previewWrap`: the draft subject and the server-rendered HTML. */
@Composable
internal fun EmailPreviewCard(subject: String, html: String, loading: Boolean) {
    val colors = LocalWebColors.current
    Column(
        Modifier.fillMaxWidth().background(colors.cardBg, RoundedCornerShape(12.dp))
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Live preview", color = colors.textSecondary, fontSize = 0.82.rem, fontWeight = FontWeight.SemiBold)
            if (loading) Text("  updating\u2026", color = colors.textMuted, fontSize = 0.75.rem)
        }
        Row {
            Text("Subject: ", color = colors.text, fontSize = 0.85.rem, fontWeight = FontWeight.Bold)
            Text(subject, color = colors.text, fontSize = 0.85.rem)
        }
        EmailHtmlView(html, Modifier.fillMaxWidth().height(EMAIL_PREVIEW_HEIGHT_DP.dp).clip(RoundedCornerShape(8.dp)))
        AdminHint(
            "Sample data is used to fill template variables. The actual emails sent to your team will use the real " +
                "recipient name, dates, task titles, etc.",
        )
    }
}

/**
 * The web's `dangerouslySetInnerHTML` pane, locked down: JavaScript,
 * file and content access are off, and link taps never navigate.
 */
@Composable
private fun EmailHtmlView(html: String, modifier: Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                }
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(null, emailPreviewDocument(html), "text/html", "UTF-8", null)
            }
        },
        onRelease = { it.destroy() },
        modifier = modifier,
    )
}

/** Wraps the fragment so a ~600 px email scales to the phone width. */
internal fun emailPreviewDocument(html: String): String =
    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>body{margin:0;padding:8px;background:#fff}img,table{max-width:100%!important;height:auto}</style>" +
        "</head><body>" + html + "</body></html>"
