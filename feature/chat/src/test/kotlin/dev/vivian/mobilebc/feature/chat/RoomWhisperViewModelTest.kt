package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.session.chat.BcSessionChat
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.*
import dev.vivian.mobilebc.domain.session.timeline.BcConversationSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcMessageSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomWhisperViewModelTest {
    @Test
    fun projectionIncludesOnlyOwnCurrentRoomWhispersAndKeepsSingleOrderedRecords() {
        val public = roomMessage("public", 20)
        val incoming = whisper("incoming", 10)
        val outgoing = whisper("outgoing", 30).copy(sender = BcSender(9999, "Me", true), targetMemberNumber = 1001)
        val omitted = listOf(
            whisper("other-room").let { it.copy(raw = it.raw!!.copy(roomName = "Other room")) },
            whisper("unknown-room").copy(raw = null),
            whisper("beep").copy(transport = BcMessageTransport.AccountBeep, kind = BcMessageKind.Beep),
            whisper("hidden").copy(visibility = BcMessageVisibility.HiddenProtocol),
            whisper("another-recipient").copy(targetMemberNumber = 8888),
            whisper("bad-conversation").copy(conversationId = BcConversationId("message:2002")),
            whisper("self-without-recipient").copy(sender = BcSender(9999, "Me", true), targetMemberNumber = null),
        )
        val result = mergeRoomTimeline(listOf(public, public), listOf(incoming, outgoing, incoming) + omitted,
            roomId, "Test room", 9999)
        assertEquals(listOf("incoming", "public", "outgoing"), result.map { it.id.value })
        assertEquals(BcConversationId("message:1001"), result.first().conversationId)
        assertEquals(listOf(public), mergeRoomTimeline(listOf(public), listOf(incoming), roomId, null, 9999))
        assertEquals(listOf(public), mergeRoomTimeline(listOf(public), listOf(incoming), roomId, "Test room", null))
    }

    @Test
    fun incomingWhisperDoesNotSelectRecipientOrAdvanceAnUnseenDirectCursor() = runTest {
        val timeline = RoomWhisperTimeline()
        val viewModel = ChatViewModel(roomId, RoomWhisperChat(), timeline, RoomWhisperConnection(), scope = backgroundScope)
        timeline.rows.value = listOf(roomMessage("public", 10))
        viewModel.activate()
        runCurrent()
        timeline.rows.value += whisper("private", 20)
        runCurrent()
        assertEquals(listOf("public", "private"), viewModel.uiState.value.messages.map { it.id.value })
        assertNull(viewModel.uiState.value.whisperTarget)
        assertEquals(ChatComposerMode.RoomMessage, (viewModel.uiState.value.composer as ChatComposerState.Ready).mode)
        assertEquals(listOf("public"), timeline.readMessages.map { it.id.value })
        viewModel.close()
    }

    @Test
    fun privateTargetsAndPublicKeepSeparateDraftsAndLeavingNeverSendsPublicly() = runTest {
        val connection = RoomWhisperConnection()
        val chat = RoomWhisperChat()
        val viewModel = ChatViewModel(roomId, chat, RoomWhisperTimeline(), connection, scope = backgroundScope)
        viewModel.onTextChange("public draft")
        viewModel.onWhisperTargetChange(1001)
        runCurrent()
        assertEquals("", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.onTextChange("Alice secret")
        viewModel.onWhisperTargetChange(1002)
        viewModel.onTextChange("Bob secret")
        viewModel.onWhisperTargetChange(1001)
        runCurrent()
        assertEquals("Alice secret", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        assertEquals(1001, viewModel.uiState.value.whisperTarget)
        connection.snapshot.value = connection.snapshot.value.copy(roomMembers = emptyList())
        viewModel.send()
        runCurrent()
        val unavailable = viewModel.uiState.value.composer as ChatComposerState.Ready
        assertEquals(ChatComposerMode.Whisper, unavailable.mode)
        assertFalse(unavailable.canSend)
        assertTrue(chat.sent.isEmpty())
        viewModel.onReturnToPublic()
        runCurrent()
        assertEquals("public draft", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.send()
        runCurrent()
        assertEquals(listOf(null to "public draft"), chat.sent)
        connection.snapshot.value = connection.snapshot.value.copy(roomMembers = listOf(BcMember(1002, "Bob", false)))
        viewModel.onWhisperTargetChange(1002)
        runCurrent()
        assertEquals("Bob secret", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.send()
        runCurrent()
        assertEquals(1002 to "Bob secret", chat.sent.last())
        assertEquals(1002, viewModel.uiState.value.whisperTarget)
        assertEquals("", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.close()
    }

    @Test
    fun replyToWhisperSelectsItsPeerAndDoesNotCarryItsQuoteIntoPublicDraft() = runTest {
        val timeline = RoomWhisperTimeline().apply { rows.value = listOf(whisper("private")) }
        val chat = RoomWhisperChat()
        val viewModel = ChatViewModel(roomId, chat, timeline, RoomWhisperConnection(), scope = backgroundScope)
        viewModel.onTextChange("public draft")
        runCurrent()
        assertTrue(whisper("private").chatUiKey in viewModel.uiState.value.replyableMessageIds)
        viewModel.onReply("private")
        viewModel.onTextChange("private answer")
        runCurrent()
        assertEquals(1001, viewModel.uiState.value.whisperTarget)
        assertNotNull(viewModel.uiState.value.reply)
        viewModel.onReturnToPublic()
        runCurrent()
        assertNull(viewModel.uiState.value.reply)
        assertEquals("public draft", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        viewModel.onWhisperTargetChange(1001)
        viewModel.send()
        runCurrent()
        assertEquals(listOf(1001 to "private answer"), chat.sent)
        assertEquals("private", chat.replies.single()?.messageId)
        assertNull(viewModel.uiState.value.reply)
        viewModel.close()
    }

    @Test
    fun collidingNativeMessageIdsReplyToTheExplicitConversationOnly() = runTest {
        val alice = whisper("same-id")
        val bob = alice.copy(conversationId = BcConversationId("message:1002"), address = BcMessageAddress.Direct(1002),
            sender = BcSender(1002, "Bob"))
        val timeline = RoomWhisperTimeline().apply { rows.value = listOf(alice, bob) }
        val viewModel = ChatViewModel(roomId, RoomWhisperChat(), timeline, RoomWhisperConnection(), scope = backgroundScope)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.messages.map { it.chatUiKey }.toSet().size)
        viewModel.onReply("same-id")
        runCurrent()
        assertNull(viewModel.uiState.value.reply)
        viewModel.onReply(bob.chatUiKey)
        runCurrent()
        assertEquals(1002, viewModel.uiState.value.whisperTarget)
        assertEquals(bob.conversationId, viewModel.uiState.value.reply?.reference?.conversationId)
        viewModel.close()
    }

    @Test
    fun pendingSendRetainsScopeAndFailedSendRetainsThePrivateDraft() = runTest {
        val chat = RoomWhisperChat().apply { gate = CompletableDeferred() }
        val viewModel = ChatViewModel(roomId, chat, RoomWhisperTimeline(), RoomWhisperConnection(), scope = backgroundScope)
        viewModel.onWhisperTargetChange(1001)
        viewModel.onTextChange("first secret")
        viewModel.send()
        runCurrent()
        viewModel.onWhisperTargetChange(1002)
        viewModel.onReturnToPublic()
        viewModel.onTextChange("next secret")
        runCurrent()
        assertEquals(1001, viewModel.uiState.value.whisperTarget)
        chat.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(1001 to "first secret"), chat.sent)
        assertEquals("next secret", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        chat.failure = IllegalStateException("storage failure")
        viewModel.send()
        runCurrent()
        assertEquals("next secret", (viewModel.uiState.value.composer as ChatComposerState.Ready).draft)
        assertEquals(ChatComposerValidationError.SendFailed, (viewModel.uiState.value.composer as ChatComposerState.Ready).validationError)
        assertEquals(1001, viewModel.uiState.value.whisperTarget)
        viewModel.close()
    }

    @Test
    fun roomTargetSelectionHonorsOwnerRulesAndHiddenTargetReset() = runTest {
        val connection = RoomWhisperConnection()
        connection.snapshot.value = connection.snapshot.value.copy(
            roomCharacters = listOf(
                BcCharacterSnapshot("self", 9999, "Me", "Me", true, ownership = BcCharacterOwnership("Owner", 2000, stage = 1)),
                BcCharacterSnapshot("owner", 2000, "Owner", "Owner", false),
            ),
            privateChatState = BcPrivateChatState(null,
                listOf(BcOwnerRuleLogRecord("BlockWhisper", "OwnerRule")), BcPrivateChatPreferences()),
        )
        val chat = RoomWhisperChat()
        val viewModel = ChatViewModel(roomId, chat, RoomWhisperTimeline(), connection, scope = backgroundScope)
        viewModel.onWhisperTargetChange(1001)
        runCurrent()
        assertNull(viewModel.uiState.value.whisperTarget)
        assertEquals(BcRoomChatRejectionReason.WhisperBlockedByOwner,
            (viewModel.uiState.value.composer as ChatComposerState.Ready).roomChatRejection?.reason)
        connection.snapshot.value = connection.snapshot.value.copy(
            privateChatState = connection.snapshot.value.privateChatState!!.copy(ruleLog = emptyList()),
        )
        viewModel.onWhisperTargetChange(1001)
        viewModel.onTextChange("private draft")
        connection.snapshot.value = connection.snapshot.value.copy(whisperTargetResetRevision = 1)
        viewModel.send()
        runCurrent()
        assertFalse((viewModel.uiState.value.composer as ChatComposerState.Ready).canSend)
        assertTrue(chat.sent.isEmpty())
        viewModel.onWhisperTargetChange(1001)
        viewModel.send()
        runCurrent()
        assertEquals(listOf(1001 to "private draft"), chat.sent)
        viewModel.close()
    }

    @Test
    fun switchingAccountHidesOldRowsBeforeTheNewDatabaseSubscriptionEmits() = runTest {
        val timeline = RoomWhisperTimeline().apply {
            rows.value = listOf(roomMessage("old-public"), whisper("old-private").copy(targetMemberNumber = null))
        }
        val connection = RoomWhisperConnection()
        val viewModel = ChatViewModel(roomId, RoomWhisperChat(), timeline, connection, scope = backgroundScope)
        viewModel.activate()
        runCurrent()
        assertEquals(2, viewModel.uiState.value.messages.size)
        timeline.suspendNewSubscriptions = true
        connection.snapshot.value = connection.snapshot.value.copy(
            account = BcAccount(7777, "Second", "second"),
            relationships = connection.snapshot.value.relationships.copy(accountGeneration = 1),
        )
        // Even before a StateFlow UI emission, a click from the old screen cannot quote the old account's message.
        viewModel.onReply("old-private")
        runCurrent()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(viewModel.uiState.value.replyableMessageIds.isEmpty())
        assertNull(viewModel.uiState.value.reply)
        assertEquals(1, timeline.readMessages.size)
        viewModel.close()
    }

    @Test
    fun disconnectedRoomKeepsItsRoomIdentityWithoutOfferingAnEnabledComposer() = runTest {
        val connection = RoomWhisperConnection()
        val viewModel = ChatViewModel(roomId, RoomWhisperChat(), RoomWhisperTimeline(), connection, scope = backgroundScope)
        runCurrent()
        connection.snapshot.value = connection.snapshot.value.copy(connectionHealth = BcConnectionHealth.Stale)
        runCurrent()
        assertTrue(viewModel.uiState.value.isCurrentRoom)
        assertFalse(viewModel.uiState.value.showRoomDetails)
        assertEquals(listOf(1001, 1002), viewModel.uiState.value.whisperTargets.map { it.memberNumber })
        assertEquals(ChatComposerState.Disabled(ChatComposerDisabledReason.ConnectionStale), viewModel.uiState.value.composer)
        viewModel.close()
    }

    @Test
    fun roomChangeRemovesProjectedWhispersAndBlocksTheOldComposer() = runTest {
        val timeline = RoomWhisperTimeline().apply { rows.value = listOf(whisper("private")) }
        val connection = RoomWhisperConnection()
        val chat = RoomWhisperChat()
        val viewModel = ChatViewModel(roomId, chat, timeline, connection, scope = backgroundScope)
        viewModel.onWhisperTargetChange(1001)
        viewModel.onTextChange("old room secret")
        runCurrent()
        connection.snapshot.value = connection.snapshot.value.copy(
            currentRoom = connection.snapshot.value.currentRoom!!.copy(name = BcRoomName("Other room")),
            roomPresenceEpoch = 1,
        )
        viewModel.send()
        runCurrent()
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertTrue(chat.sent.isEmpty())
        viewModel.close()
    }
}

private val roomId = bcRoomConversationId("Test room")
private fun roomMessage(id: String, timestamp: Long = 0) = BcMessage(
    BcMessageId(id), roomId, sender = BcSender(1001, "Alice"), kind = BcMessageKind.Chat,
    text = id, timestamp = Instant.ofEpochMilli(timestamp),
)
private fun whisper(id: String, timestamp: Long = 0) = BcMessage(
    BcMessageId(id), BcConversationId("message:1001"), BcMessageAddress.Direct(1001), BcSender(1001, "Alice"),
    BcMessageKind.Whisper, BcMessageTransport.RoomWhisper, id, Instant.ofEpochMilli(timestamp),
    targetMemberNumber = 9999,
    raw = BcRawMessage("ChatRoomMessage", "Whisper", dictionaryJson = "[{\"Tag\":\"MsgId\",\"MsgId\":\"$id\"}]", roomName = "Test room"),
)

private class RoomWhisperConnection : ConnectionStatusReader {
    override val snapshot = MutableStateFlow(BcSessionSnapshot(
        state = BcSessionState.Ready, connectionHealth = BcConnectionHealth.VerifiedOnline,
        desiredOnline = true, account = BcAccount(9999, "Me", "me"),
        currentRoom = BcRoom(BcRoomName("Test room"), "", 3, 10, false, null),
        roomMembers = listOf(BcMember(9999, "Me", true), BcMember(1001, "Alice", false), BcMember(1002, "Bob", false)),
        roomCharacters = listOf(BcCharacterSnapshot("self", 9999, "Me", "Me", true)),
        relationships = BcRelationshipSnapshot().nextLoaded(9999, BcSelfRelations()),
    ))
}
private class RoomWhisperTimeline : BcSessionTimeline {
    override val conversations = MutableStateFlow(BcConversationSnapshot())
    override val persistenceFailure = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
    val rows = MutableStateFlow<List<BcMessage>>(emptyList())
    val readMessages = mutableListOf<BcMessage>()
    var suspendNewSubscriptions = false
    override fun observeMessages(conversationId: BcConversationId, limit: Int): Flow<BcMessageSnapshot> =
        if (suspendNewSubscriptions) flow { awaitCancellation() }
        else rows.map { list -> BcMessageSnapshot(9999, 1, list.filter { it.conversationId == conversationId }) }
    // Deliberately return unfiltered candidates: the view-model must still enforce room and recipient scope.
    override fun observeRoomWhispers(roomName: String, limit: Int): Flow<BcMessageSnapshot> =
        if (suspendNewSubscriptions) flow { awaitCancellation() } else rows.map { BcMessageSnapshot(9999, 1, it) }
    override fun selectConversation(conversationId: BcConversationId, owner: Any) = Unit
    override fun clearSelectedConversation(owner: Any) = Unit
    override fun isConversationVisible(conversationId: BcConversationId) = false
    override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) {
        require(throughMessage.conversationId == conversationId)
        readMessages += throughMessage
    }
}
private class RoomWhisperChat : BcSessionChat {
    val sent = mutableListOf<Pair<Int?, String>>()
    val replies = mutableListOf<BcMessageReplyReference?>()
    var gate: CompletableDeferred<Unit>? = null
    var failure: Throwable? = null
    override suspend fun sendChat(text: String) { sent += null to text }
    override suspend fun sendEmote(text: String) = Unit
    override suspend fun sendAction(text: String) = Unit
    override suspend fun sendBeep(targetMemberNumber: Int, text: String) = Unit
    override suspend fun sendWhisper(targetMemberNumber: Int, text: String) = recordWhisper(targetMemberNumber, text, null)
    override suspend fun sendWhisper(targetMemberNumber: Int, text: String, replyTo: BcMessageReplyReference) =
        recordWhisper(targetMemberNumber, text, replyTo)
    private suspend fun recordWhisper(target: Int, text: String, replyTo: BcMessageReplyReference?) {
        gate?.await()
        failure?.let { throw it }
        sent += target to text
        replies += replyTo
    }
}
