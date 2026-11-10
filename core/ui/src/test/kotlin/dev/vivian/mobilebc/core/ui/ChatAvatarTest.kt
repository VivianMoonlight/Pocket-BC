package dev.vivian.mobilebc.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatAvatarTest {
    @Test
    fun acceptsRemoteAvatarUrlsWithoutRequiringFileExtensions() {
        assertEquals("https://i.imgur.com/avatar?id=123", chatAvatarUrl(" https://i.imgur.com/avatar?id=123 "))
        assertEquals("https://i.ibb.co/a.png", chatAvatarUrl("http://i.ibb.co/a.png"))
        listOf("github.io", "gitlab.io", "ibb.co", "imgbb.com", "imgchest.com", "imgur.com", "postimg.cc", "hd-r.icu")
            .forEach { assertEquals("https://img.$it/a.gif", chatAvatarUrl("https://img.$it/a.gif")) }
    }

    @Test
    fun untrustedProfileCannotLoadLocalFilesOrContentProviders() {
        listOf(null, "", "file:///sdcard/avatar.png", "content://media/1", "data:image/png;base64,abc",
            "https:///missing-host", "https://user:password@imgur.com/a", "not a URL",
            "https://imgur.com.evil.example/a.png", "https://evilimgur.com/a.png").forEach {
            assertNull(chatAvatarUrl(it))
        }
    }
}
