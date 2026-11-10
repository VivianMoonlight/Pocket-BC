package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot

data class BcRelationshipSnapshot(
    val accountMemberNumber: Int? = null,
    val accountGeneration: Long = 0L,
    val loadState: BcRelationshipLoadState = BcRelationshipLoadState.Unloaded,
    val self: BcSelfRelations = BcSelfRelations(),
    val capabilitiesByMember: Map<Int, BcRelationshipCapabilities> = emptyMap(),
    val pendingOperations: Map<BcRelationshipOperationKey, BcPendingRelationshipOperation> = emptyMap(),
    val friendRequests: Map<Int, BcFriendRequest> = emptyMap(),
    /** Shares the gateway's private-log login UUID so same-account logins remain distinct. */
    val loginSessionId: String? = null,
    /** Update order within one gateway login, independent of the runtime account generation. */
    val revision: Long = 0L,
    val ownershipClearRevision: Long? = null,
) {
    fun nextLoaded(
        memberNumber: Int,
        relations: BcSelfRelations,
        loginSessionId: String? = null,
        capabilitiesByMember: Map<Int, BcRelationshipCapabilities> = emptyMap(),
        revision: Long = 0L,
        ownershipClearRevision: Long? = null,
    ): BcRelationshipSnapshot =
        BcRelationshipSnapshot(
            accountMemberNumber = memberNumber,
            accountGeneration = accountGeneration + 1,
            loadState = BcRelationshipLoadState.Loaded,
            self = relations,
            loginSessionId = loginSessionId,
            capabilitiesByMember = capabilitiesByMember,
            revision = revision,
            ownershipClearRevision = ownershipClearRevision,
        )

    fun nextUnloaded(): BcRelationshipSnapshot =
        BcRelationshipSnapshot(accountGeneration = accountGeneration + 1)
}

fun BcRelationshipSnapshot.withGatewayUpdate(
    update: BcRelationshipGatewayUpdate,
    receivedAtMillis: Long,
    revision: Long = this.revision + 1L,
): BcRelationshipSnapshot = when (update) {
    is BcRelationshipGatewayUpdate.Ownership -> {
        val updatedSelf = when {
            update.clearOwnership -> self.copy(legacyOwnerName = null, ownership = null)
            update.ownerName != null && update.ownership != null -> self.copy(
                legacyOwnerName = update.ownerName,
                ownership = update.ownership,
            )
            else -> self
        }
        val memberNumber = update.memberNumber
        val result = update.result
        val capabilities = if (memberNumber == null || result == null) capabilitiesByMember else {
            capabilitiesByMember + (memberNumber to (
                capabilitiesByMember[memberNumber] ?: BcRelationshipCapabilities()
            ).copy(ownershipResult = result, queriedAtMillis = receivedAtMillis))
        }
        copy(
            self = updatedSelf,
            ownershipClearRevision = if (update.clearOwnership) revision else ownershipClearRevision,
            capabilitiesByMember = capabilities,
            pendingOperations = if (update.clearOwnership || update.ownerName != null && update.ownership != null) {
                pendingOperations.filterKeys { it.type != BcRelationshipOperationType.Ownership }
            } else pendingOperations,
        )
    }
    is BcRelationshipGatewayUpdate.Lovership -> {
        val memberNumber = update.memberNumber
        val result = update.result
        val capabilities = if (memberNumber == null || result == null) capabilitiesByMember else {
            capabilitiesByMember + (memberNumber to (
                capabilitiesByMember[memberNumber] ?: BcRelationshipCapabilities()
            ).copy(lovershipResult = result, queriedAtMillis = receivedAtMillis))
        }
        copy(
            self = update.loverships?.let { values ->
                self.copy(loverships = values.filterNot { it.memberNumber == accountMemberNumber })
            } ?: self,
            capabilitiesByMember = capabilities,
            pendingOperations = if (update.loverships != null) {
                pendingOperations.filterKeys { it.type != BcRelationshipOperationType.Lovership }
            } else pendingOperations,
        )
    }
}

fun BcSessionSnapshot.acceptsRelationshipEvent(event: BcSessionGatewayEvent.RelationshipUpdated): Boolean {
    if (relationships.loadState != BcRelationshipLoadState.Loaded) return false
    if (event.accountMemberNumber == null && event.selfRuleLogSessionId == null && event.revision == null) {
        return relationships.loginSessionId == null && selfRuleLogSessionId == null &&
            (state == BcSessionState.Idle || desiredOnline && state.isAuthenticated)
    }
    val memberNumber = event.accountMemberNumber ?: return false
    val sessionId = event.selfRuleLogSessionId?.takeIf(String::isNotBlank) ?: return false
    val revision = event.revision ?: return false
    return desiredOnline && state.isAuthenticated && revision >= 0L &&
        account?.memberNumber == memberNumber && relationships.accountMemberNumber == memberNumber &&
        relationships.loginSessionId == sessionId && selfRuleLogSessionId == sessionId
}

