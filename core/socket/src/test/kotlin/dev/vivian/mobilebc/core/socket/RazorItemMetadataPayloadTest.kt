package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class RazorItemMetadataPayloadTest {
    @Test
    fun addLockActionResolvesTheLockNameFromItemMiscAndKeepsTheWornItemGroup() {
        val payload = buildItemActionChatPayload(BcCharacterItemUpdateCommand(2002, "ItemArms", "Cuffs",
            publishAction = dev.vivian.mobilebc.core.model.BcItemActionMessage("ActionAddLock", "Cuffs", "MetalPadlock",
                "定制铐", nextAssetGroup = "ItemMisc")), 1001)
        val entries = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }
        val previous = entries.single { it.get("Tag")?.asString == "PrevAsset" }
        val next = entries.single { it.get("Tag")?.asString == "NextAsset" }
        assertEquals("ItemArms", previous.get("GroupName").asString)
        assertEquals("定制铐", previous.get("CraftName").asString)
        assertEquals("ItemMisc", next.get("GroupName").asString)
        assertEquals("MetalPadlock", next.get("AssetName").asString)
    }

    @Test
    fun actionDictionaryKeepsCustomNamesSeparateFromAssetIds() {
        val payload = buildItemActionChatPayload(BcCharacterItemUpdateCommand(2002, "ItemArms", "HempRope",
            publishAction = dev.vivian.mobilebc.core.model.BcItemActionMessage("ActionSwap", "OldRope", "HempRope",
                previousCraftName = "旧名 🔒", nextCraftName = "新名 $2 TargetCharacter")), 1001)
        val entries = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }
        val previous = entries.single { it.get("Tag")?.asString == "PrevAsset" }
        val next = entries.single { it.get("Tag")?.asString == "NextAsset" }
        assertEquals("OldRope", previous.get("AssetName").asString)
        assertEquals("旧名 🔒", previous.get("CraftName").asString)
        assertEquals("HempRope", next.get("AssetName").asString)
        assertEquals("新名 $2 TargetCharacter", next.get("CraftName").asString)
    }

    @Test
    fun configurationUpdateEmitsLayerColorsAndCustomCraftAsStructuredJson() {
        val colors = """["#663300","#112233","Default"]"""
        val craft = """{"Name":"Custom Rope","Description":"Made for a friend","MemberNumber":1001,"Property":"Secure"}"""
        val property = """{"TypeRecord":{"typed":1},"Effect":["Block"]}"""
        val payload = buildCharacterItemUpdatePayload(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = 2002,
                group = "ItemArms",
                name = "HempRope",
                colorJson = colors,
                difficulty = 7,
                propertyJson = property,
                craftJson = craft,
            ),
        )

        assertEquals(JsonParser.parseString(colors), payload.get("Color"))
        assertEquals(JsonParser.parseString(craft), payload.get("Craft"))
        assertEquals(JsonParser.parseString(property), payload.get("Property"))
        assertEquals(7, payload.get("Difficulty").asInt)
    }
}
