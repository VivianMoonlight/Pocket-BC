package dev.vivian.mobilebc.domain.character

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcEchoCompatibilityCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawOrderCommand
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import dev.vivian.mobilebc.core.model.BcInboundActivityCommand
import dev.vivian.mobilebc.core.model.BcInboundActivityResult
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcSelfAppearanceUpdateCommand
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRequest
import dev.vivian.mobilebc.domain.bcselfstate.BcSelfStateRules
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlanner
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlan
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeRequest
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeResult
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeSourceState
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEmissionRejectedException
import dev.vivian.mobilebc.domain.bcpermission.BcActionExecutionPlan
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.withItemPrivateState
import dev.vivian.mobilebc.domain.bcpermission.BcExecutableInteraction
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionContext
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionDecision
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReason
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRoomContext
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcInteractionPermissionEngine
import dev.vivian.mobilebc.domain.bcrules.BcActivityEffect
import dev.vivian.mobilebc.domain.bcrules.BcEchoActivityCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

private const val ECHO_CLOTHING_MOD_NAME = "服装拓展"
private const val ECHO_CLOTHING_COMPATIBILITY_VERSION = "1.129.2-mobile-bc.1"

class BcCharacterInteractionsController(
    private val gateway: InteractionGateway,
    private val sessionSnapshot: StateFlow<BcSessionSnapshot>,
    private val permissionEngine: DefaultBcInteractionPermissionEngine = DefaultBcInteractionPermissionEngine(),
    private val selfStateRules: BcSelfStateRules = BcSelfStateRules(),
    private val wardrobePlanner: BcWardrobePlanner = BcWardrobePlanner(),
) : BcCharacterInteractions {
    private val selfStateMutex = Mutex()

    override suspend fun saveWardrobe(request: BcWardrobeRequest): BcWardrobeResult = selfStateMutex.withLock {
        wardrobeAction {
            val snapshot = sessionSnapshot.value
            wardrobePlanner.rejection(request, snapshot)?.let { return@wardrobeAction BcWardrobeResult.Rejected(it) }
            val context = checkNotNull(wardrobePlanner.context(snapshot))
            val slot = context.wardrobeState?.slots?.firstOrNull { it.index == request.slotIndex }
                ?: return@wardrobeAction BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.InvalidSlot)
            if (slot.status == BcWardrobeSlotStatus.Unsupported) return@wardrobeAction BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.UnsupportedSlot)
            val appearance = wardrobePlanner.saveAppearance(context.self, request.rules)
            gateway.saveWardrobe(BcWardrobeSaveRequest(request.slotIndex, request.expectedRevision), appearance, snapshot.selfStateEmitExpectation(context.self)) {
                validateWardrobeEmission(request, context.sourceState())
            }
            BcWardrobeResult.Sent()
        }
    }

    override suspend fun renameWardrobe(request: BcWardrobeRequest, name: String): BcWardrobeResult = selfStateMutex.withLock {
        wardrobeAction {
            val snapshot = sessionSnapshot.value
            wardrobePlanner.rejection(request, snapshot)?.let { return@wardrobeAction BcWardrobeResult.Rejected(it) }
            if (!wardrobePlanner.validateName(name)) return@wardrobeAction BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.InvalidName)
            if (snapshot.wardrobeState?.namesAvailable != true) return@wardrobeAction BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.NamesUnavailable)
            val context = checkNotNull(wardrobePlanner.context(snapshot))
            gateway.renameWardrobe(BcWardrobeRenameRequest(request.slotIndex, request.expectedRevision, name), snapshot.selfStateEmitExpectation(context.self)) {
                validateWardrobeEmission(request, context.sourceState())
            }
            BcWardrobeResult.Sent()
        }
    }

    override suspend fun applyWardrobe(request: BcWardrobeRequest, expectedPlan: BcWardrobePlan): BcWardrobeResult = selfStateMutex.withLock {
        wardrobeAction {
            val snapshot = sessionSnapshot.value
            wardrobePlanner.rejection(request, snapshot)?.let { return@wardrobeAction BcWardrobeResult.Rejected(it) }
            val plan = wardrobePlanner.plan(request, snapshot)
            plan.unavailableReason?.let { return@wardrobeAction BcWardrobeResult.Rejected(it) }
            if (plan != expectedPlan || plan.sourceState == null) return@wardrobeAction BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.CharacterStateChanged)
            if (!plan.hasChanges) return@wardrobeAction BcWardrobeResult.Rejected(BcWardrobeUnavailableReason.NoChanges)
            val context = checkNotNull(wardrobePlanner.context(snapshot))
            gateway.restoreSelfAppearance(
                BcSelfAppearanceUpdateCommand(plan.appearance, context.self.activePose, request.expectedRevision, request.slotIndex),
                snapshot.selfStateEmitExpectation(context.self),
            ) {
                validateWardrobeEmission(request, checkNotNull(plan.sourceState), plan)
            }
            BcWardrobeResult.Sent(plan)
        }
    }

    private suspend fun wardrobeAction(action: suspend () -> BcWardrobeResult): BcWardrobeResult = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: BcWardrobeEmissionRejectedException) {
        BcWardrobeResult.Rejected(rejected.reason)
    } catch (throwable: Throwable) {
        BcWardrobeResult.Failed(throwable.message ?: "Could not update your wardrobe.")
    }

    private fun validateWardrobeEmission(request: BcWardrobeRequest, expectedSource: BcWardrobeSourceState, expectedPlan: BcWardrobePlan? = null) {
        val snapshot = sessionSnapshot.value
        wardrobePlanner.rejection(request, snapshot)?.let { throw BcWardrobeEmissionRejectedException(it) }
        if (wardrobePlanner.context(snapshot)?.sourceState() != expectedSource ||
            expectedPlan != null && wardrobePlanner.plan(request, snapshot) != expectedPlan) {
            throw BcWardrobeEmissionRejectedException(BcWardrobeUnavailableReason.CharacterStateChanged)
        }
    }

    override suspend fun updateSelfState(request: BcSelfStateRequest): BcSelfStateResult = selfStateMutex.withLock {
        selfStateRules.rejection(request, sessionSnapshot.value)?.let { return@withLock BcSelfStateResult.Rejected(it) }
        try {
            when (request.change) {
                is BcSelfStateChange.Pose,
                BcSelfStateChange.ResetPose,
                -> {
                    val snapshot = sessionSnapshot.value
                    selfStateRules.rejection(request, snapshot)?.let { return@withLock BcSelfStateResult.Rejected(it) }
                    val self = snapshot.roomCharacters.first { it.isSelf && it.memberNumber == request.targetMemberNumber }
                    gateway.updatePose(
                        BcPoseUpdateCommand(selfStateRules.poseUpdate(self, request.rules, request.change)),
                        snapshot.selfStateEmitExpectation(self),
                    ) {
                        validateSelfStateEmission(request, self)
                    }
                }
                is BcSelfStateChange.Expression,
                BcSelfStateChange.ResetExpressions,
                -> {
                    val startedSelf = sessionSnapshot.value.roomCharacters.first { it.isSelf && it.memberNumber == request.targetMemberNumber }
                    val groups = selfStateRules.expressionUpdates(startedSelf, request.rules, request.change).map { it.group }
                    groups.forEach { group ->
                        val snapshot = sessionSnapshot.value
                        selfStateRules.rejection(request, snapshot)?.let { return@withLock BcSelfStateResult.Rejected(it) }
                        val self = snapshot.roomCharacters.first { it.isSelf && it.memberNumber == request.targetMemberNumber }
                        val updates = selfStateRules.expressionUpdates(self, request.rules, request.change)
                        val command = updates.firstOrNull { it.group == group }
                            ?: return@withLock BcSelfStateResult.Failed("The expression group changed before the update finished.")
                        val editedGroups = updates.map { it.group }.toSet()
                        gateway.updateExpression(command, snapshot.selfStateEmitExpectation(self, editedGroups)) {
                            validateSelfStateEmission(request, self, editedGroups)
                        }
                    }
                }
            }
            BcSelfStateResult.Sent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: SelfStateEmissionRejected) {
            BcSelfStateResult.Rejected(rejected.reason)
        } catch (throwable: Throwable) {
            BcSelfStateResult.Failed(throwable.message ?: "Could not update your pose or expression.")
        }
    }

    private fun validateSelfStateEmission(
        request: BcSelfStateRequest,
        expectedSelf: BcCharacterSnapshot,
        editedExpressionGroups: Set<String> = emptySet(),
    ) {
        val snapshot = sessionSnapshot.value
        selfStateRules.rejection(request, snapshot)?.let { throw SelfStateEmissionRejected(it) }
        val self = snapshot.roomCharacters.first { it.isSelf && it.memberNumber == request.targetMemberNumber }
        if (self.appearance.forEmissionFence(editedExpressionGroups) != expectedSelf.appearance.forEmissionFence(editedExpressionGroups) ||
            editedExpressionGroups.isEmpty() && self.activePose != expectedSelf.activePose
        ) {
            throw SelfStateEmissionRejected(BcSelfStateUnavailableReason.CharacterStateChanged)
        }
    }

    private fun BcSessionSnapshot.selfStateEmitExpectation(
        self: BcCharacterSnapshot,
        editedExpressionGroups: Set<String> = emptySet(),
    ): BcSelfStateEmitExpectation = BcSelfStateEmitExpectation(
        character = self,
        ownerPresent = self.ownership?.memberNumber?.let { owner -> roomCharacters.any { it.memberNumber == owner } } == true,
        editedExpressionGroups = editedExpressionGroups,
        selfRuleLogSessionId = selfRuleLogSessionId,
    )

    private fun List<BcCharacterItem>.forEmissionFence(editedExpressionGroups: Set<String>): List<BcCharacterItem> = map { item ->
        if (item.group in editedExpressionGroups) {
            // Our earlier paired/reset expression echoes may arrive while the next packet waits.
            item.copy(propertyJson = BcItemPropertyJson.mergeForModification(item.propertyJson, "{\"Expression\":null}"))
        } else item
    }.sortedBy(BcCharacterItem::group)

    private class SelfStateEmissionRejected(val reason: BcSelfStateUnavailableReason) : IllegalStateException(reason.name)

    override suspend fun sendInteraction(
        request: BcPermissionRequest,
        activityClientMsgId: String?,
    ): BcCharacterInteractionResult {
        val startedSnapshot = sessionSnapshot.value
        validateInteractionSession(request, startedSnapshot)?.let { return it }
        val currentRequest = request.inSnapshot(startedSnapshot)
        return when (val decision = permissionEngine.evaluate(currentRequest)) {
            is BcPermissionDecision.Allowed -> sendPrecheckedExecutable(
                executable = decision.executable,
                activityClientMsgId = activityClientMsgId,
                request = currentRequest,
                startedSnapshot = startedSnapshot,
            )
            is BcPermissionDecision.Denied -> BcCharacterInteractionResult.Rejected(
                decision.reasons.toInteractionMessage(),
            )
            is BcPermissionDecision.NeedsServerCheck -> sendAfterServerAllowItemCheck(
                request = currentRequest,
                activityClientMsgId = activityClientMsgId,
                startedSnapshot = startedSnapshot,
            )
        }
    }

    override suspend fun handleInboundActivity(command: BcInboundActivityCommand): BcInboundActivityResult {
        val snapshot = sessionSnapshot.value
        val room = snapshot.currentRoom ?: return BcInboundActivityResult.Ignored
        val target = snapshot.roomCharacters.firstOrNull(BcCharacterSnapshot::isSelf)
            ?: return BcInboundActivityResult.Ignored
        if (command.targetMemberNumber != target.memberNumber || command.sourceMemberNumber == target.memberNumber) {
            return BcInboundActivityResult.Ignored
        }
        val actor = snapshot.roomCharacters.firstOrNull { it.memberNumber == command.sourceMemberNumber }
            ?: return BcInboundActivityResult.Ignored
        val rules = BcEchoActivityCatalog.mergeInto(BcRuleCatalog.Empty)
        val activity = rules.activity(command.activityName) ?: return BcInboundActivityResult.Ignored
        if (activity.targetGroups.none { it.equals(command.targetGroup, ignoreCase = true) }) {
            return BcInboundActivityResult.Rejected(
                "${command.activityName} is not allowed on ${command.targetGroup}.",
            )
        }
        val effect = activity.effects.filterIsInstance<BcActivityEffect.EchoPairedItem>().singleOrNull()
            ?: return BcInboundActivityResult.Ignored
        val context = BcPermissionContext(
            actor = actor,
            target = target,
            room = BcPermissionRoomContext(
                roomName = room.name.value,
                memberNumbers = snapshot.roomCharacters.map(BcCharacterSnapshot::memberNumber).toSet(),
                blockCategories = room.blockCategories,
            ),
            rules = rules,
        )
        if (effect.targetRequiresAllowItem) {
            val permissionReasons = permissionEngine.localAllowItemBlockingReasons(context)
            if (permissionReasons.isNotEmpty()) {
                return BcInboundActivityResult.Rejected(permissionReasons.toInteractionMessage())
            }
        }
        return applyInboundEchoPairedItem(
            context = context,
            startedSnapshot = snapshot,
            effect = effect,
        )
    }

    private suspend fun applyInboundEchoPairedItem(
        context: BcPermissionContext,
        startedSnapshot: BcSessionSnapshot,
        effect: BcActivityEffect.EchoPairedItem,
    ): BcInboundActivityResult {
        return try {
            currentInboundContext(context, startedSnapshot)
            gateway.publishEchoCompatibility(
                BcEchoCompatibilityCommand(
                    modName = ECHO_CLOTHING_MOD_NAME,
                    version = ECHO_CLOTHING_COMPATIBILITY_VERSION,
                ),
            )
            val currentContext = currentInboundContext(context, startedSnapshot)
            if (effect.targetRequiresAllowItem) {
                val reasons = permissionEngine.localAllowItemBlockingReasons(currentContext)
                if (reasons.isNotEmpty()) return BcInboundActivityResult.Rejected(reasons.toInteractionMessage())
            }
            val hasTargetItem = currentContext.target.appearance.any { item ->
                item.group.equals(effect.targetGroup, ignoreCase = true) &&
                    item.name.equals(effect.targetName, ignoreCase = true)
            }
            if (!hasTargetItem) {
                gateway.updateCharacterItem(
                    BcCharacterItemUpdateCommand(
                        targetMemberNumber = currentContext.target.memberNumber,
                        group = effect.targetGroup,
                        name = effect.targetName,
                    ),
                )
            }
            val drawOrderContext = currentInboundContext(context, startedSnapshot)
            if (effect.targetRequiresAllowItem) {
                val reasons = permissionEngine.localAllowItemBlockingReasons(drawOrderContext)
                check(reasons.isEmpty()) { reasons.toInteractionMessage() }
            }
            gateway.publishEchoDrawOrder(
                BcEchoDrawOrderCommand(
                    otherMemberNumber = context.actor.memberNumber,
                    relation = effect.targetRelation,
                    associatedAssetGroup = effect.targetGroup,
                    associatedAssetName = effect.targetName,
                    leash = effect.targetLeash,
                ),
            )
            BcInboundActivityResult.Applied
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            BcInboundActivityResult.Failed(throwable.message ?: "Inbound Echo activity failed.")
        }
    }

    private fun currentInboundContext(
        context: BcPermissionContext,
        startedSnapshot: BcSessionSnapshot,
    ): BcPermissionContext {
        val snapshot = sessionSnapshot.value
        check(
            snapshot.isVerifiedOnline && snapshot.currentRoom != null &&
                snapshot.account?.memberNumber == context.target.memberNumber &&
                snapshot.account?.memberNumber == startedSnapshot.account?.memberNumber &&
                snapshot.relationships.accountGeneration == startedSnapshot.relationships.accountGeneration &&
                snapshot.roomPresenceEpoch == startedSnapshot.roomPresenceEpoch,
        ) { "The active session or room changed before the inbound interaction finished." }
        val actor = snapshot.roomCharacters.firstOrNull { it.memberNumber == context.actor.memberNumber }
        val target = snapshot.roomCharacters.firstOrNull { it.isSelf && it.memberNumber == context.target.memberNumber }
        check(actor != null && target != null) { "An inbound interaction participant left the room." }
        val room = checkNotNull(snapshot.currentRoom)
        return context.copy(
            actor = actor,
            target = target,
            room = BcPermissionRoomContext(
                roomName = room.name.value,
                memberNumbers = snapshot.roomCharacters.map(BcCharacterSnapshot::memberNumber).toSet(),
                blockCategories = room.blockCategories,
            ),
        )
    }

    private suspend fun sendAfterServerAllowItemCheck(
        request: BcPermissionRequest,
        activityClientMsgId: String?,
        startedSnapshot: BcSessionSnapshot,
    ): BcCharacterInteractionResult {
        val allowed = try {
            gateway.queryAllowItem(request.candidate.targetMemberNumber)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            return BcCharacterInteractionResult.Failed(
                "Could not verify item permissions: ${throwable.message ?: "unknown error"}"
            )
        }
        val snapshot = sessionSnapshot.value
        validateInteractionSession(request, snapshot, startedSnapshot)?.let { return it }
        val currentRequest = request.inSnapshot(snapshot)
        val localDecision = permissionEngine.evaluate(currentRequest)
        if (localDecision is BcPermissionDecision.Denied) {
            return BcCharacterInteractionResult.Rejected(localDecision.reasons.toInteractionMessage())
        }
        val recheckedRequest = currentRequest.copy(
            context = currentRequest.context.copy(serverAllowItem = allowed),
        )
        return when (val decision = permissionEngine.evaluate(recheckedRequest)) {
            is BcPermissionDecision.Allowed -> sendPrecheckedExecutable(
                executable = decision.executable,
                activityClientMsgId = activityClientMsgId,
                request = recheckedRequest,
                startedSnapshot = startedSnapshot,
            )
            is BcPermissionDecision.Denied -> BcCharacterInteractionResult.Rejected(
                decision.reasons.toInteractionMessage(),
            )
            is BcPermissionDecision.NeedsServerCheck -> BcCharacterInteractionResult.Rejected(
                decision.reasons.toInteractionMessage(),
            )
        }
    }

    private suspend fun sendPrecheckedExecutable(
        executable: BcExecutableInteraction,
        activityClientMsgId: String?,
        request: BcPermissionRequest,
        startedSnapshot: BcSessionSnapshot,
    ): BcCharacterInteractionResult =
        when (executable) {
            is BcExecutableInteraction.Activity -> sendActivityCommand(
                executable = executable,
                activityClientMsgId = activityClientMsgId,
                request = request,
                startedSnapshot = startedSnapshot,
            )
            is BcExecutableInteraction.ItemUpdate -> updateCharacterItemAfterPermissionCheck(
                executable.command, listOf(request.context.actor, request.context.target),
            ) {
                val snapshot = sessionSnapshot.value
                validateInteractionSession(request, snapshot, startedSnapshot)?.let { error(it.reason) }
                val currentRequest = request.inSnapshot(snapshot)
                check(currentRequest.context.actor == request.context.actor && currentRequest.context.target == request.context.target) {
                    "The character or item changed. Select the operation again."
                }
                val decision = permissionEngine.evaluate(currentRequest.copy(
                    context = currentRequest.context.copy(serverAllowItem = request.context.serverAllowItem),
                ))
                check(decision is BcPermissionDecision.Allowed && decision.executable == executable) {
                    "Item permissions changed before this update was sent. Select the operation again."
                }
            }
        }

    private suspend fun sendActivityCommand(
        executable: BcExecutableInteraction.Activity,
        activityClientMsgId: String?,
        request: BcPermissionRequest,
        startedSnapshot: BcSessionSnapshot,
    ): BcCharacterInteractionResult {
        val clientMsgId = activityClientMsgId ?: UUID.randomUUID().toString()
        val normalizedCommand = executable.command
            .withClientMsgId(clientMsgId)
            .normalized()
        val plan = BcActionExecutionPlan(
            activity = normalizedCommand,
            effects = executable.effects,
        )
        validateActivityCommand(normalizedCommand)?.let { return it }
        validateEffects(plan)?.let { return it }
        return try {
            prepareEffects(plan)
            val snapshot = sessionSnapshot.value
            validateInteractionSession(request, snapshot, startedSnapshot)?.let { return it }
            val currentRequest = request.inSnapshot(snapshot)
            val currentExecutable = when (val decision = permissionEngine.evaluate(currentRequest)) {
                is BcPermissionDecision.Allowed -> decision.executable as BcExecutableInteraction.Activity
                is BcPermissionDecision.Denied -> return BcCharacterInteractionResult.Rejected(
                    decision.reasons.toInteractionMessage(),
                )
                is BcPermissionDecision.NeedsServerCheck -> return BcCharacterInteractionResult.Rejected(
                    decision.reasons.toInteractionMessage(),
                )
            }
            val currentCommand = currentExecutable.command.withClientMsgId(clientMsgId).normalized()
            val currentPlan = BcActionExecutionPlan(currentCommand, currentExecutable.effects)
            validateEffects(currentPlan)?.let { return it }
            gateway.sendActivity(currentCommand)
            executeEffects(currentPlan, currentRequest, startedSnapshot)
            BcCharacterInteractionResult.Sent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            val reason = throwable.message ?: "Character interaction failed."
            BcCharacterInteractionResult.Failed(reason)
        }
    }

    private fun validateEffects(plan: BcActionExecutionPlan): BcCharacterInteractionResult.Rejected? {
        if (plan.effects.isEmpty()) return null
        val actorMemberNumber = currentActorMemberNumber()
            ?: return BcCharacterInteractionResult.Rejected("Cannot execute action effects before the local character is known.")
        val selfOnlyEffects = plan.effects.filter {
            it is BcActivityEffect.Pose || it is BcActivityEffect.Expression
        }
        if (selfOnlyEffects.isNotEmpty() && plan.activity.targetMemberNumber != actorMemberNumber) {
            return BcCharacterInteractionResult.Rejected(
                "This action effect can only be executed on your own character.",
            )
        }
        val pairedEffects = plan.effects.filterIsInstance<BcActivityEffect.EchoPairedItem>()
        if (pairedEffects.isNotEmpty() && plan.activity.targetMemberNumber == actorMemberNumber) {
            return BcCharacterInteractionResult.Rejected(
                "This paired Echo action requires another character.",
            )
        }
        plan.effects.filterIsInstance<BcActivityEffect.Expression>().firstOrNull { it.group.isBlank() }?.let {
            return BcCharacterInteractionResult.Rejected("Expression group is required.")
        }
        pairedEffects.firstOrNull { it.group.isBlank() || it.name.isBlank() }?.let {
            return BcCharacterInteractionResult.Rejected("Echo paired item group and name are required.")
        }
        return null
    }

    private suspend fun prepareEffects(plan: BcActionExecutionPlan) {
        if (plan.effects.any { it is BcActivityEffect.EchoPairedItem }) {
            gateway.publishEchoCompatibility(
                BcEchoCompatibilityCommand(
                    modName = ECHO_CLOTHING_MOD_NAME,
                    version = ECHO_CLOTHING_COMPATIBILITY_VERSION,
                ),
            )
        }
    }

    private suspend fun executeEffects(
        plan: BcActionExecutionPlan,
        request: BcPermissionRequest,
        startedSnapshot: BcSessionSnapshot,
    ) {
        val actorMemberNumber = request.context.actor.memberNumber
        plan.effects.forEach { effect ->
            requireCurrentInteractionSession(request, startedSnapshot)
            when (effect) {
                is BcActivityEffect.Pose -> gateway.updatePose(BcPoseUpdateCommand(effect.poses))
                is BcActivityEffect.Expression -> gateway.updateExpression(
                    BcExpressionUpdateCommand(group = effect.group, name = effect.name),
                )
                is BcActivityEffect.EchoPairedItem -> {
                    if (!currentActorHasItem(effect.group, effect.name)) {
                        gateway.updateCharacterItem(
                            BcCharacterItemUpdateCommand(
                                targetMemberNumber = actorMemberNumber,
                                group = effect.group,
                                name = effect.name,
                            ),
                        )
                    }
                    requireCurrentInteractionSession(request, startedSnapshot)
                    gateway.publishEchoDrawOrder(
                        BcEchoDrawOrderCommand(
                            otherMemberNumber = plan.activity.targetMemberNumber,
                            relation = effect.actorRelation,
                            associatedAssetGroup = effect.group,
                            associatedAssetName = effect.name,
                            leash = effect.actorLeash,
                        ),
                    )
                }
            }
        }
    }

    private fun currentActorMemberNumber(): Int? = sessionSnapshot.value.let { snapshot ->
        snapshot.roomCharacters
            .takeIf { snapshot.currentRoom != null }
            ?.firstOrNull(BcCharacterSnapshot::isSelf)
            ?.memberNumber
    }

    private fun currentActorHasItem(group: String, name: String): Boolean =
        sessionSnapshot.value.roomCharacters
            .firstOrNull(BcCharacterSnapshot::isSelf)
            ?.appearance
            ?.any { item ->
                item.group.equals(group, ignoreCase = true) && item.name.equals(name, ignoreCase = true)
            } == true

    private fun BcPermissionRequest.inSnapshot(snapshot: BcSessionSnapshot): BcPermissionRequest {
        val room = checkNotNull(snapshot.currentRoom)
        return copy(
            context = context.copy(
                actor = snapshot.roomCharacters.first(BcCharacterSnapshot::isSelf),
                target = snapshot.roomCharacters.first { it.memberNumber == candidate.targetMemberNumber },
                room = BcPermissionRoomContext(
                    roomName = room.name.value,
                    memberNumbers = snapshot.roomCharacters.map(BcCharacterSnapshot::memberNumber).toSet(),
                    blockCategories = room.blockCategories,
                ),
                serverAllowItem = null,
            ).withItemPrivateState(snapshot),
        )
    }

    private fun validateInteractionSession(
        request: BcPermissionRequest,
        snapshot: BcSessionSnapshot,
        startedSnapshot: BcSessionSnapshot? = null,
    ): BcCharacterInteractionResult.Rejected? {
        val room = snapshot.currentRoom
            ?: return BcCharacterInteractionResult.Rejected("Join a room before using character interactions.")
        if (!snapshot.isVerifiedOnline) {
            return BcCharacterInteractionResult.Rejected("Character interactions require an active authenticated session.")
        }
        val actor = snapshot.roomCharacters.firstOrNull(BcCharacterSnapshot::isSelf)
        if (
            actor == null || actor.memberNumber != snapshot.account?.memberNumber ||
            actor.memberNumber != request.context.actor.memberNumber ||
            startedSnapshot != null && (
                snapshot.account?.memberNumber != startedSnapshot.account?.memberNumber ||
                    snapshot.relationships.accountGeneration != startedSnapshot.relationships.accountGeneration
                )
        ) {
            return BcCharacterInteractionResult.Rejected("The active account changed. Select the interaction again.")
        }
        if (
            request.context.room.roomName?.let { it != room.name.value } == true ||
            startedSnapshot != null && snapshot.roomPresenceEpoch != startedSnapshot.roomPresenceEpoch
        ) {
            return BcCharacterInteractionResult.Rejected("The current room changed. Select the interaction again.")
        }
        if (snapshot.roomCharacters.none { it.memberNumber == request.candidate.targetMemberNumber }) {
            return BcCharacterInteractionResult.Rejected("Target character is not in the current room.")
        }
        if (request.candidate !is BcInteractionCandidate.Activity) {
            val target = snapshot.roomCharacters.first { it.memberNumber == request.candidate.targetMemberNumber }
            fun BcCharacterSnapshot.selectedItem() = appearance.firstOrNull {
                it.group.equals(request.candidate.group, ignoreCase = true)
            }
            if (target.selectedItem() != request.context.target.selectedItem()) {
                return BcCharacterInteractionResult.Rejected("The selected item changed. Select the operation again.")
            }
        }
        return null
    }

    private fun requireCurrentInteractionSession(request: BcPermissionRequest, startedSnapshot: BcSessionSnapshot) {
        validateInteractionSession(request, sessionSnapshot.value, startedSnapshot)?.let { error(it.reason) }
    }

    private suspend fun updateCharacterItemAfterPermissionCheck(
        command: BcCharacterItemUpdateCommand,
        expectedCharacters: List<BcCharacterSnapshot>? = null,
        validateBeforeEmit: () -> Unit = {},
    ): BcCharacterInteractionResult {
        val normalizedCommand = command.normalized()
        validateItemCommand(normalizedCommand)?.let { return it }
        return try {
            gateway.updateCharacterItem(normalizedCommand, expectedCharacters, validateBeforeEmit)
            BcCharacterInteractionResult.Sent
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            BcCharacterInteractionResult.Failed(throwable.message ?: "Character interaction failed.")
        }
    }

    private fun validateActivityCommand(command: BcActivityCommand): BcCharacterInteractionResult.Rejected? {
        if (command.targetGroup.isBlank()) {
            return BcCharacterInteractionResult.Rejected("Activity target group is required.")
        }
        if (command.activityName.isBlank()) {
            return BcCharacterInteractionResult.Rejected("Activity name is required.")
        }
        return validateTargetInCurrentRoom(command.targetMemberNumber)
    }

    private fun validateItemCommand(command: BcCharacterItemUpdateCommand): BcCharacterInteractionResult.Rejected? {
        if (command.group.isBlank()) {
            return BcCharacterInteractionResult.Rejected("Item group is required.")
        }
        return validateTargetInCurrentRoom(command.targetMemberNumber)
    }

    private fun validateTargetInCurrentRoom(memberNumber: Int): BcCharacterInteractionResult.Rejected? {
        val snapshot = sessionSnapshot.value
        if (snapshot.currentRoom == null) {
            return BcCharacterInteractionResult.Rejected("Join a room before using character interactions.")
        }
        if (snapshot.roomCharacters.none { it.memberNumber == memberNumber }) {
            return BcCharacterInteractionResult.Rejected("Target character is not in the current room.")
        }
        return null
    }

    private fun BcActivityCommand.withClientMsgId(clientMsgId: String?): BcActivityCommand {
        val normalizedClientMsgId = clientMsgId?.trim().emptyToNull()
        return if (normalizedClientMsgId == null) this else copy(clientMsgId = normalizedClientMsgId)
    }

    private fun BcActivityCommand.normalized(): BcActivityCommand =
        copy(
            targetGroup = targetGroup.trim(),
            activityName = activityName.trim(),
            activityAssetGroup = activityAssetGroup?.trim().emptyToNull(),
            activityAssetName = activityAssetName?.trim().emptyToNull(),
            fallbackText = fallbackText?.trim().emptyToNull(),
            clientMsgId = clientMsgId?.trim().emptyToNull(),
        )

    private fun BcCharacterItemUpdateCommand.normalized(): BcCharacterItemUpdateCommand =
        copy(
            group = group.trim(),
            name = name?.trim().emptyToNull(),
        )

    private fun List<BcPermissionReason>.toInteractionMessage(): String =
        joinToString(separator = "\n") { it.message }.ifBlank { "Interaction is not allowed." }

    private fun String?.emptyToNull(): String? =
        if (this == null || isBlank()) null else this
}
