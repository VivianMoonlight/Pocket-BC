package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomInteractionContextTest {
    @Test
    fun switchingModesKeepsBodyPartButDiscardsItemConfiguration() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ItemPicker, 7)
        state.selectGroup("ItemMouth")
        state.selectItemPickerOperation(BcItemManagementOperation.Modify)
        state.selectItemPickerItem("BallGag")
        state.updateItemPickerQuery("tight")

        state.open(InteractionMode.ActionPicker, 7)

        assertEquals("ItemMouth", state.group)
        assertEquals(7, state.targetMemberNumber)
        assertNull(state.itemPickerOperation)
        assertNull(state.itemPickerItemName)
        assertEquals("", state.itemPickerQuery)
        state.open(InteractionMode.ItemPicker, 7)
        assertEquals("ItemMouth", state.group)
    }

    @Test
    fun changingTargetOrEnteringClothingClearsBodyPart() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ActionPicker, 7)
        state.selectGroup("ItemMouth")
        state.open(InteractionMode.ItemPicker, 8)
        assertNull(state.group)
        state.selectGroup("ItemArms")
        state.open(InteractionMode.ClothingPicker, 8)
        assertNull(state.group)
    }

    @Test
    fun explicitTargetSelectionStillResetsBodyPart() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ItemPicker, 7)
        state.selectGroup("ItemMouth")
        state.selectTarget(InteractionMode.ActionPicker, 7)
        assertNull(state.group)
    }

    @Test
    fun reopeningAfterCloseDoesNotRestoreOldBodyPart() {
        val state = RoomInteractionSelectionState()
        state.open(InteractionMode.ActionPicker, 7)
        state.selectGroup("ItemMouth")
        state.close()
        state.open(InteractionMode.ItemPicker, 7)
        assertNull(state.group)
    }
}
