package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatMaxLength
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectedException
import dev.vivian.mobilebc.domain.bcspeech.bcWhisperSelectionRejection
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.messaging.replyReferenceOrNull
import dev.vivian.mobilebc.domain.session.chat.BcSessionChat
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.estimatedServerTimeMillis
import dev.vivian.mobilebc.domain.session.timeline.BcMessageSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ChatComposerMode {
    RoomMessage,
    RoomEmote,
    CustomAction,
    DirectMessage,
    Whisper,
}

data class ChatComposerModeOption(
    val mode: ChatComposerMode,
    val enabled: Boolean,
)

enum class ChatComposerDisabledReason {
    NotInRoom,
    Reconnecting,
    ConnectionStale,
    VerifyingConnection,
    DirectMessageUnavailable,
}

enum class ChatComposerValidationError {
    InvalidWhisperCommand,
    WhisperTargetUnavailable,
    EmptyEmote,
    UnsupportedRoomCommand,
    MessageTooLong,
    ReplyUnavailable,
    ReplyModeUnsupported,
    SendFailed,
    WhisperTargetReset,
}

sealed interface ChatComposerState {
    data class Ready(
        val draft: String,
        val mode: ChatComposerMode,
        val availableModes: List<ChatComposerModeOption>,
        val canSend: Boolean,
        val errorMessage: String? = null,
        val validationError: ChatComposerValidationError? = null,
        val modeAvailable: Boolean = true,
        val roomChatRejection: BcRoomChatRejection? = null,
    ) : ChatComposerState

    data class Disabled(
        val reason: ChatComposerDisabledReason,
    ) : ChatComposerState

    data class SelectingMessages(
        val count: Int,
    ) : ChatComposerState

    data class Sending(
        val draft: String,
        val mode: ChatComposerMode,
    ) : ChatComposerState
}

data class ChatUiState(
    val conversationId: BcConversationId,
    val title: String,
    val subtitle: String,
    val messages: List<BcMessage> = emptyList(),
    val canLoadOlderMessages: Boolean = false,
    val directTarget: Int? = null,
    val whisperTarget: Int? = null,
    val whisperTargetName: String? = null,
    val whisperTargets: List<ChatWhisperTarget> = emptyList(),
    val roomUnavailableMessage: String? = null,
    val persistenceFailure: BcTimelinePersistenceFailure? = null,
    val showRoomDetails: Boolean = false,
    val isCurrentRoom: Boolean = false,
    val reply: ChatReplySelection? = null,
    val replyPreviews: Map<String, ChatReplyPreview> = emptyMap(),
    val replyableMessageIds: Set<String> = emptySet(),
    val composer: ChatComposerState,
)

data class ChatWhisperTarget(val memberNumber: Int, val displayName: String)

data class ChatRoomDraft(val text: String = "", val reply: ChatReplySelection? = null)

data class ChatWhisperSelection(
    val ownerMemberNumber: Int,
    val accountGeneration: Long,
    val selfRuleLogSessionId: String?,
    val roomPresenceEpoch: Long,
    val resetRevision: Long,
)

data class ChatInputState(
    val text: String = "",
    val requestedComposerMode: ChatComposerMode = ChatComposerMode.RoomMessage,
    val pendingSend: ChatComposerState.Sending? = null,
    val sendErrorMessage: String? = null,
    val validationError: ChatComposerValidationError? = null,
    val reply: ChatReplySelection? = null,
    val roomChatRejection: BcRoomChatRejection? = null,
    val whisperSelection: ChatWhisperSelection? = null,
    val roomWhisperTarget: Int? = null,
    val roomDrafts: Map<Int?, ChatRoomDraft> = emptyMap(),
)

