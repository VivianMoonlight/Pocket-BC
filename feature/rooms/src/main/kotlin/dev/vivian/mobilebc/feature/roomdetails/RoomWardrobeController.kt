package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotCount
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlan
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlanner
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeRequest
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeResult
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason
import dev.vivian.mobilebc.domain.character.BcCharacterInteractions
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A single wardrobe draft and preview; hiding it never cancels an emitted update. */
internal class RoomWardrobeController(
    private val characterInteractions: BcCharacterInteractions,
    private val connectionStatus: ConnectionStatusReader,
    private val currentVersion: StateFlow<String>,
    private val ruleCatalogRepository: BcRuleCatalogRepository,
    private val scope: CoroutineScope,
    private val isOtherMutationBusy: () -> Boolean,
) {
    private val planner = BcWardrobePlanner()
    private val mutableState = MutableStateFlow(RoomWardrobeUiState())
    val state: StateFlow<RoomWardrobeUiState> = mutableState.asStateFlow()
    val isBusy: Boolean get() = pending != null
    private var owner: Owner? = null
    private var actionBase: ActionBase? = null
    private var pending: Submission? = null
    private var appliedPlan: BcWardrobePlan? = null
    private var appliedSource: BcCharacterSnapshot? = null

    init {
        scope.launch { connectionStatus.snapshot.collect { refresh() } }
        scope.launch { currentVersion.collect { refresh() } }
    }

    fun open() {
        if (isOtherMutationBusy()) return
        val snapshot = connectionStatus.snapshot.value
        val nextOwner = Owner.from(snapshot, version()) ?: return
        if (pending != null && owner != nextOwner) return
        if (owner != nextOwner) {
            owner = nextOwner
            actionBase = null
            appliedPlan = null
            appliedSource = null
            mutableState.value = RoomWardrobeUiState(visible = true)
        } else mutableState.value = mutableState.value.copy(visible = true)
        refresh()
    }

    fun close() {
        mutableState.value = mutableState.value.copy(visible = false)
    }

    fun selectSlot(index: Int) {
        if (isBusy || isOtherMutationBusy() || !mutableState.value.visible || index !in 0 until BcWardrobeSlotCount) return
        actionBase = null
        appliedPlan = null
        appliedSource = null
        mutableState.value = mutableState.value.copy(selectedSlotIndex = index, editMode = null, preview = null, result = null)
        refresh()
    }

    fun beginSave() {
        refresh()
        if (!mutableState.value.canSave) return
        actionBase = captureBase() ?: return
        appliedPlan = null
        appliedSource = null
        mutableState.value = mutableState.value.copy(editMode = RoomWardrobeEditMode.Save, preview = null, result = null)
    }

    fun beginRename() {
        refresh()
        if (!mutableState.value.canRename) return
        actionBase = captureBase() ?: return
        appliedPlan = null
        appliedSource = null
        val slot = selectedSlot() ?: return
        mutableState.value = mutableState.value.copy(editMode = RoomWardrobeEditMode.Rename, nameDraft = slot.name.orEmpty(), preview = null, result = null)
        refresh()
    }

    fun changeName(name: String) {
        if (isBusy || mutableState.value.editMode != RoomWardrobeEditMode.Rename) return
        mutableState.value = mutableState.value.copy(nameDraft = name, nameValid = planner.validateName(name), result = null)
    }

    fun cancelEdit() {
        if (isBusy) return
        actionBase = null
        mutableState.value = mutableState.value.copy(editMode = null)
        refresh()
    }

    fun previewLoad() {
        refresh()
        if (!mutableState.value.canPreview) return
        val base = captureBase() ?: return
        val plan = planner.plan(base.request, connectionStatus.snapshot.value)
        plan.unavailableReason?.let { reason ->
            mutableState.value = mutableState.value.copy(result = RoomWardrobeResult.Rejected(reason))
            return
        }
        actionBase = base
        appliedPlan = null
        appliedSource = null
        mutableState.value = mutableState.value.copy(editMode = null, preview = plan, result = null)
    }

    fun cancelLoad() {
        if (isBusy) return
        actionBase = null
        mutableState.value = mutableState.value.copy(preview = null)
        refresh()
    }

    fun confirmSave() {
        if (mutableState.value.editMode == RoomWardrobeEditMode.Save) submit(RoomWardrobeOperation.Save)
    }

    fun confirmRename() {
        if (mutableState.value.editMode != RoomWardrobeEditMode.Rename) return
        if (!planner.validateName(mutableState.value.nameDraft)) {
            mutableState.value = mutableState.value.copy(nameValid = false, result = RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.InvalidName))
            return
        }
        submit(RoomWardrobeOperation.Rename)
    }

    fun confirmLoad() {
        if (mutableState.value.preview?.hasChanges == true) submit(RoomWardrobeOperation.Load)
    }

    fun clearResult() {
        appliedPlan = null
        appliedSource = null
        mutableState.value = mutableState.value.copy(result = null)
        refresh()
    }

    fun refresh() {
        val snapshot = connectionStatus.snapshot.value
        if (owner != null && owner != Owner.from(snapshot, version())) {
            owner = null
            actionBase = null
            appliedPlan = null
            appliedSource = null
            mutableState.value = RoomWardrobeUiState(busy = isBusy)
        }
        val current = mutableState.value
        val rules = version()?.let(ruleCatalogRepository::currentSnapshot)
        val unavailable = planner.unavailableReason(snapshot, rules)
        val privateState = snapshot.wardrobeState?.takeIf {
            it.memberNumber == snapshot.account?.memberNumber && it.selfRuleLogSessionId == snapshot.selfRuleLogSessionId
        }
        val knownSlots = privateState?.slots.orEmpty().associateBy { it.index }
        val slots = (0 until BcWardrobeSlotCount).map { index ->
            knownSlots[index] ?: BcWardrobeSlot(index, null, BcWardrobeSlotStatus.Unsupported, null)
        }
        val selectedSlot = slots.firstOrNull { it.index == current.selectedSlotIndex }
        val blocked = isOtherMutationBusy()
        val ready = current.visible && owner != null && !isBusy && !blocked && unavailable == null
        val source = self(snapshot)
        val plan = appliedPlan
        val hasLoadFeedback = current.result is RoomWardrobeResult.AppearanceObserved ||
            current.result is RoomWardrobeResult.Sent && current.result.operation == RoomWardrobeOperation.Load
        val result = if (plan != null && hasLoadFeedback) {
            if (source != null && source != appliedSource && source.appearance.sortedBy { it.group } == plan.appearance.sortedBy { it.group }) RoomWardrobeResult.AppearanceObserved(plan.partial)
            else RoomWardrobeResult.Sent(RoomWardrobeOperation.Load, plan.partial)
        } else current.result
        mutableState.value = current.copy(
            busy = isBusy,
            blockedByOtherOperation = blocked,
            slots = slots,
            unavailableReason = unavailable,
            nameValid = planner.validateName(current.nameDraft),
            canSave = ready && privateState?.wardrobeAvailable == true && selectedSlot != null && selectedSlot.status != BcWardrobeSlotStatus.Unsupported,
            canRename = ready && privateState?.namesAvailable == true && selectedSlot != null,
            canPreview = ready && selectedSlot?.status == BcWardrobeSlotStatus.Saved,
            result = result,
        )
    }

    private fun submit(operation: RoomWardrobeOperation) {
        if (isBusy || isOtherMutationBusy() || !mutableState.value.visible) return
        val base = actionBase ?: return
        val snapshot = connectionStatus.snapshot.value
        if (owner != base.owner || owner != Owner.from(snapshot, version())) return
        val rejection = planner.rejection(base.request, snapshot)
            ?: BcWardrobeUnavailableReason.CharacterStateChanged.takeIf { self(snapshot) != base.character }
        if (rejection != null) {
            mutableState.value = mutableState.value.copy(result = RoomWardrobeResult.Rejected(rejection))
            return
        }
        val slot = selectedSlot() ?: return
        if (operation == RoomWardrobeOperation.Save && slot.status == BcWardrobeSlotStatus.Unsupported) {
            mutableState.value = mutableState.value.copy(result = RoomWardrobeResult.Rejected(BcWardrobeUnavailableReason.UnsupportedSlot))
            return
        }
        val expectedPlan = if (operation == RoomWardrobeOperation.Load) mutableState.value.preview ?: return else null
        if (expectedPlan != null) {
            val freshPlan = planner.plan(base.request, snapshot)
            if (freshPlan != expectedPlan) {
                mutableState.value = mutableState.value.copy(result = RoomWardrobeResult.Rejected(
                    freshPlan.unavailableReason ?: BcWardrobeUnavailableReason.CharacterStateChanged,
                ))
                return
            }
        }
        val submission = Submission(base, operation, mutableState.value.nameDraft)
        pending = submission
        mutableState.value = mutableState.value.copy(result = null)
        refresh()
        scope.launch {
            try {
                val result = when (operation) {
                    RoomWardrobeOperation.Save -> characterInteractions.saveWardrobe(base.request)
                    RoomWardrobeOperation.Rename -> characterInteractions.renameWardrobe(base.request, submission.name)
                    RoomWardrobeOperation.Load -> characterInteractions.applyWardrobe(base.request, requireNotNull(expectedPlan))
                }
                if (stillOwned(submission)) {
                    mutableState.value = mutableState.value.copy(result = when (result) {
                        is BcWardrobeResult.Sent -> {
                            if (operation == RoomWardrobeOperation.Load) {
                                appliedPlan = result.plan
                                appliedSource = base.character
                            }
                            actionBase = null
                            RoomWardrobeResult.Sent(operation, result.plan?.partial == true)
                        }
                        is BcWardrobeResult.Rejected -> RoomWardrobeResult.Rejected(result.reason)
                        is BcWardrobeResult.Failed -> RoomWardrobeResult.Failed
                    }, editMode = if (result is BcWardrobeResult.Sent) null else mutableState.value.editMode,
                        preview = if (result is BcWardrobeResult.Sent) null else mutableState.value.preview)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (stillOwned(submission)) mutableState.value = mutableState.value.copy(result = RoomWardrobeResult.Failed)
            }
        }.invokeOnCompletion {
            if (pending === submission) {
                pending = null
                refresh()
            }
        }
    }

    private fun captureBase(): ActionBase? {
        val snapshot = connectionStatus.snapshot.value
        val currentOwner = owner ?: return null
        val slotIndex = mutableState.value.selectedSlotIndex ?: return null
        val privateState = snapshot.wardrobeState ?: return null
        val rules = version()?.let(ruleCatalogRepository::currentSnapshot) ?: return null
        val character = self(snapshot) ?: return null
        return ActionBase(currentOwner, BcWardrobeRequest(currentOwner.memberNumber, currentOwner.roomName, currentOwner.roomPresenceEpoch,
            currentOwner.accountGeneration, slotIndex, privateState.revision, rules), character)
    }

    private fun stillOwned(submission: Submission): Boolean = owner == submission.base.owner &&
        owner == Owner.from(connectionStatus.snapshot.value, version())

    private fun selectedSlot(): BcWardrobeSlot? = mutableState.value.slots.firstOrNull { it.index == mutableState.value.selectedSlotIndex }
    private fun self(snapshot: BcSessionSnapshot): BcCharacterSnapshot? = snapshot.roomCharacters.firstOrNull {
        it.isSelf && it.memberNumber == snapshot.account?.memberNumber
    }
    private fun version(): String? = currentVersion.value.takeIf(String::isNotBlank)?.normalizedBcVersion()

    private data class ActionBase(val owner: Owner, val request: BcWardrobeRequest, val character: BcCharacterSnapshot)
    private data class Submission(val base: ActionBase, val operation: RoomWardrobeOperation, val name: String)
    private data class Owner(val memberNumber: Int, val accountGeneration: Long, val loginSessionId: String,
        val roomName: BcRoomName, val roomPresenceEpoch: Long, val gatewayRoomPresenceEpoch: Long?, val version: String) {
        companion object {
            fun from(snapshot: BcSessionSnapshot, version: String?): Owner? {
                return Owner(snapshot.account?.memberNumber ?: return null, snapshot.relationships.accountGeneration,
                    snapshot.selfRuleLogSessionId ?: return null, snapshot.currentRoom?.name ?: return null,
                    snapshot.roomPresenceEpoch, snapshot.gatewayRoomPresenceEpoch, version ?: return null)
            }
        }
    }
}
