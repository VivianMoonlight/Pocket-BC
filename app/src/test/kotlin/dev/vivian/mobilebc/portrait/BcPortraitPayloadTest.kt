package dev.vivian.mobilebc.portrait

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BcPortraitPayloadTest {
    @Test
    fun preservesDrawingPropertiesWithoutExposingAccountState() {
        val character = BcCharacterSnapshot(
            id = "42", memberNumber = 42, name = "Someone", displayName = "Someone", isSelf = true,
            activePose = listOf("Kneel", "BackBoxTie"),
            appearance = listOf(BcCharacterItem(
                group = "Cloth", name = "Dress", colorJson = "[\"#123456\",\"Default\"]",
                propertyJson = """{"TypeRecord":{"m":2,"s":0,"Fa":1},"Opacity":0.4,"OverridePriority":4,"Text":"\"</script>"}""",
                craftJson = """{"MemberNumber":99,"Description":"private crafting metadata"}""",
            )),
            accountName = "private-account", rawDataJson = """{"Password":"secret"}""",
            onlineSharedSettingsJson = """{"private":"settings"}""", inventoryData = "private-inventory",
        )
        val encoded = portraitPayload(character)
        val data = JSONObject(encoded)
        assertEquals(42, data.getInt("memberNumber"))
        assertEquals("Kneel", data.getJSONArray("activePose").getString(0))
        val item = data.getJSONArray("appearance").getJSONObject(0)
        assertEquals("#123456", item.getJSONArray("color").getString(0))
        assertEquals(2, item.getJSONObject("property").getJSONObject("TypeRecord").getInt("m"))
        assertEquals(0, item.getJSONObject("property").getJSONObject("TypeRecord").getInt("s"))
        assertEquals(1, item.getJSONObject("property").getJSONObject("TypeRecord").getInt("Fa"))
        assertEquals(4, item.getJSONObject("property").getInt("OverridePriority"))
        assertEquals("\"</script>", item.getJSONObject("property").getString("Text"))
        assertFalse(encoded.contains("secret"))
        assertFalse(encoded.contains("private"))
        assertFalse(item.has("craft"))
    }

    @Test(expected = org.json.JSONException::class)
    fun malformedItemPropertiesDoNotBecomeAnEmptyReplacement() {
        portraitPayload(BcCharacterSnapshot(
            id = "42", memberNumber = 42, name = "", displayName = "", isSelf = false,
            appearance = listOf(BcCharacterItem("Cloth", "Dress", propertyJson = "not json")),
        ))
    }
}
