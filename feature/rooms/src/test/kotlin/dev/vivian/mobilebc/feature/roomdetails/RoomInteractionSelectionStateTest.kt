package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomInteractionSelectionStateTest {
    @Test
    fun changingItemPickerGroupClearsDependentSelections() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ItemPicker, targetMemberNumber = 7)
        state.selectGroup("ItemMouth")
        state.selectItemPickerOperation(BcItemManagementOperation.Wear)
        state.selectItemPickerItem("BallGag")
        state.updateItemPickerQuery("ball")
        state.toggleItemPickerUnavailable()

        state.selectGroup("ItemArms")

        assertEquals("ItemArms", state.group)
        assertNull(state.itemPickerOperation)
        assertNull(state.itemPickerItemName)
        assertEquals("", state.itemPickerQuery)
        assertFalse(state.itemPickerShowUnavailable)
    }

    @Test
    fun successfulItemSendMovesToTheNextValidOperation() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ItemPicker, targetMemberNumber = 7)
        state.selectGroup("ItemMouth")
        state.selectItemPickerOperation(BcItemManagementOperation.Wear)
        state.updateItemPickerQuery("gag")
        state.selectItemPickerItem("BallGag")
        state.updateItemPickerQuery("tight")

        assertTrue(state.updateAfterSuccessfulItemSend(BcItemManagementOperation.Wear))
        assertEquals(BcItemManagementOperation.Replace, state.itemPickerOperation)
        assertEquals("gag", state.itemPickerQuery)

        assertTrue(state.updateAfterSuccessfulItemSend(BcItemManagementOperation.Remove))
        assertEquals(BcItemManagementOperation.Wear, state.itemPickerOperation)
        assertEquals("gag", state.itemPickerQuery)
    }

    @Test
    fun closingClearsAllNavigationState() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ItemPicker, targetMemberNumber = 7)
        state.selectGroup("ItemMouth")
        state.toggleItemPickerUnavailable()

        state.close()

        assertEquals(InteractionMode.Closed, state.mode)
        assertNull(state.targetMemberNumber)
        assertNull(state.group)
        assertFalse(state.itemPickerShowUnavailable)
    }
}
