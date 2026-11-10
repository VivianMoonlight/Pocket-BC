package dev.vivian.mobilebc.domain.session.relationship

import dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractCommand
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.acceptsRelationshipEvent
import dev.vivian.mobilebc.domain.session.core.withGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipOperationKey
import dev.vivian.mobilebc.domain.session.core.BcRelationshipOperationType
import dev.vivian.mobilebc.domain.session.core.BcPendingRelationshipOperation
import dev.vivian.mobilebc.domain.session.core.RelationshipGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.withListMutation
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

class BcSessionRelationshipController(
    private val stateHolder: BcSessionRuntimeState,
    private val gateway: RelationshipGateway,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
    private val friendRequestTtlMillis: Long = DEFAULT_FRIEND_REQUEST_TTL_MILLIS,
    private val capabilityTtlMillis: Long = DEFAULT_CAPABILITY_TTL_MILLIS,
    private val contractOperationTtlMillis: Long = DEFAULT_CONTRACT_OPERATION_TTL_MILLIS,
    private val contractPendingScope: CoroutineScope? = null,
) : BcSessionRelationships {
    private val listMutationMutex = Mutex()
    private val contractMutationMutex = Mutex()

    override suspend fun queryRelationshipCapabilities(
        memberNumber: Int,
    ): BcRelationshipCapabilityQueryResult {
        val initialSession = stateHolder.current
        if (!initialSession.isVerifiedOnline) {
            return BcRelationshipCapabilityQueryResult.Rejected(
                BcRelationshipMutationRejection.NotVerifiedOnline
            )
        }
        val initialRelationships = initialSession.relationships
        if (initialRelationships.loadState != BcRelationshipLoadState.Loaded) {
            return BcRelationshipCapabilityQueryResult.Rejected(
                BcRelationshipMutationRejection.RelationshipsNotLoaded
            )
        }
        val selfMemberNumber = initialRelationships.accountMemberNumber
            ?: return BcRelationshipCapabilityQueryResult.Rejected(
                BcRelationshipMutationRejection.RelationshipsNotLoaded
            )
        if (memberNumber <= 0) {
            return BcRelationshipCapabilityQueryResult.Rejected(
                BcRelationshipMutationRejection.InvalidMemberNumber
            )
        }
        if (memberNumber == selfMemberNumber) {
            return BcRelationshipCapabilityQueryResult.Rejected(
                BcRelationshipMutationRejection.CannotTargetSelf
            )
        }

        val generation = initialRelationships.accountGeneration
        return runCatching {
            val (ownershipResult, lovershipResult) = coroutineScope {
                val ownership = async { gateway.queryOwnershipCapability(memberNumber) }
                val lovership = async { gateway.queryLovershipCapability(memberNumber) }
                ownership.await() to lovership.await()
            }
            coroutineContext.ensureActive()
            val capabilities = BcRelationshipCapabilities(
                ownershipResult = ownershipResult,
                lovershipResult = lovershipResult,
                queriedAtMillis = if (ownershipResult != null || lovershipResult != null) nowMillis() else null,
            )
            var applied = false
            stateHolder.updateSession { session ->
                val relationships = session.relationships
                if (relationships.accountGeneration != generation ||
                    relationships.accountMemberNumber != selfMemberNumber ||
                    relationships.loadState != BcRelationshipLoadState.Loaded ||
                    session.roomPresenceEpoch != initialSession.roomPresenceEpoch ||
                    (initialSession.currentRoom != null && (!session.isVerifiedOnline || session.currentRoom?.name != initialSession.currentRoom?.name ||
                        session.roomCharacters.none { it.memberNumber == memberNumber }))
                ) {
                    return@updateSession session
                }
                applied = true
                session.copy(
                    relationships = relationships.copy(
                        capabilitiesByMember = relationships.capabilitiesByMember +
                            (memberNumber to capabilities)
                    )
                )
            }
            if (applied) {
                when {
                    ownershipResult != null && lovershipResult != null ->
                        BcRelationshipCapabilityQueryResult.Loaded(capabilities)
                    ownershipResult != null || lovershipResult != null ->
                        BcRelationshipCapabilityQueryResult.Partial(capabilities)
                    else -> BcRelationshipCapabilityQueryResult.Unanswered
                }
            } else {
                BcRelationshipCapabilityQueryResult.Rejected(
                    BcRelationshipMutationRejection.AccountChanged
                )
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            BcRelationshipCapabilityQueryResult.Failed(
                throwable.message ?: "Relationship capability query failed."
            )
        }
    }

    override suspend fun submitRelationshipContractAction(
        memberNumber: Int,
        kind: BcRelationshipContractKind,
        action: BcRelationshipContractAction,
        destructiveActionConfirmed: Boolean,
    ): BcRelationshipContractActionResult = contractMutationMutex.withLock {
        val initialSession = stateHolder.current
        if (!initialSession.isVerifiedOnline) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.NotVerifiedOnline
            )
        }
        val initialRelationships = initialSession.relationships
        if (initialRelationships.loadState != BcRelationshipLoadState.Loaded) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.RelationshipsNotLoaded
            )
        }
        val selfMemberNumber = initialRelationships.accountMemberNumber
            ?: return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.RelationshipsNotLoaded
            )
        if (memberNumber <= 0) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.InvalidMemberNumber
            )
        }
        if (memberNumber == selfMemberNumber) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.CannotTargetSelf
            )
        }
        if (action.isDestructive && !destructiveActionConfirmed) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.DestructiveConfirmationRequired
            )
        }

        val now = nowMillis()
        val capabilities = initialRelationships.capabilitiesByMember[memberNumber]
        val queriedAtMillis = capabilities?.queriedAtMillis
        if (queriedAtMillis == null ||
            now - queriedAtMillis !in 0..capabilityTtlMillis
        ) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.CapabilitiesMissingOrStale
            )
        }
        val actionPlan = initialRelationships.self.safeActionPlan(
            memberNumber = memberNumber,
            kind = kind,
            action = action,
            capabilities = capabilities,
        ) ?: run {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.ActionNotAllowed
            )
        }
        actionPlan.economyEffect?.preflightRejection(initialRelationships.self)?.let { rejection ->
            return@withLock BcRelationshipContractActionResult.Rejected(rejection)
        }

        val generation = initialRelationships.accountGeneration
        val operationKey = BcRelationshipOperationKey(
            type = kind.toOperationType(),
            memberNumber = memberNumber,
            accountGeneration = generation,
        )
        val activePending = initialRelationships.pendingOperations.filterValues { pending ->
            now - pending.submittedAtMillis <= contractOperationTtlMillis
        }
        if (activePending.keys.any { key -> key.type == operationKey.type }) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.OperationPending
            )
        }

        val operationId = newOperationId()
        var pendingApplied = false
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            if (relationships.accountGeneration != generation ||
                relationships.accountMemberNumber != selfMemberNumber ||
                relationships.loadState != BcRelationshipLoadState.Loaded
            ) {
                return@updateSession session
            }
            pendingApplied = true
            val currentPending = relationships.pendingOperations.filterValues { pending ->
                now - pending.submittedAtMillis <= contractOperationTtlMillis
            }
            session.copy(
                relationships = relationships.copy(
                    pendingOperations = currentPending + (
                        operationKey to BcPendingRelationshipOperation(operationId, now)
                    )
                )
            )
        }
        if (!pendingApplied) {
            return@withLock BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.AccountChanged
            )
        }
        scheduleContractPendingExpiry(operationKey, operationId)

        actionPlan.economyEffect?.let { effect ->
            val economy = runCatching {
                gateway.applyRelationshipEconomyEffect(effect).also { coroutineContext.ensureActive() }
            }.getOrElse { throwable ->
                finishContractPending(operationKey, operationId)
                if (throwable is CancellationException) throw throwable
                return@withLock BcRelationshipContractActionResult.Failed(
                    throwable.message ?: "Relationship economy update failed."
                )
            }
            var economyApplied = false
            stateHolder.updateSession { session ->
                val relationships = session.relationships
                if (relationships.accountGeneration != generation ||
                    relationships.accountMemberNumber != selfMemberNumber ||
                    relationships.loadState != BcRelationshipLoadState.Loaded
                ) {
                    return@updateSession session
                }
                economyApplied = true
                session.copy(
                    relationships = relationships.copy(
                        self = relationships.self.copy(
                            money = economy.money,
                            reputation = economy.reputation,
                        )
                    )
                )
            }
            if (!economyApplied) {
                finishContractPending(operationKey, operationId)
                return@withLock BcRelationshipContractActionResult.Rejected(
                    BcRelationshipMutationRejection.AccountChanged
                )
            }
        }

        val command = BcRelationshipContractCommand(kind, memberNumber, action)
        runCatching {
            coroutineContext.ensureActive()
            gateway.sendRelationshipContractAction(command)
            coroutineContext.ensureActive()
        }
            .fold(
                onSuccess = { BcRelationshipContractActionResult.Submitted },
                onFailure = { throwable ->
                    finishContractPending(operationKey, operationId)
                    if (throwable is CancellationException) throw throwable
                    BcRelationshipContractActionResult.Failed(
                        throwable.message ?: "Relationship contract action failed."
                    )
                },
            )
    }

    fun handleGatewayEvent(event: BcSessionGatewayEvent): Boolean {
        val relationshipEvent = event as? BcSessionGatewayEvent.RelationshipUpdated ?: return false
        val revision = relationshipEvent.revision
        var accepted = false
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            accepted = session.acceptsRelationshipEvent(relationshipEvent) &&
                (revision == null || revision > relationships.revision)
            if (!accepted) return@updateSession session
            val nextRelationships = relationships.withGatewayUpdate(
                relationshipEvent.update, nowMillis(), revision ?: relationships.revision + 1L,
            ).copy(revision = revision ?: relationships.revision + 1L)
            if (nextRelationships == relationships) session else session.copy(relationships = nextRelationships)
        }
        return accepted
    }

    override suspend fun updateRelationshipList(
        memberNumber: Int,
        kind: BcRelationshipListKind,
        enabled: Boolean,
    ): BcRelationshipMutationResult = listMutationMutex.withLock {
        val initialSession = stateHolder.current
        if (!initialSession.isVerifiedOnline) {
            return@withLock BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.NotVerifiedOnline
            )
        }
        val initialRelationships = initialSession.relationships
        if (initialRelationships.loadState != BcRelationshipLoadState.Loaded) {
            return@withLock BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.RelationshipsNotLoaded
            )
        }
        val selfMemberNumber = initialRelationships.accountMemberNumber
            ?: return@withLock BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.RelationshipsNotLoaded
            )
        if (memberNumber <= 0) {
            return@withLock BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.InvalidMemberNumber
            )
        }
        if (memberNumber == selfMemberNumber) {
            return@withLock BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.CannotTargetSelf
            )
        }

        val previousSelf = initialRelationships.self
        val nextSelf = previousSelf.withListMutation(kind, memberNumber, enabled)
        if (nextSelf == previousSelf) return@withLock BcRelationshipMutationResult.Unchanged

        val generation = initialRelationships.accountGeneration
        val operationId = newOperationId()
        val operationKey = BcRelationshipOperationKey(
            type = kind.toOperationType(),
            memberNumber = memberNumber,
            accountGeneration = generation,
        )
        var optimisticStateApplied = false
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            if (relationships.accountGeneration != generation ||
                relationships.accountMemberNumber != selfMemberNumber ||
                relationships.loadState != BcRelationshipLoadState.Loaded
            ) {
                return@updateSession session
            }
            optimisticStateApplied = true
            session.copy(
                relationships = relationships.copy(
                    self = nextSelf,
                    pendingOperations = relationships.pendingOperations + (
                        operationKey to BcPendingRelationshipOperation(operationId, nowMillis())
                    ),
                )
            )
        }
        if (!optimisticStateApplied) {
            return@withLock BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.AccountChanged
            )
        }

        val update = BcRelationshipListsUpdate(
            friends = nextSelf.friends,
            friendNames = nextSelf.friendNames,
            ignored = nextSelf.ignored,
            whitelist = nextSelf.whitelist,
            blacklist = nextSelf.blacklist,
            submissives = nextSelf.submissives,
        )
        runCatching {
            coroutineContext.ensureActive()
            gateway.updateRelationshipLists(update)
            coroutineContext.ensureActive()
        }
            .fold(
                onSuccess = {
                    finishPendingOperation(operationKey, operationId, rollbackTo = null)
                    BcRelationshipMutationResult.Submitted
                },
                onFailure = { throwable ->
                    finishPendingOperation(operationKey, operationId, rollbackTo = previousSelf)
                    if (throwable is CancellationException) throw throwable
                    BcRelationshipMutationResult.Failed(
                        throwable.message ?: "Relationship list update failed."
                    )
                },
            )
    }

    override fun recordFriendRequest(senderMemberNumber: Int, receivedAtMillis: Long) {
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            val self = relationships.self
            val selfMemberNumber = relationships.accountMemberNumber
            if (relationships.loadState != BcRelationshipLoadState.Loaded ||
                senderMemberNumber <= 0 ||
                senderMemberNumber == selfMemberNumber ||
                senderMemberNumber in self.ignored ||
                senderMemberNumber in self.friends
            ) {
                return@updateSession session
            }
            val unexpired = relationships.friendRequests.filterValues { request ->
                request.expiresAtMillis > receivedAtMillis
            }
            val request = dev.vivian.mobilebc.domain.session.core.BcFriendRequest(
                senderMemberNumber = senderMemberNumber,
                receivedAtMillis = receivedAtMillis,
                expiresAtMillis = receivedAtMillis + friendRequestTtlMillis,
            )
            session.copy(
                relationships = relationships.copy(
                    friendRequests = unexpired + (senderMemberNumber to request)
                )
            )
        }
    }

    override suspend fun acceptFriendRequest(senderMemberNumber: Int): BcRelationshipMutationResult {
        val request = stateHolder.current.relationships.friendRequests[senderMemberNumber]
        if (request == null || request.expiresAtMillis <= nowMillis()) {
            dismissFriendRequest(senderMemberNumber)
            return BcRelationshipMutationResult.Rejected(
                BcRelationshipMutationRejection.FriendRequestNotFound
            )
        }
        return updateRelationshipList(
            memberNumber = senderMemberNumber,
            kind = BcRelationshipListKind.Friend,
            enabled = true,
        ).also { result ->
            if (result == BcRelationshipMutationResult.Submitted ||
                result == BcRelationshipMutationResult.Unchanged
            ) {
                dismissFriendRequest(senderMemberNumber)
            }
        }
    }

    override fun dismissFriendRequest(senderMemberNumber: Int) {
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            if (senderMemberNumber !in relationships.friendRequests) return@updateSession session
            session.copy(
                relationships = relationships.copy(
                    friendRequests = relationships.friendRequests - senderMemberNumber
                )
            )
        }
    }

    private fun finishPendingOperation(
        key: BcRelationshipOperationKey,
        operationId: String,
        rollbackTo: BcSelfRelations?,
    ) {
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            val pending = relationships.pendingOperations[key]
            if (pending?.operationId != operationId ||
                relationships.accountGeneration != key.accountGeneration
            ) {
                return@updateSession session
            }
            session.copy(
                relationships = relationships.copy(
                    self = if (rollbackTo == null) {
                        relationships.self
                    } else {
                        relationships.self.withRelationshipListsFrom(rollbackTo)
                    },
                    pendingOperations = relationships.pendingOperations - key,
                )
            )
        }
    }

    private fun finishContractPending(
        key: BcRelationshipOperationKey,
        operationId: String,
    ) {
        stateHolder.updateSession { session ->
            val relationships = session.relationships
            val pending = relationships.pendingOperations[key]
            if (pending?.operationId != operationId ||
                relationships.accountGeneration != key.accountGeneration
            ) {
                return@updateSession session
            }
            session.copy(
                relationships = relationships.copy(
                    pendingOperations = relationships.pendingOperations - key
                )
            )
        }
    }

    private fun scheduleContractPendingExpiry(
        key: BcRelationshipOperationKey,
        operationId: String,
    ) {
        val scope = contractPendingScope ?: return
        scope.launch {
            delay(contractOperationTtlMillis)
            finishContractPending(key, operationId)
        }
    }

    private fun BcSelfRelations.withRelationshipListsFrom(previous: BcSelfRelations): BcSelfRelations =
        copy(
            friends = previous.friends,
            ignored = previous.ignored,
            whitelist = previous.whitelist,
            blacklist = previous.blacklist,
        )

    private fun BcRelationshipListKind.toOperationType(): BcRelationshipOperationType =
        when (this) {
            BcRelationshipListKind.Friend -> BcRelationshipOperationType.FriendList
            BcRelationshipListKind.Ignore -> BcRelationshipOperationType.IgnoreList
            BcRelationshipListKind.White -> BcRelationshipOperationType.WhiteList
            BcRelationshipListKind.Black -> BcRelationshipOperationType.BlackList
        }

    private fun BcRelationshipContractKind.toOperationType(): BcRelationshipOperationType =
        when (this) {
            BcRelationshipContractKind.Ownership -> BcRelationshipOperationType.Ownership
            BcRelationshipContractKind.Lovership -> BcRelationshipOperationType.Lovership
        }

    private companion object {
        private const val DEFAULT_FRIEND_REQUEST_TTL_MILLIS = 15 * 60 * 1_000L
        private const val DEFAULT_CAPABILITY_TTL_MILLIS = 60_000L
        private const val DEFAULT_CONTRACT_OPERATION_TTL_MILLIS = 30_000L
    }
}

