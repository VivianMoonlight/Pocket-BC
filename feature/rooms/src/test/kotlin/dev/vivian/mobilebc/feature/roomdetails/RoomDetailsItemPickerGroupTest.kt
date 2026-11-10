package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionGroup
import dev.vivian.mobilebc.core.model.BcInteractionItem
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomDetailsItemPickerGroupTest {
    @Test
    fun pickerSeparatesItemsAndClothingFromOtherAppearanceGroups() {
        val groups = buildItemPickerGroupStatesForTarget(
            target = character(
                appearance = listOf(
                    BcCharacterItem(group = "BodyUpper", name = "Normal"),
                    BcCharacterItem(group = "Pussy", name = "Pussy1"),
                    BcCharacterItem(group = "Cloth", name = "Dress"),
                    BcCharacterItem(group = "ItemMouth", name = "BallGag"),
                ),
            ),
            snapshot = catalogSnapshot(),
            rules = ruleCatalog(),
        )

        val names = groups.map { it.name }
        assertFalse(names.contains("BodyUpper"))
        assertFalse(names.contains("Pussy"))
        assertTrue(names.contains("Cloth"))
        assertTrue(names.contains("ItemMouth"))
        assertTrue(names.contains("ItemArms"))
        assertEquals(true, groups.first { it.name == "ItemMouth" }.hasCurrentItem)
        assertEquals(BcAppearanceGroupKind.Item, groups.first { it.name == "ItemMouth" }.kind)
        assertEquals(BcAppearanceGroupKind.Clothing, groups.first { it.name == "Cloth" }.kind)
    }

    @Test
    fun itemPickerGroupsFallbackStillOnlyUsesItemGroups() {
        val groups = buildItemPickerGroupStatesForTarget(
            target = character(
                appearance = listOf(
                    BcCharacterItem(group = "BodyUpper", name = "Normal"),
                    BcCharacterItem(group = "Cloth", name = "Dress"),
                    BcCharacterItem(group = "ItemNeck", name = "Collar"),
                ),
            ),
            snapshot = catalogSnapshot(),
            rules = null,
        )

        val names = groups.map { it.name }
        assertFalse(names.contains("BodyUpper"))
        assertFalse(names.contains("Cloth"))
        assertTrue(names.contains("ItemMouth"))
        assertTrue(names.contains("ItemArms"))
        assertTrue(names.contains("ItemNeck"))
    }

    private fun character(
        appearance: List<BcCharacterItem>,
    ): BcCharacterSnapshot = BcCharacterSnapshot(
        id = "target",
        memberNumber = 2,
        name = "Target",
        displayName = "Target",
        isSelf = false,
        appearance = appearance,
    )

    private fun catalogSnapshot(): BcInteractionCatalogSnapshot = BcInteractionCatalogSnapshot(
        version = "R129",
        generatedAtEpochMillis = 0L,
        groups = listOf(
            BcInteractionGroup(name = "BodyUpper", displayName = "Body upper", hasActivities = false, hasItems = true),
            BcInteractionGroup(name = "Pussy", displayName = "Pussy", hasActivities = false, hasItems = true),
            BcInteractionGroup(name = "Cloth", displayName = "Cloth", hasActivities = false, hasItems = true),
            BcInteractionGroup(name = "ItemMouth", displayName = "Mouth item", hasActivities = false, hasItems = true),
            BcInteractionGroup(name = "ItemArms", displayName = "Arms item", hasActivities = false, hasItems = true),
        ),
        activities = emptyList(),
        items = listOf(
            BcInteractionItem(group = "BodyUpper", name = "Normal", displayName = "Normal"),
            BcInteractionItem(group = "Cloth", name = "Dress", displayName = "Dress"),
            BcInteractionItem(group = "ItemMouth", name = "BallGag", displayName = "Ball gag"),
            BcInteractionItem(group = "ItemArms", name = "Rope", displayName = "Rope"),
        ),
        sourcePaths = emptyList(),
    )

    private fun ruleCatalog(): BcRuleCatalog = BcRuleCatalog(
        version = "R129",
        groups = listOf(
            BcGroupRule(name = "BodyUpper", category = "Body", isItem = false),
            BcGroupRule(name = "Cloth", category = "Appearance", clothing = true, isItem = false),
            BcGroupRule(name = "ItemMouth", category = "Item", isItem = true),
            BcGroupRule(name = "ItemArms", category = "Item", isItem = true),
        ).associateBy { it.name },
        activities = emptyMap(),
        assets = mapOf(
            BcAssetKey(group = "ItemMouth", name = "BallGag") to BcAssetRule(group = "ItemMouth", name = "BallGag"),
            BcAssetKey(group = "ItemArms", name = "Rope") to BcAssetRule(group = "ItemArms", name = "Rope"),
            BcAssetKey(group = "Cloth", name = "Dress") to BcAssetRule(group = "Cloth", name = "Dress"),
        ),
    )
}
