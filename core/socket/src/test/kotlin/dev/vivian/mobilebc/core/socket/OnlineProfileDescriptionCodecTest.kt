package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.core.protocol.LzStringUtf16Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineProfileDescriptionCodecTest {
    @Test
    fun nullAndMalformedCompressedDescriptionsKeepExistingFallback() {
        assertNull(null.decodeOnlineProfileDescription())
        assertEquals("", "\u256C".decodeOnlineProfileDescription())
        assertEquals("", "\u256C\u0000".decodeOnlineProfileDescription())
    }

    @Test
    fun plainAndCompressedDescriptionsShareTheDisplayLimit() {
        val description = "中文 profile ".repeat(1_000)
        val compressed = "\u256C" + LzStringUtf16Codec.compressToUtf16(description)

        assertEquals(description.take(10_000), description.decodeOnlineProfileDescription())
        assertEquals(description.take(10_000), compressed.decodeOnlineProfileDescription())
    }
}
