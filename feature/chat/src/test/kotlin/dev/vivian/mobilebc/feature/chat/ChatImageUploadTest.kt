package dev.vivian.mobilebc.feature.chat

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ChatImageUploadTest {
    @Test fun parsesDirectLinksForEveryHost() {
        assertEquals("https://litter.catbox.moe/a.png", parseImageUploadResponse(ChatImageHost.Litterbox, "https://litter.catbox.moe/a.png\n"))
        assertEquals("https://files.catbox.moe/a.gif", parseImageUploadResponse(ChatImageHost.Catbox, "https://files.catbox.moe/a.gif"))
        assertEquals("https://tmpfiles.org/dl/12/a.png", parseImageUploadResponse(ChatImageHost.TmpFiles, """{"status":"success","data":{"url":"http://tmpfiles.org/12/a.png"}}"""))
        assertEquals("https://i.ibb.co/id/a.jpg", parseImageUploadResponse(ChatImageHost.ImgBB, """{"success":true,"data":{"url":"https://i.ibb.co/id/a.jpg","url_viewer":"https://ibb.co/id"}}"""))
        assertEquals("https://i.imgur.com/id.jpg", parseImageUploadResponse(ChatImageHost.Imgur, """{"success":true,"data":{"link":"https://i.imgur.com/id.jpg"}}"""))
    }
    @Test fun rejectsErrorsAndUnexpectedResponseHosts() {
        listOf("https://files.catbox.moe.evil.com/a.jpg", "https://user@files.catbox.moe/a.jpg", "javascript:alert(1)", "Error: too large").forEach { url ->
            assertThrows(IOException::class.java) { hostedImageUrl(ChatImageHost.Catbox, url) }
        }
        assertThrows(IOException::class.java) { parseImageUploadResponse(ChatImageHost.Imgur, """{"success":false,"data":{"error":"rate limit"}}""") }
    }
    @Test fun tmpFilesDirectLinkIsNotRewrittenTwice() {
        assertEquals("https://tmpfiles.org/dl/12/a.png", hostedImageUrl(ChatImageHost.TmpFiles, "https://tmpfiles.org/dl/12/a.png"))
    }
    @Test fun limitsStreamsWithoutTrustingReportedFileSize() {
        val output = ByteArrayOutputStream()
        copyChatImage(ByteArrayInputStream(byteArrayOf(1, 2, 3)), output)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertThrows(InvalidImageException::class.java) { copyChatImage(ByteArrayInputStream(byteArrayOf()), output) }
        assertThrows(ImageTooLargeException::class.java) { copyChatImage(ByteArrayInputStream(ByteArray(ChatImageMaxBytes + 1)), ByteArrayOutputStream()) }
    }
}
