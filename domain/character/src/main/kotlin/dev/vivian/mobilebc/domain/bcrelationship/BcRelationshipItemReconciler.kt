package dev.vivian.mobilebc.domain.bcrelationship

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.session.core.BcOwnershipStage
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Applies the appearance side effects that native BC runs after ownership/lovership changes.
 *
 * The relationship snapshot and room snapshot remain server-authoritative. This component only
 * submits idempotent item updates, suppresses duplicate submissions while waiting for ItemSync,
 * and rejects delayed work after an account-generation change.
 */
class BcRelationshipItemReconciler(
    private val gateway: InteractionGateway,
    private val sessionSnapshot: StateFlow<BcSessionSnapshot>,
    private val catalogProvider: () -> BcRuleCatalog? = { null },
    private val catalogDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val diagnostic: (String) -> Unit = {},
    private val retryScope: CoroutineScope? = null,
) {
    private val mutex = Mutex()
    private var trackedGeneration: Long? = null
    private var trackedRoomPresenceEpoch: Long? = null
    private var ownershipCleanupGeneration: Long? = null
    private var handledOwnershipClearRevision: Long? = null
    private var retryJob: Job? = null
    private val submissionsByGroup = mutableMapOf<String, PendingSubmission>()

    suspend fun reconcile(
        ownershipCleared: Boolean = false,
        expectedAccountGeneration: Long? = null,
        expectedLoginSessionId: String? = null,
    ) = mutex.withLock {
        val initial = sessionSnapshot.value
        val relationships = initial.relationships
        if (relationships.loadState != BcRelationshipLoadState.Loaded) return@withLock
        if (expectedAccountGeneration != null &&
            (relationships.accountGeneration != expectedAccountGeneration ||
                relationships.loginSessionId != expectedLoginSessionId ||
                initial.selfRuleLogSessionId != expectedLoginSessionId)
        ) return@withLock

        val generation = relationships.accountGeneration
        if (trackedGeneration != generation || trackedRoomPresenceEpoch != initial.roomPresenceEpoch) {
            if (trackedGeneration != generation) {
                ownershipCleanupGeneration = null
                handledOwnershipClearRevision = null
            }
            trackedGeneration = generation
            trackedRoomPresenceEpoch = initial.roomPresenceEpoch
            submissionsByGroup.clear()
            cancelRetry()
        }
        val ownershipClearRevision = relationships.ownershipClearRevision
        val handledClearRevision = handledOwnershipClearRevision
        if (ownershipClearRevision != null &&
            (handledClearRevision == null || ownershipClearRevision > handledClearRevision)
        ) {
            handledOwnershipClearRevision = ownershipClearRevision
            ownershipCleanupGeneration = generation
        } else if (ownershipCleared && ownershipClearRevision == null) {
            ownershipCleanupGeneration = generation
        }

        val accountMemberNumber = relationships.accountMemberNumber
            ?: initial.account?.memberNumber
            ?: return@withLock
        if (!initial.isVerifiedOnline || initial.currentRoom == null) return@withLock
        if (initial.roomCharacters.selfCharacter(accountMemberNumber) == null) return@withLock
        val cleanupOwnership = ownershipCleanupGeneration == generation
        val catalog = withContext(catalogDispatcher) {
            try {
                catalogProvider()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
        }
        val plannedSession = sessionSnapshot.value
        if (!plannedSession.isSameRelationshipRoom(initial, accountMemberNumber)) return@withLock
        val self = plannedSession.roomCharacters.selfCharacter(accountMemberNumber) ?: return@withLock
        val plannedRelations = plannedSession.relationships.self
        val plan = BcRelationshipItemPlanner.plan(
            character = self,
            relations = plannedRelations,
            catalog = catalog,
            cleanupOwnership = cleanupOwnership,
        )

        val plannedGroups = plan.mutations.mapTo(hashSetOf()) { it.command.group }
        submissionsByGroup.keys.retainAll(plannedGroups)
        if (cleanupOwnership && !plan.ownershipCleanupRequired) {
            ownershipCleanupGeneration = null
        }
        if (plan.mutations.isEmpty()) {
            cancelRetry()
            return@withLock
        }

        for (mutation in plan.mutations) {
            coroutineContext.ensureActive()
            val current = sessionSnapshot.value
            if (!current.isSameRelationshipRoom(initial, accountMemberNumber) ||
                current.relationships.self != plannedRelations
            ) return@withLock
            val currentSelf = current.roomCharacters.selfCharacter(accountMemberNumber) ?: return@withLock
            if (currentSelf != self) return@withLock
            val currentItem = currentSelf.appearance.itemIn(mutation.command.group)
            val previousSubmission = submissionsByGroup[mutation.command.group]
            val now = clockMillis()
            if (
                previousSubmission?.command == mutation.command &&
                previousSubmission.baseItem == currentItem &&
                now - previousSubmission.submittedAtMillis < SUBMISSION_RETRY_MILLIS
            ) {
                continue
            }

            runCatching {
                gateway.updateCharacterItem(mutation.command)
                coroutineContext.ensureActive()
            }.onSuccess {
                submissionsByGroup[mutation.command.group] = PendingSubmission(
                    command = mutation.command,
                    baseItem = currentItem,
                    submittedAtMillis = now,
                )
            }.onFailure { throwable ->
                if (throwable is CancellationException) throw throwable
                diagnostic(
                    "Relationship item reconciliation failed for ${mutation.command.group}: " +
                        (throwable.message ?: throwable::class.java.simpleName),
                )
                scheduleRetry(generation, initial.roomPresenceEpoch)
                return@withLock
            }
        }
        scheduleRetry(generation, initial.roomPresenceEpoch)
    }

    private fun scheduleRetry(generation: Long, roomPresenceEpoch: Long) {
        val scope = retryScope ?: return
        if (retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(SUBMISSION_RETRY_MILLIS)
            retryJob = null
            val current = sessionSnapshot.value
            if (current.isVerifiedOnline && current.currentRoom != null &&
                current.relationships.accountGeneration == generation && current.roomPresenceEpoch == roomPresenceEpoch
            ) {
                reconcile()
            }
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    private data class PendingSubmission(
        val command: BcCharacterItemUpdateCommand,
        val baseItem: BcCharacterItem?,
        val submittedAtMillis: Long,
    )

    private companion object {
        private const val SUBMISSION_RETRY_MILLIS = 15_000L
    }
}

internal object BcRelationshipItemPlanner {
    fun plan(
        character: BcCharacterSnapshot,
        relations: BcSelfRelations,
        catalog: BcRuleCatalog?,
        cleanupOwnership: Boolean,
    ): BcRelationshipItemPlan {
        val initial = character.appearance.associateByTo(linkedMapOf(), BcCharacterItem::group)
        val desired = LinkedHashMap(initial)
        val cleanupRequired = cleanupOwnership && initial.values.any { item ->
            item.isOwnerOnly(catalog) || item.hasOwnerOnlyLock(catalog)
        }

        if (cleanupOwnership) {
            desired.entries.removeAll { (_, item) -> item.isOwnerOnly(catalog) }
            desired.replaceAll { _, item ->
                if (item.hasOwnerOnlyLock(catalog)) {
                    item.copy(propertyJson = BcItemPropertyJson.withoutLock(item.propertyJson))
                } else {
                    item
                }
            }
        }

        val fullyOwned = relations.ownership?.stageKind == BcOwnershipStage.FullyOwned
        val neckItem = desired[ITEM_NECK]
        if (fullyOwned) {
            if (neckItem == null || neckItem.name !in VALID_OWNED_COLLARS) {
                desired[ITEM_NECK] = BcCharacterItem(
                    group = ITEM_NECK,
                    name = SLAVE_COLLAR,
                )
            }
        } else if (neckItem?.name == SLAVE_COLLAR) {
            desired.remove(ITEM_NECK)
        }

        if (relations.loverships.isEmpty()) {
            val collar = desired[ITEM_NECK]
            if (collar?.name == SLAVE_COLLAR && collar.isLoveLeatherCollar()) {
                desired[ITEM_NECK] = collar.copy(
                    colorJson = DEFAULT_COLOR_JSON,
                    propertyJson = DEFAULT_SLAVE_COLLAR_PROPERTY_JSON,
                )
            }
        }

        val mutationsByGroup = linkedMapOf<String, BcRelationshipItemMutation>()
        (initial.keys + desired.keys).forEach { group ->
            val previous = initial[group]
            val next = desired[group]
            if (previous != next) {
                mutationsByGroup[group] = BcRelationshipItemMutation(
                    command = next.toUpdateCommand(character.memberNumber, group),
                )
            }
        }

        if (initial[ITEM_NECK]?.name == SLAVE_COLLAR && desired[ITEM_NECK] == null) {
            LINKED_NECK_GROUPS.forEach { group ->
                mutationsByGroup.putIfAbsent(
                    group,
                    BcRelationshipItemMutation(
                        command = desired[group].toUpdateCommand(character.memberNumber, group),
                    ),
                )
            }
        }

        return BcRelationshipItemPlan(
            mutations = mutationsByGroup.values.toList(),
            ownershipCleanupRequired = cleanupRequired,
        )
    }

    private fun BcCharacterItem?.toUpdateCommand(
        targetMemberNumber: Int,
        group: String,
    ): BcCharacterItemUpdateCommand = BcCharacterItemUpdateCommand(
        targetMemberNumber = targetMemberNumber,
        group = group,
        name = this?.name,
        colorJson = this?.colorJson,
        difficulty = this?.difficulty,
        propertyJson = this?.propertyJson,
        craftJson = this?.craftJson,
    )

    private fun BcCharacterItem.isOwnerOnly(catalog: BcRuleCatalog?): Boolean =
        catalog?.asset(group, name)?.ownerOnly == true ||
            (group to name) in FALLBACK_OWNER_ONLY_ASSETS

    private fun BcCharacterItem.hasOwnerOnlyLock(catalog: BcRuleCatalog?): Boolean {
        val lockName = propertyJson.readBcStringProperty("LockedBy") ?: return false
        return catalog?.asset(LOCK_ASSET_GROUP, lockName)?.ownerOnly == true ||
            catalog?.assets?.values?.any { asset ->
                asset.name.equals(lockName, ignoreCase = true) && asset.ownerOnly
            } == true || FALLBACK_OWNER_ONLY_LOCKS.any { it.equals(lockName, ignoreCase = true) }
    }

    private fun BcCharacterItem.isLoveLeatherCollar(): Boolean =
        propertyJson.readBcStringProperty("Type").equals(LOVE_LEATHER_COLLAR, ignoreCase = true) ||
            BcItemPropertyJson.readTypeRecord(propertyJson)[NO_ARCH_RECORD] == LOVE_LEATHER_COLLAR_INDEX

    private const val ITEM_NECK = "ItemNeck"
    private const val SLAVE_COLLAR = "SlaveCollar"
    private const val CLUB_SLAVE_COLLAR = "ClubSlaveCollar"
    private const val LOVE_LEATHER_COLLAR = "LoveLeatherCollar"
    private const val NO_ARCH_RECORD = "noarch"
    private const val LOVE_LEATHER_COLLAR_INDEX = 21
    private const val LOCK_ASSET_GROUP = "ItemMisc"
    private const val DEFAULT_COLOR_JSON = "\"Default\""
    private const val DEFAULT_SLAVE_COLLAR_PROPERTY_JSON = """{"TypeRecord":{"noarch":0}}"""
    private val VALID_OWNED_COLLARS = setOf(SLAVE_COLLAR, CLUB_SLAVE_COLLAR)
    private val LINKED_NECK_GROUPS = listOf("ItemNeckAccessories", "ItemNeckRestraints")
    private val FALLBACK_OWNER_ONLY_ASSETS = setOf(
        ITEM_NECK to SLAVE_COLLAR,
        "ItemPelvis" to "LoveChastityBelt",
    )
    private val FALLBACK_OWNER_ONLY_LOCKS = setOf("OwnerPadlock", "OwnerTimerPadlock")
}

internal data class BcRelationshipItemPlan(
    val mutations: List<BcRelationshipItemMutation>,
    val ownershipCleanupRequired: Boolean,
)

internal data class BcRelationshipItemMutation(
    val command: BcCharacterItemUpdateCommand,
)

private fun List<BcCharacterSnapshot>.selfCharacter(accountMemberNumber: Int): BcCharacterSnapshot? =
    firstOrNull(BcCharacterSnapshot::isSelf)
        ?: firstOrNull { it.memberNumber == accountMemberNumber }

private fun List<BcCharacterItem>.itemIn(group: String): BcCharacterItem? =
    firstOrNull { it.group == group }

private fun BcSessionSnapshot.isSameRelationshipRoom(
    started: BcSessionSnapshot,
    memberNumber: Int,
): Boolean =
    isVerifiedOnline && currentRoom != null &&
        roomPresenceEpoch == started.roomPresenceEpoch &&
        relationships.loadState == BcRelationshipLoadState.Loaded &&
        relationships.accountGeneration == started.relationships.accountGeneration &&
        relationships.loginSessionId == started.relationships.loginSessionId &&
        selfRuleLogSessionId == started.selfRuleLogSessionId &&
        (relationships.accountMemberNumber ?: account?.memberNumber) == memberNumber