enum class BcRelationshipLoadState {
    Unloaded,
    Loaded,
}

data class BcSelfRelations(
    val friends: Set<Int> = emptySet(),
    val friendNames: Map<Int, String> = emptyMap(),
    val ignored: Set<Int> = emptySet(),
    val whitelist: Set<Int> = emptySet(),
    val blacklist: Set<Int> = emptySet(),
    val submissives: Set<Int> = emptySet(),
    val legacyOwnerName: String? = null,
    val ownership: BcOwnership? = null,
    val loverships: List<BcLovership> = emptyList(),
    val money: Int? = null,
    val reputation: Map<String, Int> = emptyMap(),
)

data class BcOwnership(
    val memberNumber: Int,
    val name: String,
    val stage: Int?,
    val startEpochMillis: Long?,
    val notes: String?,
) {
    val stageKind: BcOwnershipStage
        get() = when (stage) {
            0 -> BcOwnershipStage.Trial
            null -> BcOwnershipStage.Unknown
            else -> if (stage > 0) BcOwnershipStage.FullyOwned else BcOwnershipStage.Unknown
        }
}

enum class BcOwnershipStage {
    Trial,
    FullyOwned,
    Unknown,
}

data class BcLovership(
    val memberNumber: Int,
    val name: String,
    val stage: Int?,
    val startEpochMillis: Long?,
) {
    val stageKind: BcLovershipStage
        get() = when (stage) {
            0 -> BcLovershipStage.Dating
            1 -> BcLovershipStage.Engaged
            2 -> BcLovershipStage.Married
            else -> BcLovershipStage.Unknown
        }
}

enum class BcLovershipStage {
    Dating,
    Engaged,
    Married,
    Unknown,
}

data class BcRelationshipCapabilities(
    val ownershipResult: String? = null,
    val lovershipResult: String? = null,
    val queriedAtMillis: Long? = null,
)

data class BcRelationshipOperationKey(
    val type: BcRelationshipOperationType,
    val memberNumber: Int,
    val accountGeneration: Long,
)

enum class BcRelationshipOperationType {
    FriendList,
    IgnoreList,
    WhiteList,
    BlackList,
    Ownership,
    Lovership,
}

enum class BcRelationshipContractKind {
    Ownership,
    Lovership,
}

enum class BcRelationshipContractAction(val wireValue: String) {
    Propose("Propose"),
    Accept("Accept"),
    Release("Release"),
    Break("Break"),
}

data class BcRelationshipContractCommand(
    val kind: BcRelationshipContractKind,
    val memberNumber: Int,
    val action: BcRelationshipContractAction,
)

enum class BcRelationshipEconomyEffect(
    val moneyDelta: Int,
    val dominantReputationProgress: Int,
) {
    OwnershipOfferEndTrial(moneyDelta = -100, dominantReputationProgress = 10),
    OwnershipAcceptEndTrial(moneyDelta = 0, dominantReputationProgress = -20),
    LovershipOfferWedding(moneyDelta = -100, dominantReputationProgress = 0),
    LovershipAcceptWedding(moneyDelta = -100, dominantReputationProgress = 0),
}

data class BcRelationshipEconomyState(
    val money: Int?,
    val reputation: Map<String, Int>,
)

fun BcRelationshipEconomyState.withNativeEffect(
    effect: BcRelationshipEconomyEffect,
): BcRelationshipEconomyState {
    val nextMoney = if (effect.moneyDelta == 0) {
        money
    } else {
        val currentMoney = requireNotNull(money) { "Relationship money state is unavailable." }
        require(currentMoney + effect.moneyDelta >= 0) { "Insufficient money for relationship action." }
        currentMoney + effect.moneyDelta
    }
    val nextReputation = if (effect.dominantReputationProgress == 0) {
        reputation
    } else {
        val currentDominant = reputation[DOMINANT_REPUTATION] ?: 0
        reputation + (
            DOMINANT_REPUTATION to nativeReputationProgress(
                currentValue = currentDominant,
                progress = effect.dominantReputationProgress,
            )
        )
    }
    return copy(money = nextMoney, reputation = nextReputation)
}