class ChatViewModel(
    private val conversationId: BcConversationId,
    private val chat: BcSessionChat,
    private val timeline: BcSessionTimeline,
    private val connectionStatus: ConnectionStatusReader,
    private val messageCoordinator: BcSessionMessageCoordinator = BcSessionMessageCoordinator(),
    scope: CoroutineScope,
    private val inputState: MutableStateFlow<ChatInputState> = MutableStateFlow(ChatInputState()),
) : AutoCloseable {
    private val viewModelJob = SupervisorJob(scope.coroutineContext[Job])
    private val viewModelScope = CoroutineScope(scope.coroutineContext + viewModelJob)
    private val sendingScope = scope
    val history = ChatHistoryViewModel(conversationId, timeline, connectionStatus, viewModelScope)
    private val historyLimit = MutableStateFlow(500)

    fun loadOlderMessages() {
        if (uiState.value.canLoadOlderMessages) historyLimit.value += 500
    }

    private val visibleOwners = MutableStateFlow<Set<Any>>(emptySet())
    private val directTarget: Int? = parseDirectTarget(conversationId)

    init {
        val environment = connectionStatus.snapshot.value
        val modes = availableComposerModes(environment)
        if (modes.none { it.mode == inputState.value.requestedComposerMode }) {
            val mode = (modes.firstOrNull { it.enabled } ?: modes.first()).mode
            val rejection = if (mode == ChatComposerMode.Whisper && modes.any { it.mode == mode && it.enabled }) {
                whisperSelectionRejection(environment)
            } else null
            inputState.value = inputState.value.copy(
                requestedComposerMode = mode,
                roomChatRejection = rejection,
                whisperSelection = if (mode == ChatComposerMode.Whisper && rejection == null &&
                    modes.any { it.mode == mode && it.enabled }
                ) whisperSelection(environment) else inputState.value.whisperSelection,
            )
        }
    }

    private data class TimelineScope(val owner: Int?, val generation: Long, val roomName: String?)
    private data class TimelineBatch(
        val scope: TimelineScope,
        val messages: List<BcMessage>,
        val canLoadOlder: Boolean = false,
    )

    private data class MessagePresentationKey(
        val messages: List<BcMessage>,
        val characters: List<BcCharacterSnapshot>,
        val replyRoom: String?,
        val replyMembers: Set<Int>,
    )

    private data class MessagePresentation(
        val messages: List<BcMessage>,
        val replyPreviews: Map<String, ChatReplyPreview>,
        val replyableMessageIds: Set<String>,
    )

    private var presentationKey: MessagePresentationKey? = null
    private var presentation: MessagePresentation? = null

    private fun messagePresentation(messages: List<BcMessage>, environment: BcSessionSnapshot): MessagePresentation {
        val key = MessagePresentationKey(
            messages,
            environment.roomCharacters,
            environment.currentRoom?.name?.value.takeIf { roomConnectionAvailable(environment) },
            environment.roomMembers.mapTo(mutableSetOf()) { it.memberNumber },
        )
        if (presentationKey == key) return checkNotNull(presentation)
        val characters = key.characters.associateBy { it.memberNumber }
        val updated = MessagePresentation(
            messages = messages.map { message ->
                val live = characters[message.sender.memberNumber]
                if (live == null || message.sender.labelColor == live.labelColor) message else message.copy(sender = message.sender.copy(
                    labelColor = live.labelColor,
                ))
            },
            replyPreviews = chatReplyPreviews(messages),
            replyableMessageIds = if (key.replyRoom != null) {
                messages.filter {
                    it.replyReferenceOrNull(key.replyRoom) != null &&
                        (it.transport != BcMessageTransport.RoomWhisper ||
                            parseDirectTarget(it.conversationId) in key.replyMembers)
                }.mapTo(mutableSetOf()) { it.chatUiKey }
            } else emptySet(),
        )
        presentationKey = key
        presentation = updated
        return updated
    }

    private fun timelineScope(environment: BcSessionSnapshot) = TimelineScope(
        owner = environment.account?.memberNumber,
        generation = environment.relationships.accountGeneration,
        roomName = environment.currentRoom?.name?.value?.takeIf {
            directTarget == null && messageCoordinator.isCurrentRoomConversation(conversationId, environment.currentRoom)
        },
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val messageBatch = connectionStatus.snapshot
        .map(::timelineScope)
        .distinctUntilChanged()
        .flatMapLatest { scope ->
            if (scope.owner == null) {
                flowOf(TimelineBatch(scope, emptyList()))
            } else {
                historyLimit.value = 500
                historyLimit.flatMapLatest { limit ->
                    combine(
                        timeline.observeMessages(conversationId, limit + 1),
                        scope.roomName?.let { timeline.observeRoomWhispers(it, limit + 1) } ?: flowOf(BcMessageSnapshot()),
                    ) { publicSnapshot, whisperSnapshot ->
                        val publicRows = publicSnapshot.rowsFor(scope.owner, scope.generation)
                        val whisperRows = whisperSnapshot.rowsFor(scope.owner, scope.generation)
                        val rows = if (directTarget != null) publicRows else mergeRoomTimeline(
                            publicRows, whisperRows, conversationId, scope.roomName, scope.owner,
                        ).let(messageCoordinator::deduplicateMessages)
                        TimelineBatch(scope, rows.takeLast(limit), rows.size > limit)
                    }
                }.onStart { emit(TimelineBatch(scope, emptyList())) }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = TimelineBatch(timelineScope(connectionStatus.snapshot.value), emptyList()),
        )

    val uiState: StateFlow<ChatUiState> = combine(
        messageBatch,
        timeline.conversations,
        connectionStatus.snapshot,
        inputState,
        timeline.persistenceFailure,
    ) { batch, conversation, environment, input, failure ->
        buildUiState(
            // Owner/room changes can precede the next database emission. Never relabel old rows
            // as the new account's data, including received whispers without an explicit target.
            messages = batch.messages.takeIf { batch.scope == timelineScope(environment) }.orEmpty(),
            conversation = conversation.rowsFor(environment).firstOrNull { it.id == conversationId },
            environment = environment,
            input = input,
            persistenceFailure = failure,
        ).copy(canLoadOlderMessages = batch.scope == timelineScope(environment) && batch.canLoadOlder)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = run {
            val environment = connectionStatus.snapshot.value
            buildUiState(
                messages = emptyList(),
                conversation = timeline.conversations.value.rowsFor(environment).firstOrNull { it.id == conversationId },
                environment = environment,
                input = inputState.value,
                persistenceFailure = timeline.persistenceFailure.value,
            )
        },
    )

    init {
        combine(messageBatch, visibleOwners, connectionStatus.snapshot) { batch, owners, environment ->
            // A direct conversation also contains account messages and whispers from other rooms.
            // Its cumulative cursor cannot safely mark only the projected room whispers as read.
            batch.messages.lastOrNull { it.conversationId == conversationId }
                .takeIf { owners.isNotEmpty() && batch.scope.owner != null && batch.scope == timelineScope(environment) }
                ?.let { batch.scope to it }
        }
            .distinctUntilChangedBy { candidate -> candidate?.let { (scope, message) -> Triple(scope, message.timestamp, message.id) } }
            .filterNotNull()
            .onEach { (scope, latest) ->
                timeline.advanceReadCursor(checkNotNull(scope.owner), scope.generation, conversationId, latest)
            }
            .launchIn(viewModelScope)
    }

    fun activate(owner: Any = this) {
        if (!viewModelJob.isActive) return
        if (visibleOwners.value.isEmpty()) timeline.selectConversation(conversationId, this)
        visibleOwners.value = visibleOwners.value + owner
    }

    fun deactivate(owner: Any = this) {
        if (owner !in visibleOwners.value) return
        visibleOwners.value = visibleOwners.value - owner
        if (visibleOwners.value.isEmpty()) timeline.clearSelectedConversation(this)
    }

    override fun close() {
        if (visibleOwners.value.isNotEmpty()) {
            visibleOwners.value = emptySet()
            timeline.clearSelectedConversation(this)
        }
        viewModelScope.cancel()
    }

    fun onTextChange(value: String) {
        inputState.value = inputState.value.copy(
            text = value,
            sendErrorMessage = null,
            validationError = null,
            roomChatRejection = null,
        )
    }

    fun onWhisperTargetChange(memberNumber: Int) {
        if (directTarget != null || inputState.value.pendingSend != null) return
        val environment = connectionStatus.snapshot.value
        if (!roomConversationInputAvailable(environment) || memberNumber == environment.account?.memberNumber ||
            environment.roomMembers.none { it.memberNumber == memberNumber }
        ) return
        whisperSelectionRejection(environment, memberNumber)?.let { rejection ->
            inputState.value = inputState.value.copy(
                sendErrorMessage = null, validationError = null, roomChatRejection = rejection,
            )
            return
        }
        switchRoomDraft(memberNumber, ChatComposerMode.Whisper)
        inputState.value = inputState.value.copy(whisperSelection = whisperSelection(environment))
    }

    fun onReturnToPublic() {
        if (directTarget != null || inputState.value.pendingSend != null) return
        switchRoomDraft(null, ChatComposerMode.RoomMessage)
    }

    private fun switchRoomDraft(target: Int?, mode: ChatComposerMode) {
        val input = inputState.value
        val drafts = input.roomDrafts + (input.roomWhisperTarget to ChatRoomDraft(input.text, input.reply))
        val restored = if (target == input.roomWhisperTarget) ChatRoomDraft(input.text, input.reply)
            else drafts[target] ?: ChatRoomDraft()
        inputState.value = input.copy(
            text = restored.text,
            reply = restored.reply,
            requestedComposerMode = mode,
            roomWhisperTarget = target,
            roomDrafts = drafts,
            sendErrorMessage = null,
            validationError = null,
            roomChatRejection = null,
        )
    }

    fun onComposerModeChange(mode: ChatComposerMode) {
        val ready = buildComposerState(connectionStatus.snapshot.value, inputState.value)
            as? ChatComposerState.Ready ?: return
        if (ready.availableModes.none { it.mode == mode && it.enabled }) return
        val environment = connectionStatus.snapshot.value
        if (mode == ChatComposerMode.Whisper) {
            val target = directTarget ?: inputState.value.roomWhisperTarget ?: return
            if (directTarget == null) {
                onWhisperTargetChange(target)
                return
            }
            whisperSelectionRejection(environment, target)?.let { rejection ->
                inputState.value = inputState.value.copy(
                    sendErrorMessage = null, validationError = null, roomChatRejection = rejection,
                )
                return
            }
        } else if (directTarget == null && inputState.value.roomWhisperTarget != null) {
            switchRoomDraft(null, mode)
            return
        }
        inputState.value = inputState.value.copy(
            requestedComposerMode = mode,
            sendErrorMessage = null,
            validationError = null,
            roomChatRejection = null,
            whisperSelection = if (mode == ChatComposerMode.Whisper) whisperSelection(environment)
            else inputState.value.whisperSelection,
        )
    }

    fun onReply(messageId: String) {
        val environment = connectionStatus.snapshot.value
        if (!roomConnectionAvailable(environment)) return
        val batch = messageBatch.value
        if (batch.scope != timelineScope(environment)) return
        val message = batch.messages.firstOrNull { it.chatUiKey == messageId }
            ?: batch.messages.singleOrNull { it.id.value == messageId } ?: return
        val reference = message.replyReferenceOrNull(environment.currentRoom?.name?.value.orEmpty()) ?: return
        val pendingSend = inputState.value.pendingSend
        if (pendingSend != null && (
                (reference.transport == BcMessageTransport.RoomWhisper) !=
                    (pendingSend.mode == ChatComposerMode.Whisper) ||
                (reference.transport == BcMessageTransport.RoomWhisper &&
                    parseDirectTarget(message.conversationId) != (directTarget ?: inputState.value.roomWhisperTarget))
            )
        ) return
        if (reference.transport == BcMessageTransport.RoomWhisper) {
            val target = parseDirectTarget(message.conversationId) ?: return
            if (environment.roomMembers.none { it.memberNumber == target }) return
            if (directTarget == null) {
                onWhisperTargetChange(target)
                if (inputState.value.roomWhisperTarget != target || inputState.value.roomChatRejection != null) return
            } else {
                if (target != directTarget) return
                whisperSelectionRejection(environment, target)?.let { rejection ->
                    inputState.value = inputState.value.copy(
                        sendErrorMessage = null, validationError = null, roomChatRejection = rejection,
                    )
                    return
                }
            }
        } else if (directTarget == null && inputState.value.roomWhisperTarget != null) {
            onReturnToPublic()
        }
        val mode = if (reference.transport == BcMessageTransport.RoomWhisper) {
            ChatComposerMode.Whisper
        } else {
            inputState.value.requestedComposerMode.takeIf {
                it == ChatComposerMode.RoomMessage || it == ChatComposerMode.RoomEmote
            } ?: ChatComposerMode.RoomMessage
        }
        inputState.value = inputState.value.copy(
            reply = ChatReplySelection(
                reference = reference,
                author = message.sender.displayName,
                text = message.replyPreviewText(),
                ownerMemberNumber = checkNotNull(environment.account).memberNumber,
                accountGeneration = environment.relationships.accountGeneration,
                roomPresenceEpoch = environment.roomPresenceEpoch,
            ),
            requestedComposerMode = mode,
            sendErrorMessage = null,
            validationError = null,
            roomChatRejection = null,
            whisperSelection = if (mode == ChatComposerMode.Whisper) whisperSelection(environment)
            else inputState.value.whisperSelection,
        )
    }

    fun onCancelReply() {
        inputState.value = inputState.value.copy(
            reply = null,
            sendErrorMessage = null,
            validationError = null,
            roomChatRejection = null,
        )
    }

    fun send() {
        val environment = connectionStatus.snapshot.value
        val current = buildComposerState(environment, inputState.value)
            as? ChatComposerState.Ready ?: return
        if (!current.canSend) return
        val outgoing = current.draft.trim()
        val roomInput = if (current.mode == ChatComposerMode.RoomMessage) {
            parseRoomChatInput(outgoing, environment.privateChatState?.preferences?.muStylePoses == true)
        } else null
        val outgoingRoomText = when (roomInput) {
            is RoomChatInput.Chat -> roomInput.text
            is RoomChatInput.Emote -> roomInput.text
            is RoomChatInput.Whisper -> roomInput.text
            else -> outgoing.takeIf { current.mode == ChatComposerMode.RoomEmote || current.mode == ChatComposerMode.Whisper }
        }
        val validationError = if (outgoingRoomText != null && outgoingRoomText.length > BcRoomChatMaxLength) {
            ChatComposerValidationError.MessageTooLong
        } else when (roomInput) {
            is RoomChatInput.Invalid -> roomInput.error
            is RoomChatInput.Whisper -> if (
                environment.roomMembers.none { it.memberNumber == roomInput.targetMemberNumber }
            ) {
                ChatComposerValidationError.WhisperTargetUnavailable
            } else {
                null
            }
            else -> null
        }
        if (validationError != null) {
            inputState.value = inputState.value.copy(
                validationError = validationError,
                sendErrorMessage = null,
                roomChatRejection = null,
            )
            return
        }

        val selectedReply = inputState.value.reply
        val submittedWhisperSelection = inputState.value.whisperSelection
        val submittedWhisperTarget = directTarget ?: inputState.value.roomWhisperTarget
        val replyTo = selectedReply?.reference
        val pendingSend = ChatComposerState.Sending(current.draft, current.mode)
        inputState.value = inputState.value.copy(
            pendingSend = pendingSend,
            sendErrorMessage = null,
            validationError = null,
            roomChatRejection = null,
        )
        sendingScope.launch {
            try {
                if (current.mode == ChatComposerMode.Whisper &&
                    (!whisperSelectionValid(submittedWhisperSelection, connectionStatus.snapshot.value) ||
                        connectionStatus.snapshot.value.roomMembers.none { it.memberNumber == submittedWhisperTarget })
                ) {
                    if (inputState.value.pendingSend === pendingSend) {
                        inputState.value = inputState.value.copy(
                            pendingSend = null,
                            validationError = ChatComposerValidationError.WhisperTargetReset,
                        )
                    }
                    return@launch
                }
                selectedReply?.let { reply ->
                    val issue = replyValidationError(reply, current.mode, connectionStatus.snapshot.value)
                    if (issue != null) {
                        if (inputState.value.pendingSend === pendingSend) {
                            inputState.value = inputState.value.copy(pendingSend = null, validationError = issue)
                        }
                        return@launch
                    }
                }
                when (current.mode) {
                    ChatComposerMode.DirectMessage ->
                        chat.sendBeep(checkNotNull(directTarget), outgoing)

                    ChatComposerMode.Whisper ->
                        sendWhisper(checkNotNull(submittedWhisperTarget), outgoing, replyTo)

                    ChatComposerMode.RoomMessage -> when (roomInput) {
                        is RoomChatInput.Chat -> sendChat(roomInput.text, replyTo)
                        is RoomChatInput.Emote -> sendEmote(roomInput.text, replyTo)
                        is RoomChatInput.Whisper -> sendWhisper(roomInput.targetMemberNumber, roomInput.text, replyTo)
                        else -> error("Room input must be validated before sending.")
                    }

                    ChatComposerMode.RoomEmote -> sendEmote(outgoing, replyTo)
                    ChatComposerMode.CustomAction -> chat.sendAction(outgoing)
                }
                val latest = inputState.value
                if (latest.pendingSend !== pendingSend) return@launch
                inputState.value = latest.copy(
                    text = if (latest.text.trim() == outgoing) "" else latest.text,
                    pendingSend = null,
                    reply = latest.reply.takeUnless { it === selectedReply },
                    sendErrorMessage = null,
                    roomChatRejection = null,
                )
            } catch (cancelled: CancellationException) {
                if (inputState.value.pendingSend === pendingSend) {
                    inputState.value = inputState.value.copy(pendingSend = null)
                }
                throw cancelled
            } catch (throwable: Throwable) {
                if (inputState.value.pendingSend === pendingSend) {
                    inputState.value = inputState.value.copy(
                        pendingSend = null,
                        sendErrorMessage = null,
                        validationError = if (throwable is BcRoomChatRejectedException) null
                        else ChatComposerValidationError.SendFailed,
                        roomChatRejection = (throwable as? BcRoomChatRejectedException)?.rejection,
                    )
                }
            }
        }
    }

    private suspend fun sendChat(text: String, replyTo: BcMessageReplyReference?) {
        if (replyTo == null) chat.sendChat(text) else chat.sendChat(text, replyTo)
    }

    private suspend fun sendEmote(text: String, replyTo: BcMessageReplyReference?) {
        if (replyTo == null) chat.sendEmote(text) else chat.sendEmote(text, replyTo)
    }

    private suspend fun sendWhisper(target: Int, text: String, replyTo: BcMessageReplyReference?) {
        if (replyTo == null) chat.sendWhisper(target, text) else chat.sendWhisper(target, text, replyTo)
    }

    private fun buildUiState(
        messages: List<BcMessage>,
        conversation: BcConversation?,
        environment: BcSessionSnapshot,
        input: ChatInputState,
        persistenceFailure: BcTimelinePersistenceFailure?,
    ): ChatUiState {
        val rows = messagePresentation(messages, environment)
        return ChatUiState(
            conversationId = conversationId,
            title = conversation?.title ?: fallbackTitle(environment),
            subtitle = if (directTarget != null && conversation?.hasMessages != true) {
                fallbackSubtitle(environment)
            } else {
                conversation?.subtitle ?: fallbackSubtitle(environment)
            },
            messages = rows.messages,
            directTarget = directTarget,
            whisperTarget = (directTarget ?: input.roomWhisperTarget).takeIf {
                input.requestedComposerMode == ChatComposerMode.Whisper
            },
            whisperTargetName = (directTarget ?: input.roomWhisperTarget)?.takeIf {
                input.requestedComposerMode == ChatComposerMode.Whisper
            }?.let { target -> environment.roomMembers.firstOrNull { it.memberNumber == target }?.name ?: "#$target" },
            whisperTargets = if (directTarget == null &&
                messageCoordinator.isCurrentRoomConversation(conversationId, environment.currentRoom)
            ) environment.roomMembers
                .filter { !it.isSelf && it.memberNumber != environment.account?.memberNumber }
                .map { ChatWhisperTarget(it.memberNumber, it.name) } else emptyList(),
            roomUnavailableMessage = roomUnavailableMessage(environment),
            persistenceFailure = persistenceFailure?.takeIf { it.belongsTo(environment) },
            showRoomDetails = directTarget == null && roomConversationInputAvailable(environment),
            isCurrentRoom = directTarget == null &&
                messageCoordinator.isCurrentRoomConversation(conversationId, environment.currentRoom),
            reply = input.reply,
            replyPreviews = rows.replyPreviews,
            replyableMessageIds = rows.replyableMessageIds,
            composer = buildComposerState(environment, input),
        )
    }

    private fun buildComposerState(
        environment: BcSessionSnapshot,
        input: ChatInputState,
    ): ChatComposerState {
        input.pendingSend?.let { return it.copy(draft = input.text) }
        val availableComposerModes = availableComposerModes(environment, input)
        if (availableComposerModes.none { it.enabled }) {
            return ChatComposerState.Disabled(composerDisabledReason(environment))
        }
        val selectedMode = availableComposerModes
            .firstOrNull { it.mode == input.requestedComposerMode }
            ?: availableComposerModes.first { it.enabled }
        val replyError = input.reply?.let { replyValidationError(it, selectedMode.mode, environment) }
        val whisperSelected = selectedMode.mode != ChatComposerMode.Whisper ||
            whisperSelectionValid(input.whisperSelection, environment)
        val selectionError = ChatComposerValidationError.WhisperTargetReset.takeIf {
            !whisperSelected && input.roomChatRejection == null
        }
        return ChatComposerState.Ready(
            draft = input.text,
            mode = selectedMode.mode,
            availableModes = availableComposerModes,
            canSend = input.text.isNotBlank() && selectedMode.enabled && replyError == null && whisperSelected,
            errorMessage = input.sendErrorMessage,
            validationError = replyError ?: selectionError ?: input.validationError,
            modeAvailable = selectedMode.enabled,
            roomChatRejection = input.roomChatRejection,
        )
    }

    private fun whisperSelectionRejection(
        environment: BcSessionSnapshot,
        target: Int = checkNotNull(directTarget ?: inputState.value.roomWhisperTarget),
    ): BcRoomChatRejection? =
        bcWhisperSelectionRejection(
            self = environment.roomCharacters.firstOrNull { it.memberNumber == environment.account?.memberNumber },
            roomCharacters = environment.roomCharacters,
            privateState = environment.privateChatState,
            serverTimeMillis = environment.estimatedServerTimeMillis(),
            targetMemberNumber = target,
        )

    private fun whisperSelection(environment: BcSessionSnapshot): ChatWhisperSelection = ChatWhisperSelection(
        ownerMemberNumber = checkNotNull(environment.account).memberNumber,
        accountGeneration = environment.relationships.accountGeneration,
        selfRuleLogSessionId = environment.selfRuleLogSessionId,
        roomPresenceEpoch = environment.roomPresenceEpoch,
        resetRevision = environment.whisperTargetResetRevision,
    )

    private fun whisperSelectionValid(selection: ChatWhisperSelection?, environment: BcSessionSnapshot): Boolean =
        selection != null && selection.ownerMemberNumber == environment.account?.memberNumber &&
            selection.accountGeneration == environment.relationships.accountGeneration &&
            selection.selfRuleLogSessionId == environment.selfRuleLogSessionId &&
            selection.roomPresenceEpoch == environment.roomPresenceEpoch &&
            selection.resetRevision == environment.whisperTargetResetRevision

    private fun replyValidationError(
        reply: ChatReplySelection,
        mode: ChatComposerMode,
        environment: BcSessionSnapshot,
    ): ChatComposerValidationError? = when {
        !roomConnectionAvailable(environment) ||
            reply.ownerMemberNumber != environment.account?.memberNumber ||
            reply.accountGeneration != environment.relationships.accountGeneration ||
            reply.roomPresenceEpoch != environment.roomPresenceEpoch ||
            reply.reference.roomName != environment.currentRoom?.name?.value ->
            ChatComposerValidationError.ReplyUnavailable
        mode == ChatComposerMode.DirectMessage || mode == ChatComposerMode.CustomAction ->
            ChatComposerValidationError.ReplyModeUnsupported
        reply.reference.transport == BcMessageTransport.RoomWhisper &&
            (mode != ChatComposerMode.Whisper || parseDirectTarget(reply.reference.conversationId) !=
                (directTarget ?: inputState.value.roomWhisperTarget)) -> ChatComposerValidationError.ReplyModeUnsupported
        else -> null
    }

    private fun availableComposerModes(
        environment: BcSessionSnapshot,
        input: ChatInputState = inputState.value,
    ): List<ChatComposerModeOption> {
        if (directTarget == null) {
            val roomInputAvailable = roomConversationInputAvailable(environment)
            return listOf(
                ChatComposerModeOption(ChatComposerMode.RoomMessage, roomInputAvailable),
                ChatComposerModeOption(ChatComposerMode.RoomEmote, roomInputAvailable),
                ChatComposerModeOption(ChatComposerMode.CustomAction, roomInputAvailable),
            ) + if (input.roomWhisperTarget != null) listOf(
                ChatComposerModeOption(
                    ChatComposerMode.Whisper,
                    roomInputAvailable && input.roomWhisperTarget != environment.account?.memberNumber &&
                        environment.roomMembers.any { it.memberNumber == input.roomWhisperTarget },
                ),
            ) else emptyList()
        }
        val directMessageEnabled = directTarget in environment.friendMemberNumbers &&
            environment.isVerifiedOnline
        val whisperEnabled = environment.roomMembers.any { it.memberNumber == directTarget } &&
            roomConnectionAvailable(environment)
        return listOf(
            ChatComposerModeOption(ChatComposerMode.DirectMessage, directMessageEnabled),
            ChatComposerModeOption(ChatComposerMode.Whisper, whisperEnabled),
        )
    }

    private fun composerDisabledReason(environment: BcSessionSnapshot): ChatComposerDisabledReason = when {
        environment.state is BcSessionState.Reconnecting -> ChatComposerDisabledReason.Reconnecting
        environment.connectionHealth == BcConnectionHealth.Stale -> ChatComposerDisabledReason.ConnectionStale
        environment.connectionHealth == BcConnectionHealth.ConnectedUnverified ->
            ChatComposerDisabledReason.VerifyingConnection
        directTarget != null -> ChatComposerDisabledReason.DirectMessageUnavailable
        else -> ChatComposerDisabledReason.NotInRoom
    }

    private fun roomConnectionAvailable(environment: BcSessionSnapshot): Boolean =
        environment.currentRoom != null &&
            environment.isVerifiedOnline

    private fun roomConversationInputAvailable(environment: BcSessionSnapshot): Boolean =
        environment.currentRoom != null &&
            messageCoordinator.isCurrentRoomConversation(conversationId, environment.currentRoom) &&
            environment.isVerifiedOnline

    private fun fallbackTitle(environment: BcSessionSnapshot): String =
        when {
            directTarget != null -> environment.roomMembers
                .firstOrNull { it.memberNumber == directTarget }
                ?.name
                ?: "#$directTarget"
            messageCoordinator.isCurrentRoomConversation(conversationId, environment.currentRoom) ->
                environment.currentRoom?.name?.value ?: "Current room"
            else -> conversationId.value
        }

    private fun fallbackSubtitle(environment: BcSessionSnapshot): String =
        when {
            directTarget != null -> directConversationStatus(
                messageAvailable = directTarget in environment.friendMemberNumbers,
                whisperAvailable = environment.roomMembers.any { it.memberNumber == directTarget },
                accountInputAvailable =
                    environment.isVerifiedOnline,
                roomInputAvailable = roomConnectionAvailable(environment),
            )
            messageCoordinator.isCurrentRoomConversation(conversationId, environment.currentRoom) ->
                environment.currentRoom?.description ?: "Room conversation"
            messageCoordinator.isRoomConversation(conversationId) -> "Room conversation"
            else -> "Conversation"
        }

    private fun roomUnavailableMessage(environment: BcSessionSnapshot): String? {
        if (directTarget != null || roomConversationInputAvailable(environment)) return null
        return when (composerDisabledReason(environment)) {
            ChatComposerDisabledReason.Reconnecting -> "Reconnecting to BC"
            ChatComposerDisabledReason.ConnectionStale -> "Connection stale"
            ChatComposerDisabledReason.VerifyingConnection -> "Verifying connection"
            ChatComposerDisabledReason.NotInRoom -> if (messageCoordinator.isRoomConversation(conversationId)) {
                "Not in this room"
            } else {
                "Not in a room"
            }
            ChatComposerDisabledReason.DirectMessageUnavailable -> null
        }
    }

    private fun directConversationStatus(
        messageAvailable: Boolean,
        whisperAvailable: Boolean,
        accountInputAvailable: Boolean,
        roomInputAvailable: Boolean,
    ): String = when {
        messageAvailable && whisperAvailable && roomInputAvailable -> "Friend and same room"
        messageAvailable && accountInputAvailable -> "Friend message available"
        messageAvailable -> "Message waits for verified connection"
        whisperAvailable && roomInputAvailable -> "Same room whisper available"
        whisperAvailable -> "Whisper waits for verified room connection"
        else -> "Message needs friend; whisper needs same room"
    }

    private companion object {
        fun parseDirectTarget(conversationId: BcConversationId): Int? =
            conversationId.value
                .substringAfter(':', missingDelimiterValue = "")
                .takeIf {
                    conversationId.value.startsWith("message:") ||
                        conversationId.value.startsWith("whisper:")
                }
                ?.toIntOrNull()
    }
}
