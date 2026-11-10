package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import org.junit.Assert.*
import org.junit.Test

class RoomItemPreviewTest {
    private val worn = BcCharacterItem("ItemArms", "HempRope", "[\"Red\"]", 7, """{"Text":"自定义😀"}""", """{"Name":"定制绳"}""")
    private val shirt = BcCharacterItem("Cloth", "Shirt")
    private val target = BcCharacterSnapshot("20", 20, "Wearer", "Wearer", false, appearance = listOf(worn, shirt))
    private fun entry(operation: String, name: String, property: String? = null) = InteractionItemPickerEntryUiState(
        operation = operation, group = "ItemArms", itemName = name, currentItemName = "HempRope", label = name,
        propertyJson = property, enabled = true)

    @Test fun previewChangesOnlyTheDraftAndKeepsUnrelatedItems() {
        val draft = itemPreviewCharacter(target, entry("Replace", "LeatherCuffs"))!!
        assertEquals("LeatherCuffs", draft.appearance.first().name)
        assertEquals(shirt, draft.appearance.last())
        assertNull(draft.appearance.first().craftJson)
        assertEquals(listOf(worn, shirt), target.appearance)
    }

    @Test fun modePreviewKeepsTheWornColorDifficultyCraftAndCustomText() {
        val property = """{"TypeRecord":{"typed":1},"Text":"自定义😀"}"""
        val draft = itemPreviewCharacter(target, entry("Modify", "HempRope", property))!!.appearance.first()
        assertEquals(property, draft.propertyJson)
        assertEquals(worn.colorJson, draft.colorJson)
        assertEquals(worn.craftJson, draft.craftJson)
        assertEquals(7, draft.difficulty)
        assertNull(itemPreviewCharacter(target, entry("Modify", "Other")))
        assertNull(itemPreviewCharacter(target, entry("Lock", "MetalPadlock")))
    }
}
