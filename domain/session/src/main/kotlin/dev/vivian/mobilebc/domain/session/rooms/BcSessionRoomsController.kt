package dev.vivian.mobilebc.domain.session.rooms

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminObservation
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.RoomRole
import dev.vivian.mobilebc.core.model.conflictingFields
import dev.vivian.mobilebc.domain.session.core.RoomGateway
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRoomJoinRejectedException
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.isAuthenticated
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

class BcSessionRoomsController(
    private val stateHolder: BcSessionRuntimeState,
    private val gateway: RoomGateway,
    private val onOperationFailure: (ReconnectReason, Throwable?) -> Unit,
    private val emitRoomTargetChanged: suspend (String?) -> Unit,
    private val upsertRoomConversation: (BcRoom) -> Unit,
    private val upsertRoomState: (BcSessionSnapshot) -> Unit,
    private val upsertSameRoomDirectConversations: (BcSessionSnapshot) -> Unit,
    private val onRoomRenamed: (BcRoom, BcRoom) -> Unit = { _, _ -> },
    private val roomMemberObservationTimeoutMillis: Long = 10_000L,
    private val onRoomRemoved: (BcRoom) -> Unit = {},
) : BcSessionRooms {
    private val roomOperationMutex = Mutex()
    private val roomTargetMutex = Mutex()

    override suspend fun searchRooms(filter: BcRoomSearchFilter): List<BcRoomSummary> {
        val started = requireOnlineSession()
        return roomOperationMutex.withLock {
            ensureCurrentSession(started)
            val searching = BcSessionState.SearchingRooms(filter)
            var publishedSearching = false
            stateHolder.updateSession {
                publishedSearching = isCurrentSession(started, it)
                if (publishedSearching) it.copy(state = searching) else it
            }
            try {
                ensureCurrentSession(started)
                val rooms = gateway.searchRooms(filter)
                ensureCurrentSession(started)
                rooms.filterNot { room -> room.creatorMemberNumber in stateHolder.current.relationships.self.ignored }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (isCurrentSession(started)) onOperationFailure(ReconnectReason.OperationFailed, throwable)
                throw throwable
            } finally {
                if (publishedSearching) {
                    stateHolder.updateSession {
                        if (it.state === searching && isCurrentSession(started, it)) {
                            it.copy(state = BcSessionState.Ready)
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    override suspend fun joinRoom(roomName: String, password: String?) {
        val started = requireOnlineSession()
        roomOperationMutex.withLock {
            ensureCurrentSession(started)
            if (isAlreadyInOrJoiningRoom(roomName)) {
                rememberRoomTarget(roomName, password)
                publishRoomTarget(started, roomName)
                return
            }
            val joining = BcSessionState.JoiningRoom(roomName)
            stateHolder.updateSession { if (isCurrentSession(started, it)) it.copy(state = joining) else it }
            try {
                ensureCurrentSession(started)
                gateway.joinRoom(roomName, password)
                ensureCurrentSession(started)
                val confirmedName = stateHolder.current
                    .takeIf { it.roomPresenceEpoch > started.roomPresenceEpoch }
                    ?.currentRoom?.name?.value ?: roomName
                rememberRoomTarget(confirmedName, password)
                publishRoomTarget(started, confirmedName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: BcRoomJoinRejectedException) {
                throw rejected
            } catch (throwable: Throwable) {
                if (isCurrentSession(started)) onOperationFailure(ReconnectReason.OperationFailed, throwable)
                throw throwable
            } finally {
                finishJoining(started, joining)
            }
        }
    }

    override suspend fun leaveRoom() {
        val started = requireOnlineSession()
        roomOperationMutex.withLock {
            ensureCurrentSession(started)
            try {
                gateway.leaveRoom()
                ensureCurrentSession(started)
                stateHolder.rememberRoomTarget(null, null)
                publishRoomTarget(started, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                if (isCurrentSession(started)) onOperationFailure(ReconnectReason.OperationFailed, throwable)
                throw throwable
            }
        }
    }

    override suspend fun createRoom(command: BcRoomCreateCommand): BcRoom {
        val started = requireOnlineRoomMutation()
        return roomOperationMutex.withLock {
            ensureCurrentSession(started)
            rejectIf(stateHolder.current.currentRoom != null, BcRoomMutationRejectionReason.AlreadyInRoom)
            performRoomMutation(started, BcSessionState.CreatingRoom(command.settings.name)) {
                gateway.createRoom(command)
            }
        }
    }

    override suspend fun updateRoom(command: BcRoomUpdateCommand): BcRoom {
        val started = requireOnlineRoomMutation()
        return roomOperationMutex.withLock {
            ensureCurrentSession(started)
            val current = stateHolder.current
            val room = current.currentRoom ?: reject(BcRoomMutationRejectionReason.RoomUnavailable)
            rejectIf(room.name != command.base.roomName, BcRoomMutationRejectionReason.RoomChanged)
            rejectIf(
                current.gatewayRoomPresenceEpoch != command.base.roomPresenceEpoch,
                BcRoomMutationRejectionReason.RoomChanged,
            )
            val administration = room.adminSettings ?: reject(BcRoomMutationRejectionReason.SettingsUnavailable)
            rejectIf(
                current.account?.memberNumber !in administration.adminMemberNumbers,
                BcRoomMutationRejectionReason.NotAdministrator,
            )
            rejectIf(
                command.conflictingFields(administration.base.settings).isNotEmpty(),
                BcRoomMutationRejectionReason.EditConflict,
            )
            performRoomMutation(started, BcSessionState.UpdatingRoom(room.name.value)) {
                gateway.updateRoom(command)
            }
        }
    }

    override suspend fun manageRoomMember(command: BcRoomMemberAdminCommand): BcRoomMemberAdminObservation {
        coroutineContext.ensureActive()
        val started = stateHolder.current
        rejectMemberIf(started.account == null, BcRoomMemberAdminRejectionReason.AccountUnavailable)
        rejectMemberIf(!started.isVerifiedOnline, BcRoomMemberAdminRejectionReason.ConnectionUnavailable)
        return roomOperationMutex.withLock {
            ensureCurrentSession(started)
            validateRoomMemberCommand(command, stateHolder.current)
            val operation = BcSessionState.ManagingRoomMember(command.roomName.value, command.targetMemberNumber)
            stateHolder.updateSession { if (isCurrentSession(started, it)) it.copy(state = operation) else it }
            try {
                ensureCurrentSession(started)
                validateRoomMemberCommand(command, stateHolder.current)
                gateway.manageRoomMember(command)
                val observed = withTimeoutOrNull(roomMemberObservationTimeoutMillis) {
                    stateHolder.snapshot.first { current ->
                        !isSameRoomVisit(started, current, command) || targetReentered(current, command) ||
                            observeRoomMemberCommand(command, current) != null
                    }
                }
                if (observed == null) {
                    ensureSameRoomVisit(started, stateHolder.current, command)
                    BcRoomMemberAdminObservation.Unconfirmed
                } else {
                    ensureSameRoomVisit(started, observed, command)
                    if (targetReentered(observed, command)) BcRoomMemberAdminObservation.Unconfirmed
                    else observeRoomMemberCommand(command, observed) ?: BcRoomMemberAdminObservation.Unconfirmed
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (rejected: BcRoomMemberAdminRejectedException) {
                if (rejected.reason == BcRoomMemberAdminRejectionReason.PendingResponse && isCurrentSession(started)) {
                    onOperationFailure(ReconnectReason.OperationFailed, rejected)
                }
                throw rejected
            } catch (throwable: Throwable) {
                if (isCurrentSession(started)) onOperationFailure(ReconnectReason.OperationFailed, throwable)
                throw throwable
            } finally {
                stateHolder.updateSession {
                    if (it.state === operation && isCurrentSession(started, it)) it.copy(state = BcSessionState.Ready) else it
                }
            }
        }
    }

    private fun validateRoomMemberCommand(command: BcRoomMemberAdminCommand, current: BcSessionSnapshot) {
        val room = current.currentRoom ?: rejectMember(BcRoomMemberAdminRejectionReason.RoomUnavailable)
        rejectMemberIf(
            room.name != command.roomName || current.gatewayRoomPresenceEpoch != command.roomPresenceEpoch,
            BcRoomMemberAdminRejectionReason.RoomChanged,
        )
        val selfNumber = current.account?.memberNumber ?: rejectMember(BcRoomMemberAdminRejectionReason.AccountUnavailable)
        val self = current.roomMembers.singleOrNull { it.memberNumber == selfNumber }
            ?: rejectMember(BcRoomMemberAdminRejectionReason.SettingsUnavailable)
        val selfIsAdmin = self.isRoomAdministrator()
            ?: rejectMember(BcRoomMemberAdminRejectionReason.SettingsUnavailable)
        rejectMemberIf(!selfIsAdmin, BcRoomMemberAdminRejectionReason.NotAdministrator)
        rejectMemberIf(command.targetMemberNumber == selfNumber, BcRoomMemberAdminRejectionReason.SelfTarget)
        rejectMemberIf(command.targetMemberNumber <= 0, BcRoomMemberAdminRejectionReason.TargetUnavailable)
        val target = current.roomMembers.singleOrNull { it.memberNumber == command.targetMemberNumber }
            ?: rejectMember(BcRoomMemberAdminRejectionReason.TargetUnavailable)
        rejectMemberIf(
            target.roomMemberPresenceEpoch != command.targetPresenceEpoch,
            BcRoomMemberAdminRejectionReason.TargetChanged,
        )
        val targetIsAdmin = target.isRoomAdministrator()
            ?: rejectMember(BcRoomMemberAdminRejectionReason.SettingsUnavailable)
        rejectMemberIf(
            targetIsAdmin != command.targetIsAdministrator ||
                command.action == BcRoomMemberAdminAction.Promote && targetIsAdmin ||
                command.action == BcRoomMemberAdminAction.Demote && !targetIsAdmin,
            BcRoomMemberAdminRejectionReason.TargetRoleChanged,
        )
    }

    private fun observeRoomMemberCommand(
        command: BcRoomMemberAdminCommand,
        current: BcSessionSnapshot,
    ): BcRoomMemberAdminObservation? {
        val target = current.roomMembers.singleOrNull { it.memberNumber == command.targetMemberNumber }
        val targetAbsent = current.roomMembers.none { it.memberNumber == command.targetMemberNumber }
        return when (command.action) {
            BcRoomMemberAdminAction.Kick -> BcRoomMemberAdminObservation.TargetLeft.takeIf { targetAbsent }
            BcRoomMemberAdminAction.Ban -> BcRoomMemberAdminObservation.TargetBanned.takeIf {
                targetAbsent && current.currentRoom?.banMemberNumbers?.contains(command.targetMemberNumber) == true
            }
            BcRoomMemberAdminAction.Promote -> BcRoomMemberAdminObservation.TargetPromoted.takeIf {
                target?.roomMemberPresenceEpoch == command.targetPresenceEpoch && target.isRoomAdministrator() == true
            }
            BcRoomMemberAdminAction.Demote -> BcRoomMemberAdminObservation.TargetDemoted.takeIf {
                target?.roomMemberPresenceEpoch == command.targetPresenceEpoch && target.isRoomAdministrator() == false
            }
        }
    }

    private fun targetReentered(current: BcSessionSnapshot, command: BcRoomMemberAdminCommand): Boolean =
        current.roomMembers.any {
            it.memberNumber == command.targetMemberNumber && it.roomMemberPresenceEpoch != command.targetPresenceEpoch
        }

    private fun isSameRoomVisit(
        started: BcSessionSnapshot,
        current: BcSessionSnapshot,
        command: BcRoomMemberAdminCommand,
    ): Boolean = isCurrentSession(started, current) && current.currentRoom?.name == command.roomName &&
        current.roomPresenceEpoch == started.roomPresenceEpoch && current.gatewayRoomPresenceEpoch == command.roomPresenceEpoch

    private suspend fun ensureSameRoomVisit(
        started: BcSessionSnapshot,
        current: BcSessionSnapshot,
        command: BcRoomMemberAdminCommand,
    ) {
        coroutineContext.ensureActive()
        if (!isSameRoomVisit(started, current, command)) {
            throw CancellationException("BC room visit changed during the member operation.")
        }
    }

    private fun BcMember?.isRoomAdministrator(): Boolean? = when (this?.role) {
        RoomRole.Admin -> true
        RoomRole.Member -> false
        else -> null
    }

    private fun rejectMemberIf(condition: Boolean, reason: BcRoomMemberAdminRejectionReason) {
        if (condition) rejectMember(reason)
    }

    private fun rejectMember(reason: BcRoomMemberAdminRejectionReason): Nothing =
        throw BcRoomMemberAdminRejectedException(reason)

    private suspend fun performRoomMutation(
        started: BcSessionSnapshot,
        operation: BcSessionState,
        mutate: suspend () -> BcRoom,
    ): BcRoom {
        stateHolder.updateSession { if (isCurrentSession(started, it)) it.copy(state = operation) else it }
        try {
            ensureCurrentSession(started)
            return mutate().also { ensureCurrentSession(started) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: BcRoomMutationRejectedException) {
            if (rejected.rejection.reason == BcRoomMutationRejectionReason.PendingResponse && isCurrentSession(started)) {
                onOperationFailure(ReconnectReason.OperationFailed, rejected)
            }
            throw rejected
        } catch (unknown: BcRoomMutationOutcomeUnknownException) {
            if (isCurrentSession(started)) onOperationFailure(ReconnectReason.OperationFailed, unknown)
            throw unknown
        } catch (throwable: Throwable) {
            if (isCurrentSession(started)) onOperationFailure(ReconnectReason.OperationFailed, throwable)
            throw throwable
        } finally {
            stateHolder.updateSession {
                if (it.state === operation && isCurrentSession(started, it)) it.copy(state = BcSessionState.Ready) else it
            }
        }
    }

    private fun rejectIf(condition: Boolean, reason: BcRoomMutationRejectionReason) {
        if (condition) reject(reason)
    }

    private fun reject(reason: BcRoomMutationRejectionReason): Nothing =
        throw BcRoomMutationRejectedException(BcRoomMutationRejection(reason))

    suspend fun handleGatewayEvent(event: BcSessionGatewayEvent) {
        when (event) {
            is BcSessionGatewayEvent.RoomJoined -> {
                val started = stateHolder.current
                if (!started.acceptsRoomEvent(event.accountMemberNumber, event.selfRuleLogSessionId)) return
                if (started.selfRuleLogSessionId != null && event.roomPresenceEpoch == null) return
                if (event.roomPresenceEpoch?.let { next ->
                        started.gatewayRoomPresenceEpoch?.let { next <= it }
                    } == true
                ) return
                val verifiedAtMillis = System.currentTimeMillis()
                var accepted = false
                stateHolder.updateSession {
                    accepted = it.acceptsRoomEvent(event.accountMemberNumber, event.selfRuleLogSessionId) &&
                        isSameAccount(started, it) &&
                        (event.roomPresenceEpoch?.let { next ->
                            it.gatewayRoomPresenceEpoch?.let { current -> next > current }
                        } != false)
                    if (!accepted) return@updateSession it
                    val joining = it.state as? BcSessionState.JoiningRoom
                    it.copy(
                        state = if (joining?.roomName.equals(event.room.name.value, ignoreCase = true)) {
                            BcSessionState.Ready
                        } else {
                            it.state
                        },
                        connectionHealth = BcConnectionHealth.VerifiedOnline,
                        lastVerifiedAtMillis = verifiedAtMillis,
                        currentRoom = event.room,
                        roomPresenceEpoch = it.roomPresenceEpoch + 1,
                        gatewayRoomPresenceEpoch = event.roomPresenceEpoch,
                        roomMembers = event.members,
                        roomCharacters = event.characters,
                    )
                }
                if (!accepted) return
                upsertRoomConversation(event.room)
                upsertRoomState(stateHolder.current)
                upsertSameRoomDirectConversations(stateHolder.current)
                if (isCurrentSession(started)) {
                    val target = stateHolder.rememberedRoomTarget
                    val password = target?.password.takeIf {
                        target?.roomName.equals(event.room.name.value, ignoreCase = true)
                    }
                    rememberRoomTarget(event.room.name.value, password)
                    publishRoomTarget(started, event.room.name.value)
                }
            }
            is BcSessionGatewayEvent.RoomUpdated -> {
                val started = stateHolder.current
                if (started.acceptsRoomEvent(event.accountMemberNumber, event.selfRuleLogSessionId) &&
                    started.acceptsRoomUpdateScope(event)
                ) {
                    val verifiedAtMillis = System.currentTimeMillis()
                    var accepted = false
                    var previousRoom: BcRoom? = null
                    stateHolder.updateSession {
                        accepted = it.acceptsRoomEvent(event.accountMemberNumber, event.selfRuleLogSessionId) &&
                            isSameAccount(started, it) && it.acceptsRoomUpdateScope(event)
                        if (!accepted) return@updateSession it
                        previousRoom = it.currentRoom
                        it.copy(
                            connectionHealth = BcConnectionHealth.VerifiedOnline,
                            lastVerifiedAtMillis = verifiedAtMillis,
                            currentRoom = event.room,
                            gatewayRoomPresenceEpoch = event.roomPresenceEpoch,
                            roomMembers = event.members,
                            roomCharacters = event.characters,
                        )
                    }
                    if (!accepted) return
                    if (previousRoom?.name != event.room.name) {
                        previousRoom?.let { previous -> onRoomRenamed(previous, event.room) }
                    }
                    upsertRoomConversation(event.room)
                    upsertRoomState(stateHolder.current)
                    upsertSameRoomDirectConversations(stateHolder.current)
                    val target = stateHolder.rememberedRoomTarget
                    val shouldRememberTarget = if (event.previousRoomPresenceEpoch == null) {
                        target?.roomName != event.room.name.value
                    } else {
                        previousRoom?.name != event.room.name &&
                            target?.roomName.equals(previousRoom?.name?.value, ignoreCase = true)
                    }
                    if (isCurrentSession(started) && shouldRememberTarget) {
                        val password = target?.password.takeIf {
                            target?.roomName.equals(previousRoom?.name?.value, ignoreCase = true)
                        }
                        rememberRoomTarget(event.room.name.value, password)
                        publishRoomTarget(started, event.room.name.value)
                    }
                }
            }
            BcSessionGatewayEvent.RoomLeft -> {
                if (stateHolder.current.selfRuleLogSessionId == null) clearRoomPresence(null, null, null)
            }
            is BcSessionGatewayEvent.RoomPresenceReset ->
                clearRoomPresence(event.accountMemberNumber, event.selfRuleLogSessionId, event.roomPresenceEpoch)
            is BcSessionGatewayEvent.RoomRemoved -> handleRoomRemoved(event)
            else -> Unit
        }
    }

    suspend fun autoJoinLastRoomAfterReconnect(account: BcAccount) {
        val started = stateHolder.current
        if (!started.autoJoinRoomAfterReconnect || !started.isVerifiedOnline || started.account !== account) return
        roomOperationMutex.withLock {
            if (!isCurrentSession(started) || !stateHolder.current.autoJoinRoomAfterReconnect) return
            val target = stateHolder.rememberedRoomTarget ?: return
            val roomName = target.roomName
            val password = target.password
            if (isAlreadyInOrJoiningRoom(roomName)) return
            val joining = BcSessionState.JoiningRoom(roomName)
            stateHolder.updateSession { if (isCurrentSession(started, it)) it.copy(state = joining) else it }
            try {
                ensureCurrentSession(started)
                gateway.joinRoom(roomName, password)
                coroutineContext.ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A saved room may be unavailable even though the recovered account is online.
            } finally {
                finishJoining(started, joining)
            }
        }
    }

    private suspend fun requireOnlineSession(): BcSessionSnapshot {
        coroutineContext.ensureActive()
        return stateHolder.current.also {
            check(it.isVerifiedOnline) { "BC session is not online." }
        }
    }

    private suspend fun requireOnlineRoomMutation(): BcSessionSnapshot {
        coroutineContext.ensureActive()
        return stateHolder.current.also {
            rejectIf(it.account == null, BcRoomMutationRejectionReason.AccountUnavailable)
            rejectIf(!it.isVerifiedOnline, BcRoomMutationRejectionReason.ConnectionUnavailable)
        }
    }

    private suspend fun ensureCurrentSession(started: BcSessionSnapshot) {
        coroutineContext.ensureActive()
        if (!isCurrentSession(started)) throw CancellationException("BC session changed during the room operation.")
    }

    private fun isCurrentSession(
        started: BcSessionSnapshot,
        current: BcSessionSnapshot = stateHolder.current,
    ): Boolean = current.isVerifiedOnline && isSameAccount(started, current)

    private fun isSameAccount(started: BcSessionSnapshot, current: BcSessionSnapshot): Boolean =
        current.account?.memberNumber == started.account?.memberNumber &&
        current.relationships.accountGeneration == started.relationships.accountGeneration &&
        current.selfRuleLogSessionId == started.selfRuleLogSessionId

    private fun finishJoining(started: BcSessionSnapshot, joining: BcSessionState.JoiningRoom) {
        stateHolder.updateSession {
            if (it.state === joining && isCurrentSession(started, it)) it.copy(state = BcSessionState.Ready) else it
        }
    }

    private fun BcSessionSnapshot.canReceiveRoomEvents(): Boolean =
        account != null && desiredOnline && state.isAuthenticated

    private fun BcSessionSnapshot.acceptsRoomEvent(memberNumber: Int?, loginToken: String?): Boolean =
        canReceiveRoomEvents() && when (selfRuleLogSessionId) {
            null -> loginToken == null && (memberNumber == null || memberNumber == account?.memberNumber)
            else -> loginToken == selfRuleLogSessionId && memberNumber == account?.memberNumber
        }

    private fun BcSessionSnapshot.acceptsRoomUpdateScope(event: BcSessionGatewayEvent.RoomUpdated): Boolean {
        val room = currentRoom ?: return false
        val previousEpoch = event.previousRoomPresenceEpoch
        if (previousEpoch == null) {
            return event.previousRoomName == null && event.roomPresenceEpoch == gatewayRoomPresenceEpoch &&
                (selfRuleLogSessionId == null || event.roomPresenceEpoch != null)
        }
        val nextEpoch = event.roomPresenceEpoch ?: return false
        return selfRuleLogSessionId != null && room.name == (event.previousRoomName ?: event.room.name) &&
            gatewayRoomPresenceEpoch == previousEpoch && nextEpoch > previousEpoch
    }

    private fun clearRoomPresence(memberNumber: Int?, loginToken: String?, gatewayEpoch: Long?) {
        val started = stateHolder.current
        if (!started.acceptsRoomEvent(memberNumber, loginToken)) return
        if (started.selfRuleLogSessionId != null && gatewayEpoch == null) return
        if (gatewayEpoch?.let { next -> started.gatewayRoomPresenceEpoch?.let { next < it } } == true) return
        var accepted = false
        stateHolder.updateSession {
            accepted = it.acceptsRoomEvent(memberNumber, loginToken) && isSameAccount(started, it) &&
                (gatewayEpoch?.let { next -> it.gatewayRoomPresenceEpoch?.let { current -> next >= current } } != false)
            if (accepted) it.copy(
                currentRoom = null,
                gatewayRoomPresenceEpoch = gatewayEpoch,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            ) else it
        }
        if (accepted) upsertSameRoomDirectConversations(stateHolder.current)
    }

    private suspend fun handleRoomRemoved(event: BcSessionGatewayEvent.RoomRemoved) {
        val started = stateHolder.current
        if (!started.acceptsRoomEvent(event.accountMemberNumber, event.selfRuleLogSessionId) ||
            started.currentRoom?.name != event.roomName ||
            started.gatewayRoomPresenceEpoch != event.previousRoomPresenceEpoch ||
            event.roomPresenceEpoch <= event.previousRoomPresenceEpoch
        ) return
        var accepted = false
        stateHolder.updateSession {
            accepted = it.acceptsRoomEvent(event.accountMemberNumber, event.selfRuleLogSessionId) &&
                isSameAccount(started, it) && it.currentRoom?.name == event.roomName &&
                it.gatewayRoomPresenceEpoch == event.previousRoomPresenceEpoch
            if (accepted) it.copy(
                currentRoom = null,
                gatewayRoomPresenceEpoch = event.roomPresenceEpoch,
                roomMembers = emptyList(),
                roomCharacters = emptyList(),
            ) else it
        }
        if (!accepted) return
        started.currentRoom?.let(onRoomRemoved)
        upsertSameRoomDirectConversations(stateHolder.current)
        if (isCurrentSession(started) && stateHolder.lastJoinedRoomName.equals(event.roomName.value, ignoreCase = true)) {
            stateHolder.rememberRoomTarget(null, null)
            publishRoomTarget(started, null)
        }
    }

    private fun isAlreadyInOrJoiningRoom(roomName: String): Boolean {
        val session = stateHolder.current
        if (session.currentRoom?.name?.value.equals(roomName, ignoreCase = true)) return true
        val state = session.state
        return state is BcSessionState.JoiningRoom && state.roomName.equals(roomName, ignoreCase = true)
    }

    private fun rememberRoomTarget(roomName: String, password: String?) {
        stateHolder.rememberRoomTarget(roomName, password)
    }

    private suspend fun publishRoomTarget(started: BcSessionSnapshot, roomName: String?) {
        roomTargetMutex.withLock {
            if (isCurrentSession(started) && stateHolder.lastJoinedRoomName == roomName) emitRoomTargetChanged(roomName)
        }
    }
}
