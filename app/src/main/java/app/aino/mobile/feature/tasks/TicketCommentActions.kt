package app.aino.mobile.feature.tasks

/** A picked comment attachment. */
class CommentFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** One session-owned thread, shared by Detail and the inline panel. */
data class CommentThread(val taskId: Long, val items: List<TaskComment> = emptyList(), val loading: Boolean = true)

interface TicketCommentActions {
    fun addComment(taskId: Long, text: String, file: CommentFile? = null, mentions: Map<String, Long> = emptyMap(), onDone: () -> Unit = {})
    fun editComment(taskId: Long, commentId: Long, text: String, onDone: () -> Unit = {})
    fun deleteComment(taskId: Long, commentId: Long)
}
