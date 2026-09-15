package app.aino.mobile.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMultipartTest {
    @Test
    fun mirrorsServerMimeAndSizeRules() {
        assertNull(validateChatUpload("photo.jpg", "image/jpeg", 10))
        assertNull(validateChatUpload("report.pdf", "application/pdf", MAX_CHAT_FILE_BYTES))
        assertEquals("File type not allowed", validateChatUpload("script.js", "application/javascript", 10))
        assertEquals("Files must be 25 MB or smaller", validateChatUpload("photo.jpg", "image/jpeg", MAX_CHAT_FILE_BYTES + 1))
        assertEquals("The selected file is empty", validateChatUpload("photo.jpg", "image/jpeg", 0))
    }

    @Test
    fun buildsAValidMultipartFileAndOptionalCaption() {
        val payload = buildChatMultipart(
            ChatUpload("report.pdf", "application/pdf", byteArrayOf(1, 2, 3), "Please review"),
            "aino-boundary-123",
        )
        val text = payload.body.toString(Charsets.ISO_8859_1)
        assertEquals("multipart/form-data; boundary=aino-boundary-123", payload.contentType)
        assertTrue(text.contains("name=\"content\""))
        assertTrue(text.contains("Please review"))
        assertTrue(text.contains("name=\"file\"; filename=\"report.pdf\""))
        assertTrue(text.contains("Content-Type: application/pdf"))
        assertTrue(text.endsWith("--aino-boundary-123--\r\n"))
    }

    @Test
    fun sanitizesUnsafeDisplayFilename() {
        val payload = buildChatMultipart(
            ChatUpload("../bad\r\nname.pdf", "application/pdf", byteArrayOf(1)),
            "aino-boundary-123",
        )
        val text = payload.body.toString(Charsets.ISO_8859_1)
        assertTrue(!text.contains("../bad"))
        assertTrue(!text.contains("name.pdf\r\nContent-Disposition"))
    }
}