package dev.vivian.mobilebc.domain.bcwardrobe

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog

/** A slot action owned by one authenticated account and one visit to its current room. */
data class BcWardrobeRequest(
    val targetMemberNumber: Int,
    val roomName: BcRoomName,
    val roomPresenceEpoch: Long,
    val accountGeneration: Long,
    val slotIndex: Int,
    val expectedRevision: Long,
    val rules: BcRuleCatalog,
)

/** Inputs copied atomically from either the domain session or the socket's canonical live state. */
data class BcWardrobeContext(
    val self: BcCharacterSnapshot,
    val roomName: BcRoomName,
    val roomSpace: String?,
    val roomGame: String?,
    val roomMemberNumbers: Set<Int>,
    val roomBlockCategories: Set<String>,
    val wardrobeState: BcWardrobeState?,
    val privateLog: List<BcOwnerRuleLogRecord>?,
    val loginSessionId: String?,
    val serverTimeMillis: Long?,
) {
    fun sourceState(): BcWardrobeSourceState = BcWardrobeSourceState(
        self, roomName, roomSpace, roomGame, roomMemberNumbers, roomBlockCategories,
        wardrobeState, privateLog, loginSessionId,
    )
}

/** The exact preview source, excluding a moving clock; timed permission is rechecked separately. */
data class BcWardrobeSourceState(
    val character: BcCharacterSnapshot,
    val roomName: BcRoomName,
    val roomSpace: String?,
    val roomGame: String?,
    val roomMemberNumbers: Set<Int>,
    val roomBlockCategories: Set<String>,
    val wardrobeState: BcWardrobeState?,
    val privateLog: List<BcOwnerRuleLogRecord>?,
    val loginSessionId: String?,
)

enum class BcWardrobeUnavailableReason {
    Offline,
    NotInRoom,
    SelfUnavailable,
    AccountChanged,
    RoomChanged,
    CharacterStateChanged,
    UnsupportedAssetFamily,
    CatalogPreparing,
    PrivateStateUnavailable,
    WardrobeLocked,
    Restrained,
    ClubSlave,
    GameBlocksChange,
    AsylumBlocksChange,
    BlockChangeRule,
    BlockChangeOwnerRule,
    InvalidSlot,
    EmptySlot,
    UnsupportedSlot,
    RevisionChanged,
    InvalidName,
    NamesUnavailable,
    NoChanges,
}

enum class BcWardrobeEntryReason {
    UnknownGroup,
    UnknownAsset,
    UnsupportedMetadata,
    DuplicateGroup,
    ItemGroupPreserved,
    LockedCurrentItem,
    BlockAppearanceOwnerRule,
    GroupNotCustomizable,
    ColorNotCustomizable,
    InventoryUnavailable,
    UnsafeSlotProperty,
    UnsupportedItemProperty,
    PermissionDenied,
    MandatoryFallbackUnavailable,
    FinalAppearanceIncompatible,
}

enum class BcWardrobeEntryAction { Applied, Removed, RestoredDefault, Preserved }

data class BcWardrobePlanEntry(
    val group: String,
    val currentName: String?,
    val desiredName: String?,
    val action: BcWardrobeEntryAction,
    val reason: BcWardrobeEntryReason? = null,
    val detail: String? = null,
)

/** The whole outgoing appearance. Item groups and every rejected group retain their current data. */
data class BcWardrobePlan(
    val appearance: List<BcCharacterItem>,
    val entries: List<BcWardrobePlanEntry>,
    val unavailableReason: BcWardrobeUnavailableReason? = null,
    val hasChanges: Boolean = false,
    val sourceState: BcWardrobeSourceState? = null,
) {
    val partial: Boolean get() = entries.any { it.reason != null && it.reason != BcWardrobeEntryReason.ItemGroupPreserved }
}

sealed interface BcWardrobeResult {
    /** The command reached the socket emission boundary; subsequent room sync owns the appearance. */
    data class Sent(val plan: BcWardrobePlan? = null) : BcWardrobeResult
    data class Rejected(val reason: BcWardrobeUnavailableReason) : BcWardrobeResult
    data class Failed(val reason: String) : BcWardrobeResult
}

class BcWardrobeEmissionRejectedException(val reason: BcWardrobeUnavailableReason) : IllegalStateException(reason.name)
