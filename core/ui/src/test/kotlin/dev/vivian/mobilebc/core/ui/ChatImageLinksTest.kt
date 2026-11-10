package dev.vivian.mobilebc.core.ui

import org.junit.Assert.*
import org.junit.Test

class ChatImageLinksTest {
    @Test fun extractsLikoParenthesesAndSignedLinksWithoutPunctuation() {
        assertEquals(listOf("https://litter.catbox.moe/abc.png", "https://example.com/a.JPG?token=abc&v=1"),
            chatImageLinks("(https://litter.catbox.moe/abc.png) 图片 https://example.com/a.JPG?token=abc&v=1。"))
    }
    @Test fun excludesPagesCredentialsAndNonNetworkUris() {
        assertEquals(emptyList<String>(), chatImageLinks("https://example.com/page file:///tmp/a.png content://local/a.jpg https://user:pass@example.com/a.gif"))
    }
    @Test fun preservesEscapedPathsAndDeduplicates() {
        assertEquals(listOf("https://i.ibb.co/id/a%20b.webp"), chatImageLinks("https://i.ibb.co/id/a%20b.webp https://i.ibb.co/id/a%20b.webp"))
    }
}
