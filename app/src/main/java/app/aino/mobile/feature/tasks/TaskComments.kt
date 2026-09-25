package app.aino.mobile.feature.tasks

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.designsystem.component.UserAvatar
import app.aino.mobile.core.designsystem.tokens.LocalWebColors
import app.aino.mobile.core.designsystem.tokens.rem
import app.aino.mobile.core.media.resolveServerMediaUrl
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** `utils.getAvatarUrl`: bare file names live under `/uploads/avatars/`. */
internal fun avatarPath(avatar: String?): String? = avatar?.takeIf(String::isNotBlank)?.let { if (it.startsWith("/")) it else "/uploads/avatars/$it" }

private const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

/**
 * `CommentSection` (task detail) / `InlineCommentPanel` (card 💬): the list with
 * author, time, "(edited)", body, attachment, own Edit/Delete; then the
 * composer with @mentions, a paperclip and send.
 */
@Composable
internal fun CommentSection(
    taskId: Long,
    comments: List<TaskComment>,
    loading: Boolean,
    currentUserId: Long?,
    users: List<AssignableUser>,
    viewModel: TaskViewModel,
    placeholder: String = "Write a comment… (type @ to mention someone)",
) {
    val colors = LocalWebColors.current
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editText by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (loading) WebSpinner()
        if (comments.isEmpty() && !loading) {
            Text(
                "No comments yet. Start the conversation!",
                color = colors.textMuted,
                fontSize = 0.85.rem,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        comments.forEach { c ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UserAvatar(c.author(), avatarPath(c.avatar), 22.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(c.author(), color = colors.text, fontWeight = FontWeight.Bold, fontSize = 0.8.rem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(6.dp))
                    Text(formatLocaleString(c.createdAt), color = colors.textMuted, fontSize = 0.7.rem, maxLines = 1)
                    if (c.updatedAt != null && c.updatedAt != c.createdAt) {
                        Spacer(Modifier.width(4.dp))
                        Text("(edited)", color = colors.textMuted, fontSize = 0.7.rem)
                    }
                }
                if (editingId == c.id) {
                    WebTextField(editText, { editText = it }, "Edit comment...", singleLine = false, minLines = 3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WebButton("Save", {
                            viewModel.editComment(taskId, c.id, editText) { editingId = null; editText = "" }
                        }, style = BtnStyle.Primary, small = true, enabled = editText.isNotBlank())
                        WebButton("Cancel", { editingId = null }, small = true)
                    }
                } else {
                    c.content?.takeIf(String::isNotBlank)?.let { TaskHtml(it, colors.text, 0.85.rem) }
                    CommentAttachment(c)
                    if (c.userId != null && c.userId == currentUserId) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!c.content.isNullOrBlank()) {
                                Text("Edit", color = colors.textMuted, fontSize = 0.75.rem, modifier = Modifier.clickable {
                                    editingId = c.id
                                    editText = stripHtml(c.content)
                                })
                            }
                            Text("Delete", color = colors.textMuted, fontSize = 0.75.rem, modifier = Modifier.clickable { viewModel.deleteComment(taskId, c.id) })
                        }
                    }
                }
            }
        }
        CommentComposer(taskId, users, viewModel, placeholder)
    }
}