private fun nativeReputationProgress(currentValue: Int, progress: Int): Int {
    val current = currentValue.coerceIn(-100, 100)
    val change = when {
        progress > 0 && current >= 70 -> kotlin.math.floor(progress / 3.0).toInt()
        progress > 0 && current >= 30 -> kotlin.math.floor(progress / 2.0).toInt()
        progress > 0 && current > -30 -> progress
        progress > 0 && current > -70 -> progress * 2
        progress > 0 -> progress * 4
        current <= -70 -> -kotlin.math.floor(-progress / 3.0).toInt()
        current <= -30 -> -kotlin.math.floor(-progress / 2.0).toInt()
        current < 30 -> progress
        current < 70 -> progress * 2
        else -> progress * 4
    }
    return (current + change).coerceIn(-100, 100)
}

private const val DOMINANT_REPUTATION = "Dominant"

enum class BcRelationshipListKind {
    Friend,
    Ignore,
    White,
    Black,
}

fun BcSelfRelations.withListMutation(
    kind: BcRelationshipListKind,
    memberNumber: Int,
    enabled: Boolean,
): BcSelfRelations =
    when (kind) {
        BcRelationshipListKind.Friend -> copy(
            friends = friends.withMembership(memberNumber, enabled),
        )
        BcRelationshipListKind.White -> if (enabled) {
            copy(
                whitelist = whitelist + memberNumber,
                blacklist = blacklist - memberNumber,
                ignored = ignored - memberNumber,
            )
        } else {
            copy(whitelist = whitelist - memberNumber)
        }
        BcRelationshipListKind.Black -> if (enabled) {
            copy(
                blacklist = blacklist + memberNumber,
                whitelist = whitelist - memberNumber,
            )
        } else {
            copy(blacklist = blacklist - memberNumber)
        }
        BcRelationshipListKind.Ignore -> if (enabled) {
            copy(
                ignored = ignored + memberNumber,
                whitelist = whitelist - memberNumber,
                blacklist = blacklist + memberNumber,
            )
        } else {
            copy(
                ignored = ignored - memberNumber,
                blacklist = blacklist - memberNumber,
            )
        }
    }

private fun Set<Int>.withMembership(memberNumber: Int, enabled: Boolean): Set<Int> =
    if (enabled) this + memberNumber else this - memberNumber

data class BcPendingRelationshipOperation(
    val operationId: String,
    val submittedAtMillis: Long,
)

data class BcFriendRequest(
    val senderMemberNumber: Int,
    val receivedAtMillis: Long,
    val expiresAtMillis: Long,
)

data class BcMemberRelationshipProjection(
    val memberNumber: Int,
    val iFriendThem: Boolean,
    val iIgnoreThem: Boolean,
    val iWhitelistThem: Boolean,
    val iBlacklistThem: Boolean,
    val theyWhitelistMe: Boolean?,
    val theyBlacklistMe: Boolean?,
    val isMyOwner: Boolean,
    val isMySubmissive: Boolean,
    val isMyLover: Boolean,
    val ownershipStage: BcOwnershipStage?,
    val lovershipStage: BcLovershipStage?,
)

fun BcSessionSnapshot.relationshipWith(memberNumber: Int): BcMemberRelationshipProjection {
    val selfRelations = relationships.self
    val selfMemberNumber = relationships.accountMemberNumber ?: account?.memberNumber
    val roomCharacter: BcCharacterSnapshot? = roomCharacters.firstOrNull { it.memberNumber == memberNumber }
    val ownership = selfRelations.ownership?.takeIf { it.memberNumber == memberNumber }
    val lovership = selfRelations.loverships.firstOrNull { it.memberNumber == memberNumber }
    return BcMemberRelationshipProjection(
        memberNumber = memberNumber,
        iFriendThem = memberNumber in selfRelations.friends,
        iIgnoreThem = memberNumber in selfRelations.ignored,
        iWhitelistThem = memberNumber in selfRelations.whitelist,
        iBlacklistThem = memberNumber in selfRelations.blacklist,
        theyWhitelistMe = roomCharacter?.let { character ->
            selfMemberNumber?.let { it in character.whiteList }
        },
        theyBlacklistMe = roomCharacter?.let { character ->
            selfMemberNumber?.let { it in character.blackList }
        },
        isMyOwner = ownership != null,
        isMySubmissive = memberNumber in selfRelations.submissives,
        isMyLover = lovership != null,
        ownershipStage = ownership?.stageKind,
        lovershipStage = lovership?.stageKind,
    )
}
