package app.aino.mobile.core.storage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageUsageTest {
    @Test fun clearsOnlyReDownloadableFoldersAndKeepsOutgoingMedia() {
        val root = Files.createTempDirectory("aino-cache").toFile()
        try {
            val download = File(root, "chat-media/files/a.pdf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(10)) }
            val outgoing = File(root, "chat-outgoing/b.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(20)) }
            val voice = File(root, "chat-media/voice.m4a").apply { writeBytes(ByteArray(5)) }
            assertEquals(35L, StorageUsage.sizeOf(root))
            StorageUsage.clearFolders(root, StorageUsage.CLEARABLE_OTHER)
            assertTrue(!download.exists())
            assertTrue(outgoing.exists())
            assertTrue(voice.exists())
            assertEquals(25L, StorageUsage.sizeOf(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
