package dev.vivian.mobilebc.feature.rooms

import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomBasicField
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomCommandValidation
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomEditBase
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejection
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationUnknownReason
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.conflictingFields
import dev.vivian.mobilebc.core.model.validateBcRoomCreate
import dev.vivian.mobilebc.core.model.validateBcRoomUpdate
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.rooms.BcSessionRooms
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RoomAdministrationDraft(
    val name: String = "",
    val description: String = "",
    val memberLimit: String = "10",
    val background: String = "",
    val language: String = "EN",
    val visibility: List<String> = listOf("All"),
    val access: List<String> = listOf("All"),
    val space: BcRoomSpace = BcRoomSpace.Mixed,
)

enum class RoomAdministrationFormMode { Create, Edit }

sealed interface RoomAdministrationFormFailure {
    data class Rejected(val rejection: BcRoomMutationRejection) : RoomAdministrationFormFailure
    data class OutcomeUnknown(val reason: BcRoomMutationUnknownReason) : RoomAdministrationFormFailure
    data object Failed : RoomAdministrationFormFailure
}

data class RoomAdministrationFormState(
    val visible: Boolean,
    val busy: Boolean,
    val canSubmit: Boolean,
    val draft: RoomAdministrationDraft,
    val backgroundNames: List<String>,
    val base: BcRoomEditBase?,
    val mode: RoomAdministrationFormMode,
    val validation: BcRoomMutationRejection? = null,
    val failure: RoomAdministrationFormFailure? = null,
)

