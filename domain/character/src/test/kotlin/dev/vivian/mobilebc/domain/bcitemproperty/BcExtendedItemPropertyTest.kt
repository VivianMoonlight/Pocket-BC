package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemModuleRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcExtendedItemPropertyTest {
    @Test
    fun missingTypedSelectionMatchesTheDefaultSummary() {
        val rule = typedRule()
        assertEquals("Loose", rule.currentSelectionSummary(null))
        assertEquals("Loose", rule.modificationOptionsPage(null).options.single { it.selected }.label)
        assertTrue(rule.modificationOptionsPage("""{"TypeRecord":{"typed":99}}""").options.none { it.selected })
    }

    @Test
    fun typedModificationPreservesUnknownCurrentFieldsAndUpdatesTypeRecord() {
        val rule = typedRule()
        val current = """{"TypeRecord":{"typed":0},"LockedBy":"OwnerPadlock","Custom":"keep"}"""

        val options = rule.modificationOptionsPage(currentPropertyJson = current).options
        val selected = options.first { it.label == "Tight" }

        assertFalse(selected.selected)
        assertTrue(selected.propertyJson.contains("\"LockedBy\":\"OwnerPadlock\""))
        assertTrue(selected.propertyJson.contains("\"Custom\":\"keep\""))
        assertTrue(selected.propertyJson.contains("\"TypeRecord\":{"))
        assertTrue(selected.propertyJson.contains("\"typed\":1"))
        assertEquals(setOf("BlockArms", "Lock"), selected.propertyJson.readBcPropertyTokens("Effect"))
    }

    @Test
    fun modularModificationChangesOnlySelectedModuleTypeRecord() {
        val rule = modularRule()
        val current = """{"TypeRecord":{"strap":1,"chain":2},"LockedBy":"OwnerPadlock"}"""

        val chainLoose = rule.modificationOptionsPage(currentPropertyJson = current).options
            .first { it.label == "Chain: Loose" }

        assertTrue(chainLoose.propertyJson.contains("\"strap\":1"))
        assertTrue(chainLoose.propertyJson.contains("\"chain\":0"))
        assertTrue(chainLoose.propertyJson.contains("\"LockedBy\":\"OwnerPadlock\""))
        assertEquals("Strap: Tight • Chain: Heavy", rule.currentSelectionSummary(current))
    }



    @Test
    fun wearOptionsPageReturnsOnlyRequestedSlice() {
        val page = modularRule().wearOptionsPage(offset = 2, limit = 2)

        assertEquals(2, page.options.size)
        assertEquals("Chain: Loose", page.options[0].label)
        assertEquals("Chain: Medium", page.options[1].label)
        assertEquals(4, page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test
    fun modificationOptionsPageDoesNotBuildWholeListForCaller() {
        val current = """{"TypeRecord":{"strap":1,"chain":2},"LockedBy":"OwnerPadlock"}"""
        val page = modularRule().modificationOptionsPage(
            currentPropertyJson = current,
            offset = 3,
            limit = 1,
        )

        assertEquals(1, page.options.size)
        assertEquals("Chain: Medium", page.options.single().label)
        assertEquals(4, page.nextOffset)
        assertTrue(page.options.single().propertyJson.contains("\"LockedBy\":\"OwnerPadlock\""))
    }

    @Test
    fun latexTankTopChangingOneHoodPreservesTheOtherHoodsProperties() {
        val rule = fixtureRule("Cloth", "LatexTankTop")
        val current = """{"TypeRecord":{"lh":0,"rh":2},"Effect":["Slow","Lock"],"Hide":["HairFront","HairBack","HairAccessory1","HairAccessory2","HairAccessory3","Hat","Head"],"LockedBy":"OwnerPadlock","LockMemberNumber":42,"Custom":{"keep":true}}"""

        val backHood = rule.modificationOptionsPage(current).options.first { it.label == "BackHood: lh1" }

        assertEquals(mapOf("lh" to 1, "rh" to 2), BcItemPropertyJson.readTypeRecord(backHood.propertyJson))
        assertEquals(setOf("Slow", "Lock"), backHood.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(current.readBcPropertyTokens("Hide"), backHood.propertyJson.readBcPropertyTokens("Hide"))
        assertEquals("OwnerPadlock", backHood.propertyJson.readBcStringProperty("LockedBy"))
        assertEquals(42, backHood.propertyJson.readBcIntProperty("LockMemberNumber"))
        assertTrue(backHood.propertyJson.contains("\"Custom\":{\"keep\":true}"))

        val noHoods = rule.modificationOptionsPage(backHood.propertyJson).options.first { it.label == "ReverseHood: rh0" }
        assertTrue(noHoods.propertyJson.readBcPropertyTokens("Hide").isEmpty())
        assertEquals(setOf("Lock"), noHoods.propertyJson.readBcPropertyTokens("Effect"))
    }

    @Test
    fun latexHobbleDressWearAndModificationCombineAllSelectedEffects() {
        val rule = fixtureRule("Cloth", "LatexHobbleDress")
        val wearOptions = rule.wearOptionsPage().options
        assertEquals(setOf("Slow"), wearOptions.first { it.label == "SkirtOpt: k1" }.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(setOf("MapSwim"), wearOptions.first { it.label == "EndSkirt: r1" }.propertyJson.readBcPropertyTokens("Effect"))

        val current = """{"TypeRecord":{"t":0,"c":0,"k":1,"r":1,"b":0},"Effect":["Slow","MapSwim"]}"""
        val top = rule.modificationOptionsPage(current).options.first { it.label == "Top: t1" }
        assertEquals(setOf("Slow", "MapSwim"), top.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(mapOf("t" to 1, "c" to 0, "k" to 1, "r" to 1, "b" to 0), BcItemPropertyJson.readTypeRecord(top.propertyJson))
    }

    private fun fixtureRule(group: String, name: String): BcExtendedItemRule {
        val extendedJs = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/Female3DCGExtended-R129.js")).readText()
        return requireNotNull(BcRuleCatalogParser().parse("R129", "var AssetFemale3DCG = []; var ActivityFemale3DCG = [];", extendedJs).extendedItem(group, name))
    }

    private fun typedRule(): BcExtendedItemRule = BcExtendedItemRule(
        group = "ItemArms",
        name = "Ribbons",
        archetype = BcExtendedItemArchetype.Typed,
        recordKey = "typed",
        typedOptions = listOf(
            BcExtendedItemOptionRule(
                name = "Loose",
                index = 0,
                displayName = "Loose",
                propertyJson = """{"TypeRecord":{"typed":0},"Effect":[]}""",
            ),
            BcExtendedItemOptionRule(
                name = "Tight",
                index = 1,
                displayName = "Tight",
                propertyJson = """{"TypeRecord":{"typed":1},"Effect":["BlockArms"]}""",
            ),
        ),
    )

    private fun modularRule(): BcExtendedItemRule = BcExtendedItemRule(
        group = "ItemArms",
        name = "Harness",
        archetype = BcExtendedItemArchetype.Modular,
        recordKey = "modular",
        modules = listOf(
            BcExtendedItemModuleRule(
                name = "Strap",
                key = "strap",
                options = listOf(
                    BcExtendedItemModuleOptionRule(
                        name = "Loose",
                        index = 0,
                        displayName = "Loose",
                        propertyJson = """{"TypeRecord":{"strap":0,"chain":0}}""",
                        modulePropertyJson = "{}",
                    ),
                    BcExtendedItemModuleOptionRule(
                        name = "Tight",
                        index = 1,
                        displayName = "Tight",
                        propertyJson = """{"TypeRecord":{"strap":1,"chain":0},"Effect":["BlockArms"]}""",
                        modulePropertyJson = """{"Effect":["BlockArms"]}""",
                    ),
                ),
            ),
            BcExtendedItemModuleRule(
                name = "Chain",
                key = "chain",
                options = listOf(
                    BcExtendedItemModuleOptionRule(
                        name = "Loose",
                        index = 0,
                        displayName = "Loose",
                        propertyJson = """{"TypeRecord":{"strap":0,"chain":0}}""",
                        modulePropertyJson = "{}",
                    ),
                    BcExtendedItemModuleOptionRule(
                        name = "Medium",
                        index = 1,
                        displayName = "Medium",
                        propertyJson = """{"TypeRecord":{"strap":0,"chain":1}}""",
                        modulePropertyJson = "{}",
                    ),
                    BcExtendedItemModuleOptionRule(
                        name = "Heavy",
                        index = 2,
                        displayName = "Heavy",
                        propertyJson = """{"TypeRecord":{"strap":0,"chain":2},"Block":["ItemFeet"]}""",
                        modulePropertyJson = """{"Block":["ItemFeet"]}""",
                    ),
                ),
            ),
        ),
    )
}
