package dev.vivian.mobilebc.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LzStringUtf16CodecTest {
    @Test
    fun matchesBondageClubLzStringFriendNamesFixture() {
        val plain = "[[2002,\"Alice\"],[3003,\"小白\"]]"
        val expectedCodeUnits = listOf(
            7032, 4896, 13256, 1696, 17448, 4141, 16566, 824, 2699,
            405, 22049, 19633, 29616, 15406, 20607, 7126, 11424, 32,
        )

        val compressed = LzStringUtf16Codec.compressToUtf16(plain)

        assertEquals(expectedCodeUnits, compressed.map(Char::code))
        assertEquals(plain, LzStringUtf16Codec.decompressFromUtf16(compressed))
    }

    @Test
    fun matchesBondageClubLzStringSubmissivesFixture() {
        val plain = "[4004,5005]"
        val expectedCodeUnits = listOf(7008, 22563, 3168, 13354, 24760, 5992, 32, 32)

        val compressed = LzStringUtf16Codec.compressToUtf16(plain)

        assertEquals(expectedCodeUnits, compressed.map(Char::code))
        assertEquals(plain, LzStringUtf16Codec.decompressFromUtf16(compressed))
    }

    @Test
    fun preservesLzStringNullAndEmptyCompatibility() {
        assertEquals("", LzStringUtf16Codec.compressToUtf16(null))
        assertEquals(listOf(8224, 32), LzStringUtf16Codec.compressToUtf16("").map(Char::code))
        assertEquals("", LzStringUtf16Codec.decompressFromUtf16(null))
        assertNull(LzStringUtf16Codec.decompressFromUtf16(""))
    }
}