private val BcRelationshipContractAction.isDestructive: Boolean
    get() = this == BcRelationshipContractAction.Release || this == BcRelationshipContractAction.Break

private fun BcSelfRelations.safeActionPlan(
    memberNumber: Int,
    kind: BcRelationshipContractKind,
    action: BcRelationshipContractAction,
    capabilities: BcRelationshipCapabilities,
): BcSafeRelationshipAction? =
    when (kind) {
        BcRelationshipContractKind.Ownership -> when (action) {
            BcRelationshipContractAction.Propose -> when (capabilities.ownershipResult) {
                "CanOfferStartTrial" -> BcSafeRelationshipAction()
                "CanOfferEndTrial" -> BcSafeRelationshipAction(
                    BcRelationshipEconomyEffect.OwnershipOfferEndTrial
                )
                else -> null
            }
            BcRelationshipContractAction.Accept -> when (capabilities.ownershipResult) {
                "CanStartTrial" -> BcSafeRelationshipAction()
                "CanEndTrial" -> BcSafeRelationshipAction(
                    BcRelationshipEconomyEffect.OwnershipAcceptEndTrial
                )
                else -> null
            }
            BcRelationshipContractAction.Release ->
                BcSafeRelationshipAction().takeIf { memberNumber in submissives }
            BcRelationshipContractAction.Break ->
                BcSafeRelationshipAction().takeIf { ownership?.memberNumber == memberNumber }
        }
        BcRelationshipContractKind.Lovership -> when (action) {
            BcRelationshipContractAction.Propose -> when (capabilities.lovershipResult) {
                "CanOfferBeginDating", "CanOfferBeginEngagement" -> BcSafeRelationshipAction()
                "CanOfferBeginWedding" -> BcSafeRelationshipAction(
                    BcRelationshipEconomyEffect.LovershipOfferWedding
                )
                else -> null
            }
            BcRelationshipContractAction.Accept -> when (capabilities.lovershipResult) {
                "CanBeginDating", "CanBeginEngagement" -> BcSafeRelationshipAction()
                "CanBeginWedding" -> BcSafeRelationshipAction(
                    BcRelationshipEconomyEffect.LovershipAcceptWedding
                )
                else -> null
            }
            BcRelationshipContractAction.Release -> null
            BcRelationshipContractAction.Break ->
                BcSafeRelationshipAction().takeIf { loverships.any { it.memberNumber == memberNumber } }
        }
    }

private data class BcSafeRelationshipAction(
    val economyEffect: BcRelationshipEconomyEffect? = null,
)

private fun BcRelationshipEconomyEffect.preflightRejection(
    relations: BcSelfRelations,
): BcRelationshipMutationRejection? {
    if (moneyDelta >= 0) return null
    val money = relations.money ?: return BcRelationshipMutationRejection.EconomyUnavailable
    return if (money + moneyDelta < 0) {
        BcRelationshipMutationRejection.InsufficientMoney
    } else {
        null
    }
}
