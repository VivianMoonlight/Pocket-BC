package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RazorCharacterProfileColorsTest {
    @Test
    fun readsColorAndLianSharedProfileSeparatelyFromBcDescription() {
        val snapshot = snapshot(
            """"LabelColor":"#12ab34","Description":"BC bio","OnlineSharedSettings":{"LCData":{"MessageSetting":{"Avatar":"https://example.com/a.png","Signature":"Lian signature"}}}""",
        )
        assertEquals("#12ab34", snapshot.labelColor)
        assertEquals("https://example.com/a.png", snapshot.avatarUrl)
        assertEquals("Lian signature", snapshot.signature)
        assertEquals("BC bio", snapshot.description)
    }

    @Test
    fun malformedPluginSettingsDoNotBreakCharacterParsing() {
        listOf("null", "[]", """{"LCData":"invalid"}""",
            """{"LCData":{"MessageSetting":{"Avatar":12,"Signature":{}}}}""").forEach { settings ->
            val snapshot = snapshot(""""OnlineSharedSettings":$settings""")
            assertNull(snapshot.avatarUrl)
            assertNull(snapshot.signature)
        }
    }

    private fun snapshot(fields: String) = checkNotNull(parseCharacterFromJson(
        JsonParser.parseString("""{"MemberNumber":123,"Name":"Alice",$fields}"""), Gson(),
    )).toBcCharacterSnapshot(isSelf = false, gson = Gson())
}
