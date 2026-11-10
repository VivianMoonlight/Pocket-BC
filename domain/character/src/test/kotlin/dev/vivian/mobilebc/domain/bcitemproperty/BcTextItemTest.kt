package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.domain.bcrules.*
import dev.vivian.mobilebc.domain.bcpermission.*
import org.junit.Assert.*
import org.junit.Test

class BcTextItemTest {
    private val nativeRules = BcRuleCatalogParser().parse("R129", "var AssetFemale3DCG = [];",
        requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/Female3DCGExtended-R129.js")).readText())
    private val text = requireNotNull(nativeRules.extendedItem("ItemMisc", "WoodenSign"))

    @Test fun pinnedNativeTextConfigInitializesAllLinesAndAcceptsChineseOrEmptyText() {
        assertEquals(BcExtendedItemSupportLevel.Supported, text.supportLevel)
        assertEquals(mapOf("Text" to 12, "Text2" to 12), text.textFields)
        assertEquals(mapOf("Text" to "", "Text2" to ""), text.textValues(text.defaultPropertyJson))
        assertTrue(text.validText(mapOf("Text" to "你好 123", "Text2" to "")))
        assertFalse(text.validText(mapOf("Text" to "😀", "Text2" to "")))
        assertFalse(text.validText(mapOf("Text" to "a\nb", "Text2" to "")))
        assertFalse(text.validText(mapOf("Text" to "a".repeat(13), "Text2" to "")))
        assertFalse(text.validText(mapOf("Text" to "one line")))
    }

    @Test fun customInitializationAndNonStaticLimitsRemainExplicitlyUnsupported() {
        val source = """var AssetFemale3DCGExtended = {ItemMisc:{
            Custom:{Archetype:ExtendedArchetype.TEXT,MaxLength:{Text:12},ScriptHooks:{Init:customInit}},
            Variable:{Archetype:ExtendedArchetype.TEXT,MaxLength:{Text:getLimit()}},
        }};"""
        val rules = BcRuleCatalogParser().parse("R133", "var AssetFemale3DCG = [];", source)
        assertEquals(BcExtendedItemSupportLevel.Unsupported, rules.extendedItem("ItemMisc", "Custom")!!.supportLevel)
        assertEquals(BcExtendedItemSupportLevel.Unsupported, rules.extendedItem("ItemMisc", "Variable")!!.supportLevel)
    }

    @Test fun wearingAndEditingUsePermissionsAndPreserveUnrelatedMetadata() {
        val asset = BcAssetRule("ItemMisc", "WoodenSign")
        val rules = nativeRules.copy(groups = mapOf("ItemMisc" to BcGroupRule("ItemMisc")), assets = mapOf(BcAssetKey(asset.group, asset.name) to asset))
        val actor = BcCharacterSnapshot("1", 1, "Actor", "Actor", true)
        val item = BcCharacterItem("ItemMisc", "WoodenSign", "[\"Red\"]", 7,
            """{"Text":"Old","Text2":"","Custom":{"keep":true}}""", """{"Name":"定制牌😀"}""")
        val target = BcCharacterSnapshot("2", 2, "Wearer", "Wearer", false, appearance = listOf(item))
        val context = BcPermissionContext(actor, target, BcPermissionRoomContext(memberNumbers = setOf(1, 2)), rules, serverAllowItem = true)
        val engine = DefaultBcInteractionPermissionEngine()
        fun modify(value: String) = engine.evaluate(BcPermissionRequest(context, BcInteractionCandidate.ModifyItem(2, "ItemMisc", "WoodenSign",
            BcItemPropertyJson.overlayStrings(item.propertyJson, mapOf("Text" to value, "Text2" to "")))))
        val command = ((modify("你好") as BcPermissionDecision.Allowed).executable as BcExecutableInteraction.ItemUpdate).command
        assertEquals(item.colorJson, command.colorJson)
        assertEquals(item.difficulty, command.difficulty)
        assertEquals(item.craftJson, command.craftJson)
        assertTrue(command.propertyJson!!.contains("\"Custom\":{\"keep\":true}"))
        assertTrue(modify("😀") is BcPermissionDecision.Denied)
        val page = DefaultBcItemManagementQuery().page(BcItemManagementPageRequest(context.copy(target = target.copy(appearance = emptyList())),
            "ItemMisc", BcItemManagementOperation.Wear))
        assertTrue(page.entries.single().enabled)
        assertEquals(text.defaultPropertyJson, page.entries.single().propertyJson)
        val blocked = context.copy(target = target.copy(blockItemsJson = """[{"Group":"ItemMisc","Name":"WoodenSign"}]"""))
        assertTrue(engine.evaluate(BcPermissionRequest(blocked, BcInteractionCandidate.ModifyItem(2,"ItemMisc","WoodenSign",command.propertyJson!!))) is BcPermissionDecision.Denied)
    }
}
