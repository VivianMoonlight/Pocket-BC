package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateProjection
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementAvailabilityReason
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcInteractionZoneRect
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationFormState

data class RoomDetailsUiState(
    val roomTitle: String? = null,
    val onlineCount: Int = 0,
    val memberLimit: Int? = null,
    val mapType: BcRoomMapType? = null,
    val roomDescription: String = "",
    val characters: List<BcCharacterSnapshot> = emptyList(),
    val interactionMode: RoomDetailsInteractionMode? = null,
    val interactionSheet: CharacterInteractionSheetUiState? = null,
    val interactionNotice: InteractionUserMessage? = null,
    val selfStateSheet: SelfStateSheetUiState? = null,
    val canEditRoomSettings: Boolean = false,
    val roomForm: RoomAdministrationFormState? = null,
    val manageableMemberNumbers: Set<Int> = emptySet(),
    val memberAdministrationBusy: Boolean = false,
    val memberManagement: RoomMemberManagementUiState? = null,
    val canOpenWardrobe: Boolean = false,
    val wardrobe: RoomWardrobeUiState? = null,
)

data class RoomMemberManagementUiState(
    val visible: Boolean,
    val target: BcCharacterSnapshot,
    val availableActions: List<BcRoomMemberAdminAction>,
    val selectedAction: BcRoomMemberAdminAction? = null,
    val busy: Boolean = false,
    val result: RoomMemberManagementResult? = null,
    val unavailableReason: BcRoomMemberAdminRejectionReason? = null,
)

sealed interface RoomMemberManagementResult {
    data class Observed(val observation: BcRoomMemberAdminObservation) : RoomMemberManagementResult
    data class Rejected(val reason: BcRoomMemberAdminRejectionReason) : RoomMemberManagementResult
    data object Failed : RoomMemberManagementResult
}

data class SelfStateSheetUiState(
    val projection: BcSelfStateProjection,
    val busy: Boolean = false,
    val result: BcSelfStateResult? = null,
)

enum class RoomDetailsInteractionMode {
    FullSheet,
    ActionPicker,
    ItemPicker,
    ClothingPicker,
}

data class CharacterInteractionSheetUiState(
    val target: BcCharacterSnapshot,
    val version: String,
    val catalogReady: Boolean,
    val permissionReady: Boolean,
    val catalogMessage: InteractionUserMessage? = null,
    val busy: Boolean = false,
    val actionMessage: InteractionUserMessage? = null,
    val groups: List<InteractionGroupUiState> = emptyList(),
    val selectedGroup: InteractionGroupUiState? = null,
    val currentItem: InteractionCurrentItemUiState? = null,
    val activities: List<InteractionActivityUiState> = emptyList(),
    val items: List<InteractionItemUiState> = emptyList(),
    val itemPicker: InteractionItemPickerUiState? = null,
    val showUnavailableActivities: Boolean = false,
    val portrait: dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState? = null,
)

sealed interface InteractionUserMessage {
    data class General(
        val key: InteractionUserMessageKey,
        val argument: String? = null,
    ) : InteractionUserMessage

    data class Permission(
        val code: BcPermissionReasonCode,
    ) : InteractionUserMessage

    data class ItemAvailability(
        val reason: BcItemManagementAvailabilityReason,
    ) : InteractionUserMessage
}

enum class InteractionUserMessageKey {
    ChooseItemOperation,
    ChooseAction,
    ChooseItem,
    ChooseCurrentItemOption,
    ChooseBodyPart,
    ChooseItemGroup,
    ItemUnavailable,
    ItemPickerInvalid,
    ItemPickerStale,
    ItemForDifferentBodyPart,
    ItemForDifferentCharacter,
    InteractionDataPreparing,
    SelfCharacterUnavailable,
    TargetLeftRoom,
    ActionSent,
    ItemUpdated,
    ItemReplaced,
    ItemConfigured,
    ItemRemoved,
    ItemUnlocked,
    ItemLocked,
    RequestRejected,
    RequestFailed,
    ResourceSyncFailed,
    NoMatchingItems,
    NoAllowedMatchingItems,
    NoItems,
    NoAllowedItems,
}

data class InteractionGroupUiState(
    val name: String,
    val displayName: String,
    val hasActivities: Boolean,
    val hasItems: Boolean,
    val hasCurrentItem: Boolean,
    val kind: BcAppearanceGroupKind = BcAppearanceGroupKind.Appearance,
    val zones: List<BcInteractionZoneRect> = emptyList(),
)

data class InteractionActivityUiState(
    val group: String,
    val name: String,
    val displayName: String,
    val activityAssetGroup: String? = null,
    val activityAssetName: String? = null,
    val usesWornItem: Boolean = false,
    val warningMessage: InteractionUserMessage? = null,
    val enabled: Boolean = true,
    val permissionMessage: InteractionUserMessage? = null,
    val effects: Set<InteractionActivityEffectUiState> = emptySet(),
)

