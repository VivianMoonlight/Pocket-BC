package dev.vivian.mobilebc.domain.bccharacterstate

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BcLockInputConditionsTest {
    @Test fun clumsinessUsesTheNativeRestraintFlagAndCapsTheCombinedValueAtFive() {
        val rules = BcRuleCatalogParser().parse("R133", """
            var AssetFemale3DCG = [
                { Group: "ItemArms", Category: "Item", IsRestraint: true, Asset: [{ Name: "Rope", Effect: ["Block"] }, { Name: "Decoration", IsRestraint: false, Effect: ["Block"] }] },
                { Group: "ItemHands", Category: "Item", IsRestraint: true, Asset: [{ Name: "Mittens", Effect: ["Block"] }] },
            ];
        """.trimIndent())
        fun clumsy(vararg items: BcCharacterItem) = DefaultBcCharacterStateResolver().resolve(
            BcCharacterSnapshot("1", 1, "Self", "Self", true, appearance = items.toList()), rules, BcCharacterItemSource.Actor).lockInputConditions().clumsiness
        assertEquals(0, clumsy())
        assertEquals(3, clumsy(BcCharacterItem("ItemArms", "Rope")))
        assertEquals(4, clumsy(BcCharacterItem("ItemHands", "Mittens")))
        assertEquals(5, clumsy(BcCharacterItem("ItemArms", "Rope"), BcCharacterItem("ItemHands", "Mittens")))
        assertEquals(1, clumsy(BcCharacterItem("ItemArms", "Decoration")))
    }
}
