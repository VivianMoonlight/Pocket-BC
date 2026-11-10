package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcitemproperty.*
import dev.vivian.mobilebc.domain.bcpermission.*
import org.junit.Assert.*
import org.junit.Test

class BcModularTextItemTest {
    @Test fun modularTextMergeMatchesTheFixturesExecutedByNativeR133() {
        val fixtures = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/modular-text-r133.json")).readText()
        BcItemPropertyJson.readObjectArray(fixtures).forEach { fixture ->
            val type = BcItemPropertyJson.readTypeRecord("""{"TypeRecord":${BcItemPropertyJson.readExactPropertyValue(fixture,"type")}}""")
            val blocks = listOfNotNull(BcItemPropertyJson.readExactPropertyValue(fixture,"baseline")) +
                BcItemPropertyJson.readObjectArray(BcItemPropertyJson.readExactPropertyValue(fixture,"options"))
            val property = BcItemPropertyJson.composeModularProperty(type,null,blocks)!!
            val expected = BcItemPropertyJson.readExactPropertyValue(fixture,"expectedText")
            listOf("Text","Text2","Text3").forEach { key ->
                assertEquals(fixture.readBcStringProperty("id"),expected.readBcStringProperty(key),property.readBcStringProperty(key))
            }
        }
    }

    private fun catalog() = BcRuleCatalogParser().parse("R133", """
        var AssetFemale3DCG = [{ Group: "ItemNeckAccessories", Category: "Item", Asset: [{ Name: "CustomCollarTag", AllowLock: true }] }];
    """.trimIndent(), """
        var AssetFemale3DCGExtended = { ItemNeckAccessories: { CustomCollarTag: {
            Archetype: ExtendedArchetype.MODULAR,
            Modules: [
                { Name: "Tag", Key: "t", Options: [{}, {}, {}, {}, {}, {}] },
                { Name: "Txt", Key: "x", Options: [{ HasSubscreen: true, ArchetypeConfig: {
                    Archetype: ExtendedArchetype.TEXT, MaxLength: { Text: 9 }, Font: "sans-serif",
                    ScriptHooks: { AfterDraw: AssetsItemNeckAccessoriesCustomCollarTagAfterDrawHook },
                } }] },
            ], ChangeWhenLocked: false, BaselineProperty: { Text: "Tag" },
        } } };
    """.trimIndent())

    @Test fun nativeTagKeepsItsDefaultTextAndOffersAllSixShapes() {
        val rule = catalog().extendedItem("ItemNeckAccessories", "CustomCollarTag")!!
        assertTrue(rule.isConfigurable)
        assertEquals("Tag", rule.defaultPropertyJson.readBcStringProperty("Text"))
        assertEquals(mapOf("Text" to 9), rule.textFields)
        assertEquals(6, rule.modules.first().options.count { it.propertyJson != null })
        assertTrue(rule.validText(mapOf("Text" to "测试Tag")))
        assertFalse(rule.validText(mapOf("Text" to "1234567890")))
    }

    @Test fun changingShapePreservesEditedTextAndCustomProperties() {
        val rule = catalog().extendedItem("ItemNeckAccessories", "CustomCollarTag")!!
        val current = """{"Text":"我的名牌","TypeRecord":{"t":2,"x":0},"Custom":{"keep":true},"Hint":"other"}"""
        val options = rule.modificationOptionsPage(current, limit=20).options
        assertEquals(7, options.size)
        options.forEach { option ->
            assertEquals("我的名牌", option.propertyJson.readBcStringProperty("Text"))
            assertEquals("{\"keep\":true}", BcItemPropertyJson.readExactPropertyValue(option.propertyJson,"Custom"))
            assertEquals("other",option.propertyJson.readBcStringProperty("Hint"))
        }
        assertTrue(options.any { BcItemPropertyJson.readTypeRecord(it.propertyJson)["t"] == 5 })
    }

    @Test fun textEditorChecksTheCurrentItemWithoutDependingOnAnUnselectedShape() {
        val rules = catalog()
        val item = BcCharacterItem("ItemNeckAccessories","CustomCollarTag",propertyJson="""{"Text":"我的名牌","TypeRecord":{"t":0,"x":0}}""")
        val actor = BcCharacterSnapshot("10",10,"Actor","Actor",true)
        val target = BcCharacterSnapshot("20",20,"Target","Target",false,appearance=listOf(item))
        val context = BcPermissionContext(actor,target,BcPermissionRoomContext(memberNumbers=setOf(10,20)),rules,serverAllowItem=true)
        val query = DefaultBcItemManagementQuery()
        val mode = query.page(BcItemManagementPageRequest(context,item.group,BcItemManagementOperation.Modify,limit=1,includeDenied=true)).entries.single()
        assertTrue(mode.selected)
        assertFalse(mode.enabled)
        val entry = query.textEditEntry(context,item.group)!!
        assertTrue(entry.enabled)
        assertTrue(entry.requiresTextInput)
        assertNull(entry.candidate)
        assertNull(entry.executable)
        val locked = context.copy(target=target.copy(appearance=listOf(item.copy(propertyJson="""{"Text":"Tag","LockedBy":"PasswordPadlock","Effect":["Lock"]}"""))))
        assertFalse(query.textEditEntry(locked,item.group)!!.enabled)
    }

    @Test fun conditionalAndCustomValidatedSubscreensAreNotExposedAsAlwaysEditableText() {
        fun fields(options: String, extra: String = "") = BcRuleCatalogParser().parse("R133","var AssetFemale3DCG = [];", """
            var AssetFemale3DCGExtended = { ItemNeckAccessories: { CustomCollarTag: {
                Archetype: ExtendedArchetype.MODULAR, Modules: [{ Name: "Txt", Key: "x", Options: $options }], $extra
            } } };
        """.trimIndent()).extendedItem("ItemNeckAccessories","CustomCollarTag")!!.textFields
        val text = """{ HasSubscreen: true, ArchetypeConfig: { Archetype: ExtendedArchetype.TEXT, MaxLength: { Text: 9 } } }"""
        assertEquals(mapOf("Text" to 9),fields("[$text]"))
        assertTrue(fields("[{}, $text]").isEmpty())
        assertTrue(fields("[$text]","Validate: CustomValidator").isEmpty())
        assertTrue(fields("[$text]","ScriptHooks: { Init: CustomInit }").isEmpty())
    }
}
