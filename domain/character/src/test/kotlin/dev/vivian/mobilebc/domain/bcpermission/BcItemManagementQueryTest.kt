package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemOptionRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcItemManagementQueryTest {
    private val query = DefaultBcItemManagementQuery()

    @Test
    fun localizedSearchKeepsWireNamesAndPagination() {
        val request = BcItemManagementPageRequest(
            context = context(rules = catalogWithWearItems()),
            group = "ItemArms",
            operation = BcItemManagementOperation.Wear,
            itemDisplayNames = mapOf("HempRope" to "麻绳", "LeatherCuffs" to "皮革腕铐"),
            query = "腕铐",
            limit = 1,
            includeDenied = true,
        )
        val entry = query.page(request).entries.single()
        assertEquals("皮革腕铐", entry.label)
        assertEquals("LeatherCuffs", entry.itemName)
        assertEquals("LeatherCuffs", (entry.candidate as BcInteractionCandidate.WearItem).itemName)
        assertEquals("皮革腕铐", query.page(request.copy(query = "Leather")).entries.single().label)
    }

    @Test
    fun wearCatalogPageReturnsOnlyRequestedSlice() {
        val result = query.page(
            BcItemManagementPageRequest(
                context = context(rules = catalogWithWearItems()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                offset = 1,
                limit = 1,
                includeDenied = true,
            ),
        )

        assertEquals(1, result.entries.size)
        assertEquals("Leather Cuffs", result.entries.single().label)
        assertEquals(2, result.nextOffset)
        assertTrue(result.hasMore)
    }


    @Test
    fun wearCatalogWithCurrentItemPointsUserToReplace() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(BcCharacterItem(group = "ItemArms", name = "LeatherCuffs")),
        )

        val result = query.page(
            BcItemManagementPageRequest(
                context = context(target = target, rules = catalogWithWearItems()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                includeDenied = true,
            ),
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(BcItemManagementOperation.Wear, entry.operation)
        assertEquals("LeatherCuffs", entry.currentItemName)
        assertFalse(entry.enabled)
        assertEquals(BcItemManagementAvailabilityReason.SlotAlreadyOccupied, entry.availabilityReason)
    }

    @Test
    fun wearOptionPageBuildsOptionCandidatesLazily() {
        val result = query.page(
            BcItemManagementPageRequest(
                context = context(rules = catalogWithExtendedItem()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                itemName = "Ribbons",
                offset = 1,
                limit = 1,
            ),
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("Tight", entry.label)
        assertEquals(BcItemManagementPermissionStatus.Allowed, entry.permissionStatus)
        assertTrue(entry.enabled)
        assertNotNull(entry.candidate)
        assertTrue(entry.propertyJson!!.contains("\"typed\":1"))
        assertEquals(2, result.nextOffset)
    }


    @Test
    fun wearOptionDefaultOptionIsSendable() {
        val result = query.page(
            BcItemManagementPageRequest(
                context = context(rules = catalogWithExtendedItem()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                itemName = "Ribbons",
                offset = 0,
                limit = 1,
            ),
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals("Loose", entry.label)
        assertFalse(entry.selected)
        assertTrue(entry.enabled)
        assertTrue(entry.candidate is BcInteractionCandidate.WearItem)
    }

    @Test
    fun modifyOptionPageUsesCurrentItemAndMarksSelectedOption() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(
                BcCharacterItem(
                    group = "ItemArms",
                    name = "Ribbons",
                    propertyJson = """{"TypeRecord":{"typed":0}}""",
                ),
            ),
        )

        val result = query.page(
            BcItemManagementPageRequest(
                context = context(target = target, rules = catalogWithExtendedItem()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Modify,
                offset = 0,
                limit = 2,
            ),
        )

        assertEquals(2, result.entries.size)
        val selected = result.entries[0]
        val next = result.entries[1]
        assertEquals("Loose", selected.label)
        assertTrue(selected.selected)
        assertFalse(selected.enabled)
        assertEquals(BcItemManagementPermissionStatus.Informational, selected.permissionStatus)
        assertEquals("Tight", next.label)
        assertFalse(next.selected)
        assertTrue(next.enabled)
        assertTrue(next.propertyJson!!.contains("\"typed\":1"))
    }


    @Test
    fun wearOptionSearchFiltersOptionsBeforePaging() {
        val result = query.page(
            BcItemManagementPageRequest(
                context = context(rules = catalogWithExtendedItem()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                itemName = "Ribbons",
                query = "strict",
                offset = 0,
                limit = 10,
            ),
        )

        assertEquals(1, result.entries.size)
        assertEquals("Strict", result.entries.single().label)
        assertEquals(1, result.totalKnownEntries)
        assertFalse(result.hasMore)
    }

    @Test
    fun modifyOptionSearchFiltersOptionsBeforePaging() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(
                BcCharacterItem(
                    group = "ItemArms",
                    name = "Ribbons",
                    propertyJson = """{"TypeRecord":{"typed":0}}""",
                ),
            ),
        )

        val result = query.page(
            BcItemManagementPageRequest(
                context = context(target = target, rules = catalogWithExtendedItem()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Modify,
                query = "tight",
                offset = 0,
                limit = 10,
            ),
        )

        assertEquals(1, result.entries.size)
        assertEquals("Tight", result.entries.single().label)
        assertFalse(result.entries.single().selected)
        assertEquals(1, result.totalKnownEntries)
    }


    @Test
    fun replaceCatalogRequiresCurrentItem() {
        val result = query.page(
            BcItemManagementPageRequest(
                context = context(rules = catalogWithWearItems()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Replace,
                includeDenied = true,
            ),
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(BcItemManagementOperation.Replace, entry.operation)
        assertFalse(entry.enabled)
        assertEquals(BcItemManagementAvailabilityReason.SlotEmpty, entry.availabilityReason)
    }

    @Test
    fun replaceCatalogExcludesCurrentItemAndBuildsWearCandidate() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(BcCharacterItem(group = "ItemArms", name = "LeatherCuffs")),
        )

        val result = query.page(
            BcItemManagementPageRequest(
                context = context(target = target, rules = catalogWithWearItems()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Replace,
                offset = 0,
                limit = 10,
            ),
        )

        assertEquals(2, result.entries.size)
        assertTrue(result.entries.none { it.itemName == "LeatherCuffs" })
        val entry = result.entries.first { it.itemName == "HempRope" }
        assertEquals(BcItemManagementOperation.Replace, entry.operation)
        assertEquals("LeatherCuffs", entry.currentItemName)
        assertTrue(entry.candidate is BcInteractionCandidate.WearItem)
        assertTrue(entry.enabled)
    }

    @Test
    fun replaceOptionPageBuildsWearCandidateForReplacementOption() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(BcCharacterItem(group = "ItemArms", name = "LeatherCuffs")),
        )

        val result = query.page(
            BcItemManagementPageRequest(
                context = context(target = target, rules = catalogWithExtendedItem()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Replace,
                itemName = "Ribbons",
                offset = 1,
                limit = 1,
            ),
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(BcItemManagementOperation.Replace, entry.operation)
        assertEquals("LeatherCuffs", entry.currentItemName)
        assertEquals("Tight", entry.label)
        assertTrue(entry.candidate is BcInteractionCandidate.WearItem)
        assertTrue(entry.propertyJson!!.contains("\"typed\":1"))
        assertTrue(entry.enabled)
    }

    @Test
    fun removePageReturnsCurrentItemCandidate() {
        val target = character(
            memberNumber = 2,
            appearance = listOf(BcCharacterItem(group = "ItemArms", name = "LeatherCuffs")),
        )

        val result = query.page(
            BcItemManagementPageRequest(
                context = context(target = target, rules = catalogWithWearItems()),
                group = "ItemArms",
                operation = BcItemManagementOperation.Remove,
            ),
        )

        assertEquals(1, result.entries.size)
        val entry = result.entries.single()
        assertEquals(BcItemManagementOperation.Remove, entry.operation)
        assertEquals("LeatherCuffs", entry.currentItemName)
        assertTrue(entry.candidate is BcInteractionCandidate.RemoveItem)
        assertTrue(entry.enabled)
    }

    @Test
    fun deniedRowsAreHiddenUnlessRequested() {
        val deniedCatalog = catalogWithWearItems().copy(
            assets = mapOf(
                BcAssetKey("ItemArms", "BrokenItem") to BcAssetRule(
                    group = "ItemArms",
                    name = "BrokenItem",
                    enable = false,
                ),
            ),
        )

        val hidden = query.page(
            BcItemManagementPageRequest(
                context = context(rules = deniedCatalog),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                includeDenied = false,
            ),
        )
        val shown = query.page(
            BcItemManagementPageRequest(
                context = context(rules = deniedCatalog),
                group = "ItemArms",
                operation = BcItemManagementOperation.Wear,
                includeDenied = true,
            ),
        )

        assertTrue(hidden.entries.isEmpty())
        assertEquals(1, shown.entries.size)
        assertEquals(BcItemManagementPermissionStatus.Denied, shown.entries.single().permissionStatus)
        assertEquals(
            listOf(BcPermissionReasonCode.AssetDisabled),
            shown.entries.single().permissionReasonCodes,
        )
    }

    private fun context(
        target: BcCharacterSnapshot = character(memberNumber = 2),
        rules: BcRuleCatalog,
        serverAllowItem: Boolean? = true,
    ): BcPermissionContext = BcPermissionContext(
        actor = character(memberNumber = 1, isSelf = true),
        target = target,
        room = BcPermissionRoomContext(memberNumbers = setOf(1, target.memberNumber)),
        rules = rules,
        serverAllowItem = serverAllowItem,
    )

    private fun character(
        memberNumber: Int,
        isSelf: Boolean = false,
        appearance: List<BcCharacterItem> = emptyList(),
    ): BcCharacterSnapshot = BcCharacterSnapshot(
        id = memberNumber.toString(),
        memberNumber = memberNumber,
        name = "C$memberNumber",
        displayName = "C$memberNumber",
        isSelf = isSelf,
        appearance = appearance,
    )

    private fun catalogWithWearItems(): BcRuleCatalog = BcRuleCatalog(
        version = "test",
        groups = mapOf("ItemArms" to BcGroupRule(name = "ItemArms")),
        activities = emptyMap(),
        assets = listOf(
            BcAssetRule(group = "ItemArms", name = "HempRope"),
            BcAssetRule(group = "ItemArms", name = "LeatherCuffs"),
            BcAssetRule(group = "ItemArms", name = "Ribbons"),
        ).associateBy { BcAssetKey(it.group, it.name) },
    )

    private fun catalogWithExtendedItem(): BcRuleCatalog {
        val base = catalogWithWearItems()
        val extended = BcExtendedItemRule(
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
                BcExtendedItemOptionRule(
                    name = "Strict",
                    index = 2,
                    displayName = "Strict",
                    propertyJson = """{"TypeRecord":{"typed":2},"Effect":["BlockArms"]}""",
                ),
            ),
            defaultPropertyJson = """{"TypeRecord":{"typed":0},"Effect":[]}""",
        )
        return base.copy(
            extendedItems = mapOf(BcAssetKey("ItemArms", "Ribbons") to extended),
        )
    }
}
