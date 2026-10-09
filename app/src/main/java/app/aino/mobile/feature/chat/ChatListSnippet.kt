package app.aino.mobile.feature.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** Material icon for a list snippet kind. */
fun previewIcon(kind: PreviewKind): ImageVector = when (kind) {
    PreviewKind.Photo -> Icons.Outlined.Photo
    PreviewKind.Video -> Icons.Outlined.Videocam
    PreviewKind.Voice -> Icons.Outlined.Mic
    PreviewKind.Gif -> Icons.Outlined.Gif
    PreviewKind.File -> Icons.Outlined.Description
    PreviewKind.Poll -> Icons.Outlined.Poll
    PreviewKind.Deleted -> Icons.Outlined.Block
}

private const val KIND_ID = "kind"

/**
 * The chat list's second line (the receipt sits under the time instead):
 * "You:" / "Ana:" in groups, then a Material icon for media and the text, all
 * flowing as one two-line text so icons wrap and ellipsize with it.
 */
@Composable
fun ConversationSnippet(
    conversation: ChatConversation,
    currentUserId: Long?,
    unread: Boolean,
    textColor: Color,
    secondaryColor: Color,
    background: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    val snippet = conversation.previewSnippet()
    val mine = conversation.lastIsMine(currentUserId)
    val sender = when {
        !conversation.isGroup || conversation.lastDeleted != null -> null
        mine -> "You"
        else -> conversation.lastSenderName?.takeIf(String::isNotBlank)?.substringBefore(' ')
    }
    val color = if (unread) textColor else secondaryColor
    val text = buildAnnotatedString {
        if (sender != null) append("$sender: ")
        if (snippet.kind != null) { appendInlineContent(KIND_ID, snippet.kind.glyph); append(" ") }
        append(snippet.text)
    }
    val inline = buildMap {
        snippet.kind?.let { kind ->
            put(KIND_ID, InlineTextContent(Placeholder(1.1.em, 1.1.em, PlaceholderVerticalAlign.TextCenter)) {
                Icon(previewIcon(kind), null, Modifier.padding(top = 1.dp), tint = color)
            })
        }
    }
    Text(
        text,
        modifier,
        inlineContent = inline,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = color,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = if (unread) FontWeight.Medium else null,
        fontStyle = if (conversation.lastDeleted != null) FontStyle.Italic else null,
    )
}