/** `CommentAttachment`: images inline, other files as a tappable chip. */
@Composable
private fun CommentAttachment(c: TaskComment) {
    val path = c.fileUrl?.takeIf(String::isNotBlank) ?: return
    val colors = LocalWebColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val url = resolveServerMediaUrl(path)
    val isImage = c.fileType?.startsWith("image/") == true ||
        Regex("\\.(png|jpe?g|gif|webp|bmp)$", RegexOption.IGNORE_CASE).containsMatchIn(c.fileName ?: path)
    if (isImage) {
        val request = remember(url) { ImageRequest.Builder(context).data(url).build() }
        AsyncImage(
            model = request,
            imageLoader = AppContainer.get(context).imageLoader,
            contentDescription = c.fileName,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { scope.launch { openTaskFile(context, url, c.fileName, c.fileType) } },
        )
    } else {
        Row(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surfaceHover)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .clickable { scope.launch { openTaskFile(context, url, c.fileName, c.fileType) } }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.AttachFile, null, Modifier.size(14.dp), tint = colors.textSecondary)
            Spacer(Modifier.width(6.dp))
            Text(c.fileName ?: "Attachment", color = colors.primary, fontSize = 0.8.rem, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** MentionInput + paperclip + send. `@` opens a people list; a pick inserts `@Name `. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentComposer(taskId: Long, users: List<AssignableUser>, viewModel: TaskViewModel, placeholder: String) {
    val colors = LocalWebColors.current
    val context = LocalContext.current
    var text by remember(taskId) { mutableStateOf("") }
    var file by remember(taskId) { mutableStateOf<CommentFile?>(null) }
    val mentions = remember(taskId) { mutableStateMapOf<String, Long>() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        file = readCommentFile(context, uri)
    }
    val query = Regex("(?:^|\\s)@([^@\\n]{0,30})$").find(text)?.groupValues?.get(1)
    val suggestions = query?.let { q -> users.filter { it.display().contains(q.trim(), ignoreCase = true) || it.username.orEmpty().contains(q.trim(), ignoreCase = true) }.take(6) }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        file?.let { f ->
            Row(
                Modifier.background(colors.surfaceHover, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.AttachFile, null, Modifier.size(13.dp), tint = colors.textSecondary)
                Spacer(Modifier.width(4.dp))
                Text(f.name, color = colors.text, fontSize = 0.78.rem, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Outlined.Close, "Remove attachment", Modifier.size(13.dp).clickable { file = null }, tint = colors.textMuted)
            }
        }
        if (suggestions.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(colors.bgElevated, RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp))) {
                suggestions.forEach { user ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val name = user.display()
                            text = text.dropLast((query?.length ?: 0) + 1) + "@$name\u00A0"
                            mentions[name] = user.id
                        }.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UserAvatar(user.display(), avatarPath(user.avatar), 20.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(user.display(), color = colors.text, fontSize = 0.85.rem)
                        user.username?.let { Text("  @$it", color = colors.textMuted, fontSize = 0.75.rem) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            WebTextField(text, { text = it }, placeholder, modifier = Modifier.weight(1f), singleLine = false, minLines = 1)
            Box(
                Modifier.padding(start = 6.dp).size(36.dp).clip(CircleShape).clickable { picker.launch("*/*") },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AttachFile, "Attach a file", Modifier.size(18.dp), tint = colors.textSecondary) }
            val canSend = text.isNotBlank() || file != null
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (canSend) colors.primary else colors.primary.copy(alpha = 0.4f))
                    .clickable(enabled = canSend) {
                        viewModel.addComment(taskId, text.replace('\u00A0', ' '), file, mentions.toMap()) {
                            text = ""
                            file = null
                            mentions.clear()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) { Text("➤", color = Color.White, fontSize = 0.9.rem) }
        }
    }
}

private fun readCommentFile(context: Context, uri: Uri): CommentFile? {
    val resolver = context.contentResolver
    var name = "attachment"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            name = cursor.getString(0) ?: name
            size = if (cursor.isNull(1)) -1 else cursor.getLong(1)
        }
    }
    if (size > MAX_ATTACHMENT_BYTES) {
        Toast.makeText(context, "File is too large (max 10 MB)", Toast.LENGTH_SHORT).show()
        return null
    }
    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() ?: return null
    return CommentFile(name, resolver.getType(uri) ?: "application/octet-stream", bytes)
}

/** Downloads through the authenticated client, then ACTION_VIEW. */
internal suspend fun openTaskFile(context: Context, url: String, fileName: String?, mimeType: String?) =
    app.aino.mobile.core.media.openAuthenticatedFile(context, url, fileName, mimeType)
