package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.core.model.BcProfileRelation
import dev.vivian.mobilebc.core.model.profileOwner
import dev.vivian.mobilebc.core.model.profileLovers
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.relationshipWith
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipCapabilityQueryResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipContractActionResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipMutationResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipMutationRejection
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationships
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.feature.rooms.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

data class CharacterProfileUiState(
    val memberNumber: Int,
    val portrait: CharacterPortraitState? = null,
    val labelColor: String? = null,
    val displayName: String = "#$memberNumber",
    val characterName: String? = null,
    val nickname: String? = null,
    val title: String? = null,
    val description: String? = null,
    val pronouns: String? = null,
    val owner: BcProfileRelation? = null,
    val lovers: List<BcProfileRelation> = emptyList(),
    val snapshotUpdatedAtMillis: Long? = null,
    val statusText: String? = null,
    val isSelf: Boolean = false,
    val isInCurrentRoom: Boolean = false,
    val online: Boolean = false,
    val lastSeenAtMillis: Long? = null,
    val relationship: CharacterRelationshipUiState? = null,
    val relationshipMessageRes: Int? = null,
)

data class CharacterRelationshipUiState(
    val ownerMemberNumber: Int?,
    val accountGeneration: Long,
    val roomPresenceEpoch: Long,
    val iFriendThem: Boolean,
    val iIgnoreThem: Boolean,
    val iWhitelistThem: Boolean,
    val iBlacklistThem: Boolean,
    val theyWhitelistMe: Boolean?,
    val theyBlacklistMe: Boolean?,
    val actions: List<CharacterRelationshipContractAction> = emptyList(),
    val capabilitiesLoaded: Boolean = false,
    val canRefreshCapabilities: Boolean = false,
    val canChangeRelationships: Boolean = false,
    val operationInFlight: Boolean = false,
)

data class CharacterRelationshipContractAction(
    val id: String,
    val labelRes: Int,
    val kind: BcRelationshipContractKind,
    val action: BcRelationshipContractAction,
    val destructive: Boolean = false,
)

