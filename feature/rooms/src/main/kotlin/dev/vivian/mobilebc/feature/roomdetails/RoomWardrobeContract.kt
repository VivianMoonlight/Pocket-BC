package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlan
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason

enum class RoomWardrobeEditMode { Save, Rename }
enum class RoomWardrobeOperation { Save, Rename, Load }

data class RoomWardrobeUiState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val blockedByOtherOperation: Boolean = false,
    val slots: List<BcWardrobeSlot> = emptyList(),
    val selectedSlotIndex: Int? = null,
    val editMode: RoomWardrobeEditMode? = null,
    val nameDraft: String = "",
    val nameValid: Boolean = true,
    val preview: BcWardrobePlan? = null,
    val unavailableReason: BcWardrobeUnavailableReason? = null,
    val canSave: Boolean = false,
    val canRename: Boolean = false,
    val canPreview: Boolean = false,
    val result: RoomWardrobeResult? = null,
)

sealed interface RoomWardrobeResult {
    data class Sent(val operation: RoomWardrobeOperation, val partial: Boolean = false) : RoomWardrobeResult
    data class AppearanceObserved(val partial: Boolean = false) : RoomWardrobeResult
    data class Rejected(val reason: BcWardrobeUnavailableReason) : RoomWardrobeResult
    data object Failed : RoomWardrobeResult
}
