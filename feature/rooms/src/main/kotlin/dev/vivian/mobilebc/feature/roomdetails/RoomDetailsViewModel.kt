package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.RoomRole
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import dev.vivian.mobilebc.feature.characterprofile.characterPortrait
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcitemproperty.validText
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcInteractionPermissionEngine
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcItemManagementQuery
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.character.toCharacterRosterState
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRequest
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRules
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationDraft
import dev.vivian.mobilebc.feature.rooms.RoomAdministrationFormController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Coordinates RoomDetails intents, asynchronous catalog loading, and interaction side effects. */
class RoomDetailsViewModel(
    private val connectionStatus: ConnectionStatusReader,
    private val characterInteractions: BcCharacterInteractions,
    private val interactionCatalogRepository: BcInteractionCatalogRepository,
    private val ruleCatalogRepository: BcRuleCatalogRepository,
    private val permissionEngine: DefaultBcInteractionPermissionEngine = DefaultBcInteractionPermissionEngine(),
    itemManagementQuery: DefaultBcItemManagementQuery = DefaultBcItemManagementQuery(permissionEngine),
    private val currentVersion: StateFlow<String> = MutableStateFlow(""),
    private val syncResources: suspend (String) -> Unit = {},
    private val scope: CoroutineScope,
    private val catalogDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val rooms: BcSessionRooms? = null,
    backgroundNamesProvider: (BcRoomSpace) -> List<String> = { emptyList() },
) {
    private val roomForm = rooms?.let {
        RoomAdministrationFormController(
            rooms = it,
            connectionStatus = connectionStatus,
            scope = scope,
            backgroundNamesProvider = backgroundNamesProvider,
        )
    }
    private val selection = RoomInteractionSelectionState()
    private val projector = RoomDetailsInteractionProjector(permissionEngine, privateSnapshot = { connectionStatus.snapshot.value }) {
        connectionStatus.snapshot.value.toCharacterRosterState()
    }
    private val itemPicker = RoomDetailsItemPickerController(itemManagementQuery, catalogProvider = interactionCatalogRepository::currentSnapshot)

    private val interactionBusy: Boolean get() = interactionJob != null
    private var interactionMessage: InteractionUserMessage? = null
    private var lastTargetMemberNumber: Int? = null
    private var interactionOwner: InteractionOwner? = null
    private val recentActivities = ArrayDeque<Pair<String, String>>()
    private var catalogMessage: InteractionUserMessage? = null
    private var catalogRefreshJob: Job? = null
    private var interactionJob: Job? = null
    private val selfStateRules = BcSelfStateRules()
    private var selfStateOpen = false
    private var selfStateOwner: SelfStateOwner? = null
    private val selfStateBusy: Boolean get() = selfStateJob != null
    private var selfStateResult: BcSelfStateResult? = null
    private var selfStateJob: Job? = null
    private var selfStateClockJob: Job? = null
    private var memberManagementSelection: MemberManagementSelection? = null
    private var memberManagementVisible = false
    private var memberAdminPending: MemberAdminSubmission? = null
    private var memberAdminResult: RoomMemberManagementResult? = null
    private val wardrobeController = RoomWardrobeController(
        characterInteractions = characterInteractions,
        connectionStatus = connectionStatus,
        currentVersion = currentVersion,
        ruleCatalogRepository = ruleCatalogRepository,
        scope = scope,
        isOtherMutationBusy = { selfStateBusy || interactionBusy || memberAdminPending != null || roomForm?.state?.value?.busy == true },
    )

    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<RoomDetailsUiState> = _uiState.asStateFlow()

    init {
        scope.launch { connectionStatus.snapshot.collect { refresh() } }
        roomForm?.let { form -> scope.launch { form.state.collect { refresh() } } }
        scope.launch { wardrobeController.state.collect { refresh() } }
        scope.launch {
            currentVersion
                .map { version ->
                    version.takeIf(String::isNotBlank)?.normalizedBcVersion()
                }
                .filterNotNull()
                .distinctUntilChanged()
                .collect(::refreshCatalogAsync)
        }
    }

    fun openSelfState() {
        if (wardrobeController.isBusy) return
        wardrobeController.close()
        hideMemberManagement()
        roomForm?.close()
        cancelInteraction()
        cancelSelfState()
        selection.close()
        itemPicker.clear()
        interactionMessage = null
        selfStateOpen = true
        val snapshot = connectionStatus.snapshot.value
        selfStateOwner = snapshot.account?.memberNumber?.let { memberNumber ->
            snapshot.currentRoom?.name?.let { roomName ->
                SelfStateOwner(memberNumber, roomName, snapshot.roomPresenceEpoch, snapshot.relationships.accountGeneration)
            }
        }
        selfStateResult = null
        refresh()
        currentVersion.value.takeIf(String::isNotBlank)?.normalizedBcVersion()?.let { version ->
            if (ruleCatalogRepository.currentSnapshot(version) == null) refreshCatalogAsync(version)
        }
    }

    fun closeSelfState() {
        cancelSelfState()
        selfStateOpen = false
        selfStateOwner = null
        selfStateResult = null
        refresh()
    }

    fun openRoomSettings() {
        val snapshot = connectionStatus.snapshot.value
        if (roomForm == null || memberAdminPending != null || wardrobeController.isBusy || !snapshot.isVerifiedOnline ||
            snapshot.account?.memberNumber !in snapshot.currentRoom?.adminSettings?.adminMemberNumbers.orEmpty()
        ) return
        wardrobeController.close()
        hideMemberManagement()
        closeSelfState()
        closeInteract()
        roomForm?.openEdit()
    }

    fun closeRoomSettings() {
        roomForm?.close()
    }

    fun changeRoomSettingsDraft(draft: RoomAdministrationDraft) {
        roomForm?.changeDraft(draft)
    }

    fun submitRoomSettings() {
        if (memberAdminPending != null || wardrobeController.isBusy) return
        roomForm?.submit()
    }

    fun reloadRoomSettings() {
        roomForm?.reloadCurrentSettings()
    }

    fun openWardrobe() {
        if (selfStateBusy || interactionBusy || memberAdminPending != null || roomForm?.state?.value?.busy == true) return
        wardrobeController.open()
        if (!wardrobeController.state.value.visible) return
        closeSelfState()
        closeInteract()
        hideMemberManagement()
        roomForm?.close()
        refresh()
        currentVersion.value.takeIf(String::isNotBlank)?.normalizedBcVersion()?.let { version ->
            if (ruleCatalogRepository.currentSnapshot(version) == null) refreshCatalogAsync(version)
        }
    }

    fun closeWardrobe() = wardrobeController.close()
    fun selectWardrobeSlot(index: Int) = wardrobeController.selectSlot(index)
    fun beginWardrobeSave() = wardrobeController.beginSave()
    fun beginWardrobeRename() = wardrobeController.beginRename()
    fun changeWardrobeName(name: String) = wardrobeController.changeName(name)
    fun cancelWardrobeEdit() = wardrobeController.cancelEdit()
    fun confirmWardrobeSave() = wardrobeController.confirmSave()
    fun confirmWardrobeRename() = wardrobeController.confirmRename()
    fun previewWardrobeLoad() = wardrobeController.previewLoad()
    fun cancelWardrobeLoad() = wardrobeController.cancelLoad()
    fun confirmWardrobeLoad() = wardrobeController.confirmLoad()
    fun clearWardrobeResult() = wardrobeController.clearResult()

    fun openMemberManagement(memberNumber: Int) {
        val snapshot = connectionStatus.snapshot.value
        if (rooms == null || wardrobeController.isBusy || roomForm?.state?.value?.busy == true) return
        wardrobeController.close()
        if (memberAdminPending != null) {
            val selected = memberManagementSelection ?: return
            if (selected.target.memberNumber != memberNumber || !selected.owner.matches(snapshot)) return
            memberManagementVisible = true
        } else {
            if (!hasMemberAdministrationAuthority(snapshot)) return
            val target = snapshot.roomCharacters.firstOrNull { it.memberNumber == memberNumber && !it.isSelf } ?: return
            val member = snapshot.roomMembers.firstOrNull { it.memberNumber == memberNumber && !it.isSelf } ?: return
            val targetEpoch = member.roomMemberPresenceEpoch ?: return
            if (memberNumber == snapshot.account?.memberNumber) return
            val owner = MemberManagementOwner.from(snapshot, memberNumber, targetEpoch) ?: return
            memberManagementSelection = MemberManagementSelection(owner, target)
            memberManagementVisible = true
            memberAdminResult = null
        }
        roomForm?.close()
        closeSelfState()
        closeInteract()
        refresh()
    }

    fun closeMemberManagement() {
        hideMemberManagement()
        refresh()
    }

    fun selectMemberAdminAction(action: BcRoomMemberAdminAction) {
        val selected = memberManagementSelection ?: return
        if (memberAdminPending != null || !memberManagementVisible || roomForm?.state?.value?.busy == true) return
        val snapshot = connectionStatus.snapshot.value
        if (memberAdministrationUnavailable(selected, snapshot) != null || snapshot.state != BcSessionState.Ready) return
        val target = snapshot.roomMembers.firstOrNull { it.memberNumber == selected.target.memberNumber } ?: return
        if (action !in availableMemberActions(target.role)) return
        memberManagementSelection = selected.copy(selectedAction = action, selectedTargetIsAdministrator = target.role == RoomRole.Admin)
        memberAdminResult = null
        refresh()
    }

    fun cancelMemberAdminAction() {
        if (memberAdminPending != null) return
        memberManagementSelection = memberManagementSelection?.copy(selectedAction = null, selectedTargetIsAdministrator = null)
        refresh()
    }

    fun confirmMemberAdminAction() {
        val selected = memberManagementSelection ?: return
        val action = selected.selectedAction ?: return
        if (!memberManagementVisible || memberAdminPending != null || wardrobeController.isBusy || roomForm?.state?.value?.busy == true) return
        val snapshot = connectionStatus.snapshot.value
        val rejection = memberAdministrationUnavailable(selected, snapshot)
            ?: BcRoomMemberAdminRejectionReason.Busy.takeIf { snapshot.state != BcSessionState.Ready }
        if (rejection != null) {
            memberAdminResult = RoomMemberManagementResult.Rejected(rejection)
            memberManagementSelection = selected.copy(selectedAction = null, selectedTargetIsAdministrator = null)
            refresh()
            return
        }
        val member = snapshot.roomMembers.firstOrNull { it.memberNumber == selected.target.memberNumber } ?: return
        val isAdministrator = member.role == RoomRole.Admin
        if (selected.selectedTargetIsAdministrator != isAdministrator || action !in availableMemberActions(member.role)) {
            memberAdminResult = RoomMemberManagementResult.Rejected(BcRoomMemberAdminRejectionReason.TargetRoleChanged)
            memberManagementSelection = selected.copy(selectedAction = null, selectedTargetIsAdministrator = null)
            refresh()
            return
        }
        val sessionRooms = rooms ?: return
        val command = BcRoomMemberAdminCommand(
            roomName = selected.owner.roomName,
            roomPresenceEpoch = selected.owner.gatewayRoomPresenceEpoch,
            targetMemberNumber = selected.target.memberNumber,
            targetPresenceEpoch = selected.owner.targetPresenceEpoch,
            action = action,
            targetIsAdministrator = isAdministrator,
        )
        val submission = MemberAdminSubmission(selected.owner, command)
        memberAdminPending = submission
        memberAdminResult = null
        refresh()
        scope.launch {
            try {
                val observation = sessionRooms.manageRoomMember(command)
                if (memberAdminCompletionStillOwned(submission)) {
                    memberAdminResult = RoomMemberManagementResult.Observed(observation)
                }
            } catch (cancelled: CancellationException) {
                if (memberAdminCompletionStillOwned(submission)) {
                    memberAdminResult = RoomMemberManagementResult.Observed(BcRoomMemberAdminObservation.Unconfirmed)
                }
                throw cancelled
            } catch (failure: Exception) {
                if (memberAdminCompletionStillOwned(submission)) {
                    memberAdminResult = if (failure is BcRoomMemberAdminRejectedException) {
                        RoomMemberManagementResult.Rejected(failure.reason)
                    } else RoomMemberManagementResult.Failed
                }
            }
        }.invokeOnCompletion {
            if (memberAdminPending === submission) {
                memberAdminPending = null
                if (memberManagementSelection?.owner == submission.owner) {
                    memberManagementSelection = memberManagementSelection?.copy(selectedAction = null, selectedTargetIsAdministrator = null)
                }
                refresh()
            }
        }
    }

    fun clearMemberAdminResult() {
        memberAdminResult = null
        refresh()
    }

    private fun hideMemberManagement() {
        memberManagementVisible = false
    }

    private fun hasMemberAdministrationAuthority(snapshot: BcSessionSnapshot): Boolean =
        rooms != null && snapshot.isVerifiedOnline && snapshot.currentRoom != null &&
            snapshot.roomMembers.any { it.memberNumber == snapshot.account?.memberNumber && it.isSelf && it.role == RoomRole.Admin }

    private fun availableMemberActions(role: RoomRole?): List<BcRoomMemberAdminAction> = when (role) {
        RoomRole.Admin -> listOf(BcRoomMemberAdminAction.Kick, BcRoomMemberAdminAction.Ban, BcRoomMemberAdminAction.Demote)
        RoomRole.Member -> listOf(BcRoomMemberAdminAction.Kick, BcRoomMemberAdminAction.Ban, BcRoomMemberAdminAction.Promote)
        else -> emptyList()
    }

    private fun memberAdministrationUnavailable(selected: MemberManagementSelection, snapshot: BcSessionSnapshot): BcRoomMemberAdminRejectionReason? {
        if (!snapshot.isVerifiedOnline) return BcRoomMemberAdminRejectionReason.ConnectionUnavailable
        if (!selected.owner.sameLogin(snapshot)) return BcRoomMemberAdminRejectionReason.AccountUnavailable
        if (snapshot.currentRoom == null) return BcRoomMemberAdminRejectionReason.RoomUnavailable
        if (!selected.owner.matches(snapshot)) return BcRoomMemberAdminRejectionReason.RoomChanged
        if (!hasMemberAdministrationAuthority(snapshot)) return BcRoomMemberAdminRejectionReason.NotAdministrator
        val target = snapshot.roomMembers.firstOrNull { it.memberNumber == selected.target.memberNumber }
            ?: return BcRoomMemberAdminRejectionReason.TargetUnavailable
        if (target.isSelf || target.memberNumber == snapshot.account?.memberNumber) return BcRoomMemberAdminRejectionReason.SelfTarget
        if (target.roomMemberPresenceEpoch != selected.owner.targetPresenceEpoch) return BcRoomMemberAdminRejectionReason.TargetChanged
        if (target.role != RoomRole.Admin && target.role != RoomRole.Member) return BcRoomMemberAdminRejectionReason.SettingsUnavailable
        return null
    }

    private fun memberAdminCompletionStillOwned(submission: MemberAdminSubmission): Boolean {
        val snapshot = connectionStatus.snapshot.value
        if (memberManagementSelection?.owner != submission.owner || !submission.owner.matches(snapshot)) return false
        val target = snapshot.roomMembers.firstOrNull { it.memberNumber == submission.command.targetMemberNumber }
        return target == null || target.roomMemberPresenceEpoch == submission.command.targetPresenceEpoch
    }

    private fun refreshMemberManagement(snapshot: BcSessionSnapshot) {
        val selected = memberManagementSelection ?: return
        val member = snapshot.roomMembers.firstOrNull { it.memberNumber == selected.target.memberNumber }
        (memberAdminResult as? RoomMemberManagementResult.Observed)?.let { result ->
            val stillSupported = when (result.observation) {
                BcRoomMemberAdminObservation.TargetLeft -> member == null
                BcRoomMemberAdminObservation.TargetBanned -> snapshot.currentRoom?.banMemberNumbers?.contains(selected.target.memberNumber) == true
                BcRoomMemberAdminObservation.TargetPromoted -> member?.role == RoomRole.Admin
                BcRoomMemberAdminObservation.TargetDemoted -> member?.role == RoomRole.Member
                BcRoomMemberAdminObservation.Unconfirmed -> true
            }
            if (!stillSupported) memberAdminResult = null
        }
        val retainedOutcome = (memberAdminResult as? RoomMemberManagementResult.Observed)?.observation in
            listOf(BcRoomMemberAdminObservation.TargetLeft, BcRoomMemberAdminObservation.TargetBanned, BcRoomMemberAdminObservation.Unconfirmed)
        if (!selected.owner.matches(snapshot) || member != null && member.roomMemberPresenceEpoch != selected.owner.targetPresenceEpoch ||
            member == null && memberAdminPending == null && !retainedOutcome
        ) {
            memberManagementSelection = null
            memberManagementVisible = false
            memberAdminResult = null
            return
        }
        val liveTarget = snapshot.roomCharacters.firstOrNull { it.memberNumber == selected.target.memberNumber } ?: selected.target
        val roleChanged = selected.selectedAction != null && memberAdminPending == null &&
            (member?.role !in listOf(RoomRole.Admin, RoomRole.Member) || selected.selectedTargetIsAdministrator != (member?.role == RoomRole.Admin))
        memberManagementSelection = if (roleChanged) {
            memberAdminResult = RoomMemberManagementResult.Rejected(BcRoomMemberAdminRejectionReason.TargetRoleChanged)
            selected.copy(target = liveTarget, selectedAction = null, selectedTargetIsAdministrator = null)
        } else selected.copy(target = liveTarget)
    }

    private fun buildMemberManagement(snapshot: BcSessionSnapshot): RoomMemberManagementUiState? {
        val selected = memberManagementSelection ?: return null
        val reason = memberAdministrationUnavailable(selected, snapshot)
            ?: BcRoomMemberAdminRejectionReason.Busy.takeIf { memberAdminPending == null && snapshot.state != BcSessionState.Ready }
            ?: BcRoomMemberAdminRejectionReason.Busy.takeIf { roomForm?.state?.value?.busy == true }
        val targetRole = snapshot.roomMembers.firstOrNull { it.memberNumber == selected.target.memberNumber }?.role
        return RoomMemberManagementUiState(
            visible = memberManagementVisible,
            target = selected.target,
            availableActions = if (reason == null) availableMemberActions(targetRole) else emptyList(),
            selectedAction = selected.selectedAction,
            busy = memberAdminPending != null,
            result = memberAdminResult,
            unavailableReason = reason,
        )
    }

    fun clearSelfStateResult() {
        selfStateResult = null
        refresh()
    }

    fun changeSelfState(change: BcSelfStateChange) {
        if (!selfStateOpen || selfStateBusy || wardrobeController.isBusy) return
        val owner = selfStateOwner
        val version = currentVersion.value.takeIf(String::isNotBlank)?.normalizedBcVersion()
        val rules = version?.let(ruleCatalogRepository::currentSnapshot)
        if (owner == null || rules == null) {
            selfStateResult = BcSelfStateResult.Rejected(
                selfStateRules.projection(connectionStatus.snapshot.value, rules).unavailableReason
                    ?: BcSelfStateUnavailableReason.CatalogPreparing,
            )
            refresh()
            return
        }
        val request = BcSelfStateRequest(
            targetMemberNumber = owner.memberNumber,
            roomName = owner.roomName,
            roomPresenceEpoch = owner.roomPresenceEpoch,
            accountGeneration = owner.accountGeneration,
            change = change,
            rules = rules,
        )
        selfStateRules.rejection(request, connectionStatus.snapshot.value)?.let { reason ->
            selfStateResult = BcSelfStateResult.Rejected(reason)
            refresh()
            return
        }
        selfStateResult = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val currentJob = currentCoroutineContext()[Job]
            val result = try {
                characterInteractions.updateSelfState(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                BcSelfStateResult.Failed(throwable.message ?: throwable::class.java.simpleName)
            }
            currentCoroutineContext().ensureActive()
            if (selfStateJob === currentJob) selfStateResult = result
        }
        selfStateJob = job
        job.invokeOnCompletion {
            if (selfStateJob === job) {
                selfStateJob = null
                refresh()
            }
        }
        refresh()
        job.start()
    }

    fun openInteract(memberNumber: Int) {
        lastTargetMemberNumber = memberNumber
        openInteraction(
            mode = InteractionMode.FullSheet,
            targetMemberNumber = memberNumber,
        )
    }

    fun openActionPicker(memberNumber: Int? = null) {
        openInteraction(
            mode = InteractionMode.ActionPicker,
            targetMemberNumber = preferredTarget(memberNumber),
        )
    }

    fun selectActionPickerTarget(memberNumber: Int) {
        lastTargetMemberNumber = memberNumber
        selection.selectTarget(InteractionMode.ActionPicker, memberNumber)
        rememberInteractionOwner()
        onSelectionChanged()
    }

    fun resetActionPickerTarget() {
        selection.resetTarget(InteractionMode.ActionPicker)
        onSelectionChanged()
    }

    fun openItemPicker(memberNumber: Int? = null) {
        openInteraction(
            mode = InteractionMode.ItemPicker,
            targetMemberNumber = preferredTarget(memberNumber),
        )
    }

    fun openClothingPicker(memberNumber: Int? = null) {
        openInteraction(
            mode = InteractionMode.ClothingPicker,
            targetMemberNumber = preferredTarget(memberNumber),
        )
    }

    fun selectItemPickerTarget(memberNumber: Int) {
        lastTargetMemberNumber = memberNumber
        selection.selectTarget(InteractionMode.ItemPicker, memberNumber)
        rememberInteractionOwner()
        onSelectionChanged()
    }

    fun selectClothingPickerTarget(memberNumber: Int) {
        lastTargetMemberNumber = memberNumber
        selection.selectTarget(InteractionMode.ClothingPicker, memberNumber)
        rememberInteractionOwner()
        onSelectionChanged()
    }

    fun resetItemPickerTarget() {
        selection.resetTarget(InteractionMode.ItemPicker)
        onSelectionChanged()
    }

    fun resetClothingPickerTarget() {
        selection.resetTarget(InteractionMode.ClothingPicker)
        onSelectionChanged()
    }

    fun resetItemPickerGroup() {
        selection.resetGroup()
        onSelectionChanged()
    }

    fun selectItemPickerOperation(operationName: String) {
        val operation = operationName.toItemManagementOperation()
        if (operation == null) {
            showInteractionMessage(InteractionUserMessageKey.ChooseItemOperation)
            return
        }
        selection.selectItemPickerOperation(operation)
        onSelectionChanged()
    }

    fun resetItemPickerOperation() {
        selection.resetItemPickerOperation()
        onSelectionChanged()
    }

    fun selectItemPickerItem(itemName: String) {
        selection.selectItemPickerItem(itemName)
        onSelectionChanged()
    }

    fun resetItemPickerItem() {
        selection.resetItemPickerItem()
        onSelectionChanged()
    }

    fun returnToItemPickerCatalog() {
        val target = selection.targetMemberNumber?.let(::findCharacter) ?: run {
            selection.resetTarget(selection.mode)
            onSelectionChanged()
            return
        }
        val group = selection.group ?: return
        selection.returnToItemPickerCatalog(defaultCatalogOperation(target, group))
        onSelectionChanged()
    }

    fun handlePickerBack(): Boolean {
        if (selfStateOpen) {
            closeSelfState()
            return true
        }
        when (selection.mode) {
            InteractionMode.Closed,
            InteractionMode.FullSheet,
            -> return false

            InteractionMode.ActionPicker -> closeInteract()

            InteractionMode.ItemPicker,
            InteractionMode.ClothingPicker,
            -> when {
                selection.itemPickerItemName != null ||
                    selection.itemPickerOperation == BcItemManagementOperation.Modify ||
                    selection.itemPickerOperation == BcItemManagementOperation.Lock -> returnToItemPickerCatalog()
                else -> closeInteract()
            }
        }
        return true
    }

    fun updateItemPickerQuery(query: String) {
        selection.updateItemPickerQuery(query)
        onSelectionChanged()
    }

    fun toggleItemPickerUnavailable() {
        selection.toggleItemPickerUnavailable()
        onSelectionChanged()
    }

    fun toggleActionPickerUnavailable() {
        selection.toggleActionPickerUnavailable()
        interactionMessage = null
        refresh()
    }

    fun loadNextItemPickerPage() {
        selection.activateItemPicker()
        val target = selection.targetMemberNumber?.let(::findCharacter) ?: return
        val group = selection.group ?: return
        val version = normalizedVersion()
        val rules = ruleCatalogRepository.currentSnapshot(version) ?: return
        val context = projector.permissionContext(target = target, rules = rules) ?: return
        if (
            itemPicker.loadNext(
                selection = selection,
                target = target,
                group = group,
                version = version,
                context = context,
                rules = rules,
            )
        ) {
            interactionMessage = null
            refresh()
        }
    }

    fun closeInteract() {
        cancelInteraction()
        selection.close()
        itemPicker.clear()
        interactionMessage = null
        refresh()
    }

    fun selectInteractionGroup(group: String) {
        selection.selectGroup(group)
        if (
            selection.mode == InteractionMode.ItemPicker ||
            selection.mode == InteractionMode.ClothingPicker
        ) {
            selection.targetMemberNumber
                ?.let(::findCharacter)
                ?.let { target -> selection.selectItemPickerOperation(defaultCatalogOperation(target, group)) }
        }
        onSelectionChanged()
    }

    fun clearInteractionMessage() {
        interactionMessage = null
        refresh()
    }

    fun sendActivity(
        group: String,
        activityName: String,
        activityAssetGroup: String? = null,
        activityAssetName: String? = null,
    ) {
        if (group.isBlank() || activityName.isBlank()) {
            showInteractionMessage(InteractionUserMessageKey.ChooseAction)
            return
        }

        val hasSpecificAsset = !activityAssetGroup.isNullOrBlank() &&
            !activityAssetName.isNullOrBlank()
        val candidate = BcInteractionCandidate.Activity(
            targetMemberNumber = selection.targetMemberNumber ?: -1,
            group = group,
            activityName = activityName,
            activityAssetGroup = activityAssetGroup.takeIf { hasSpecificAsset },
            activityAssetName = activityAssetName.takeIf { hasSpecificAsset },
        )
        sendCandidate(
            candidate = candidate,
            successMessage = InteractionUserMessage.General(InteractionUserMessageKey.ActionSent),
            onSent = {
                val key = group to activityName
                recentActivities.remove(key)
                recentActivities.addFirst(key)
                while (recentActivities.size > 6) recentActivities.removeLast()
            },
        )
    }

    fun wearItem(
        group: String,
        itemName: String,
        propertyJson: String? = null,
        difficulty: Int? = null,
    ) {
        if (group.isBlank() || itemName.isBlank()) {
            showInteractionMessage(InteractionUserMessageKey.ChooseItem)
            return
        }
        sendCandidate(
            candidate = BcInteractionCandidate.WearItem(
                targetMemberNumber = selection.targetMemberNumber ?: -1,
                group = group,
                itemName = itemName,
                propertyJson = propertyJson,
                difficulty = difficulty,
            ),
            successMessage = InteractionUserMessage.General(InteractionUserMessageKey.ItemUpdated),
        )
    }

    fun removeItem(group: String) {
        if (group.isBlank()) {
            showInteractionMessage(InteractionUserMessageKey.ChooseBodyPart)
            return
        }
        sendCandidate(
            candidate = BcInteractionCandidate.RemoveItem(
                targetMemberNumber = selection.targetMemberNumber ?: -1,
                group = group,
            ),
            successMessage = InteractionUserMessage.General(InteractionUserMessageKey.ItemRemoved),
        )
    }

    fun sendItemPickerEntry(entry: InteractionItemPickerEntryUiState) {
        if (entry.canOpenOptions && selection.itemPickerItemName == null && entry.itemName != null) {
            selectItemPickerItem(entry.itemName)
            return
        }
        if (!entry.enabled || entry.selected) {
            showInteractionMessage(
                entry.permissionMessage
                    ?: InteractionUserMessage.General(InteractionUserMessageKey.ItemUnavailable),
            )
            return
        }
        val candidate = entry.candidate
        if (candidate == null) {
            showInteractionMessage(InteractionUserMessageKey.ItemPickerInvalid)
            return
        }
        val operation = entry.operation.toItemManagementOperation()
        if (
            operation == null ||
            (operation != BcItemManagementOperation.Remove && operation != BcItemManagementOperation.Unlock && selection.itemPickerOperation != operation)
        ) {
            rejectStalePickerEntry(InteractionUserMessageKey.ItemPickerStale)
            return
        }
        if (!candidate.group.sameToken(selection.group)) {
            rejectStalePickerEntry(InteractionUserMessageKey.ItemForDifferentBodyPart)
            return
        }
        val target = selectedTargetOrMessage() ?: return
        if (candidate.targetMemberNumber != target.memberNumber) {
            rejectStalePickerEntry(InteractionUserMessageKey.ItemForDifferentCharacter)
            return
        }

        val request = permissionRequestFor(candidate) ?: return
        runInteraction(
            successMessage = operation.successMessage(),
            onSent = {
                if (selection.updateAfterSuccessfulItemSend(operation)) itemPicker.clear()
            },
        ) {
            characterInteractions.sendInteraction(request)
        }
    }

    fun editCurrentItemText(expectedTarget: BcCharacterSnapshot, group: String, values: Map<String, String>) {
        val target = selectedTargetOrMessage() ?: return
        if (target != expectedTarget || !group.sameToken(selection.group)) {
            showInteractionMessage(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale))
            return
        }
        val item = target.appearance.firstOrNull { it.group.sameToken(selection.group) } ?: return
        val rule = ruleCatalogRepository.currentSnapshot(normalizedVersion())?.extendedItem(item.group, item.name) ?: return
        if (!rule.validText(values)) {
            showInteractionMessage(InteractionUserMessage.Permission(dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode.InvalidItemText))
            return
        }
        sendCandidate(BcInteractionCandidate.ModifyItem(target.memberNumber, item.group, item.name,
            BcItemPropertyJson.overlayStrings(item.propertyJson, values)),
            InteractionUserMessage.General(InteractionUserMessageKey.ItemConfigured))
    }

    fun unlockCurrentItemWithCode(expectedTarget: BcCharacterSnapshot, group: String, code: String) {
        val target = selectedTargetOrMessage() ?: return
        if (target != expectedTarget || !group.sameToken(selection.group)) {
            showInteractionMessage(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale))
            return
        }
        val item = target.appearance.firstOrNull { it.group.sameToken(group) } ?: return
        val lock = item.propertyJson.readBcStringProperty("LockedBy") ?: return
        val request = permissionRequestFor(BcInteractionCandidate.UnlockItem(target.memberNumber, item.group, item.name, lock,
            dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential(code))) ?: return
        val decision = permissionEngine.evaluate(request)
        if (decision is dev.vivian.mobilebc.domain.bcpermission.BcPermissionDecision.Denied) {
            showInteractionMessage(InteractionUserMessage.Permission(decision.reasons.first().code))
            return
        }
        runInteraction(BcItemManagementOperation.Unlock.successMessage()) { characterInteractions.sendInteraction(request) }
    }

    fun configureCurrentItemLock(expectedTarget: BcCharacterSnapshot, group: String, configuration: dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration) {
        val target = selectedTargetOrMessage() ?: return
        if (target != expectedTarget || !group.sameToken(selection.group)) {
            showInteractionMessage(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale))
            return
        }
        val item = target.appearance.firstOrNull { it.group.sameToken(group) } ?: return
        val lock = item.propertyJson.readBcStringProperty("LockedBy") ?: return
        val request = permissionRequestFor(BcInteractionCandidate.ConfigureLock(target.memberNumber, item.group, item.name, lock, configuration)) ?: return
        val decision = permissionEngine.evaluate(request)
        if (decision is dev.vivian.mobilebc.domain.bcpermission.BcPermissionDecision.Denied) {
            showInteractionMessage(InteractionUserMessage.Permission(decision.reasons.first().code))
            return
        }
        runInteraction(InteractionUserMessage.General(InteractionUserMessageKey.ItemConfigured)) { characterInteractions.sendInteraction(request) }
    }

    fun changeCurrentItemTimer(expectedTarget: BcCharacterSnapshot, group: String, change: dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange) {
        val target = selectedTargetOrMessage() ?: return
        if (target != expectedTarget || !group.sameToken(selection.group)) {
            showInteractionMessage(InteractionUserMessage.General(InteractionUserMessageKey.ItemPickerStale))
            return
        }
        val item = target.appearance.firstOrNull { it.group.sameToken(group) } ?: return
        val lock = item.propertyJson.readBcStringProperty("LockedBy") ?: return
        val request = permissionRequestFor(BcInteractionCandidate.ChangeLockTimer(target.memberNumber, item.group, item.name, lock, change)) ?: return
        val decision = permissionEngine.evaluate(request)
        if (decision is dev.vivian.mobilebc.domain.bcpermission.BcPermissionDecision.Denied) {
            showInteractionMessage(InteractionUserMessage.Permission(decision.reasons.first().code))
            return
        }
        runInteraction(InteractionUserMessage.General(InteractionUserMessageKey.ItemConfigured)) { characterInteractions.sendInteraction(request) }
    }

    private fun openInteraction(
        mode: InteractionMode,
        targetMemberNumber: Int? = null,
    ) {
        if (wardrobeController.isBusy) return
        wardrobeController.close()
        hideMemberManagement()
        roomForm?.close()
        closeSelfState()
        cancelInteraction()
        selection.open(mode = mode, targetMemberNumber = targetMemberNumber)
        lastTargetMemberNumber = targetMemberNumber ?: lastTargetMemberNumber
        rememberInteractionOwner()
        itemPicker.clear()
        interactionMessage = null
        catalogMessage = null
        refresh()
        val version = normalizedVersion()
        if (
            interactionCatalogRepository.currentSnapshot(version) == null ||
            ruleCatalogRepository.currentSnapshot(version) == null
        ) {
            refreshCatalogAsync(version)
        }
    }

    private fun onSelectionChanged() {
        cancelInteraction()
        itemPicker.clear()
        interactionMessage = null
        refresh()
    }

    private fun showInteractionMessage(message: InteractionUserMessage) {
        interactionMessage = message
        refresh()
    }

    private fun showInteractionMessage(key: InteractionUserMessageKey) {
        showInteractionMessage(InteractionUserMessage.General(key))
    }

    private fun rejectStalePickerEntry(key: InteractionUserMessageKey) {
        interactionMessage = InteractionUserMessage.General(key)
        itemPicker.clear()
        refresh()
    }

    private fun BcItemManagementOperation.successMessage(): InteractionUserMessage =
        InteractionUserMessage.General(
            when (this) {
                BcItemManagementOperation.Wear -> InteractionUserMessageKey.ItemUpdated
                BcItemManagementOperation.Replace -> InteractionUserMessageKey.ItemReplaced
                BcItemManagementOperation.Modify -> InteractionUserMessageKey.ItemConfigured
                BcItemManagementOperation.Remove -> InteractionUserMessageKey.ItemRemoved
                BcItemManagementOperation.Unlock -> InteractionUserMessageKey.ItemUnlocked
                BcItemManagementOperation.Lock -> InteractionUserMessageKey.ItemLocked
            },
        )

    private fun sendCandidate(
        candidate: BcInteractionCandidate,
        successMessage: InteractionUserMessage?,
        onSent: suspend () -> Unit = {},
    ) {
        val request = permissionRequestFor(candidate) ?: run {
            refresh()
            return
        }
        runInteraction(successMessage = successMessage, onSent = onSent) {
            characterInteractions.sendInteraction(request)
        }
    }

    private fun permissionRequestFor(candidate: BcInteractionCandidate): BcPermissionRequest? {
        val target = selectedTargetOrMessage() ?: return null
        val rules = ruleCatalogRepository.currentSnapshot(normalizedVersion())
        if (rules == null) {
            interactionMessage = InteractionUserMessage.General(InteractionUserMessageKey.InteractionDataPreparing)
            return null
        }
        return projector.permissionRequest(target = target, rules = rules, candidate = candidate)
            ?: run {
                interactionMessage = InteractionUserMessage.General(InteractionUserMessageKey.SelfCharacterUnavailable)
                null
            }
    }

    private fun runInteraction(
        successMessage: InteractionUserMessage?,
        onSent: suspend () -> Unit = {},
        block: suspend () -> BcCharacterInteractionResult,
    ) {
        if (interactionBusy || wardrobeController.isBusy) return
        interactionMessage = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val currentJob = currentCoroutineContext()[Job]
            val result = try {
                block().also { result ->
                    currentCoroutineContext().ensureActive()
                    if (interactionJob !== currentJob) return@launch
                    if (result == BcCharacterInteractionResult.Sent) onSent()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                BcCharacterInteractionResult.Failed(
                    throwable.message ?: throwable::class.java.simpleName,
                )
            }
            currentCoroutineContext().ensureActive()
            if (interactionJob !== currentJob) return@launch
            if (result != BcCharacterInteractionResult.Sent) itemPicker.clear()
            interactionMessage = when (result) {
                BcCharacterInteractionResult.Sent -> successMessage
                is BcCharacterInteractionResult.Rejected ->
                    InteractionUserMessage.General(InteractionUserMessageKey.RequestRejected)
                is BcCharacterInteractionResult.Failed ->
                    InteractionUserMessage.General(InteractionUserMessageKey.RequestFailed)
            }
        }
        interactionJob = job
        job.invokeOnCompletion {
            if (interactionJob === job) {
                interactionJob = null
                refresh()
            }
        }
        refresh()
        job.start()
    }

    private fun cancelInteraction() {
        val job = interactionJob
        interactionJob = null
        job?.cancel()
    }

    private fun cancelSelfState() {
        val job = selfStateJob
        selfStateJob = null
        job?.cancel()
        selfStateClockJob?.cancel()
        selfStateClockJob = null
    }

    private fun selectedTargetOrMessage(): BcCharacterSnapshot? {
        val target = selection.targetMemberNumber?.let(::findCharacter)
        if (target == null) showInteractionMessage(InteractionUserMessageKey.TargetLeftRoom)
        return target
    }

    private fun preferredTarget(requested: Int?): Int? =
        (requested ?: lastTargetMemberNumber)?.takeIf { findCharacter(it) != null }

    private fun defaultCatalogOperation(
        target: BcCharacterSnapshot,
        group: String,
    ): BcItemManagementOperation = if (
        target.appearance.any { it.group.equals(group, ignoreCase = true) }
    ) {
        BcItemManagementOperation.Replace
    } else {
        BcItemManagementOperation.Wear
    }

    private fun refreshCatalogAsync(version: String = normalizedVersion()) {
        catalogRefreshJob?.cancel()
        catalogRefreshJob = scope.launch {
            catalogMessage = withContext(catalogDispatcher) { refreshCatalogMessage(version) }
            refresh()
        }
    }

    private suspend fun refreshCatalogMessage(version: String): InteractionUserMessage? {
        var catalogResult = interactionCatalogRepository.refresh(version)
        var ruleResult = ruleCatalogRepository.refresh(version)
        val resourcesMissing = catalogResult is BcInteractionCatalogLoadResult.MissingResources ||
            ruleResult is BcRuleCatalogLoadResult.MissingResources
        val syncFailed = resourcesMissing && syncResourcesFailed(version)
        if (resourcesMissing && !syncFailed) {
            catalogResult = interactionCatalogRepository.refresh(version)
            ruleResult = ruleCatalogRepository.refresh(version)
        }
        return when {
            syncFailed ||
                catalogResult is BcInteractionCatalogLoadResult.Failed ||
                ruleResult is BcRuleCatalogLoadResult.Failed ->
                InteractionUserMessage.General(InteractionUserMessageKey.ResourceSyncFailed)
            catalogResult is BcInteractionCatalogLoadResult.MissingResources ||
                ruleResult is BcRuleCatalogLoadResult.MissingResources ->
                InteractionUserMessage.General(InteractionUserMessageKey.InteractionDataPreparing)
            else -> null
        }
    }

    private suspend fun syncResourcesFailed(version: String): Boolean = try {
        syncResources(version)
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        true
    }

    private fun rememberInteractionOwner() {
        val snapshot = connectionStatus.snapshot.value
        interactionOwner = selection.targetMemberNumber?.let { target ->
            InteractionOwner(
                roomName = snapshot.currentRoom?.name,
                roomPresenceEpoch = snapshot.roomPresenceEpoch,
                accountMemberNumber = snapshot.account?.memberNumber,
                accountGeneration = snapshot.relationships.accountGeneration,
                targetMemberNumber = target,
                targetPresenceEpoch = snapshot.roomMembers.firstOrNull { it.memberNumber == target }?.roomMemberPresenceEpoch,
            )
        }
    }

    private fun refresh() {
        val snapshot = connectionStatus.snapshot.value
        interactionOwner?.takeIf { selection.mode != InteractionMode.Closed && selection.targetMemberNumber != null }
            ?.takeUnless { it.matches(snapshot) }?.let {
                cancelInteraction()
                val mode = selection.mode
                selection.resetTarget(mode)
                itemPicker.clear()
                lastTargetMemberNumber = null
                interactionOwner = null
                interactionMessage = InteractionUserMessage.General(InteractionUserMessageKey.TargetLeftRoom)
            }
        wardrobeController.refresh()
        refreshMemberManagement(snapshot)
        selfStateOwner?.takeIf { owner ->
            owner.memberNumber != snapshot.account?.memberNumber || owner.roomName != snapshot.currentRoom?.name ||
                owner.roomPresenceEpoch != snapshot.roomPresenceEpoch || owner.accountGeneration != snapshot.relationships.accountGeneration
        }?.let {
            cancelSelfState()
            selfStateOwner = null
            selfStateOpen = false
            selfStateResult = null
        }
        val hasTimedPoseRule = selfStateOpen && snapshot.selfRuleLog.orEmpty().any {
            it.name == "BlockChangePose" && it.group == "OwnerRule" && it.expiresAtMillis != null
        }
        if (hasTimedPoseRule && selfStateClockJob == null) {
            selfStateClockJob = scope.launch {
                while (isActive) {
                    delay(1_000)
                    refresh()
                }
            }
        } else if (!hasTimedPoseRule) {
            selfStateClockJob?.cancel()
            selfStateClockJob = null
        }
        _uiState.value = buildUiState()
    }

    private fun buildUiState(): RoomDetailsUiState {
        val session = connectionStatus.snapshot.value
        val currentRoom = session.currentRoom
        val characters = session.roomCharacters.sortedWith(
            compareByDescending<BcCharacterSnapshot> { it.isSelf }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.memberNumber },
        )
        val interactionSheet = buildInteractionSheet()
        return RoomDetailsUiState(
            roomTitle = currentRoom?.name?.value,
            onlineCount = characters.size,
            memberLimit = currentRoom?.memberLimit,
            mapType = currentRoom?.mapType,
            roomDescription = currentRoom?.description.orEmpty(),
            characters = characters,
            interactionMode = selection.mode.toUiModeOrNull(
                hasTarget = interactionSheet != null,
            ),
            interactionSheet = interactionSheet,
            interactionNotice = interactionMessage.takeIf { interactionSheet == null },
            selfStateSheet = if (selfStateOpen) {
                val version = currentVersion.value.takeIf(String::isNotBlank)?.normalizedBcVersion()
                SelfStateSheetUiState(
                    projection = selfStateRules.projection(session, version?.let(ruleCatalogRepository::currentSnapshot)),
                    busy = selfStateBusy,
                    result = selfStateResult,
                )
            } else null,
            canEditRoomSettings = roomForm != null && memberAdminPending == null && !wardrobeController.isBusy && session.isVerifiedOnline &&
                session.account?.memberNumber in currentRoom?.adminSettings?.adminMemberNumbers.orEmpty(),
            roomForm = roomForm?.state?.value,
            manageableMemberNumbers = if (hasMemberAdministrationAuthority(session) && session.gatewayRoomPresenceEpoch != null &&
                session.selfRuleLogSessionId != null
            ) session.roomMembers.filter { member ->
                !member.isSelf && member.memberNumber != session.account?.memberNumber && member.roomMemberPresenceEpoch != null &&
                    characters.any { it.memberNumber == member.memberNumber }
            }.mapTo(mutableSetOf()) { it.memberNumber } else emptySet(),
            memberAdministrationBusy = memberAdminPending != null,
            memberManagement = buildMemberManagement(session),
            canOpenWardrobe = session.isVerifiedOnline && currentVersion.value.isNotBlank() && currentRoom != null && characters.any { it.isSelf && it.memberNumber == session.account?.memberNumber } &&
                !selfStateBusy && !interactionBusy && memberAdminPending == null && roomForm?.state?.value?.busy != true,
            wardrobe = wardrobeController.state.value,
        )
    }

    private fun buildInteractionSheet(): CharacterInteractionSheetUiState? {
        val target = selection.targetMemberNumber?.let(::findCharacter) ?: return null
        val version = normalizedVersion()
        val snapshot = interactionCatalogRepository.currentSnapshot(version)
        val rules = ruleCatalogRepository.currentSnapshot(version)
        val actionPickerOnly = selection.mode == InteractionMode.ActionPicker
        val itemPickerOnly = selection.mode == InteractionMode.ItemPicker ||
            selection.mode == InteractionMode.ClothingPicker
        val groups = projector.groupStates(
            mode = selection.mode,
            target = target,
            snapshot = snapshot,
            rules = rules,
        )
        val selectedGroup = projector.selectGroup(
            groups = groups,
            selectedGroupName = selection.group,
            mode = selection.mode,
        )
        val permissionContext = rules?.let { projector.permissionContext(target = target, rules = it) }
        return CharacterInteractionSheetUiState(
            target = target,
            portrait = connectionStatus.snapshot.value.characterPortrait(target.memberNumber),
            version = version,
            catalogReady = snapshot != null,
            permissionReady = rules != null,
            catalogMessage = catalogMessage,
            busy = interactionBusy,
            actionMessage = interactionMessage,
            groups = groups,
            selectedGroup = selectedGroup,
            currentItem = if (actionPickerOnly || itemPickerOnly) {
                null
            } else {
                selectedGroup?.let { group ->
                    projector.currentItemUiState(
                        target = target,
                        group = group.name,
                        snapshot = snapshot,
                        rules = rules,
                    )
                }
            },
            activities = if (itemPickerOnly) {
                emptyList()
            } else {
                val activityGroups = if (actionPickerOnly && selectedGroup == null) {
                    groups.filter { it.hasActivities }
                } else {
                    listOfNotNull(selectedGroup)
                }
                activityGroups.flatMap { group ->
                    projector.activityUiStates(
                        group = group.name,
                        target = target,
                        rules = rules,
                        snapshot = snapshot,
                        allowedOnly = actionPickerOnly && !selection.actionPickerShowUnavailable,
                    )
                }.sortedWith(compareBy<InteractionActivityUiState> {
                    recentActivities.indexOf(it.group to it.name).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
                }.thenBy { it.displayName })
            },
            items = if (actionPickerOnly || itemPickerOnly) {
                emptyList()
            } else {
                selectedGroup?.let { group ->
                    projector.itemUiStates(
                        group = group.name,
                        target = target,
                        rules = rules,
                        snapshot = snapshot,
                    )
                }.orEmpty()
            },
            itemPicker = if (itemPickerOnly) {
                itemPicker.buildUiState(
                    selection = selection,
                    target = target,
                    selectedGroup = selectedGroup,
                    rules = rules,
                    permissionContext = permissionContext,
                    version = version,
                )
            } else {
                null
            },
            showUnavailableActivities = selection.actionPickerShowUnavailable,
        )
    }

    private fun currentCharacters(): List<BcCharacterSnapshot> =
        connectionStatus.snapshot.value.let { snapshot ->
            snapshot.roomCharacters.takeIf { snapshot.currentRoom != null }.orEmpty()
        }

    private fun findCharacter(memberNumber: Int): BcCharacterSnapshot? =
        currentCharacters().firstOrNull { it.memberNumber == memberNumber }

    private fun normalizedVersion(): String = currentVersion.value
        .takeIf(String::isNotBlank)
        ?.normalizedBcVersion()
        ?: error("BC version is unavailable for room interactions.")

    private data class MemberManagementSelection(
        val owner: MemberManagementOwner,
        val target: BcCharacterSnapshot,
        val selectedAction: BcRoomMemberAdminAction? = null,
        val selectedTargetIsAdministrator: Boolean? = null,
    )

    private data class MemberAdminSubmission(
        val owner: MemberManagementOwner,
        val command: BcRoomMemberAdminCommand,
    )

    private data class MemberManagementOwner(
        val memberNumber: Int,
        val accountGeneration: Long,
        val loginSessionId: String,
        val roomName: BcRoomName,
        val roomPresenceEpoch: Long,
        val gatewayRoomPresenceEpoch: Long,
        val targetMemberNumber: Int,
        val targetPresenceEpoch: Long,
    ) {
        fun sameLogin(snapshot: BcSessionSnapshot): Boolean = memberNumber == snapshot.account?.memberNumber &&
            accountGeneration == snapshot.relationships.accountGeneration && loginSessionId == snapshot.selfRuleLogSessionId

        fun matches(snapshot: BcSessionSnapshot): Boolean = sameLogin(snapshot) && roomName == snapshot.currentRoom?.name &&
            roomPresenceEpoch == snapshot.roomPresenceEpoch && gatewayRoomPresenceEpoch == snapshot.gatewayRoomPresenceEpoch

        companion object {
            fun from(snapshot: BcSessionSnapshot, targetMemberNumber: Int, targetPresenceEpoch: Long): MemberManagementOwner? {
                return MemberManagementOwner(
                    memberNumber = snapshot.account?.memberNumber ?: return null,
                    accountGeneration = snapshot.relationships.accountGeneration,
                    loginSessionId = snapshot.selfRuleLogSessionId ?: return null,
                    roomName = snapshot.currentRoom?.name ?: return null,
                    roomPresenceEpoch = snapshot.roomPresenceEpoch,
                    gatewayRoomPresenceEpoch = snapshot.gatewayRoomPresenceEpoch ?: return null,
                    targetMemberNumber = targetMemberNumber,
                    targetPresenceEpoch = targetPresenceEpoch,
                )
            }
        }
    }

    private data class InteractionOwner(
        val roomName: BcRoomName?,
        val roomPresenceEpoch: Long,
        val accountMemberNumber: Int?,
        val accountGeneration: Long,
        val targetMemberNumber: Int,
        val targetPresenceEpoch: Long?,
    ) {
        fun matches(snapshot: BcSessionSnapshot): Boolean =
            roomName == snapshot.currentRoom?.name && roomPresenceEpoch == snapshot.roomPresenceEpoch &&
                accountMemberNumber == snapshot.account?.memberNumber && accountGeneration == snapshot.relationships.accountGeneration &&
                snapshot.roomCharacters.any { it.memberNumber == targetMemberNumber } &&
                targetPresenceEpoch == snapshot.roomMembers.firstOrNull { it.memberNumber == targetMemberNumber }?.roomMemberPresenceEpoch
    }

    private data class SelfStateOwner(
        val memberNumber: Int,
        val roomName: BcRoomName,
        val roomPresenceEpoch: Long,
        val accountGeneration: Long,
    )

}

private fun String?.sameToken(other: String?): Boolean =
    this != null && other != null && trim().equals(other.trim(), ignoreCase = true)

private fun InteractionMode.toUiModeOrNull(hasTarget: Boolean): RoomDetailsInteractionMode? = when (this) {
    InteractionMode.Closed -> null
    InteractionMode.FullSheet -> RoomDetailsInteractionMode.FullSheet.takeIf { hasTarget }
    InteractionMode.ActionPicker -> RoomDetailsInteractionMode.ActionPicker
    InteractionMode.ItemPicker -> RoomDetailsInteractionMode.ItemPicker
    InteractionMode.ClothingPicker -> RoomDetailsInteractionMode.ClothingPicker
}
