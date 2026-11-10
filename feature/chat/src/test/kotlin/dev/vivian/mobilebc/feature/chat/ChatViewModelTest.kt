package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.chat.BcSessionChat
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.timeline.BcConversationSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcMessageSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceOperation
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @Test
    fun olderMessagesCanBeLoadedBeyondTheInitialWindow() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(id, "Alice")))
        timeline.messages.value = (0 until 1201).map { index ->
            message(id, "message $index").copy(
                id = dev.vivian.mobilebc.core.model.BcMessageId("history-$index"),
                timestamp = Instant.ofEpochSecond(index.toLong()),
            )
        }
        val model = ChatViewModel(id, FakeChat(), timeline, FakeConnection(), scope = backgroundScope)
        runCurrent()
        assertEquals(500, model.uiState.value.messages.size)
        assertTrue(model.uiState.value.canLoadOlderMessages)
        model.loadOlderMessages()
        runCurrent()
        assertEquals(1000, model.uiState.value.messages.size)
        assertTrue(model.uiState.value.canLoadOlderMessages)
        model.loadOlderMessages()
        runCurrent()
        assertEquals(1201, model.uiState.value.messages.size)
        assertEquals("history-0", model.uiState.value.messages.first().id.value)
        assertEquals(false, model.uiState.value.canLoadOlderMessages)
        model.close()
    }

    @Test
    fun composerChangesReuseMessagePresentationAndNewMessagesRefreshIt() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(id, "Alice")))
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        timeline.messages.value = listOf(message(id, "hello"))
        val chat = FakeChat().apply { beforeSend = CompletableDeferred() }
        val model = ChatViewModel(id, chat, timeline, connection, scope = backgroundScope)
        runCurrent()
        val initial = model.uiState.value

        fun assertPresentationReused() {
            org.junit.Assert.assertSame(initial.messages, model.uiState.value.messages)
            org.junit.Assert.assertSame(initial.replyPreviews, model.uiState.value.replyPreviews)
            org.junit.Assert.assertSame(initial.replyableMessageIds, model.uiState.value.replyableMessageIds)
        }
        model.onTextChange("outgoing")
        runCurrent()
        assertPresentationReused()
        model.send()
        runCurrent()
        assertTrue(model.uiState.value.composer is ChatComposerState.Sending)
        assertPresentationReused()
        chat.beforeSend!!.complete(Unit)
        runCurrent()
        assertEquals("", (model.uiState.value.composer as ChatComposerState.Ready).draft)
        assertPresentationReused()

        timeline.messages.value = timeline.messages.value + message(id, "new message")
        runCurrent()
        assertEquals(2, model.uiState.value.messages.size)
        org.junit.Assert.assertNotSame(initial.messages, model.uiState.value.messages)
        model.close()
    }

    @Test
    fun persistedAvatarUpdatesAndRemovalAreNotOverriddenByOlderRoomState() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(id, "Alice")))
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        val cached = message(id, "hello").let {
            it.copy(sender = it.sender.copy(avatarUrl = "https://example.com/old.png"))
        }
        timeline.messages.value = listOf(cached)
        val model = ChatViewModel(id, FakeChat(), timeline, connection, scope = backgroundScope)
        runCurrent()
        assertEquals("https://example.com/old.png", model.uiState.value.messages.single().sender.avatarUrl)
        val live = BcCharacterSnapshot("alice", 1001, "Alice", "Alice", false,
            avatarUrl = "https://example.com/old.png")
        connection.snapshot.value = connection.snapshot.value.copy(roomCharacters = listOf(live))
        timeline.messages.value = listOf(cached.copy(sender = cached.sender.copy(avatarUrl = "https://example.com/new.png")))
        runCurrent()
        assertEquals("https://example.com/new.png", model.uiState.value.messages.single().sender.avatarUrl)
        timeline.messages.value = listOf(cached.copy(sender = cached.sender.copy(avatarUrl = "")))
        runCurrent()
        assertEquals("", model.uiState.value.messages.single().sender.avatarUrl)
        model.close()
    }

    @Test
    fun oldMessageSnapshotsCannotBeRelabeledOrMarkedReadAfterRelogin() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(id, "Alice")))
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        timeline.messages.value = listOf(message(id, "old"))
        val model = ChatViewModel(id, FakeChat(), timeline, connection, scope = backgroundScope)
        model.activate()
        runCurrent()
        assertEquals(1, timeline.readCursorMessages.size)
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.nextUnloaded(),
        )
        runCurrent()
        timeline.messages.value = listOf(message(id, "late old query"))
        runCurrent()
        assertTrue(model.uiState.value.messages.isEmpty())
        assertEquals(1, timeline.readCursorMessages.size)
        timeline.messageOwner.value = 9999 to 2L
        runCurrent()
        assertEquals(1, model.uiState.value.messages.size)
        assertEquals(2, timeline.readCursorMessages.size)
        assertEquals(listOf(9999 to 1L, 9999 to 2L), timeline.readCursorOwners)
        model.close()
    }

    @Test
    fun conversationHeaderWaitsForMetadataFromTheCurrentAccountGeneration() = runTest {
        val id = BcConversationId("message:1001")
        val row = conversation(id, "Old account title")
        val timeline = FakeTimeline(listOf(row))
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        val model = ChatViewModel(id, FakeChat(), timeline, connection, scope = backgroundScope)
        runCurrent()
        assertEquals(row.title, model.uiState.value.title)
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.nextUnloaded(),
        )
        runCurrent()
        assertTrue(model.uiState.value.title != row.title)
        timeline.conversations.value = BcConversationSnapshot(9999, 2, listOf(row.copy(title = "New title")))
        runCurrent()
        assertEquals("New title", model.uiState.value.title)
        model.close()
    }

    @Test
    fun oldLoginFailureIsHiddenUntilCurrentGenerationReportsItsOwnFailure() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(id, "Alice")))
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        val failure = BcTimelinePersistenceFailure(BcTimelinePersistenceOperation.ObserveMessages, 9999, 1, id)
        timeline.persistenceFailure.value = failure
        val model = ChatViewModel(id, FakeChat(), timeline, connection, scope = backgroundScope)
        runCurrent()
        assertEquals(failure, model.uiState.value.persistenceFailure)
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.nextUnloaded(),
        )
        runCurrent()
        assertEquals(null, model.uiState.value.persistenceFailure)
        timeline.persistenceFailure.value = failure.copy(accountGeneration = 2)
        runCurrent()
        assertEquals(timeline.persistenceFailure.value, model.uiState.value.persistenceFailure)
        model.close()
    }

    @Test
    fun outgoingOrHiddenPageCannotDeactivateTheVisibleOwner() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(id, "Alice")))
        val model = ChatViewModel(id, FakeChat(), timeline,
            FakeConnection(friendMemberNumbers = setOf(1001)), scope = backgroundScope)
        val outgoing = Any()
        val incoming = Any()
        model.activate(outgoing)
        model.activate(incoming)
        model.activate(incoming)
        assertEquals(listOf(id), timeline.selectedConversationIds)
        model.deactivate(outgoing)
        model.deactivate(Any())
        timeline.messages.value = listOf(message(id, "visible after transition"))
        runCurrent()
        assertTrue(timeline.clearedConversationIds.isEmpty())
        assertEquals("visible after transition", timeline.readCursorMessages.last().text)
        model.deactivate(incoming)
        assertEquals(listOf(id), timeline.clearedConversationIds)
        timeline.messages.value = listOf(message(id, "hidden after last owner leaves"))
        runCurrent()
        assertEquals("visible after transition", timeline.readCursorMessages.last().text)
        model.close()
    }

    @Test
    fun closedChatCannotBeReactivatedByALateLifecycleEvent() = runTest {
        val id = BcConversationId("message:1001")
        val timeline = FakeTimeline(emptyList())
        val model = ChatViewModel(id, FakeChat(), timeline,
            FakeConnection(friendMemberNumbers = setOf(1001)), scope = backgroundScope)
        model.activate(Any())
        model.close()
        model.activate(Any())
        assertEquals(listOf(id), timeline.selectedConversationIds)
        assertEquals(listOf(id), timeline.clearedConversationIds)
    }

    @Test
    fun preloadedChatWaitsUntilVisibleToReadAndPreservesDraftAcrossPageChanges() = runTest {
        val conversationId = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(conversationId, "Alice")))
        timeline.messages.value = listOf(message(conversationId, "before opening"))
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = FakeChat(),
            timeline = timeline,
            connectionStatus = FakeConnection(friendMemberNumbers = setOf(1001)),
            scope = backgroundScope,
        )
        runCurrent()
        assertTrue(timeline.selectedConversationIds.isEmpty())
        assertTrue(timeline.readCursorMessages.isEmpty())

        viewModel.activate()
        viewModel.onTextChange("unsent draft")
        runCurrent()
        assertEquals("before opening", timeline.readCursorMessages.last().text)
        viewModel.deactivate()
        timeline.messages.value = timeline.messages.value + message(conversationId, "while hidden")
        runCurrent()
        assertEquals("before opening", timeline.readCursorMessages.last().text)

        viewModel.activate()
        runCurrent()
        assertEquals("while hidden", timeline.readCursorMessages.last().text)
        assertEquals("unsent draft", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    @Test
    fun muStylePosePreferenceRoutesColonWithoutRemovingTrailingStar() = runTest {
        val chat = FakeChat()
        val connection = FakeConnection(currentRoom = room("Test room"))
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = backgroundScope,
        )
        viewModel.onTextChange(":waves*")
        viewModel.send()
        runCurrent()
        assertEquals(listOf(":waves*"), chat.sentChats)

        connection.snapshot.value = connection.snapshot.value.copy(
            privateChatState = BcPrivateChatState(null, emptyList(), BcPrivateChatPreferences(muStylePoses = true)),
        )
        viewModel.onTextChange(":waves*")
        viewModel.send()
        runCurrent()
        assertEquals(listOf("waves*"), chat.sentEmotes)
        assertEquals("", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    @Test
    fun whisperRuleRejectsTargetSelectionButDoesNotBecomeASendRestriction() = runTest {
        val chat = FakeChat()
        val connection = ownedWhisperConnection()
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = backgroundScope,
        )
        viewModel.onTextChange("keep this draft")
        viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        runCurrent()
        val rejected = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals(ChatComposerMode.DirectMessage, rejected.mode)
        assertEquals("keep this draft", rejected.draft)
        assertEquals(BcRoomChatRejectionReason.WhisperBlockedByOwner, rejected.roomChatRejection?.reason)
        assertTrue(chat.sentWhispers.isEmpty())

        val privateState = checkNotNull(connection.snapshot.value.privateChatState)
        connection.snapshot.value = connection.snapshot.value.copy(privateChatState = privateState.copy(ruleLog = emptyList()))
        viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        // A log change alone is not the native Hidden reset event, and Whisper send has no BlockWhisper gate.
        connection.snapshot.value = connection.snapshot.value.copy(privateChatState = privateState)
        viewModel.send()
        runCurrent()
        assertEquals(listOf(1001 to "keep this draft"), chat.sentWhispers)
        assertTrue(chat.sentBeeps.isEmpty())
        viewModel.close()
    }

    @Test
    fun initialNonFriendWhisperSelectionCannotBypassTheOwnerRule() = runTest {
        val chat = FakeChat()
        val connection = ownedWhisperConnection()
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.copy(self = BcSelfRelations()),
        )
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = backgroundScope,
        )
        viewModel.onTextChange("keep this draft")
        viewModel.send()
        runCurrent()
        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals(ChatComposerMode.Whisper, ready.mode)
        assertEquals(false, ready.canSend)
        assertEquals("keep this draft", ready.draft)
        assertTrue(chat.sentWhispers.isEmpty())
        assertTrue(chat.sentBeeps.isEmpty())
        viewModel.close()
    }

    @Test
    fun whisperCommandKeepsTheNativeCommandPathWithoutTargetSelectionGate() = runTest {
        val chat = FakeChat()
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = ownedWhisperConnection(),
            scope = backgroundScope,
        )
        viewModel.onTextChange("/w 1001 private command")
        viewModel.send()
        runCurrent()
        assertEquals(listOf(1001 to "private command"), chat.sentWhispers)
        assertTrue(chat.sentChats.isEmpty())
        viewModel.close()
    }

    @Test
    fun readCursorFailureIsVisibleAndReopeningRetriesTheSameMessage() = runTest {
        val conversationId = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(conversationId, "Alice")))
        timeline.messages.value = listOf(message(conversationId, "latest"))
        val failure = BcTimelinePersistenceFailure(BcTimelinePersistenceOperation.AdvanceReadCursor, 9999, 1, conversationId)
        timeline.readCursorFailure = failure
        val viewModel = chatViewModel(conversationId, timeline, backgroundScope)
        runCurrent()
        viewModel.activate()
        runCurrent()

        assertEquals(failure, viewModel.uiState.value.persistenceFailure)
        assertEquals(1, timeline.readCursorMessages.size)

        timeline.readCursorFailure = null
        viewModel.deactivate()
        runCurrent()
        viewModel.activate()
        runCurrent()

        assertEquals(2, timeline.readCursorMessages.size)
        assertEquals(timeline.readCursorMessages[0], timeline.readCursorMessages[1])
        assertEquals(null, viewModel.uiState.value.persistenceFailure)
        viewModel.close()
    }

    @Test
    fun reusedViewModelsObserveTheirOwnConversationMessages() = runTest {
        val firstId = BcConversationId("message:1")
        val secondId = BcConversationId("message:2")
        val timeline = FakeTimeline(
            listOf(
                conversation(firstId, "First"),
                conversation(secondId, "Second"),
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val first = chatViewModel(firstId, timeline, scope)
        val second = chatViewModel(secondId, timeline, scope)
        advanceUntilIdle()

        timeline.messages.value = listOf(
            message(firstId, "first message"),
            message(secondId, "second message"),
        )
        advanceUntilIdle()

        assertEquals(listOf("first message"), first.uiState.value.messages.map { it.text })
        assertEquals(listOf("second message"), second.uiState.value.messages.map { it.text })
    }

    @Test
    fun sameRoomFriendCanSendWhisperFromDirectConversation() = runTest {
        val conversationId = BcConversationId("message:1001")
        val chat = FakeChat()
        val connection = FakeConnection(
            currentRoom = room("Test room"),
            roomMembers = listOf(
                BcMember(memberNumber = 9999, name = "Me", isSelf = true),
                BcMember(memberNumber = 1001, name = "Alice", isSelf = false),
            ),
            friendMemberNumbers = setOf(1001),
        )
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = chat,
            timeline = FakeTimeline(listOf(conversation(conversationId, "Alice"))),
            connectionStatus = connection,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals(
            listOf(
                ChatComposerModeOption(ChatComposerMode.DirectMessage, true),
                ChatComposerModeOption(ChatComposerMode.Whisper, true),
            ),
            ready.availableModes,
        )

        viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        viewModel.onTextChange("quiet hello")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf(1001 to "quiet hello"), chat.sentWhispers)
        assertEquals(emptyList<Pair<Int, String>>(), chat.sentBeeps)
    }

    @Test
    fun failedSendPreservesDraftAndShowsTheFailure() = runTest {
        val conversationId = BcConversationId("message:1001")
        val chat = FakeChat().apply {
            failure = IllegalStateException("Could not queue message locally")
        }
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = chat,
            timeline = FakeTimeline(listOf(conversation(conversationId, "Alice"))),
            connectionStatus = FakeConnection(friendMemberNumbers = setOf(1001)),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        viewModel.onTextChange("keep this draft")
        viewModel.send()
        advanceUntilIdle()

        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals("keep this draft", ready.draft)
        assertEquals(null, ready.errorMessage)
        assertEquals(ChatComposerValidationError.SendFailed, ready.validationError)
    }

    @Test
    fun whisperCommandsNeverFallBackToPublicChat() = runTest {
        val chat = FakeChat()
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(
                currentRoom = room("Test room"),
                roomMembers = listOf(BcMember(1001, "Alice", false)),
            ),
            scope = backgroundScope,
        )
        listOf("/w", "/whisper 1001", "/w nope secret", "/W nope secret", "/w -1 secret", "/w 1002 secret", "/Whisper 1002 secret").forEach { text ->
            viewModel.onTextChange(text)
            viewModel.send()
            runCurrent()

            val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
            assertEquals(text, ready.draft)
            assertEquals(
                if (text in listOf("/w 1002 secret", "/Whisper 1002 secret")) ChatComposerValidationError.WhisperTargetUnavailable
                else ChatComposerValidationError.InvalidWhisperCommand,
                ready.validationError,
            )
            assertTrue(chat.sentChats.isEmpty())
            assertTrue(chat.sentWhispers.isEmpty())
        }
        viewModel.onTextChange("/WHISPER\t1001   quiet hello")
        viewModel.send()
        runCurrent()

        assertEquals(listOf(1001 to "quiet hello"), chat.sentWhispers)
        assertTrue(chat.sentChats.isEmpty())
        viewModel.close()
    }

    @Test
    fun selectedWhisperDoesNotSilentlyBecomeFriendMessageAfterTargetLeaves() = runTest {
        val chat = FakeChat()
        val connection = FakeConnection(
            currentRoom = room("Test room"),
            roomMembers = listOf(BcMember(1001, "Alice", false)),
            friendMemberNumbers = setOf(1001),
        )
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = backgroundScope,
        )
        viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        viewModel.onTextChange("same-room only")
        connection.snapshot.value = connection.snapshot.value.copy(roomMembers = emptyList())
        viewModel.send()
        runCurrent()

        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals(ChatComposerMode.Whisper, ready.mode)
        assertEquals("same-room only", ready.draft)
        assertEquals(false, ready.modeAvailable)
        assertEquals(false, ready.canSend)
        assertTrue(chat.sentWhispers.isEmpty())
        assertTrue(chat.sentBeeps.isEmpty())

        viewModel.onComposerModeChange(ChatComposerMode.DirectMessage)
        viewModel.send()
        runCurrent()
        assertEquals(listOf(1001 to "same-room only"), chat.sentBeeps)
        viewModel.close()
    }

    @Test
    fun sharedInputPreservesDraftAndPendingSendAcrossClosedScreenObservers() = runTest {
        val input = MutableStateFlow(ChatInputState())
        val chat = FakeChat()
        val timeline = FakeTimeline(emptyList())
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        fun openScreen() = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = timeline,
            connectionStatus = connection,
            scope = backgroundScope,
            inputState = input,
        )
        val first = openScreen()
        first.onTextChange("submitted draft")
        first.onComposerModeChange(ChatComposerMode.DirectMessage)
        val sendGate = CompletableDeferred<Unit>()
        chat.beforeSend = sendGate
        first.send()
        runCurrent()
        first.close()
        runCurrent()
        assertEquals(1, timeline.messageObserverStops)

        val second = openScreen()
        runCurrent()
        assertEquals(
            ChatComposerState.Sending("submitted draft", ChatComposerMode.DirectMessage),
            second.uiState.value.composer,
        )
        second.send()
        second.onTextChange("next draft")
        runCurrent()
        assertEquals(
            ChatComposerState.Sending("next draft", ChatComposerMode.DirectMessage),
            second.uiState.value.composer,
        )
        second.send()
        runCurrent()
        sendGate.complete(Unit)
        runCurrent()
        assertEquals(listOf(1001 to "submitted draft"), chat.sentBeeps)
        assertEquals("next draft", (second.uiState.value.composer as ChatComposerState.Ready).draft)

        val failureGate = CompletableDeferred<Unit>()
        chat.beforeSend = failureGate
        second.send()
        runCurrent()
        second.close()
        runCurrent()
        val third = openScreen()
        chat.failure = IllegalStateException("Could not queue message locally")
        failureGate.complete(Unit)
        runCurrent()
        val recovered = third.uiState.value.composer as ChatComposerState.Ready
        assertEquals("next draft", recovered.draft)
        assertEquals(ChatComposerMode.DirectMessage, recovered.mode)
        assertEquals(null, recovered.errorMessage)
        assertEquals(ChatComposerValidationError.SendFailed, recovered.validationError)
        assertEquals(listOf(1001 to "submitted draft"), chat.sentBeeps)
        third.close()
    }

    @Test
    fun roomComposerExposesAllRoomModesAndSendsCustomActionFromDraft() = runTest {
        val conversationId = bcRoomConversationId("Test room")
        val chat = FakeChat()
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = chat,
            timeline = FakeTimeline(listOf(conversation(conversationId, "Test room"))),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals(
            listOf(
                ChatComposerMode.RoomMessage,
                ChatComposerMode.RoomEmote,
                ChatComposerMode.CustomAction,
            ),
            ready.availableModes.map { it.mode },
        )

        viewModel.onComposerModeChange(ChatComposerMode.CustomAction)
        viewModel.onTextChange("waves gently")
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("waves gently"), chat.sentActions)
        assertEquals("", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
    }

    @Test
    fun sendUsesLatestDraftAndModeBeforeUiStateEmission() = runTest {
        val conversationId = bcRoomConversationId("Test room")
        val chat = FakeChat()
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        viewModel.onTextChange("previous draft")
        runCurrent()

        viewModel.onTextChange("latest draft")
        viewModel.onComposerModeChange(ChatComposerMode.CustomAction)
        assertEquals("previous draft", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.send()
        viewModel.send()
        runCurrent()

        assertEquals(listOf("latest draft"), chat.sentActions)
        assertEquals(emptyList<String>(), chat.sentChats)
        assertEquals("", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    @Test
    fun sendRejectsStaleConnectionBeforeUiStateEmission() = runTest {
        val chat = FakeChat()
        val connection = FakeConnection(friendMemberNumbers = setOf(1001))
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        viewModel.onTextChange("keep this draft")
        runCurrent()

        connection.snapshot.value = connection.snapshot.value.copy(connectionHealth = BcConnectionHealth.Stale)
        assertTrue((viewModel.uiState.value.composer as ChatComposerState.Ready).canSend)
        viewModel.send()
        runCurrent()

        assertEquals(emptyList<Pair<Int, String>>(), chat.sentBeeps)
        assertEquals(
            ChatComposerState.Disabled(ChatComposerDisabledReason.ConnectionStale),
            viewModel.uiState.value.composer,
        )
        connection.snapshot.value = connection.snapshot.value.copy(connectionHealth = BcConnectionHealth.VerifiedOnline)
        runCurrent()
        assertEquals("keep this draft", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    @Test
    fun modeChangeUsesCurrentAvailabilityBeforeUiStateEmission() = runTest {
        val chat = FakeChat()
        val connection = FakeConnection()
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        runCurrent()
        assertEquals(
            ChatComposerState.Disabled(ChatComposerDisabledReason.DirectMessageUnavailable),
            viewModel.uiState.value.composer,
        )

        connection.snapshot.value = connection.snapshot.value.copy(
            currentRoom = room("Test room"),
            roomMembers = listOf(BcMember(memberNumber = 1001, name = "Alice", isSelf = false)),
        )
        viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        viewModel.onTextChange("quiet hello")
        viewModel.send()
        runCurrent()

        assertEquals(listOf(1001 to "quiet hello"), chat.sentWhispers)
        assertEquals(emptyList<Pair<Int, String>>(), chat.sentBeeps)
        viewModel.close()
    }

    @Test
    fun customActionUsesCurrentRoomAvailabilityBeforeUiStateEmission() = runTest {
        val chat = FakeChat()
        val connection = FakeConnection(currentRoom = room("Test room"))
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        runCurrent()

        viewModel.onComposerModeChange(ChatComposerMode.CustomAction)
        viewModel.onTextChange("must not send in another room")
        runCurrent()
        connection.snapshot.value = connection.snapshot.value.copy(currentRoom = room("Other room"))
        viewModel.send()
        runCurrent()
        assertTrue(chat.sentActions.isEmpty())
        connection.snapshot.value = connection.snapshot.value.copy(currentRoom = room("Test room"))
        viewModel.onTextChange("restored room action")
        viewModel.send()
        runCurrent()

        assertEquals(listOf("restored room action"), chat.sentActions)
        viewModel.close()
    }

    @Test
    fun pendingSendDisplaysEditedDraftAndKeepsSubmittedModeWhileEnvironmentChanges() = runTest {
        val sendGate = CompletableDeferred<Unit>()
        val chat = FakeChat().apply { beforeSend = sendGate }
        val connection = FakeConnection(
            currentRoom = room("Test room"),
            roomMembers = listOf(BcMember(memberNumber = 1001, name = "Alice", isSelf = false)),
            friendMemberNumbers = setOf(1001),
        )
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        viewModel.onTextChange("submitted draft")
        viewModel.send()
        runCurrent()

        viewModel.onTextChange("next draft")
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = BcRelationshipSnapshot().nextLoaded(9999, BcSelfRelations()),
        )
        runCurrent()
        assertEquals(
            ChatComposerState.Sending("next draft", ChatComposerMode.DirectMessage),
            viewModel.uiState.value.composer,
        )

        sendGate.complete(Unit)
        runCurrent()
        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals("next draft", ready.draft)
        assertEquals(ChatComposerMode.DirectMessage, ready.mode)
        assertEquals(false, ready.modeAvailable)
        assertEquals(false, ready.canSend)
        assertEquals(listOf(1001 to "submitted draft"), chat.sentBeeps)
        viewModel.close()
    }

    @Test
    fun pendingRoomSendAllowsEditingTheNextMessageWithoutSendingItTwice() = runTest {
        val sendGate = CompletableDeferred<Unit>()
        val chat = FakeChat().apply { beforeSend = sendGate }
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = backgroundScope,
        )
        viewModel.onTextChange("first message")
        viewModel.send()
        runCurrent()
        assertEquals(
            ChatComposerState.Sending("first message", ChatComposerMode.RoomMessage),
            viewModel.uiState.value.composer,
        )

        viewModel.onTextChange("next message")
        runCurrent()
        assertEquals(
            ChatComposerState.Sending("next message", ChatComposerMode.RoomMessage),
            viewModel.uiState.value.composer,
        )
        viewModel.send()
        runCurrent()
        assertTrue(chat.sentChats.isEmpty())

        sendGate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first message"), chat.sentChats)
        assertEquals("next message", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)

        viewModel.send()
        runCurrent()
        assertEquals(listOf("first message", "next message"), chat.sentChats)
        assertEquals("", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    @Test
    fun cancelledSendRestoresDraftWithoutPresentingCancellationAsFailure() = runTest {
        val chat = FakeChat().apply { failure = kotlinx.coroutines.CancellationException("cancelled") }
        val viewModel = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(friendMemberNumbers = setOf(1001)),
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        viewModel.onTextChange("keep this draft")
        viewModel.send()
        runCurrent()

        val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals("keep this draft", ready.draft)
        assertEquals(null, ready.errorMessage)
        assertEquals(emptyList<Pair<Int, String>>(), chat.sentBeeps)
        viewModel.close()
    }

    @Test
    fun roomBannerAndComposerUseTheSameConnectionDisabledReason() = runTest {
        val connection = FakeConnection(currentRoom = room("Test room"))
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = FakeChat(),
            timeline = FakeTimeline(emptyList()),
            connectionStatus = connection,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        )
        val online = connection.snapshot.value
        val cases = listOf(
            Triple(
                online.copy(
                    state = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, 1),
                    connectionHealth = BcConnectionHealth.Stale,
                ),
                ChatComposerDisabledReason.Reconnecting,
                "Reconnecting to BC",
            ),
            Triple(
                online.copy(connectionHealth = BcConnectionHealth.Stale),
                ChatComposerDisabledReason.ConnectionStale,
                "Connection stale",
            ),
            Triple(
                online.copy(connectionHealth = BcConnectionHealth.ConnectedUnverified),
                ChatComposerDisabledReason.VerifyingConnection,
                "Verifying connection",
            ),
        )
        cases.forEach { (snapshot, reason, banner) ->
            connection.snapshot.value = snapshot
            runCurrent()
            assertEquals(ChatComposerState.Disabled(reason), viewModel.uiState.value.composer)
            assertEquals(banner, viewModel.uiState.value.roomUnavailableMessage)
        }
        viewModel.close()
    }

    @Test
    fun activatingChatSelectsCurrentConversation() = runTest {
        val conversationId = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(conversationId, "Alice")))

        val viewModel = chatViewModel(
            conversationId = conversationId,
            timeline = timeline,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        viewModel.activate()
        advanceUntilIdle()

        assertEquals(conversationId, timeline.selectedConversationIds.last())

        viewModel.deactivate()

        assertEquals(conversationId, timeline.clearedConversationIds.last())
    }

    @Test
    fun activeChatAdvancesReadCursorToLatestObservedMessage() = runTest {
        val conversationId = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(conversationId, "Alice")))
        val viewModel = chatViewModel(
            conversationId = conversationId,
            timeline = timeline,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        viewModel.activate()
        timeline.messages.value = listOf(
            message(conversationId, "first"),
            message(conversationId, "second"),
        )
        advanceUntilIdle()

        assertEquals("second", timeline.readCursorMessages.last().text)

        viewModel.deactivate()
        timeline.messages.value = timeline.messages.value + message(conversationId, "third")
        advanceUntilIdle()

        assertEquals("second", timeline.readCursorMessages.last().text)
    }

    @Test
    fun oneMessagePipelineIsClosedWithViewModel() = runTest {
        val conversationId = BcConversationId("message:1001")
        val timeline = FakeTimeline(listOf(conversation(conversationId, "Alice")))
        val viewModel = chatViewModel(
            conversationId = conversationId,
            timeline = timeline,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertEquals(1, timeline.messageObserverStarts)
        assertEquals(0, timeline.messageObserverStops)

        viewModel.activate()
        advanceUntilIdle()
        assertEquals(1, timeline.messageObserverStarts)

        viewModel.close()
        advanceUntilIdle()

        assertEquals(1, timeline.messageObserverStops)
        assertEquals(conversationId, timeline.clearedConversationIds.last())

        val beforeClose = viewModel.uiState.value.messages
        timeline.messages.value = listOf(message(conversationId, "after close"))
        advanceUntilIdle()
        assertEquals(beforeClose, viewModel.uiState.value.messages)
        assertTrue(timeline.selectedConversationIds.isNotEmpty())
    }

    @Test
    fun directConversationFallbackTitleDoesNotUseCurrentRoomName() = runTest {
        val conversationId = BcConversationId("message:1001")
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = FakeChat(),
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(
                currentRoom = room("Room should not be title"),
                roomMembers = listOf(BcMember(memberNumber = 1001, name = "Alice", isSelf = false)),
            ),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertEquals("Alice", viewModel.uiState.value.title)
    }

    @Test
    fun historicalRoomFallbackDoesNotUseAnotherRoomsIdentity() = runTest {
        val conversationId = bcRoomConversationId("Old room")
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = FakeChat(),
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(
                currentRoom = room("New room").copy(description = "New room description"),
            ),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertEquals(conversationId.value, viewModel.uiState.value.title)
        assertEquals("Room conversation", viewModel.uiState.value.subtitle)
        assertEquals(
            ChatComposerState.Disabled(ChatComposerDisabledReason.NotInRoom),
            viewModel.uiState.value.composer,
        )
        assertEquals("Not in this room", viewModel.uiState.value.roomUnavailableMessage)
        viewModel.close()
    }

    @Test
    fun historicalRoomMetadataKeepsItsOwnTitleAndSubtitle() = runTest {
        val conversationId = bcRoomConversationId("Old room")
        val viewModel = ChatViewModel(
            conversationId = conversationId,
            chat = FakeChat(),
            timeline = FakeTimeline(
                listOf(conversation(conversationId, "Old room").copy(subtitle = "Old room description")),
            ),
            connectionStatus = FakeConnection(
                currentRoom = room("New room").copy(description = "New room description"),
            ),
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()

        assertEquals("Old room", viewModel.uiState.value.title)
        assertEquals("Old room description", viewModel.uiState.value.subtitle)
        viewModel.close()
    }

    @Test
    fun presenceOnlyFriendHeaderUsesCurrentSnapshotAfterLeavingRoom() = runTest {
        val bob = BcMember(memberNumber = 1002, name = "Bob", isSelf = false)
        val presence = BcSessionMessageCoordinator().sameRoomDirectConversations(listOf(bob)).single()
            .copy(availability = setOf(ConversationAvailability.OnlineFriend, ConversationAvailability.SameRoom))
        val timeline = FakeTimeline(listOf(presence))
        val connection = FakeConnection(room("Test room"), listOf(bob), setOf(bob.memberNumber))
        val viewModel = ChatViewModel(
            conversationId = presence.id,
            chat = FakeChat(),
            timeline = timeline,
            connectionStatus = connection,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        assertEquals("Friend and same room", viewModel.uiState.value.subtitle)

        timeline.conversations.value = timeline.conversations.value.copy(rows = listOf(presence.copy(availability = setOf(ConversationAvailability.OnlineFriend))))
        connection.snapshot.value = connection.snapshot.value.copy(currentRoom = null, roomMembers = emptyList())
        advanceUntilIdle()

        assertEquals("Same room, whisper available", timeline.conversations.value.rows.single().subtitle)
        assertEquals("Bob", viewModel.uiState.value.title)
        assertEquals("Friend message available", viewModel.uiState.value.subtitle)
        assertEquals(
            listOf(
                ChatComposerModeOption(ChatComposerMode.DirectMessage, true),
                ChatComposerModeOption(ChatComposerMode.Whisper, false),
            ),
            (viewModel.uiState.value.composer as ChatComposerState.Ready).availableModes,
        )
        viewModel.close()
    }

    @Test
    fun presenceOnlyRoomMemberHeaderStopsOfferingWhisperAfterLeavingRoom() = runTest {
        val bob = BcMember(memberNumber = 1002, name = "Bob", isSelf = false)
        val presence = BcSessionMessageCoordinator().sameRoomDirectConversations(listOf(bob)).single()
        val connection = FakeConnection(currentRoom = room("Test room"), roomMembers = listOf(bob))
        val viewModel = ChatViewModel(
            conversationId = presence.id,
            chat = FakeChat(),
            timeline = FakeTimeline(listOf(presence)),
            connectionStatus = connection,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        )
        advanceUntilIdle()
        assertEquals("Same room whisper available", viewModel.uiState.value.subtitle)

        connection.snapshot.value = connection.snapshot.value.copy(currentRoom = null, roomMembers = emptyList())
        advanceUntilIdle()

        assertEquals("Message needs friend; whisper needs same room", viewModel.uiState.value.subtitle)
        assertEquals(
            ChatComposerState.Disabled(ChatComposerDisabledReason.DirectMessageUnavailable),
            viewModel.uiState.value.composer,
        )
        viewModel.close()
    }

    @Test
    fun roomChatRoutesBcEmotesAndEscapesWithoutChangingSelectedMode() = runTest {
        val chat = FakeChat()
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = backgroundScope,
        )
        listOf("/me waves", "/ACTION The lights dim", "*smiles*", "//me waves").forEach { text ->
            viewModel.onTextChange(text)
            viewModel.send()
            runCurrent()
            val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
            assertEquals("", ready.draft)
            assertEquals(ChatComposerMode.RoomMessage, ready.mode)
        }
        assertEquals(listOf("waves", "*The lights dim", "smiles"), chat.sentEmotes)
        assertEquals(listOf("/me waves"), chat.sentChats)
        assertTrue(chat.sentActions.isEmpty())
        viewModel.close()
    }

    @Test
    fun unsupportedCommandsAndEmptyEmotesKeepDraftWithoutSending() = runTest {
        val chat = FakeChat()
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = backgroundScope,
        )
        listOf("/unknown private input", "/me", "/action", "**").forEach { text ->
            viewModel.onTextChange(text)
            viewModel.send()
            runCurrent()
            val ready = viewModel.uiState.value.composer as ChatComposerState.Ready
            assertEquals(text, ready.draft)
            assertEquals(
                if (text.startsWith("/unknown")) ChatComposerValidationError.UnsupportedRoomCommand
                else ChatComposerValidationError.EmptyEmote,
                ready.validationError,
            )
        }
        assertTrue(chat.sentChats.isEmpty())
        assertTrue(chat.sentEmotes.isEmpty())
        assertTrue(chat.sentWhispers.isEmpty())
        viewModel.close()
    }

    @Test
    fun explicitDirectAndEmoteModesKeepSlashTextLiteral() = runTest {
        val chat = FakeChat()
        val direct = ChatViewModel(
            conversationId = BcConversationId("message:1001"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(friendMemberNumbers = setOf(1001)),
            scope = backgroundScope,
        )
        direct.onTextChange("/me is literal here")
        direct.send()
        runCurrent()
        assertEquals(listOf(1001 to "/me is literal here"), chat.sentBeeps)
        direct.close()

        val roomViewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = backgroundScope,
        )
        roomViewModel.onComposerModeChange(ChatComposerMode.RoomEmote)
        roomViewModel.onTextChange("/w 1001 waves")
        roomViewModel.send()
        runCurrent()
        assertEquals(listOf("/w 1001 waves"), chat.sentEmotes)
        assertTrue(chat.sentWhispers.isEmpty())
        roomViewModel.close()
    }

    @Test
    fun emoteFailureKeepsOriginalCommandAndSuccessfulSendKeepsNewDraft() = runTest {
        val chat = FakeChat().apply { failure = IllegalStateException("Storage unavailable") }
        val viewModel = ChatViewModel(
            conversationId = bcRoomConversationId("Test room"),
            chat = chat,
            timeline = FakeTimeline(emptyList()),
            connectionStatus = FakeConnection(currentRoom = room("Test room")),
            scope = backgroundScope,
        )
        viewModel.onTextChange("/me waves")
        viewModel.send()
        runCurrent()
        assertEquals("/me waves", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        assertEquals(null, (viewModel.uiState.value.composer as ChatComposerState.Ready).errorMessage)
        assertEquals(ChatComposerValidationError.SendFailed, (viewModel.uiState.value.composer as ChatComposerState.Ready).validationError)

        chat.failure = null
        chat.beforeSend = CompletableDeferred()
        viewModel.send()
        runCurrent()
        viewModel.onTextChange("new draft")
        chat.beforeSend!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("waves"), chat.sentEmotes)
        assertEquals("new draft", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    private fun chatViewModel(
        conversationId: BcConversationId,
        timeline: FakeTimeline,
        scope: CoroutineScope,
    ): ChatViewModel =
        ChatViewModel(
            conversationId = conversationId,
            chat = FakeChat(),
            timeline = timeline,
            connectionStatus = FakeConnection(),
            scope = scope,
        )

    private fun conversation(id: BcConversationId, title: String): BcConversation =
        BcConversation(
            id = id,
            type = ConversationType.Direct,
            title = title,
            subtitle = "",
            updatedAt = Instant.EPOCH,
        )

    private fun message(conversationId: BcConversationId, text: String): BcMessage =
        BcMessage(
            id = dev.vivian.mobilebc.core.model.BcMessageId("${conversationId.value}:$text"),
            conversationId = conversationId,
            address = dev.vivian.mobilebc.core.model.BcMessageAddress.Direct(
                conversationId.value.removePrefix("message:").toInt(),
            ),
            sender = dev.vivian.mobilebc.core.model.BcSender(
                memberNumber = 1001,
                displayName = "Alice",
                isSelf = false,
            ),
            kind = dev.vivian.mobilebc.core.model.BcMessageKind.Beep,
            transport = dev.vivian.mobilebc.core.model.BcMessageTransport.AccountBeep,
            text = text,
            timestamp = Instant.EPOCH,
            delivery = dev.vivian.mobilebc.core.model.DeliveryState.Sent,
            visibility = dev.vivian.mobilebc.core.model.BcMessageVisibility.Visible,
            notificationPolicy = dev.vivian.mobilebc.core.model.BcNotificationPolicy.None,
        )

    private fun room(name: String): BcRoom =
        BcRoom(
            name = BcRoomName(name),
            description = "",
            memberCount = 2,
            memberLimit = 10,
            isLocked = false,
            language = null,
            mapType = BcRoomMapType.ChatRoom,
        )

    private fun ownedWhisperConnection(): FakeConnection = FakeConnection(
        currentRoom = room("Test room"),
        roomMembers = listOf(BcMember(1001, "Alice", false), BcMember(2000, "Owner", false)),
        friendMemberNumbers = setOf(1001),
    ).apply {
        snapshot.value = snapshot.value.copy(
            roomCharacters = listOf(
                BcCharacterSnapshot("self", 9999, "Me", "Me", true, ownership = BcCharacterOwnership("Owner", 2000, stage = 1)),
                BcCharacterSnapshot("owner", 2000, "Owner", "Owner", false),
            ),
            privateChatState = BcPrivateChatState(
                null,
                listOf(BcOwnerRuleLogRecord("BlockWhisper", "OwnerRule")),
                BcPrivateChatPreferences(),
            ),
        )
    }
}

private class FakeTimeline(
    initialConversations: List<BcConversation>,
) : BcSessionTimeline {
    override val conversations = MutableStateFlow(BcConversationSnapshot(9999, 1, initialConversations))
    override val persistenceFailure = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
    val messages = MutableStateFlow<List<BcMessage>>(emptyList())
    val messageOwner = MutableStateFlow(9999 to 1L)
    var messageObserverStarts: Int = 0
    var messageObserverStops: Int = 0

    override fun observeMessages(conversationId: BcConversationId, limit: Int): Flow<BcMessageSnapshot> =
        flow {
            messageObserverStarts += 1
            try {
                emitAll(
                    combine(messages, messageOwner) { rows, owner ->
                        BcMessageSnapshot(owner.first, owner.second, rows.filter { it.conversationId == conversationId }.takeLast(limit))
                    },
                )
            } finally {
                messageObserverStops += 1
            }
        }

    private var selection: Pair<BcConversationId, Any>? = null
    val selectedConversationIds = mutableListOf<BcConversationId>()
    val clearedConversationIds = mutableListOf<BcConversationId>()
    val readCursorMessages = mutableListOf<BcMessage>()
    val readCursorOwners = mutableListOf<Pair<Int, Long>>()
    var readCursorFailure: BcTimelinePersistenceFailure? = null

    override fun selectConversation(conversationId: BcConversationId, owner: Any) {
        selection = conversationId to owner
        selectedConversationIds += conversationId
    }

    override fun clearSelectedConversation(owner: Any) {
        val current = selection ?: return
        if (current.second !== owner) return
        clearedConversationIds += current.first
        selection = null
    }

    override fun isConversationVisible(conversationId: BcConversationId): Boolean = false

    override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) {
        readCursorOwners += ownerMemberNumber to accountGeneration
        readCursorMessages += throughMessage
        persistenceFailure.value = readCursorFailure
    }
}

private class FakeChat : BcSessionChat {
    val sentChats = mutableListOf<String>()
    val sentWhispers = mutableListOf<Pair<Int, String>>()
    val sentBeeps = mutableListOf<Pair<Int, String>>()
    val sentActions = mutableListOf<String>()
    val sentEmotes = mutableListOf<String>()
    var failure: Throwable? = null
    var beforeSend: CompletableDeferred<Unit>? = null

    override suspend fun sendChat(text: String) {
        beforeSend?.await()
        failure?.let { throw it }
        sentChats += text
    }
    override suspend fun sendEmote(text: String) {
        beforeSend?.await()
        failure?.let { throw it }
        sentEmotes += text
    }
    override suspend fun sendAction(text: String) {
        sentActions += text
    }
    override suspend fun sendWhisper(targetMemberNumber: Int, text: String) {
        sentWhispers += targetMemberNumber to text
    }
    override suspend fun sendBeep(targetMemberNumber: Int, text: String) {
        beforeSend?.await()
        failure?.let { throw it }
        sentBeeps += targetMemberNumber to text
    }
}

private class FakeConnection(
    currentRoom: BcRoom? = null,
    roomMembers: List<BcMember> = emptyList(),
    friendMemberNumbers: Set<Int> = emptySet(),
) : ConnectionStatusReader {
    override val snapshot = MutableStateFlow(
        BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = BcAccount(9999, "Me", "me"),
            currentRoom = currentRoom,
            roomMembers = roomMembers,
            roomCharacters = listOf(BcCharacterSnapshot("self", 9999, "Me", "Me", true)),
            relationships = BcRelationshipSnapshot().nextLoaded(
                memberNumber = 9999,
                relations = BcSelfRelations(friends = friendMemberNumbers),
            ),
        ),
    )
}
