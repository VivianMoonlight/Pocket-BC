package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation

internal enum class InteractionMode {
    Closed,
    FullSheet,
    ActionPicker,
    ItemPicker,
    ClothingPicker,
}

/** Owns navigation and selection invariants for the room interaction sheet. */
internal class RoomInteractionSelectionState {
    var mode: InteractionMode = InteractionMode.Closed
        private set

    var targetMemberNumber: Int? = null
        private set

    var group: String? = null
        private set

    var itemPickerOperation: BcItemManagementOperation? = null
        private set

    var itemPickerItemName: String? = null
        private set

    var itemPickerQuery: String = ""
        private set

    var itemPickerShowUnavailable: Boolean = false
        private set

    var actionPickerShowUnavailable: Boolean = false
        private set

    private var itemPickerCatalogQuery: String = ""

    fun open(mode: InteractionMode, targetMemberNumber: Int? = null) {
        // BC keeps FocusGroup when switching between activities and inventory.
        val keepGroup = this.targetMemberNumber == targetMemberNumber && targetMemberNumber != null &&
            ((this.mode == InteractionMode.ActionPicker && mode == InteractionMode.ItemPicker) ||
                (this.mode == InteractionMode.ItemPicker && mode == InteractionMode.ActionPicker))
        this.mode = mode
        this.targetMemberNumber = targetMemberNumber
        if (!keepGroup) group = null
        resetItemPicker()
        actionPickerShowUnavailable = false
    }

    fun selectTarget(mode: InteractionMode, memberNumber: Int) {
        open(mode = mode, targetMemberNumber = memberNumber)
        resetGroup()
    }

    fun resetTarget(mode: InteractionMode) {
        open(mode = mode)
    }

    fun resetGroup() {
        group = null
        resetItemPicker()
    }

    fun selectGroup(group: String) {
        this.group = group.trim().ifBlank { null }
        if (mode == InteractionMode.ItemPicker || mode == InteractionMode.ClothingPicker) resetItemPicker()
    }

    fun selectItemPickerOperation(operation: BcItemManagementOperation) {
        ensurePickerMode()
        if ((operation == BcItemManagementOperation.Modify || operation == BcItemManagementOperation.Lock) && !hasItemPickerOptions()) {
            itemPickerCatalogQuery = itemPickerQuery
        }
        itemPickerOperation = operation
        itemPickerItemName = null
        itemPickerQuery = ""
    }

    fun returnToItemPickerCatalog(operation: BcItemManagementOperation) {
        ensurePickerMode()
        itemPickerOperation = operation
        itemPickerItemName = null
        itemPickerQuery = itemPickerCatalogQuery
    }

    fun resetItemPickerOperation() {
        ensurePickerMode()
        itemPickerOperation = null
        itemPickerItemName = null
    }

    fun selectItemPickerItem(itemName: String) {
        ensurePickerMode()
        if (!hasItemPickerOptions()) itemPickerCatalogQuery = itemPickerQuery
        itemPickerItemName = itemName.trim().ifBlank { null }
        itemPickerQuery = ""
    }

    fun resetItemPickerItem() {
        ensurePickerMode()
        itemPickerItemName = null
        itemPickerQuery = itemPickerCatalogQuery
    }

    fun updateItemPickerQuery(query: String) {
        ensurePickerMode()
        itemPickerQuery = query
        if (!hasItemPickerOptions()) itemPickerCatalogQuery = query
    }

    fun toggleItemPickerUnavailable() {
        ensurePickerMode()
        itemPickerShowUnavailable = !itemPickerShowUnavailable
    }

    fun toggleActionPickerUnavailable() {
        mode = InteractionMode.ActionPicker
        actionPickerShowUnavailable = !actionPickerShowUnavailable
    }

    fun activateItemPicker() {
        ensurePickerMode()
    }

    fun close() {
        open(InteractionMode.Closed)
    }

    fun updateAfterSuccessfulItemSend(operation: BcItemManagementOperation): Boolean {
        if (mode != InteractionMode.ItemPicker && mode != InteractionMode.ClothingPicker) return false
        itemPickerOperation = when (operation) {
            BcItemManagementOperation.Wear,
            BcItemManagementOperation.Replace,
            BcItemManagementOperation.Modify,
            BcItemManagementOperation.Unlock,
            BcItemManagementOperation.Lock,
            -> BcItemManagementOperation.Replace

            BcItemManagementOperation.Remove -> BcItemManagementOperation.Wear
        }
        itemPickerItemName = null
        itemPickerQuery = itemPickerCatalogQuery
        return true
    }

    private fun resetItemPicker() {
        itemPickerOperation = null
        itemPickerItemName = null
        itemPickerQuery = ""
        itemPickerCatalogQuery = ""
        itemPickerShowUnavailable = false
    }

    private fun hasItemPickerOptions(): Boolean =
        itemPickerItemName != null || itemPickerOperation == BcItemManagementOperation.Modify || itemPickerOperation == BcItemManagementOperation.Lock

    private fun ensurePickerMode() {
        if (mode != InteractionMode.ItemPicker && mode != InteractionMode.ClothingPicker) {
            mode = InteractionMode.ItemPicker
        }
    }
}
