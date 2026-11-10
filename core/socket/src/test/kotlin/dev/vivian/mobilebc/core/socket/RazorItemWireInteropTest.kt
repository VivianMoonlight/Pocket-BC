package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.*
import dev.vivian.mobilebc.domain.bcpermission.*

class RazorItemWireInteropTest {
    @Test fun timerExpiryUsesTheNativeDestinationNameAndLockNameDictionaryTags() {
        val command = BcCharacterItemUpdateCommand(10, "ItemArms", "LeatherCuffs",
            publishAction = dev.vivian.mobilebc.core.model.BcItemActionMessage("TimerRelease", lockName = "OwnerTimerPadlock", destinationCharacterName = "昵称"))
        val payload = buildItemActionChatPayload(command, 10)
        val dictionary = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }
        val destination = dictionary.single { it.get("Tag")?.asString == "DestinationCharacterName" }
        assertEquals(10, destination.get("MemberNumber").asInt)
        assertEquals("昵称", destination.get("Text").asString)
        val lock = dictionary.single { it.get("Tag")?.asString == "LockName" }
        assertEquals("ItemMisc", lock.get("GroupName").asString)
        assertEquals("OwnerTimerPadlock", lock.get("AssetName").asString)
    }

    @Test fun successfulLockSettingsActionsDoNotPublishPasswordsOrCombinationsToChat() {
        for (content in listOf("PasswordChangeSuccess", "CombinationChangeSuccess")) {
            val command = BcCharacterItemUpdateCommand(20, "ItemArms", "LeatherCuffs",
                propertyJson = """{"Password":"SECRET","CombinationNumber":"0007"}""",
                publishAction = dev.vivian.mobilebc.core.model.BcItemActionMessage(content))
            val action = buildItemActionChatPayload(command, 10).toString()
            assertFalse(action.contains("SECRET"))
            assertFalse(action.contains("0007"))
            assertTrue(action.contains("FocusGroupName"))
        }
    }

    @Test fun modernPermissionsOverrideLegacyListsAndKeepTypeRestrictionsSeparate() {
        val raw = JsonParser.parseString("""{"ID":"20","MemberNumber":20,"Name":"Wearer",
            "BlockItems":[{"Group":"ItemArms","Name":"OldBlock"}],
            "PermissionItems":{"ItemArms/HempRope":{"Permission":"Limited","TypePermissions":{"typed1":"Block","typed2":"Limited"}},
                "ItemFeet/LegCuffs":{"Permission":"Favorite"}}}""")
        val character = requireNotNull(parseCharacterFromJson(raw, Gson())).toBcCharacterSnapshot(false, Gson())
        assertEquals(JsonParser.parseString("""[{"Group":"ItemArms","Name":"HempRope","Type":"typed1"}]"""), JsonParser.parseString(character.blockItemsJson))
        assertEquals(JsonParser.parseString("""[{"Group":"ItemArms","Name":"HempRope"},{"Group":"ItemArms","Name":"HempRope","Type":"typed2"}]"""), JsonParser.parseString(character.limitedItemsJson))
        assertEquals(JsonParser.parseString("""[{"Group":"ItemFeet","Name":"LegCuffs"}]"""), JsonParser.parseString(character.favoriteItemsJson))
        val rules = BcRuleCatalog("R133",
            mapOf("ItemArms" to BcGroupRule("ItemArms")), emptyMap(),
            mapOf(BcAssetKey("ItemArms", "HempRope") to BcAssetRule("ItemArms", "HempRope")))
        val actor = BcCharacterSnapshot("10", 10, "Actor", "Actor", true)
        val context = BcPermissionContext(actor, character.copy(whiteList = listOf(10)),
            BcPermissionRoomContext(memberNumbers = setOf(10, 20)), rules, serverAllowItem = true)
        fun decision(type: Int) = DefaultBcInteractionPermissionEngine().evaluate(
            BcPermissionRequest(context,
                BcInteractionCandidate.WearItem(20, "ItemArms", "HempRope", """{"TypeRecord":{"typed":$type}}""")))
        assertTrue(decision(0) is BcPermissionDecision.Allowed)
        assertTrue(decision(1) is BcPermissionDecision.Denied)
        assertTrue(decision(2) is BcPermissionDecision.Allowed)
    }

    @Test fun generatedPacketsMatchTheFixturesUsedByTheNativeReceiverCheck() {
        val fixtures = JsonParser.parseString(requireNotNull(javaClass.classLoader?.getResource("bc-item-wire-r133.json")).readText()).asJsonArray
        fixtures.forEach { element ->
            val fixture = element.asJsonObject
            val command = Gson().fromJson(fixture.get("command"), BcCharacterItemUpdateCommand::class.java)
            assertEquals(fixture.get("id").asString, fixture.get("wire"), buildCharacterItemUpdatePayload(command))
        }
    }
}
