package dev.vivian.mobilebc.core.model

const val BcWardrobeSlotCount = 24

data class BcWardrobeItem(
    val group: String,
    val name: String,
    val colorJson: String? = null,
    val propertyJson: String? = null,
)

enum class BcWardrobeSlotStatus {
    Empty,
    Saved,
    Unsupported,
}

data class BcWardrobeSlot(
    val index: Int,
    val name: String?,
    val status: BcWardrobeSlotStatus,
    val appearance: List<BcWardrobeItem>?,
    val rawSlotJson: String? = null,
)

data class BcWardrobeInventoryItem(
    val group: String,
    val name: String,
)

data class BcWardrobePrivateInputs(
    val gameJson: String? = null,
    val inventoryJson: String? = null,
    val inventoryData: String? = null,
    val gameAvailable: Boolean = false,
    val inventoryAvailable: Boolean = false,
    val inventoryItems: List<BcWardrobeInventoryItem>? = null,
    val inventoryIds: List<Int>? = null,
    val privateCharacterJson: String? = null,
    val privateCharacterAvailable: Boolean = false,
    val owner: String? = null,
)

data class BcWardrobeState(
    val memberNumber: Int,
    val selfRuleLogSessionId: String,
    val revision: Long,
    val rawWardrobeJson: String?,
    val rawNamesJson: String?,
    val slots: List<BcWardrobeSlot>,
    val wardrobeAvailable: Boolean,
    val namesAvailable: Boolean,
    val privateInputs: BcWardrobePrivateInputs = BcWardrobePrivateInputs(),
)

data class BcWardrobeSaveRequest(
    val slotIndex: Int,
    val expectedRevision: Long,
)

data class BcWardrobeRenameRequest(
    val slotIndex: Int,
    val expectedRevision: Long,
    val name: String,
)

data class BcWardrobeRestoreRequest(
    val slotIndex: Int,
    val expectedRevision: Long,
)

data class BcSelfAppearanceUpdateCommand(
    val appearance: List<BcCharacterItem>,
    val activePose: List<String>,
    val wardrobeRevision: Long,
    val wardrobeSlotIndex: Int,
)

enum class BcWardrobeEncodingRejectionReason {
    InvalidSlot,
    InvalidName,
    WardrobeUnavailable,
    NamesUnavailable,
    UnsupportedWardrobeData,
    UnsafeItemData,
    RevisionChanged,
}

sealed interface BcWardrobeEncodingResult {
    data class Encoded(val valueJson: String) : BcWardrobeEncodingResult
    data class Rejected(val reason: BcWardrobeEncodingRejectionReason) : BcWardrobeEncodingResult
}