enum class InteractionActivityEffectUiState {
    Pose,
    Expression,
    PairedItem,
}

data class InteractionItemUiState(
    val group: String,
    val name: String,
    val displayName: String,
    val enabled: Boolean = true,
    val permissionMessage: InteractionUserMessage? = null,
    val defaultPropertyJson: String? = null,
    val defaultDifficulty: Int? = null,
    val extendedKind: String? = null,
    val extendedStatusMessage: String? = null,
)

data class InteractionCurrentItemUiState(
    val group: String,
    val name: String,
    val displayName: String,
    val difficulty: Int? = null,
    val selectedOptionSummary: String? = null,
    val extendedKind: String? = null,
    val extendedStatusMessage: String? = null,
    val configurable: Boolean = false,
    val removeEnabled: Boolean = true,
    val removePermissionMessage: InteractionUserMessage? = null,
)

data class InteractionItemPickerUiState(
    val step: InteractionItemPickerStep,
    val selectedOperation: InteractionItemPickerOperationUiState? = null,
    val selectedItemName: String? = null,
    val selectedItemDisplayName: String? = null,
    val currentItemDisplayName: String? = null,
    val currentItemDescription: String? = null,
    val currentItemTextLines: List<String> = emptyList(),
    val currentItemTextFields: Map<String, Int> = emptyMap(),
    val currentItemTextValues: Map<String, String> = emptyMap(),
    val currentItemTextEditEntry: InteractionItemPickerEntryUiState? = null,
    val currentItemName: String? = null,
    val currentItemRemoveEntry: InteractionItemPickerEntryUiState? = null,
    val currentItemUnlockEntry: InteractionItemPickerEntryUiState? = null,
    val currentItemCredentialUnlockEntry: InteractionItemPickerEntryUiState? = null,
    val currentItemLockInputKind: dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind? = null,
    val lockInputConditions: dev.vivian.mobilebc.domain.bccharacterstate.BcLockInputConditions = dev.vivian.mobilebc.domain.bccharacterstate.BcLockInputConditions(),
    val currentItemLockHint: String? = null,
    val currentItemLockConfigurationMode: dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode? = null,
    val currentItemLockConfigurationEntry: InteractionItemPickerEntryUiState? = null,
    val currentItemKeyholders: String = "",
    val currentItemKeysDeposited: Boolean = false,
    val currentItemLockIsTimerPassword: Boolean = false,
    val currentItemRemoveOnUnlock: Boolean = false,
    val currentItemTimer: InteractionItemTimerUiState? = null,
    val currentItemCanLock: Boolean = false,
    val currentItemIsAfcHeartLock: Boolean = false,
    val currentItemLockOwnerNumber: Int? = null,
    val lockingCurrentItem: Boolean = false,
    val currentItemConfigurable: Boolean = false,
    val configuringCurrentItem: Boolean = false,
    val query: String = "",
    val showUnavailable: Boolean = false,
    val operations: List<InteractionItemPickerOperationUiState> = emptyList(),
    val entries: List<InteractionItemPickerEntryUiState> = emptyList(),
    val hasMore: Boolean = false,
    val nextOffset: Int? = null,
    val loadedCount: Int = entries.size,
    val totalKnownEntries: Int = 0,
    val emptyMessage: InteractionUserMessage? = null,
)

data class InteractionItemTimerUiState(
    val basic: Boolean,
    val removeItem: Boolean,
    val showTimer: Boolean,
    val randomInput: Boolean,
    val deadlineMillis: Double?,
    val clock: dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor?,
    val canOpen: Boolean,
    val permissionMessage: InteractionUserMessage? = null,
    val settings: InteractionItemPickerEntryUiState? = null,
    val adjust: InteractionItemPickerEntryUiState? = null,
    val restart: InteractionItemPickerEntryUiState? = null,
    val limited: InteractionItemPickerEntryUiState? = null,
    val limitedStepSeconds: Int? = null,
    val maximumSeconds: Long? = null,
)

enum class InteractionItemPickerStep {
    BodyPart,
    Item,
}

data class InteractionItemPickerOperationUiState(
    val name: String,
    val enabled: Boolean = true,
    val subtitle: String? = null,
)

data class InteractionItemPickerEntryUiState(
    val operation: String,
    val group: String,
    val itemName: String? = null,
    val currentItemName: String? = null,
    val label: String,
    val subtitle: String? = null,
    val propertyJson: String? = null,
    val difficulty: Int? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val permissionMessage: InteractionUserMessage? = null,
    val extendedKind: String? = null,
    val extendedStatusMessage: String? = null,
    val canOpenOptions: Boolean = false,
    val candidate: BcInteractionCandidate? = null,
    val requiresTextInput: Boolean = false,
    val requiresUnlockInput: Boolean = false,
)