/** Owns one draft and one wait; hiding the sheet never cancels a server write. */
class RoomAdministrationFormController(
    private val rooms: BcSessionRooms,
    private val connectionStatus: ConnectionStatusReader,
    private val scope: CoroutineScope,
    private val backgroundNamesProvider: (BcRoomSpace) -> List<String> = { emptyList() },
    private val onCompleted: (BcRoom) -> Unit = {},
) {
    private val lock = Any()
    private val mutableState = MutableStateFlow<RoomAdministrationFormState?>(null)
    val state: StateFlow<RoomAdministrationFormState?> = mutableState.asStateFlow()
    private var owner: FormOwner? = null
    private var revision = 0L
    private var activeSubmission: Submission? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            connectionStatus.snapshot.collect { snapshot ->
                synchronized(lock) {
                    val current = mutableState.value ?: return@synchronized
                    if (owner?.memberNumber != snapshot.account?.memberNumber && snapshot.account != null) {
                        revision += 1
                        owner = null
                        mutableState.value = null
                    } else {
                        mutableState.value = project(current, snapshot)
                    }
                }
            }
        }
    }

    fun openCreate(space: BcRoomSpace) = open(RoomAdministrationFormMode.Create, space)

    fun openEdit() = open(RoomAdministrationFormMode.Edit, BcRoomSpace.Mixed)

    private fun open(mode: RoomAdministrationFormMode, space: BcRoomSpace) {
        synchronized(lock) {
            val snapshot = connectionStatus.snapshot.value
            val current = mutableState.value
            if (current != null && activeSubmission != null) {
                mutableState.value = project(current.copy(visible = true), snapshot)
                return
            }
            val nextOwner = FormOwner.from(snapshot)
            val base = if (mode == RoomAdministrationFormMode.Edit) snapshot.currentRoom?.adminSettings?.base else null
            val sameDraft = current != null && current.mode == mode && owner?.memberNumber == nextOwner.memberNumber &&
                (mode == RoomAdministrationFormMode.Create || owner?.roomName == snapshot.currentRoom?.name?.value)
            val formSpace = when {
                mode == RoomAdministrationFormMode.Edit -> snapshot.roomSpace()
                sameDraft && current != null -> current.draft.space
                else -> space
            }
            val backgrounds = backgroundNamesProvider(formSpace).distinct()
            val draft = if (sameDraft && current != null) {
                current.draft
            } else if (base != null) {
                base.settings.toDraft(formSpace)
            } else {
                RoomAdministrationDraft(
                    background = "MainHall".takeIf { it in backgrounds } ?: backgrounds.firstOrNull().orEmpty(),
                    space = formSpace,
                )
            }
            val failure = current?.failure?.takeIf { sameDraft && owner?.sameSession(nextOwner) == true }
            owner = nextOwner
            revision += 1
            mutableState.value = project(
                RoomAdministrationFormState(true, activeSubmission != null, false, draft, backgrounds, if (sameDraft) current?.base else base, mode, failure = failure),
                snapshot,
            )
        }
    }

    fun changeDraft(draft: RoomAdministrationDraft) {
        synchronized(lock) {
            val current = mutableState.value ?: return
            if (activeSubmission != null) return
            // An unknown write may still complete. Editing is safe; sending on that session is not.
            val failure = current.failure.takeIf { it is RoomAdministrationFormFailure.OutcomeUnknown }
            val backgrounds = if (current.mode == RoomAdministrationFormMode.Create && draft.space != current.draft.space) {
                backgroundNamesProvider(draft.space).distinct()
            } else current.backgroundNames
            val nextDraft = if (current.mode == RoomAdministrationFormMode.Create && draft.space != current.draft.space &&
                draft.background == current.draft.background && draft.background !in backgrounds) {
                draft.copy(background = "MainHall".takeIf { it in backgrounds } ?: backgrounds.firstOrNull().orEmpty())
            } else draft
            mutableState.value = project(current.copy(draft = nextDraft, backgroundNames = backgrounds, failure = failure), connectionStatus.snapshot.value)
        }
    }

    fun close() {
        synchronized(lock) {
            mutableState.value = mutableState.value?.copy(visible = false)
        }
    }

    /** Explicitly discards unsaved edits and takes the current authoritative edit base. */
    fun reloadCurrentSettings() {
        synchronized(lock) {
            val current = mutableState.value ?: return
            if (activeSubmission != null || current.mode != RoomAdministrationFormMode.Edit) return
            val snapshot = connectionStatus.snapshot.value
            val base = snapshot.currentRoom?.adminSettings?.base ?: return
            val nextOwner = FormOwner.from(snapshot)
            val failure = current.failure.takeIf {
                it is RoomAdministrationFormFailure.OutcomeUnknown && owner?.sameSession(nextOwner) == true
            }
            owner = nextOwner
            revision += 1
            mutableState.value = project(
                current.copy(
                    draft = base.settings.toDraft(snapshot.roomSpace()),
                    base = base,
                    failure = failure,
                    backgroundNames = backgroundNamesProvider(snapshot.roomSpace()).distinct(),
                ),
                snapshot,
            )
        }
    }

    fun submit() {
        val submission = synchronized(lock) {
            val current = mutableState.value ?: return
            val snapshot = connectionStatus.snapshot.value
            val projected = project(current, snapshot)
            mutableState.value = projected
            if (!projected.canSubmit || activeSubmission != null) return
            val settings = projected.draft.toSettings() ?: return
            val command = if (projected.mode == RoomAdministrationFormMode.Create) {
                when (val validation = validateBcRoomCreate(BcRoomCreateCommand(settings, projected.draft.space), projected.backgroundNames.toSet())) {
                    is BcRoomCommandValidation.Valid -> validation.command
                    is BcRoomCommandValidation.Invalid -> return
                }
            } else {
                when (val validation = validateBcRoomUpdate(BcRoomUpdateCommand(projected.base ?: return, settings), projected.backgroundNames.toSet())) {
                    is BcRoomCommandValidation.Valid -> validation.command
                    is BcRoomCommandValidation.Invalid -> return
                }
            }
            Submission(revision, owner ?: return, command).also {
                activeSubmission = it
                mutableState.value = project(projected.copy(failure = null), snapshot)
            }
        }
        scope.launch {
            var completedRoom: BcRoom? = null
            try {
                val room = when (val command = submission.command) {
                    is BcRoomCreateCommand -> rooms.createRoom(command)
                    is BcRoomUpdateCommand -> rooms.updateRoom(command)
                    else -> error("Unsupported room command")
                }
                synchronized(lock) {
                    if (isCurrent(submission) && completionStillOwned(submission, room)) {
                        mutableState.value = null
                        owner = null
                        revision += 1
                        completedRoom = room
                    }
                }
            } catch (failure: Exception) {
                synchronized(lock) {
                    if (isCurrent(submission) && submission.owner.sameSession(FormOwner.from(connectionStatus.snapshot.value))) {
                        val current = mutableState.value ?: return@synchronized
                        mutableState.value = current.copy(
                            failure = when (failure) {
                                is BcRoomMutationRejectedException -> RoomAdministrationFormFailure.Rejected(failure.rejection)
                                is BcRoomMutationOutcomeUnknownException -> RoomAdministrationFormFailure.OutcomeUnknown(failure.reason)
                                is CancellationException -> RoomAdministrationFormFailure.OutcomeUnknown(BcRoomMutationUnknownReason.Cancelled)
                                else -> RoomAdministrationFormFailure.Failed
                            },
                        )
                    }
                }
                if (failure is CancellationException) throw failure
            } finally {
                releaseSubmission(submission)
            }
            completedRoom?.let(onCompleted)
        }.invokeOnCompletion { releaseSubmission(submission) }
    }

    private fun releaseSubmission(submission: Submission) {
        synchronized(lock) {
            if (activeSubmission !== submission) return
            activeSubmission = null
            mutableState.value?.let { current ->
                mutableState.value = project(current, connectionStatus.snapshot.value)
            }
        }
    }

    private fun isCurrent(submission: Submission): Boolean =
        submission.revision == revision && submission.owner == owner

    private fun completionStillOwned(submission: Submission, room: BcRoom): Boolean {
        val snapshot = connectionStatus.snapshot.value
        val currentOwner = FormOwner.from(snapshot)
        if (!snapshot.isVerifiedOnline || !submission.owner.sameSession(currentOwner)) return false
        return if (submission.command is BcRoomUpdateCommand) {
            snapshot.roomPresenceEpoch == submission.owner.roomPresenceEpoch &&
                snapshot.currentRoom?.name?.value in setOf(submission.owner.roomName, room.name.value)
        } else {
            snapshot.currentRoom?.name == room.name ||
                snapshot.currentRoom == null && snapshot.roomPresenceEpoch == submission.owner.roomPresenceEpoch
        }
    }

    private fun project(current: RoomAdministrationFormState, snapshot: BcSessionSnapshot): RoomAdministrationFormState {
        val currentOwner = FormOwner.from(snapshot)
        val rejection = when {
            !snapshot.isVerifiedOnline -> BcRoomMutationRejection(BcRoomMutationRejectionReason.ConnectionUnavailable)
            owner?.sameSession(currentOwner) != true -> BcRoomMutationRejection(BcRoomMutationRejectionReason.AccountUnavailable)
            current.failure is RoomAdministrationFormFailure.OutcomeUnknown -> BcRoomMutationRejection(BcRoomMutationRejectionReason.PendingResponse)
            current.mode == RoomAdministrationFormMode.Create && snapshot.currentRoom != null -> BcRoomMutationRejection(BcRoomMutationRejectionReason.AlreadyInRoom)
            current.mode == RoomAdministrationFormMode.Create && snapshot.roomPresenceEpoch != owner?.roomPresenceEpoch ->
                BcRoomMutationRejection(BcRoomMutationRejectionReason.RoomChanged)
            current.mode == RoomAdministrationFormMode.Edit &&
                (snapshot.roomPresenceEpoch != owner?.roomPresenceEpoch || snapshot.currentRoom?.name?.value != owner?.roomName) ->
                BcRoomMutationRejection(BcRoomMutationRejectionReason.RoomChanged)
            current.mode == RoomAdministrationFormMode.Edit && snapshot.currentRoom?.adminSettings == null ->
                BcRoomMutationRejection(BcRoomMutationRejectionReason.SettingsUnavailable)
            current.mode == RoomAdministrationFormMode.Edit &&
                current.base?.roomPresenceEpoch != snapshot.currentRoom?.adminSettings?.base?.roomPresenceEpoch ->
                BcRoomMutationRejection(BcRoomMutationRejectionReason.RoomChanged)
            current.mode == RoomAdministrationFormMode.Edit && snapshot.account?.memberNumber !in
                snapshot.currentRoom?.adminSettings?.adminMemberNumbers.orEmpty() ->
                BcRoomMutationRejection(BcRoomMutationRejectionReason.NotAdministrator)
            activeSubmission == null && snapshot.state != BcSessionState.Ready -> BcRoomMutationRejection(BcRoomMutationRejectionReason.Busy)
            else -> validate(current, snapshot)
        }
        return current.copy(busy = activeSubmission != null, canSubmit = activeSubmission == null && rejection == null, validation = rejection)
    }

    private fun validate(current: RoomAdministrationFormState, snapshot: BcSessionSnapshot): BcRoomMutationRejection? {
        val settings = current.draft.toSettings()
            ?: return BcRoomMutationRejection(BcRoomMutationRejectionReason.InvalidMemberLimit, BcRoomBasicField.MemberLimit)
        val validation = if (current.mode == RoomAdministrationFormMode.Create) {
            validateBcRoomCreate(BcRoomCreateCommand(settings, current.draft.space), current.backgroundNames.toSet())
        } else {
            val base = current.base ?: return BcRoomMutationRejection(BcRoomMutationRejectionReason.SettingsUnavailable)
            val live = snapshot.currentRoom?.adminSettings?.base?.settings
            if (live != null && BcRoomUpdateCommand(base, settings).conflictingFields(live).isNotEmpty()) {
                return BcRoomMutationRejection(BcRoomMutationRejectionReason.EditConflict)
            }
            validateBcRoomUpdate(BcRoomUpdateCommand(base, settings), current.backgroundNames.toSet())
        }
        return (validation as? BcRoomCommandValidation.Invalid)?.rejection
    }

    private data class FormOwner(
        val memberNumber: Int?,
        val accountGeneration: Long,
        val sessionId: String?,
        val roomPresenceEpoch: Long,
        val roomName: String?,
    ) {
        fun sameSession(other: FormOwner): Boolean = memberNumber == other.memberNumber &&
            accountGeneration == other.accountGeneration && sessionId == other.sessionId

        companion object {
            fun from(snapshot: BcSessionSnapshot) = FormOwner(
                snapshot.account?.memberNumber,
                snapshot.relationships.accountGeneration,
                snapshot.selfRuleLogSessionId,
                snapshot.roomPresenceEpoch,
                snapshot.currentRoom?.name?.value,
            )
        }
    }

    private data class Submission(val revision: Long, val owner: FormOwner, val command: Any)
}

private fun RoomAdministrationDraft.toSettings(): BcRoomBasicSettings? {
    val limit = memberLimit.takeIf { it.isNotEmpty() && it.all { digit -> digit in '0'..'9' } }?.toIntOrNull() ?: return null
    return BcRoomBasicSettings(name, description, limit, background, language, visibility, access)
}

private fun BcRoomBasicSettings.toDraft(space: BcRoomSpace) =
    RoomAdministrationDraft(name, description, memberLimit.toString(), background, language, visibility, access, space)

private fun BcSessionSnapshot.roomSpace(): BcRoomSpace =
    BcRoomSpace.entries.firstOrNull { it.wireValue == currentRoom?.space } ?: BcRoomSpace.Mixed
