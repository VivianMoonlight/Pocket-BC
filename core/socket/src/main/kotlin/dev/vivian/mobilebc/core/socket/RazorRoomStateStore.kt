package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.common.ItemBundle
import bot.inker.bc.razor.protocol.account.AccountOwnershipResponse
import bot.inker.bc.razor.state.CharacterState
import bot.inker.bc.razor.state.RoomState
import com.google.gson.Gson
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomAdministration
import dev.vivian.mobilebc.core.model.BcRoomEditBase
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomRemovalReason
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.RoomRole
import dev.vivian.mobilebc.core.protocol.BcInboundReceiveContext
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import java.util.Collections
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

private fun JsonElement?.exactStringOrNull(): String? =
    this?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

/** Owns the mutable room snapshot and translates room sync packets into stable session events. */
internal class RazorRoomStateStore(
    private val gson: Gson,
    private val emit: (BcSessionGatewayEvent) -> Unit,
    private val queueAccountAppearance: (List<ItemBundle>) -> Unit,
    private val diagnostic: (String) -> Unit = {},
) {
    @Volatile
    var selfMemberNumber: Int? = null
        set(value) = synchronized(this) {
            if (field != value) {
                field = value
                roomPresenceEpoch++
                publishReceiveContext()
            }
        }
    private var current: RoomState? = null
    private var rawSettings: RazorRoomRawSettings? = null
    private var currentRoomSpace: String? = null
    private var currentRoomGame: String? = null
    private var currentAdministrators: List<Int>? = null
    private var currentBannedMembers: List<Int>? = null
    private var nextMemberPresenceEpoch = 0L
    private val memberPresenceEpochs = mutableMapOf<Int, Long>()
    private var roomPresenceEpoch = 0L
    private var roomJoinPending = false
    private var roomJoinPreviousPresenceEpoch: Long? = null
    private var roomJoinPreviousRoomName: BcRoomName? = null
    private var selfRuleRevision = 0L
    private var selfRuleLogInvalidated = false
    private var receiveContext = BcInboundReceiveContext(
        ownerMemberNumber = null,
        ownerDisplayName = null,
        roomName = null,
        roomMembers = emptyMap(),
        roomPresenceEpoch = 0L,
    )
    private val pendingCharacterUpdates = linkedMapOf<Int, ArrayDeque<PendingCharacterUpdate>>()
    private val memberProjectionCache = mutableMapOf<Int, CachedMemberProjection>()
    private val characterProjectionCache = mutableMapOf<Int, CachedCharacterProjection>()

    @Synchronized
    fun captureReceiveContext(): BcInboundReceiveContext = receiveContext

    @Synchronized
    fun captureCharacterSnapshots(): List<BcCharacterSnapshot> = current?.let(::projectCharacters).orEmpty()

    /** BC does not echo a single-item update to its sender; mirror it only after emit succeeds. */
    @Synchronized
    fun applyEmittedItemUpdate(payload: JsonObject) {
        val source = checkNotNull(selfMemberNumber)
        val item = gson.fromJson(payload, bot.inker.bc.razor.protocol.sync.ItemSyncData::class.java)
        handle(RazorWireEvent.ItemSync(bot.inker.bc.razor.protocol.sync.ItemSyncMessage(source, item)))
    }

    @Synchronized
    fun applyEmittedPoseUpdate(poses: List<String>) {
        val source = checkNotNull(selfMemberNumber)
        handle(RazorWireEvent.PoseSync(bot.inker.bc.razor.protocol.sync.PoseSyncMessage(source, poses)))
    }

    @Synchronized
    fun applyEmittedExpressionUpdate(group: String, name: String?) {
        val source = checkNotNull(selfMemberNumber)
        handle(RazorWireEvent.ExpressionSync(bot.inker.bc.razor.protocol.sync.ExpressionSyncMessage(source, name, group)))
    }

    @Synchronized
    fun captureRoomProjection(): BcRoom? = current?.let(::projectRoom)

    @Synchronized
    fun captureMemberProjection(): List<BcMember> = current?.let(::projectMembers).orEmpty()

    @Synchronized
    fun captureRawSettings(): JsonObject? = rawSettings?.evidenceCopy()

    @Synchronized
    fun <T> withRoomCreation(accountMemberNumber: Int, expectedEpoch: Long, block: () -> T): T {
        rejectRoomMutationIf(selfMemberNumber != accountMemberNumber, BcRoomMutationRejectionReason.AccountUnavailable)
        rejectRoomMutationIf(current != null, BcRoomMutationRejectionReason.AlreadyInRoom)
        rejectRoomMutationIf(roomJoinPending || roomPresenceEpoch != expectedEpoch, BcRoomMutationRejectionReason.RoomChanged)
        return block()
    }

    @Synchronized
    fun markRoomCreationPending(): Long {
        roomJoinPending = true
        roomJoinPreviousPresenceEpoch = null
        roomJoinPreviousRoomName = null
        roomPresenceEpoch++
        publishReceiveContext()
        return roomPresenceEpoch
    }

    @Synchronized
    fun <T> withRoomUpdate(command: BcRoomUpdateCommand, accountMemberNumber: Int, block: (JsonObject) -> T): T {
        rejectRoomMutationIf(selfMemberNumber != accountMemberNumber, BcRoomMutationRejectionReason.AccountUnavailable)
        rejectRoomMutationIf(current == null, BcRoomMutationRejectionReason.RoomUnavailable)
        rejectRoomMutationIf(roomJoinPending || roomPresenceEpoch != command.base.roomPresenceEpoch ||
            current?.name != command.base.roomName.value, BcRoomMutationRejectionReason.RoomChanged)
        val raw = rawSettings ?: throw BcRoomMutationRejectedException(
            BcRoomMutationRejection(BcRoomMutationRejectionReason.SettingsUnavailable),
        )
        val payload = try {
            raw.buildUpdate(command.base.settings.toRoomBasicJson(), command.editedRoomFields(), accountMemberNumber)
        } catch (failure: RazorRoomRawSettingsException) {
            throw failure.toRoomMutationRejection()
        }
        return block(payload)
    }

    @Synchronized
    fun <T> withRoomMemberAdministration(command: BcRoomMemberAdminCommand, accountMemberNumber: Int, block: () -> T): T {
        rejectRoomMemberAdministrationIf(selfMemberNumber != accountMemberNumber, BcRoomMemberAdminRejectionReason.AccountUnavailable)
        val room = current
        rejectRoomMemberAdministrationIf(room == null, BcRoomMemberAdminRejectionReason.RoomUnavailable)
        rejectRoomMemberAdministrationIf(roomJoinPending || roomPresenceEpoch != command.roomPresenceEpoch ||
            room?.name != command.roomName.value, BcRoomMemberAdminRejectionReason.RoomChanged)
        val administrators = currentAdministrators
        rejectRoomMemberAdministrationIf(administrators == null, BcRoomMemberAdminRejectionReason.SettingsUnavailable)
        rejectRoomMemberAdministrationIf(accountMemberNumber !in administrators.orEmpty(), BcRoomMemberAdminRejectionReason.NotAdministrator)
        rejectRoomMemberAdministrationIf(command.targetMemberNumber == accountMemberNumber, BcRoomMemberAdminRejectionReason.SelfTarget)
        rejectRoomMemberAdministrationIf(command.targetMemberNumber <= 0 ||
            room?.characters?.none { it.memberNumber == command.targetMemberNumber } != false,
            BcRoomMemberAdminRejectionReason.TargetUnavailable)
        rejectRoomMemberAdministrationIf(memberPresenceEpochs[command.targetMemberNumber] != command.targetPresenceEpoch,
            BcRoomMemberAdminRejectionReason.TargetChanged)
        val targetIsAdministrator = command.targetMemberNumber in administrators.orEmpty()
        rejectRoomMemberAdministrationIf(targetIsAdministrator != command.targetIsAdministrator ||
            (command.action == BcRoomMemberAdminAction.Promote && targetIsAdministrator) ||
            (command.action == BcRoomMemberAdminAction.Demote && !targetIsAdministrator),
            BcRoomMemberAdminRejectionReason.TargetRoleChanged)
        return block()
    }

    @Synchronized
    fun removeCurrentRoom(context: BcInboundReceiveContext, reason: BcRoomRemovalReason, loginSessionId: String): Boolean {
        if (roomJoinPending || !isCurrentRoomContext(context)) return false
        val name = context.roomName ?: return false
        val accountMemberNumber = context.ownerMemberNumber ?: return false
        clearRoom()
        emit(BcSessionGatewayEvent.RoomRemoved(BcRoomName(name), reason, accountMemberNumber, loginSessionId,
            context.roomPresenceEpoch, roomPresenceEpoch))
        emit(BcSessionGatewayEvent.RoomLeft)
        return true
    }

    @Synchronized
    fun applySelfOwnership(response: AccountOwnershipResponse) {
        val memberNumber = selfMemberNumber ?: return
        if (response.clearOwnership != true && response.owner == null && response.ownership == null) return
        update(transform = { room ->
            room.withCharacterUpdated(memberNumber) { character ->
                if (response.clearOwnership == true) character.copy(owner = null, ownership = null)
                else character.copy(owner = response.owner ?: character.owner, ownership = response.ownership ?: character.ownership)
            }
        })
    }

    @Synchronized
    fun <T> withCurrentRoomCharacters(
        context: BcInboundReceiveContext,
        block: (List<BcCharacterSnapshot>) -> T,
    ): T? {
        if (!isCurrentRoomContext(context)) return null
        return block(current?.let(::projectCharacters).orEmpty())
    }

    @Synchronized
    fun currentOwnerMemberNumber(context: BcInboundReceiveContext): Int? =
        current?.characters
            ?.firstOrNull { it.memberNumber == context.ownerMemberNumber }
            ?.ownership?.memberNumber
            .takeIf { isCurrentRoomContext(context) }

    @Synchronized
    fun invalidateSelfRuleLog(context: BcInboundReceiveContext): Boolean {
        if (!isCurrentRoomContext(context)) return false
        selfRuleRevision++
        selfRuleLogInvalidated = true
        return true
    }

    @Synchronized
    fun renewSelfRuleLog() {
        selfRuleLogInvalidated = false
        selfRuleRevision++
    }

    @Synchronized
    fun captureProtectedSelfUpdateGuard(
        expected: BcSelfStateEmitExpectation,
        requireOwnerPoseRules: Boolean,
    ): (() -> Unit) -> Unit {
        val context = receiveContext
        check(expected.character.isSelf && expected.character.memberNumber == context.ownerMemberNumber) {
            "The active character changed before this update was sent."
        }
        val expectedState = expected.character.selfUpdateState(expected.ownerPresent, expected.editedExpressionGroups)
        check(currentSelfUpdateState(expected.editedExpressionGroups) == expectedState) {
            "Your character state changed before this update was sent."
        }
        check(!requireOwnerPoseRules || !selfRuleLogInvalidated || !expected.ownerPresent ||
            (expected.character.ownership?.stage ?: 0) < 1
        ) { "Your owner rules changed. Reconnect before changing your pose."
        }
        val ruleRevision = selfRuleRevision
        return roomGuard(context) {
            check(selfRuleRevision == ruleRevision && currentSelfUpdateState(expected.editedExpressionGroups) == expectedState) {
                "Your character state or owner rules changed before this update was sent."
            }
            true
        }
    }

    private fun currentSelfUpdateState(editedExpressionGroups: Set<String>): SelfUpdateState? {
        val room = current ?: return null
        val self = room.characters.firstOrNull { it.memberNumber == selfMemberNumber } ?: return null
        val ownerPresent = self.ownership?.memberNumber?.let { owner -> room.characters.any { it.memberNumber == owner } } == true
        return self.toBcCharacterSnapshot(isSelf = true, gson = gson).selfUpdateState(ownerPresent, editedExpressionGroups)
    }

    private fun BcCharacterSnapshot.selfUpdateState(ownerPresent: Boolean, editedExpressionGroups: Set<String>): SelfUpdateState =
        SelfUpdateState(
            memberNumber = memberNumber,
            assetFamily = assetFamily,
            appearance = appearance.map { it.selfUpdateItemState(it.group in editedExpressionGroups) }.sortedBy { it.group },
            activePose = activePose,
            owner = owner,
            ownership = ownership,
            ownerPresent = ownerPresent,
        )

    private fun BcCharacterItem.selfUpdateItemState(ignoreExpression: Boolean): SelfUpdateItemState {
        val property = propertyJson.selfUpdateJsonOrNull()
        return SelfUpdateItemState(
            group = group,
            name = name,
            color = colorJson.selfUpdateJsonOrNull(),
            difficulty = difficulty,
            property = if (ignoreExpression) {
                (property?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy() ?: JsonObject()).apply { remove("Expression") }
            } else property,
            craft = craftJson.selfUpdateJsonOrNull(),
        )
    }

    private fun String?.selfUpdateJsonOrNull(): JsonElement? =
        this?.let(JsonParser::parseString)?.takeUnless { it.isJsonNull }

    @Synchronized
    fun isCurrentRoomContext(context: BcInboundReceiveContext): Boolean {
        val active = receiveContext
        return active.ownerMemberNumber != null &&
            active.roomName != null &&
            active.ownerMemberNumber == context.ownerMemberNumber &&
            active.roomName == context.roomName &&
            active.roomPresenceEpoch == context.roomPresenceEpoch
    }

    fun captureRoomMessageGuard(command: BcOutboundCommand): (() -> Unit) -> Unit =
        roomGuard(captureReceiveContext()) { active ->
            (command.replyToId == null || active.roomName == command.replyRoomName) && when (command.transport) {
                BcMessageTransport.RoomChat -> active.roomName?.let(::bcRoomConversationId) == command.conversationId
                BcMessageTransport.RoomWhisper ->
                    !(command.originRoomName ?: command.replyRoomName).isNullOrBlank() &&
                        active.roomName == (command.originRoomName ?: command.replyRoomName) &&
                        command.targetMemberNumber?.let { it in active.roomMembers } == true
                else -> false
            }
        }

    fun captureRoomActionGuard(
        targetMemberNumber: Int? = null,
        context: BcInboundReceiveContext = captureReceiveContext(),
    ): (() -> Unit) -> Unit = roomGuard(context) { active ->
        targetMemberNumber == null || targetMemberNumber in active.roomMembers
    }

    @Synchronized
    fun captureItemUpdateGuard(targetMemberNumber: Int, context: BcInboundReceiveContext, selectedCharacters: List<BcCharacterSnapshot>?): (() -> Unit) -> Unit {
        val participants = setOfNotNull(context.ownerMemberNumber, targetMemberNumber)
        fun characters() = current?.let(::projectCharacters)?.filter { it.memberNumber in participants }?.sortedBy { it.memberNumber }
        val expectedCharacters = characters()
        check(expectedCharacters?.size == participants.size) { "An item interaction participant left the room." }
        check(selectedCharacters == null || selectedCharacters.distinctBy { it.memberNumber }.sortedBy { it.memberNumber } == expectedCharacters) {
            "The character or item changed. Select the operation again."
        }
        val expectedVisits = participants.associateWith { memberPresenceEpochs[it] }
        val expectedBlockedCategories = current?.blockCategory?.toSet()
        return roomGuard(context) {
            check(characters() == expectedCharacters && current?.blockCategory?.toSet() == expectedBlockedCategories &&
                participants.all { memberPresenceEpochs[it] == expectedVisits[it] }) {
                "The character or item changed before this update was sent. Select the operation again."
            }
            true
        }
    }

    @Synchronized
    fun captureExpiredItemUpdateGuard(targetMemberNumber: Int, context: BcInboundReceiveContext,
        expectedItem: dev.vivian.mobilebc.core.model.BcCharacterItem): (() -> Unit) -> Unit {
        check(targetMemberNumber == context.ownerMemberNumber) { "Only the wearer can settle an expired item timer." }
        fun item() = current?.let(::projectCharacters)?.firstOrNull { it.memberNumber == targetMemberNumber }
            ?.appearance?.firstOrNull { it.group == expectedItem.group }
        check(item() == expectedItem) { "The timed item changed before expiry was queued." }
        val expectedVisit = memberPresenceEpochs[targetMemberNumber]
        return roomGuard(context) {
            check(item() == expectedItem && memberPresenceEpochs[targetMemberNumber] == expectedVisit) {
                "The timed item changed before expiry was sent."
            }
            true
        }
    }

    private fun roomGuard(
        queuedContext: BcInboundReceiveContext,
        routeMatches: (BcInboundReceiveContext) -> Boolean,
    ): (() -> Unit) -> Unit {
        return { emitPacket ->
            synchronized(this) {
                if (roomJoinPending || !isCurrentRoomContext(queuedContext) || !routeMatches(receiveContext)) {
                    throw OutboxDispatchDeferredException("The queued room command no longer matches the active room session.")
                }
                // Room transitions and the local socket emit share this lock: checking the epoch
                // and emitting cannot be separated by a kick, room sync, or account change.
                emitPacket()
            }
        }
    }

    @Synchronized
    fun emitRoomTransition(emitPacket: () -> Unit) {
        emitPacket()
        clearRoom()
        emit(BcSessionGatewayEvent.RoomLeft)
    }

    @Synchronized
    fun emitRoomJoin(emitPacket: () -> Unit): Long {
        emitPacket()
        roomJoinPending = true
        roomJoinPreviousPresenceEpoch = roomPresenceEpoch
        roomJoinPreviousRoomName = current?.name?.let(::BcRoomName)
        roomPresenceEpoch++
        publishReceiveContext()
        return roomPresenceEpoch
    }

    @Synchronized
    fun rejectRoomJoin(transitionEpoch: Long) {
        if (!roomJoinPending || roomPresenceEpoch != transitionEpoch) return
        val previousEpoch = roomJoinPreviousPresenceEpoch
        val previousRoomName = roomJoinPreviousRoomName
        roomJoinPending = false
        roomJoinPreviousPresenceEpoch = null
        roomJoinPreviousRoomName = null
        roomPresenceEpoch++
        publishReceiveContext()
        if (current != null && previousEpoch != null) {
            emitCurrent(
                joined = false,
                previousRoomPresenceEpoch = previousEpoch,
                previousRoomName = previousRoomName,
            )
        }
    }

    @Synchronized
    fun handle(event: RazorWireEvent): Boolean {
        return when (event) {
            is RazorWireEvent.ChatRoomSync -> {
                val room = runCatching {
                    require(event.value.name.isNotBlank()) { "Room sync name is missing." }
                    RoomState.fromSyncMessage(
                        msg = event.value,
                        characters = event.value.character?.mapNotNull { parseCharacterFromJson(it, gson) }.orEmpty(),
                    )
                }.getOrElse { throwable ->
                    diagnostic("Ignored malformed room sync: ${throwable.message ?: "Room conversion failed."}")
                    clearRoom()
                    emit(BcSessionGatewayEvent.RoomLeft)
                    return true
                }
                roomJoinPending = false
                roomJoinPreviousPresenceEpoch = null
                roomJoinPreviousRoomName = null
                pendingCharacterUpdates.clear()
                clearProjectionCaches()
                roomPresenceEpoch++
                current = room
                rawSettings = event.rawPayloadJson?.let(RazorRoomRawSettings::capture)
                currentAdministrators = roomMemberNumbers(event.rawPayloadJson, "Admin", event.value.admin)
                currentBannedMembers = roomMemberNumbers(event.rawPayloadJson, "Ban", event.value.ban)
                memberPresenceEpochs.clear()
                room.characters.forEach { advanceMemberPresence(it.memberNumber) }
                currentRoomSpace = rawSettings?.evidenceCopy()?.get("Space").exactStringOrNull()
                currentRoomGame = roomGame(event.rawPayloadJson, event.value.game)
                emitCurrent(joined = true)
                true
            }
            is RazorWireEvent.MemberJoin -> {
                val joined = parseCharacterFromJson(event.value.character, gson) ?: return true
                if (current == null) return true
                advanceMemberPresence(joined.memberNumber)
                val previous = current
                upsertCharacter(joined) { room ->
                    room.withMemberJoinRelationshipHints(
                        joinedMemberNumber = joined.memberNumber,
                        whiteListedBy = event.value.whiteListedBy.orEmpty(),
                        blackListedBy = event.value.blackListedBy.orEmpty(),
                        selfMemberNumber = selfMemberNumber,
                    )
                }
                if (current == previous) emitCurrent(joined = false)
                true
            }
            is RazorWireEvent.MemberLeave -> {
                val memberNumber = event.value.sourceMemberNumber
                pendingCharacterUpdates.remove(memberNumber)
                memberPresenceEpochs.remove(memberNumber)
                if (memberNumber == selfMemberNumber) {
                    clearRoom()
                    emit(BcSessionGatewayEvent.RoomLeft)
                } else {
                    update(transform = { room ->
                        room.copy(characters = room.characters.filterNot { it.memberNumber == memberNumber })
                    })
                }
                true
            }
            is RazorWireEvent.RoomProperties -> {
                current?.let { room ->
                    rawSettings = event.rawPayloadJson?.let(RazorRoomRawSettings::capture)
                    currentAdministrators = roomMemberNumbers(event.rawPayloadJson, "Admin", event.value.admin)
                    currentBannedMembers = roomMemberNumbers(event.rawPayloadJson, "Ban", event.value.ban)
                    val evidence = rawSettings?.evidenceCopy()
                    if (evidence?.has("Space") == true) currentRoomSpace = evidence.get("Space").exactStringOrNull()
                    currentRoomGame = roomGame(event.rawPayloadJson, event.value.game)
                    current = room.withProperties(event.value)
                    emitCurrent(joined = false)
                }
                true
            }
            is RazorWireEvent.InvalidRoomSettings -> {
                if (event.eventName == "ChatRoomSync") {
                    clearRoom()
                    emit(BcSessionGatewayEvent.RoomLeft)
                } else {
                    rawSettings = null
                    currentAdministrators = null
                    currentBannedMembers = null
                    currentRoomGame = roomGame(event.rawPayloadJson, null)
                    emitCurrent(joined = false)
                }
                true
            }
            is RazorWireEvent.CharacterSync -> {
                val character = parseCharacterFromJson(event.value.character, gson) ?: return true
                if (current != null && character.memberNumber !in memberPresenceEpochs) advanceMemberPresence(character.memberNumber)
                upsertCharacter(character)
                true
            }
            is RazorWireEvent.ExpressionSync -> {
                updateCharacterOrDefer(
                    memberNumber = event.value.memberNumber,
                    kind = "ExpressionSync",
                    transform = { it.applyExpressionSync(event.value) },
                )
                true
            }
            is RazorWireEvent.PoseSync -> {
                updateCharacterOrDefer(
                    memberNumber = event.value.memberNumber,
                    kind = "PoseSync",
                    transform = { it.applyPoseSync(event.value) },
                )
                true
            }
            is RazorWireEvent.ArousalSync -> {
                updateCharacterOrDefer(
                    memberNumber = event.value.memberNumber,
                    kind = "ArousalSync",
                    transform = { it.applyArousalSync(event.value) },
                )
                true
            }
            is RazorWireEvent.ItemSync -> {
                if (event.value.item?.group.isNullOrBlank()) return true
                updateCharacterOrDefer(
                    memberNumber = event.value.item.target,
                    kind = "ItemSync",
                    transform = { it.applyItemSync(event.value) },
                    persistSelfAppearance = event.value.item.target == selfMemberNumber,
                )
                true
            }
            is RazorWireEvent.MapDataSync -> {
                updateCharacterOrDefer(
                    memberNumber = event.value.memberNumber,
                    kind = "MapDataSync",
                    transform = { it.applyMapDataSync(event.value) },
                )
                true
            }
            is RazorWireEvent.ReorderPlayers -> {
                update(transform = { it.applyReorderPlayers(event.value) })
                true
            }
            else -> false
        }
    }

    @Synchronized
    fun resetSession() {
        selfRuleLogInvalidated = false
        selfRuleRevision++
        selfMemberNumber = null
        clearRoom()
    }

    @Synchronized
    fun clearRoom() {
        roomJoinPending = false
        roomJoinPreviousPresenceEpoch = null
        roomJoinPreviousRoomName = null
        pendingCharacterUpdates.clear()
        clearProjectionCaches()
        current = null
        rawSettings = null
        currentRoomSpace = null
        currentRoomGame = null
        currentAdministrators = null
        currentBannedMembers = null
        memberPresenceEpochs.clear()
        roomPresenceEpoch++
        publishReceiveContext()
    }

    private fun upsertCharacter(
        character: CharacterState,
        afterUpsert: (RoomState) -> RoomState = { it },
    ) {
        val pending = pendingCharacterUpdates.remove(character.memberNumber).orEmpty()
        update(
            transform = { room ->
                val upserted = afterUpsert(room.withCharacterUpserted(character))
                pending.fold(upserted) { state, deferred ->
                    deferred.transform(state)
                }
            },
            persistSelfAppearance = pending.any(PendingCharacterUpdate::persistSelfAppearance),
        )
        if (pending.isNotEmpty()) {
            diagnostic(
                "Replayed ${pending.size} deferred character update(s) for member ${character.memberNumber}.",
            )
        }
    }

    private fun updateCharacterOrDefer(
        memberNumber: Int,
        kind: String,
        transform: (RoomState) -> RoomState,
        persistSelfAppearance: Boolean = false,
    ) {
        val room = current ?: return
        if (room.findCharacter(memberNumber) == null) {
            deferCharacterUpdate(
                memberNumber = memberNumber,
                update = PendingCharacterUpdate(kind, persistSelfAppearance, transform),
            )
            return
        }
        update(transform, persistSelfAppearance)
    }

    private fun deferCharacterUpdate(memberNumber: Int, update: PendingCharacterUpdate) {
        if (memberNumber !in pendingCharacterUpdates && pendingCharacterUpdates.size >= MAX_PENDING_MEMBERS) {
            val droppedMember = pendingCharacterUpdates.keys.first()
            val droppedCount = pendingCharacterUpdates.remove(droppedMember)?.size.orZero()
            diagnostic("Dropped $droppedCount deferred update(s) for member $droppedMember: buffer full.")
        }
        val updates = pendingCharacterUpdates.getOrPut(memberNumber) { ArrayDeque() }
        if (updates.size >= MAX_PENDING_UPDATES_PER_MEMBER) {
            val dropped = updates.removeFirst()
            diagnostic("Dropped deferred ${dropped.kind} for member $memberNumber: per-member buffer full.")
        }
        updates.addLast(update)
        diagnostic("Deferred ${update.kind} for unknown room member $memberNumber.")
    }

    private fun update(
        transform: (RoomState) -> RoomState,
        persistSelfAppearance: Boolean = false,
    ) {
        val previous = current ?: return
        val next = transform(previous)
        if (next == previous) return
        current = next
        if (persistSelfAppearance) {
            val memberNumber = selfMemberNumber
            val appearance = next.accountAppearanceFor(memberNumber)
            if (appearance != null && previous.hasDifferentAppearanceFor(memberNumber, next)) {
                queueAccountAppearance(appearance)
            }
        }
        emitCurrent(joined = false)
    }

    private fun emitCurrent(
        joined: Boolean,
        previousRoomPresenceEpoch: Long? = null,
        previousRoomName: BcRoomName? = null,
    ) {
        val room = current ?: return
        publishReceiveContext()
        val event = if (joined) {
            BcSessionGatewayEvent.RoomJoined(
                room = projectRoom(room),
                members = projectMembers(room),
                characters = projectCharacters(room),
            )
        } else {
            BcSessionGatewayEvent.RoomUpdated(
                room = projectRoom(room),
                members = projectMembers(room),
                characters = projectCharacters(room),
                previousRoomPresenceEpoch = previousRoomPresenceEpoch,
                previousRoomName = previousRoomName,
            )
        }
        emit(event)
    }

    private fun projectRoom(room: RoomState): BcRoom {
        val administration = selfMemberNumber?.let { memberNumber ->
            rawSettings?.let { raw ->
                runCatching {
                    val settings = raw.validatedUpdateSettings()
                    BcRoomAdministration(
                        base = BcRoomEditBase(room.toBcRoom().name, roomPresenceEpoch, settings.toRoomBasicSettings()),
                        adminMemberNumbers = settings.getAsJsonArray("Admin").map { it.asInt },
                    )
                }.getOrNull()
            }
        }
        val access = rawSettings?.evidenceCopy()?.get("Access")
        val isLocked = if (access?.isJsonArray == true && access.asJsonArray.all { it.isJsonPrimitive && it.asJsonPrimitive.isString }) {
            access.asJsonArray.map { it.asString } != listOf("All")
        } else room.locked
        return room.toBcRoom().copy(isLocked = isLocked, adminSettings = administration, space = currentRoomSpace,
            banMemberNumbers = currentBannedMembers, game = currentRoomGame)
    }

    private fun roomGame(rawPayloadJson: String?, typedGame: String?): String? {
        if (rawPayloadJson == null) return typedGame ?: ""
        val fields = runCatching { JsonParser.parseString(rawPayloadJson) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return if (fields.has("Game")) fields.get("Game").exactStringOrNull() else ""
    }

    private fun publishReceiveContext() {
        val room = current
        val members = room?.characters?.associate { character ->
            character.memberNumber to (character.nickname?.takeIf { it.isNotBlank() } ?: character.name)
        }.orEmpty()
        receiveContext = BcInboundReceiveContext(
            ownerMemberNumber = selfMemberNumber,
            ownerDisplayName = selfMemberNumber?.let(members::get),
            roomName = room?.name,
            roomMembers = Collections.unmodifiableMap(members),
            roomPresenceEpoch = roomPresenceEpoch,
        )
    }

    private fun projectMembers(room: RoomState): List<BcMember> {
        val activeMemberNumbers = room.characters.mapTo(hashSetOf(), CharacterState::memberNumber)
        memberProjectionCache.keys.retainAll(activeMemberNumbers)
        return room.characters.map { character ->
            val isSelf = character.memberNumber == selfMemberNumber
            val isAdmin = character.memberNumber in currentAdministrators.orEmpty()
            val authorityKnown = currentAdministrators != null
            val presenceEpoch = memberPresenceEpochs[character.memberNumber]
            val cached = memberProjectionCache[character.memberNumber]
            if (cached != null && cached.source == character && cached.isSelf == isSelf && cached.isAdmin == isAdmin &&
                cached.authorityKnown == authorityKnown && cached.presenceEpoch == presenceEpoch) {
                cached.value
            } else {
                character.toBcMember(isSelf = isSelf, isAdmin = isAdmin).copy(
                    role = if (!authorityKnown) null else if (isAdmin) RoomRole.Admin else RoomRole.Member,
                    roomMemberPresenceEpoch = presenceEpoch,
                ).also { value ->
                    memberProjectionCache[character.memberNumber] = CachedMemberProjection(
                        source = character,
                        isSelf = isSelf,
                        isAdmin = isAdmin,
                        authorityKnown = authorityKnown,
                        presenceEpoch = presenceEpoch,
                        value = value,
                    )
                }
            }
        }
    }

    private fun projectCharacters(room: RoomState): List<BcCharacterSnapshot> {
        val activeMemberNumbers = room.characters.mapTo(hashSetOf(), CharacterState::memberNumber)
        characterProjectionCache.keys.retainAll(activeMemberNumbers)
        return room.characters.map { character ->
            val isSelf = character.memberNumber == selfMemberNumber
            val cached = characterProjectionCache[character.memberNumber]
            if (cached != null && cached.source == character && cached.isSelf == isSelf) {
                cached.value
            } else {
                character.toBcCharacterSnapshot(isSelf = isSelf, gson = gson).also { value ->
                    characterProjectionCache[character.memberNumber] = CachedCharacterProjection(
                        source = character,
                        isSelf = isSelf,
                        value = value,
                    )
                }
            }
        }
    }

    private fun clearProjectionCaches() {
        memberProjectionCache.clear()
        characterProjectionCache.clear()
    }

    private fun advanceMemberPresence(memberNumber: Int) {
        nextMemberPresenceEpoch++
        memberPresenceEpochs[memberNumber] = nextMemberPresenceEpoch
    }

    private fun roomMemberNumbers(payloadJson: String?, field: String, typed: List<Int>?): List<Int>? =
        if (payloadJson == null) typed?.takeIf { values -> values.all { it > 0 } }?.toList()
        else runCatching { JsonParser.parseString(payloadJson).asJsonObject.get(field).exactRoomMemberNumbersOrNull() }.getOrNull()

    private data class PendingCharacterUpdate(
        val kind: String,
        val persistSelfAppearance: Boolean,
        val transform: (RoomState) -> RoomState,
    )

    private data class CachedMemberProjection(
        val source: CharacterState,
        val isSelf: Boolean,
        val isAdmin: Boolean,
        val authorityKnown: Boolean,
        val presenceEpoch: Long?,
        val value: BcMember,
    )

    private data class CachedCharacterProjection(
        val source: CharacterState,
        val isSelf: Boolean,
        val value: BcCharacterSnapshot,
    )

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        private const val MAX_PENDING_MEMBERS = 32
        private const val MAX_PENDING_UPDATES_PER_MEMBER = 8
    }
}

private data class SelfUpdateState(
    val memberNumber: Int,
    val assetFamily: String,
    val appearance: List<SelfUpdateItemState>,
    val activePose: List<String>,
    val owner: String?,
    val ownership: BcCharacterOwnership?,
    val ownerPresent: Boolean,
)

private data class SelfUpdateItemState(
    val group: String,
    val name: String,
    val color: JsonElement?,
    val difficulty: Int?,
    val property: JsonElement?,
    val craft: JsonElement?,
)