class CharacterProfileViewModel(
    val memberNumber: Int,
    timeline: BcSessionTimeline,
    private val connectionStatus: ConnectionStatusReader,
    private val relationships: BcSessionRelationships,
    scope: CoroutineScope,
) : AutoCloseable {
    private val viewModelJob = SupervisorJob(scope.coroutineContext[Job])
    private val viewModelScope = CoroutineScope(scope.coroutineContext + viewModelJob)
    private var operationJob: Job? = null
    private val operation = MutableStateFlow(CharacterRelationshipOperationState())
    val uiState: StateFlow<CharacterProfileUiState> = combine(
        timeline.observeCharacterProfile(memberNumber),
        connectionStatus.snapshot,
        operation,
    ) { stored, session, operationState ->
        buildState(stored.profileFor(session), session, operationState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = buildState(null, connectionStatus.snapshot.value, operation.value),
    )

    init {
        viewModelScope.launch {
            connectionStatus.snapshot
                .map { session ->
                    RelationshipCapabilityQueryTarget(
                        ownerMemberNumber = session.account?.memberNumber,
                        generation = session.relationships.accountGeneration,
                        roomPresenceEpoch = session.roomPresenceEpoch,
                        eligible = session.canQueryRelationshipCapabilities(memberNumber),
                    )
                }
                .distinctUntilChanged()
                .collect { target ->
                    operationJob?.cancel()
                    operation.value = CharacterRelationshipOperationState()
                    if (target.eligible) queryRelationshipCapabilities()
                }
        }
    }

    override fun close() {
        viewModelScope.cancel()
    }

    fun updateRelationshipList(kind: BcRelationshipListKind, enabled: Boolean) {
        launchOperation {
            relationships.updateRelationshipList(memberNumber, kind, enabled).toFeedbackRes()
        }
    }

    fun queryRelationshipCapabilities() {
        if (!connectionStatus.snapshot.value.canQueryRelationshipCapabilities(memberNumber)) return
        launchOperation {
            relationships.queryRelationshipCapabilities(memberNumber).toFeedbackRes()
        }
    }

    fun submitRelationshipAction(
        action: CharacterRelationshipContractAction,
        destructiveActionConfirmed: Boolean = false,
    ) {
        launchOperation {
            relationships.submitRelationshipContractAction(
                memberNumber = memberNumber,
                kind = action.kind,
                action = action.action,
                destructiveActionConfirmed = destructiveActionConfirmed,
            ).toFeedbackRes()
        }
    }

    fun clearRelationshipMessage() {
        operation.update { it.copy(messageRes = null) }
    }

    private fun launchOperation(block: suspend () -> Int?) {
        if (!viewModelJob.isActive) return
        val started = connectionStatus.snapshot.value
        if (!started.canQueryRelationshipCapabilities(memberNumber)) return
        val ownerState = CharacterRelationshipOperationState(
            ownerMemberNumber = started.account?.memberNumber,
            generation = started.relationships.accountGeneration,
            roomPresenceEpoch = started.roomPresenceEpoch,
        )
        val pending = started.relationships.pendingOperations.keys.any {
            it.memberNumber == memberNumber && it.accountGeneration == started.relationships.accountGeneration
        }
        if (pending) return
        val request = Any()
        while (true) {
            val current = operation.value
            if (current.belongsTo(started) && current.request != null) return
            if (operation.compareAndSet(current, ownerState.copy(request = request))) break
        }
        operationJob = viewModelScope.launch {
            try {
                if (!isCurrentAccount(started)) return@launch
                val message = block()
                coroutineContext.ensureActive()
                if (isCurrentAccount(started)) {
                    operation.update { current ->
                        if (current.request === request) current.copy(messageRes = message) else current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                coroutineContext.ensureActive()
                if (isCurrentAccount(started)) {
                    operation.update { current ->
                        if (current.request === request) current.copy(messageRes = R.string.character_operation_failed)
                        else current
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                operation.update { current ->
                    if (current.request === request) current.copy(request = null) else current
                }
            }
        }
    }

    private fun isCurrentAccount(started: BcSessionSnapshot): Boolean {
        val current = connectionStatus.snapshot.value
        return current.account?.memberNumber == started.account?.memberNumber &&
            current.relationships.accountGeneration == started.relationships.accountGeneration &&
            current.roomPresenceEpoch == started.roomPresenceEpoch &&
            current.canQueryRelationshipCapabilities(memberNumber)
    }

    private fun buildState(
        stored: BcContactProfile?,
        session: BcSessionSnapshot,
        operationState: CharacterRelationshipOperationState,
    ): CharacterProfileUiState {
        val currentOperation = operationState.takeIf { it.belongsTo(session) }
        val live = session.roomCharacters
            .takeIf { session.isVerifiedOnline && session.currentRoom != null }
            ?.firstOrNull { it.memberNumber == memberNumber }
        val history = stored.takeIf { live == null }
        val isSelf = live?.isSelf == true || session.account?.memberNumber == memberNumber
        return CharacterProfileUiState(
            memberNumber = memberNumber,
            portrait = session.characterPortrait(memberNumber) ?: history?.let { session.cachedCharacterPortrait(it) },
            displayName = live?.displayName?.takeIf(String::isNotBlank)
                ?: live?.name?.takeIf(String::isNotBlank)
                ?: history?.name?.takeIf(String::isNotBlank)
                ?: "#$memberNumber",
            characterName = live?.name ?: history?.characterName,
            labelColor = live?.labelColor ?: history?.labelColor,
            nickname = live?.nickname ?: history?.nickname,
            title = live?.title ?: history?.title,
            description = live?.description ?: history?.description,
            pronouns = live?.pronouns ?: history?.pronouns,
            owner = if (live != null) live.profileOwner() else history?.ownerRelation ?: history?.ownerName?.let { BcProfileRelation(it) },
            lovers = if (live != null) live.profileLovers() else history?.loverRelations ?: history?.loverNames.orEmpty().map { BcProfileRelation(it) },
            snapshotUpdatedAtMillis = history?.snapshotUpdatedAtMillis,
            statusText = if (live != null) {
                session.roomMembers.firstOrNull { it.memberNumber == memberNumber }?.statusText
            } else {
                history?.statusText
            },
            isSelf = isSelf,
            isInCurrentRoom = live != null,
            online = session.isVerifiedOnline,
            lastSeenAtMillis = stored?.lastSeenAtMillis,
            relationship = session.toCharacterRelationshipUiState(
                memberNumber = memberNumber,
                isSelf = isSelf,
                operationInFlight = currentOperation?.request != null,
            ),
            relationshipMessageRes = currentOperation?.messageRes,
        )
    }
}

private data class CharacterRelationshipOperationState(
    val ownerMemberNumber: Int? = null,
    val generation: Long = 0,
    val roomPresenceEpoch: Long = -1,
    val request: Any? = null,
    val messageRes: Int? = null,
) {
    fun belongsTo(session: BcSessionSnapshot): Boolean =
        ownerMemberNumber == session.account?.memberNumber && generation == session.relationships.accountGeneration &&
            roomPresenceEpoch == session.roomPresenceEpoch && session.isVerifiedOnline
}

private data class RelationshipCapabilityQueryTarget(
    val ownerMemberNumber: Int?,
    val generation: Long,
    val roomPresenceEpoch: Long,
    val eligible: Boolean,
)

private fun BcSessionSnapshot.canQueryRelationshipCapabilities(memberNumber: Int): Boolean =
    isVerifiedOnline &&
        currentRoom != null &&
        roomCharacters.any { it.memberNumber == memberNumber } &&
        relationships.loadState == BcRelationshipLoadState.Loaded &&
        memberNumber > 0 &&
        account?.let { it.memberNumber != memberNumber } == true

internal fun BcSessionSnapshot.toCharacterRelationshipUiState(
    memberNumber: Int,
    isSelf: Boolean,
    operationInFlight: Boolean,
): CharacterRelationshipUiState? {
    if (isSelf || !canQueryRelationshipCapabilities(memberNumber)) return null
    val projection = relationshipWith(memberNumber)
    val capabilities = relationships.capabilitiesByMember[memberNumber]
    val pending = relationships.pendingOperations.keys.any { key ->
        key.memberNumber == memberNumber && key.accountGeneration == relationships.accountGeneration
    }
    return CharacterRelationshipUiState(
        ownerMemberNumber = account?.memberNumber,
        accountGeneration = relationships.accountGeneration,
        roomPresenceEpoch = roomPresenceEpoch,
        iFriendThem = projection.iFriendThem,
        iIgnoreThem = projection.iIgnoreThem,
        iWhitelistThem = projection.iWhitelistThem,
        iBlacklistThem = projection.iBlacklistThem,
        theyWhitelistMe = projection.theyWhitelistMe,
        theyBlacklistMe = projection.theyBlacklistMe,
        actions = relationshipActions(memberNumber, capabilities?.ownershipResult, capabilities?.lovershipResult),
        capabilitiesLoaded = capabilities?.queriedAtMillis != null &&
            capabilities.ownershipResult != null && capabilities.lovershipResult != null,
        canRefreshCapabilities = canQueryRelationshipCapabilities(memberNumber),
        canChangeRelationships = isVerifiedOnline,
        operationInFlight = operationInFlight || pending,
    )
}

private fun BcSessionSnapshot.relationshipActions(
    memberNumber: Int,
    ownershipResult: String?,
    lovershipResult: String?,
): List<CharacterRelationshipContractAction> = buildList {
    when (ownershipResult) {
        "CanOfferStartTrial" -> add(contractAction("ownership-offer-trial", R.string.character_offer_ownership_trial, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Propose))
        "CanStartTrial" -> add(contractAction("ownership-accept-trial", R.string.character_accept_ownership_trial, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Accept))
        "CanOfferEndTrial" -> add(contractAction("ownership-offer-full", R.string.character_offer_full_ownership, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Propose))
        "CanEndTrial" -> add(contractAction("ownership-accept-full", R.string.character_accept_full_ownership, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Accept))
    }
    when (lovershipResult) {
        "CanOfferBeginEngagement" -> add(contractAction("lovership-offer-engagement", R.string.character_offer_engagement, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Propose))
        "CanBeginEngagement" -> add(contractAction("lovership-accept-engagement", R.string.character_accept_engagement, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Accept))
        "CanOfferBeginDating" -> add(contractAction("lovership-offer-dating", R.string.character_offer_dating, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Propose))
        "CanBeginDating" -> add(contractAction("lovership-accept-dating", R.string.character_accept_dating, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Accept))
        "CanOfferBeginWedding" -> add(contractAction("lovership-offer-wedding", R.string.character_offer_marriage, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Propose))
        "CanBeginWedding" -> add(contractAction("lovership-accept-wedding", R.string.character_accept_marriage, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Accept))
    }

    val self = relationships.self
    if (self.ownership?.memberNumber == memberNumber) {
        add(contractAction("ownership-break", R.string.character_break_ownership, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Break, destructive = true))
    }
    if (memberNumber in self.submissives) {
        add(contractAction("ownership-release", R.string.character_release_submissive, BcRelationshipContractKind.Ownership, BcRelationshipContractAction.Release, destructive = true))
    }
    if (self.loverships.any { it.memberNumber == memberNumber }) {
        add(contractAction("lovership-break", R.string.character_break_lovership, BcRelationshipContractKind.Lovership, BcRelationshipContractAction.Break, destructive = true))
    }
}

private fun contractAction(
    id: String,
    labelRes: Int,
    kind: BcRelationshipContractKind,
    action: BcRelationshipContractAction,
    destructive: Boolean = false,
) = CharacterRelationshipContractAction(id, labelRes, kind, action, destructive)

private fun BcRelationshipCapabilityQueryResult.toFeedbackRes(): Int? = when (this) {
    is BcRelationshipCapabilityQueryResult.Loaded -> null
    is BcRelationshipCapabilityQueryResult.Partial -> null
    BcRelationshipCapabilityQueryResult.Unanswered -> null
    is BcRelationshipCapabilityQueryResult.Rejected -> reason.toFeedbackRes()
    is BcRelationshipCapabilityQueryResult.Failed -> R.string.character_operation_failed
}

private fun BcRelationshipMutationResult.toFeedbackRes(): Int? = when (this) {
    BcRelationshipMutationResult.Submitted -> R.string.character_list_update_submitted
    BcRelationshipMutationResult.Unchanged -> R.string.character_list_unchanged
    is BcRelationshipMutationResult.Rejected -> reason.toFeedbackRes()
    is BcRelationshipMutationResult.Failed -> R.string.character_operation_failed
}

private fun BcRelationshipContractActionResult.toFeedbackRes(): Int? = when (this) {
    BcRelationshipContractActionResult.Submitted -> R.string.character_action_submitted
    is BcRelationshipContractActionResult.Rejected -> reason.toFeedbackRes()
    is BcRelationshipContractActionResult.Failed -> R.string.character_operation_failed
}

private fun BcRelationshipMutationRejection.toFeedbackRes(): Int = when (this) {
    BcRelationshipMutationRejection.NotVerifiedOnline -> R.string.character_connection_required
    BcRelationshipMutationRejection.RelationshipsNotLoaded -> R.string.character_relationships_loading
    BcRelationshipMutationRejection.InvalidMemberNumber -> R.string.character_invalid_member
    BcRelationshipMutationRejection.CannotTargetSelf -> R.string.character_cannot_target_self
    BcRelationshipMutationRejection.AccountChanged -> R.string.character_account_changed
    BcRelationshipMutationRejection.FriendRequestNotFound -> R.string.character_request_missing
    BcRelationshipMutationRejection.CapabilitiesMissingOrStale -> R.string.character_actions_stale
    BcRelationshipMutationRejection.ActionNotAllowed -> R.string.character_action_unavailable
    BcRelationshipMutationRejection.DestructiveConfirmationRequired -> R.string.character_confirmation_required
    BcRelationshipMutationRejection.OperationPending -> R.string.character_operation_pending
    BcRelationshipMutationRejection.EconomyUnavailable -> R.string.character_economy_unavailable
    BcRelationshipMutationRejection.InsufficientMoney -> R.string.character_insufficient_money
}
