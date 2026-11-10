package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class RazorCharacterLovershipTest {
    @Test
    fun missingAndNullLoverNamesPreserveRelationshipMetadata() {
        val snapshot = snapshot("""[{"MemberNumber":42,"Stage":2},{"Name":null,"MemberNumber":43},{"Name":"NPC-Love"}]""")
        assertEquals(listOf("Love"), snapshot.loverNames)
        assertEquals(3, snapshot.lovership.size)
        assertEquals(42, snapshot.lovership.first().memberNumber)
        assertEquals(2, snapshot.lovership.first().stage)
        assertEquals("", snapshot.lovership.first().name)
    }

    @Test
    fun unnamedLoversFallBackToLegacyLoverName() {
        assertEquals(listOf("Legacy"), snapshot("""[{"Name":null},{}]""").loverNames)
    }

    private fun snapshot(lovership: String) = checkNotNull(parseCharacterFromJson(
        JsonParser.parseString("""{"MemberNumber":123,"Name":"Alice","Lover":"NPC-Legacy","Lovership":$lovership}"""), Gson(),
    )).toBcCharacterSnapshot(isSelf = false, gson = Gson())
}
