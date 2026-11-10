package dev.vivian.mobilebc.feature.chat

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomChatRejectedException
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.domain.session.chat.BcSessionChat
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.timeline.BcConversationSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcMessageSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatReplyViewModelTest {
    @Test
    fun onlySentVisibleNativeMessagesWithMatchingIdsCanBeSelected() = runTest {
        val valid = replyVmRoomMessage("native")
        val invalid = listOf(
            replyVmRoomMessage("no-dictionary").copy(raw = null),
            replyVmRoomMessage("wrong-id").copy(raw = valid.raw),
            replyVmRoomMessage("pending").copy(delivery = DeliveryState.Sending),
            replyVmRoomMessage("failed").copy(delivery = DeliveryState.Failed),
            replyVmRoomMessage("hidden").copy(visibility = BcMessageVisibility.HiddenProtocol),
            replyVmRoomMessage("local").copy(visibility = BcMessageVisibility.LocalOnly),
            replyVmRoomMessage("action").copy(kind = BcMessageKind.Action),
            replyVmRoomMessage("beep").copy(kind = BcMessageKind.Beep, transport = BcMessageTransport.AccountBeep),
            replyVmRoomMessage("other-room").let { it.copy(raw = it.raw!!.copy(roomName = "Another room")) },
            replyVmRoomMessage("wrong-address").copy(address = BcMessageAddress.Direct(1001)),
        )
        val fixture = replyVmFixture(backgroundScope, messages = listOf(valid) + invalid)
        runCurrent()

        assertEquals(setOf(valid.chatUiKey), fixture.viewModel.uiState.value.replyableMessageIds)
        invalid.forEach { message ->
            fixture.viewModel.onReply(message.id.value)
            runCurrent()
            assertNull(fixture.viewModel.uiState.value.reply)
        }
        fixture.viewModel.onReply(valid.id.value)
        runCurrent()
        assertEquals("native", fixture.viewModel.uiState.value.reply!!.reference.messageId)
        fixture.viewModel.close()
    }

    @Test
    fun historicalRoomMessageCannotBeSelectedFromAnotherRoom() = runTest {
        val historical = replyVmRoomMessage("old").copy(conversationId = bcRoomConversationId("Old room"))
        val fixture = replyVmFixture(backgroundScope, historical.conversationId, listOf(historical))
        runCurrent()

        fixture.viewModel.onReply("old")
        runCurrent()

        assertTrue(fixture.viewModel.uiState.value.replyableMessageIds.isEmpty())
        assertNull(fixture.viewModel.uiState.value.reply)
        fixture.viewModel.close()
    }

    @Test
    fun publicReplyFollowsParsedChatEmoteAndWhisperWithoutLosingItsReference() = runTest {
        val fixture = replyVmFixture(backgroundScope, messages = listOf(replyVmRoomMessage("original")))
        runCurrent()
        listOf(
            Triple("answer", ChatComposerMode.RoomMessage, "answer"),
            Triple("/me smiles", ChatComposerMode.RoomEmote, "smiles"),
            Triple("/w 1001 private answer", ChatComposerMode.Whisper, "private answer"),
        ).forEach { (draft, sentMode, sentText) ->
            fixture.viewModel.onReply("original")
            runCurrent()
            val reference = fixture.viewModel.uiState.value.reply!!.reference
            fixture.viewModel.onTextChange(draft)
            fixture.viewModel.send()
            runCurrent()

            val sent = fixture.chat.sent.last()
            assertEquals(sentMode, sent.mode)
            assertEquals(sentText, sent.text)
            assertEquals(reference, sent.reply)
            assertEquals(if (sentMode == ChatComposerMode.Whisper) 1001 else null, sent.target)
            assertNull(fixture.viewModel.uiState.value.reply)
            assertEquals("", fixture.ready().draft)
            assertEquals(ChatComposerMode.RoomMessage, fixture.ready().mode)
        }
        fixture.viewModel.close()
    }

    @Test
    fun privateReplySelectsWhisperAndNeverBecomesBeepWhenTheTargetLeaves() = runTest {
        val direct = BcConversationId("message:1001")
        val fixture = replyVmFixture(backgroundScope, direct, listOf(replyVmWhisperMessage("private")))
        runCurrent()
        assertEquals(ChatComposerMode.DirectMessage, fixture.ready().mode)

        fixture.viewModel.onReply("private")
        fixture.viewModel.onTextChange("private answer")
        runCurrent()
        assertEquals(ChatComposerMode.Whisper, fixture.ready().mode)
        assertTrue(fixture.ready().canSend)
        fixture.connection.snapshot.value = fixture.connection.snapshot.value.copy(roomMembers = emptyList())
        runCurrent()
        assertEquals(ChatComposerMode.Whisper, fixture.ready().mode)
        assertFalse(fixture.ready().canSend)
        fixture.viewModel.send()
        runCurrent()
        assertTrue(fixture.chat.sent.isEmpty())

        fixture.viewModel.onComposerModeChange(ChatComposerMode.DirectMessage)
        runCurrent()
        assertEquals(ChatComposerValidationError.ReplyModeUnsupported, fixture.ready().validationError)
        assertFalse(fixture.ready().canSend)
        assertEquals("private answer", fixture.ready().draft)
        assertNotNull(fixture.viewModel.uiState.value.reply)
        fixture.viewModel.close()
    }

    @Test
    fun privateReplyNeedsAnExplicitMatchingRoomAndCurrentTarget() = runTest {
        val direct = BcConversationId("message:1001")
        val valid = replyVmWhisperMessage("valid")
        val missing = replyVmWhisperMessage("missing").let { it.copy(raw = it.raw!!.copy(roomName = null)) }
        val old = replyVmWhisperMessage("old").let { it.copy(raw = it.raw!!.copy(roomName = "Old room")) }
        val fixture = replyVmFixture(backgroundScope, direct, listOf(valid, missing, old))
        runCurrent()
        assertEquals(setOf(valid.chatUiKey), fixture.viewModel.uiState.value.replyableMessageIds)
        listOf(missing, old).forEach {
            fixture.viewModel.onReply(it.id.value)
            runCurrent()
            assertNull(fixture.viewModel.uiState.value.reply)
        }

        fixture.connection.snapshot.value = fixture.connection.snapshot.value.copy(roomMembers = emptyList())
        fixture.viewModel.onReply("valid")
        runCurrent()
        assertNull(fixture.viewModel.uiState.value.reply)
        assertTrue(fixture.viewModel.uiState.value.replyableMessageIds.isEmpty())
        fixture.viewModel.close()
    }

    @Test
    fun hiddenWhisperTargetResetKeepsThePrivateReplyAndDraftUntilTheTargetIsReselected() = runTest {
        val fixture = replyVmFixture(
            backgroundScope,
            BcConversationId("message:1001"),
            listOf(replyVmWhisperMessage("private")),
        )
        runCurrent()
        fixture.viewModel.onReply("private")
        fixture.viewModel.onTextChange("private answer")
        runCurrent()
        val selected = fixture.viewModel.uiState.value.reply
        assertEquals(ChatComposerMode.Whisper, fixture.ready().mode)
        assertTrue(fixture.ready().canSend)

        fixture.connection.snapshot.value = fixture.connection.snapshot.value.let {
            it.copy(whisperTargetResetRevision = it.whisperTargetResetRevision + 1)
        }
        fixture.viewModel.send()
        runCurrent()

        assertTrue(fixture.chat.sent.isEmpty())
        assertEquals(ChatComposerMode.Whisper, fixture.ready().mode)
        assertEquals(ChatComposerValidationError.WhisperTargetReset, fixture.ready().validationError)
        assertFalse(fixture.ready().canSend)
        assertEquals("private answer", fixture.ready().draft)
        assertSame(selected, fixture.viewModel.uiState.value.reply)

        fixture.viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        runCurrent()
        assertTrue(fixture.ready().canSend)
        assertNull(fixture.ready().validationError)
        assertSame(selected, fixture.viewModel.uiState.value.reply)
        fixture.viewModel.send()
        runCurrent()

        assertEquals(ChatComposerMode.Whisper, fixture.chat.sent.single().mode)
        assertEquals(1001, fixture.chat.sent.single().target)
        assertEquals("private answer", fixture.chat.sent.single().text)
        assertEquals(selected!!.reference, fixture.chat.sent.single().reply)
        assertNull(fixture.viewModel.uiState.value.reply)
        assertEquals("", fixture.ready().draft)
        fixture.viewModel.close()
    }

    @Test
    fun hiddenWhisperTargetResetIsCheckedAgainBeforeTheSendCoroutineStarts() = runTest {
        val fixture = replyVmFixture(
            backgroundScope,
            BcConversationId("message:1001"),
            listOf(replyVmWhisperMessage("private")),
        )
        runCurrent()
        fixture.viewModel.onReply("private")
        fixture.viewModel.onTextChange("private answer")
        runCurrent()
        val selected = fixture.viewModel.uiState.value.reply
        fixture.viewModel.send()
        fixture.connection.snapshot.value = fixture.connection.snapshot.value.let {
            it.copy(whisperTargetResetRevision = it.whisperTargetResetRevision + 1)
        }
        runCurrent()

        assertTrue(fixture.chat.sent.isEmpty())
        assertEquals(ChatComposerMode.Whisper, fixture.ready().mode)
        assertEquals(ChatComposerValidationError.WhisperTargetReset, fixture.ready().validationError)
        assertFalse(fixture.ready().canSend)
        assertEquals("private answer", fixture.ready().draft)
        assertSame(selected, fixture.viewModel.uiState.value.reply)
        fixture.viewModel.close()
    }

    @Test
    fun unsupportedCustomActionKeepsTheReplyAndDraftUntilTheUserCancels() = runTest {
        val fixture = replyVmFixture(backgroundScope, messages = listOf(replyVmRoomMessage("original")))
        runCurrent()
        fixture.viewModel.onReply("original")
        fixture.viewModel.onTextChange("keep this draft")
        fixture.viewModel.onComposerModeChange(ChatComposerMode.CustomAction)
        runCurrent()

        assertFalse(fixture.ready().canSend)
        assertEquals(ChatComposerValidationError.ReplyModeUnsupported, fixture.ready().validationError)
        fixture.viewModel.send()
        runCurrent()
        assertTrue(fixture.chat.sent.isEmpty())
        assertNotNull(fixture.viewModel.uiState.value.reply)
        fixture.viewModel.onCancelReply()
        runCurrent()
        assertNull(fixture.viewModel.uiState.value.reply)
        assertEquals("keep this draft", fixture.ready().draft)
        assertTrue(fixture.ready().canSend)
        fixture.viewModel.close()
    }

    @Test
    fun roomVisitAccountGenerationAndAccountIdentityEachInvalidateTheSelectedReply() = runTest {
        val mutations: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(roomPresenceEpoch = it.roomPresenceEpoch + 1) },
            { it.copy(relationships = it.relationships.copy(accountGeneration = it.relationships.accountGeneration + 1)) },
            { it.copy(account = BcAccount(8888, "Other account", "other")) },
        )
        mutations.forEach { mutate ->
            val fixture = replyVmFixture(backgroundScope, messages = listOf(replyVmRoomMessage("original")))
            runCurrent()
            fixture.viewModel.onReply("original")
            fixture.viewModel.onTextChange("keep this draft")
            fixture.connection.snapshot.value = mutate(fixture.connection.snapshot.value)
            fixture.viewModel.send()
            runCurrent()

            assertEquals(ChatComposerValidationError.ReplyUnavailable, fixture.ready().validationError)
            assertFalse(fixture.ready().canSend)
            assertEquals("keep this draft", fixture.ready().draft)
            assertNotNull(fixture.viewModel.uiState.value.reply)
            assertTrue(fixture.chat.sent.isEmpty())
            fixture.viewModel.close()
        }
    }

    @Test
    fun replyIsCheckedAgainWhenTheSendCoroutineStartsAfterARoomVisitChange() = runTest {
        val fixture = replyVmFixture(backgroundScope, messages = listOf(replyVmRoomMessage("original")))
        runCurrent()
        fixture.viewModel.onReply("original")
        fixture.viewModel.onTextChange("answer")
        fixture.viewModel.send()
        fixture.connection.snapshot.value = fixture.connection.snapshot.value.copy(roomPresenceEpoch = 2)
        runCurrent()

        assertTrue(fixture.chat.sent.isEmpty())
        assertEquals("answer", fixture.ready().draft)
        assertEquals(ChatComposerValidationError.ReplyUnavailable, fixture.ready().validationError)
        assertNotNull(fixture.viewModel.uiState.value.reply)
        fixture.viewModel.close()
    }

    @Test
    fun failedReplyPreservesTheSelectionAndOriginalDraftAndSuccessfulRetryClearsBoth() = runTest {
        val fixture = replyVmFixture(backgroundScope, messages = listOf(replyVmRoomMessage("original")))
        fixture.chat.failure = IllegalStateException("Storage unavailable")
        runCurrent()
        fixture.viewModel.onReply("original")
        runCurrent()
        val selected = fixture.viewModel.uiState.value.reply
        fixture.viewModel.onTextChange("/me smiles")
        fixture.viewModel.send()
        runCurrent()

        assertSame(selected, fixture.viewModel.uiState.value.reply)
        assertEquals("/me smiles", fixture.ready().draft)
        assertNull(fixture.ready().errorMessage)
        assertEquals(ChatComposerValidationError.SendFailed, fixture.ready().validationError)
        assertNull(fixture.ready().roomChatRejection)
        fixture.chat.failure = null
        fixture.viewModel.send()
        runCurrent()
        assertEquals("smiles", fixture.chat.sent.single().text)
        assertEquals("original", fixture.chat.sent.single().reply!!.messageId)
        assertNull(fixture.viewModel.uiState.value.reply)
        assertEquals("", fixture.ready().draft)
        assertNull(fixture.ready().validationError)
        assertNull(fixture.ready().roomChatRejection)
        fixture.viewModel.close()
    }

    @Test
    fun everyRoomChatRejectionPreservesTheReplyAndDraftAndSuccessfulRetryClearsBoth() = runTest {
        BcRoomChatRejectionReason.entries.forEach { reason ->
            val fixture = replyVmFixture(backgroundScope, messages = listOf(replyVmRoomMessage("original")))
            val rejection = BcRoomChatRejection(
                reason = reason,
                forbiddenWord = "forbidden".takeIf { reason == BcRoomChatRejectionReason.ForbiddenWord },
            )
            fixture.chat.failure = BcRoomChatRejectedException(rejection)
            runCurrent()
            fixture.viewModel.onReply("original")
            runCurrent()
            val selected = fixture.viewModel.uiState.value.reply
            val draft = if (reason == BcRoomChatRejectionReason.EmoteBlockedByOwner) "/me smiles" else "answer"
            fixture.viewModel.onTextChange(draft)
            fixture.viewModel.send()
            runCurrent()

            assertSame(selected, fixture.viewModel.uiState.value.reply)
            assertEquals(draft, fixture.ready().draft)
            assertSame(rejection, fixture.ready().roomChatRejection)
            assertNull(fixture.ready().errorMessage)
            assertNull(fixture.ready().validationError)
            assertTrue(fixture.chat.sent.isEmpty())

            fixture.chat.failure = null
            fixture.viewModel.send()
            runCurrent()

            assertEquals(if (draft.startsWith("/me ")) "smiles" else draft, fixture.chat.sent.single().text)
            assertEquals(selected!!.reference, fixture.chat.sent.single().reply)
            assertNull(fixture.viewModel.uiState.value.reply)
            assertEquals("", fixture.ready().draft)
            assertNull(fixture.ready().roomChatRejection)
            assertNull(fixture.ready().errorMessage)
            assertNull(fixture.ready().validationError)
            fixture.viewModel.close()
        }
    }

    @Test
    fun pendingRuleRejectionKeepsANewReplyAndDraftAndRetriesTheCurrentSelection() = runTest {
        val fixture = replyVmFixture(
            backgroundScope,
            messages = listOf(replyVmRoomMessage("first"), replyVmRoomMessage("second")),
        )
        val gate = CompletableDeferred<Unit>()
        val rejection = BcRoomChatRejection(BcRoomChatRejectionReason.ForbiddenWord, "forbidden")
        fixture.chat.beforeSend = gate
        fixture.chat.failure = BcRoomChatRejectedException(rejection)
        runCurrent()
        fixture.viewModel.onReply("first")
        fixture.viewModel.onTextChange("first answer")
        fixture.viewModel.send()
        runCurrent()

        assertTrue(fixture.viewModel.uiState.value.composer is ChatComposerState.Sending)
        fixture.viewModel.onReply("second")
        fixture.viewModel.onTextChange("new answer")
        runCurrent()
        val selected = fixture.viewModel.uiState.value.reply
        gate.complete(Unit)
        runCurrent()

        assertTrue(fixture.chat.sent.isEmpty())
        assertEquals("second", selected!!.reference.messageId)
        assertSame(selected, fixture.viewModel.uiState.value.reply)
        assertEquals("new answer", fixture.ready().draft)
        assertSame(rejection, fixture.ready().roomChatRejection)
        assertNull(fixture.ready().errorMessage)
        assertNull(fixture.ready().validationError)

        fixture.chat.failure = null
        fixture.chat.beforeSend = null
        fixture.viewModel.send()
        runCurrent()

        assertEquals("new answer", fixture.chat.sent.single().text)
        assertEquals(selected.reference, fixture.chat.sent.single().reply)
        assertNull(fixture.viewModel.uiState.value.reply)
        assertEquals("", fixture.ready().draft)
        assertNull(fixture.ready().roomChatRejection)
        assertNull(fixture.ready().errorMessage)
        assertNull(fixture.ready().validationError)
        fixture.viewModel.close()
    }

    @Test
    fun successfulPendingReplyDoesNotClearANewSelectionOrNewDraft() = runTest {
        val fixture = replyVmFixture(
            backgroundScope,
            messages = listOf(replyVmRoomMessage("first"), replyVmRoomMessage("second")),
        )
        fixture.chat.beforeSend = CompletableDeferred()
        runCurrent()
        fixture.viewModel.onReply("first")
        fixture.viewModel.onTextChange("first answer")
        fixture.viewModel.send()
        runCurrent()

        fixture.viewModel.onReply("second")
        fixture.viewModel.onTextChange("new answer")
        fixture.chat.beforeSend!!.complete(Unit)
        runCurrent()

        assertEquals("first", fixture.chat.sent.single().reply!!.messageId)
        assertEquals("first answer", fixture.chat.sent.single().text)
        assertEquals("second", fixture.viewModel.uiState.value.reply!!.reference.messageId)
        assertEquals("new answer", fixture.ready().draft)
        fixture.viewModel.close()
    }

    @Test
    fun roomLengthLimitCountsUtf16IncludingSurrogatePairsWithoutTruncatingDrafts() = runTest {
        val fixture = replyVmFixture(backgroundScope)
        runCurrent()
        listOf("a".repeat(2000), "\uD83D\uDE00".repeat(1000)).forEach { text ->
            fixture.viewModel.onTextChange(text)
            fixture.viewModel.send()
            runCurrent()
            assertEquals(text, fixture.chat.sent.last().text)
            assertEquals("", fixture.ready().draft)
        }
        listOf("a".repeat(2001), "\uD83D\uDE00".repeat(1000) + "x").forEach { text ->
            fixture.viewModel.onTextChange(text)
            fixture.viewModel.send()
            runCurrent()
            assertEquals(ChatComposerValidationError.MessageTooLong, fixture.ready().validationError)
            assertEquals(text, fixture.ready().draft)
        }
        assertEquals(2, fixture.chat.sent.size)
        fixture.viewModel.close()
    }

    @Test
    fun roomCommandLimitAppliesAfterParsingAndIncludesActionControlPrefix() = runTest {
        val fixture = replyVmFixture(backgroundScope)
        runCurrent()
        val body = "a".repeat(2000)
        fixture.viewModel.onTextChange("/me $body")
        fixture.viewModel.send()
        runCurrent()
        assertEquals(body, fixture.chat.sent.last().text)

        fixture.viewModel.onTextChange("/w 1001 $body")
        fixture.viewModel.send()
        runCurrent()
        assertEquals(body, fixture.chat.sent.last().text)
        assertEquals(ChatComposerMode.Whisper, fixture.chat.sent.last().mode)

        fixture.viewModel.onTextChange("/action ${body.dropLast(1)}")
        fixture.viewModel.send()
        runCurrent()
        assertEquals("*${body.dropLast(1)}", fixture.chat.sent.last().text)

        val tooLong = "/action $body"
        fixture.viewModel.onTextChange(tooLong)
        fixture.viewModel.send()
        runCurrent()
        assertEquals(3, fixture.chat.sent.size)
        assertEquals(tooLong, fixture.ready().draft)
        assertEquals(ChatComposerValidationError.MessageTooLong, fixture.ready().validationError)
        fixture.viewModel.close()
    }

    @Test
    fun explicitEmoteAndWhisperHaveTheRoomLimitWhileCustomActionsAndBeepsDoNot() = runTest {
        val text = "a".repeat(2001)
        val room = replyVmFixture(backgroundScope)
        runCurrent()
        room.viewModel.onComposerModeChange(ChatComposerMode.RoomEmote)
        room.viewModel.onTextChange(text)
        room.viewModel.send()
        runCurrent()
        assertEquals(ChatComposerValidationError.MessageTooLong, room.ready().validationError)
        assertTrue(room.chat.sent.isEmpty())
        room.viewModel.onComposerModeChange(ChatComposerMode.CustomAction)
        room.viewModel.send()
        runCurrent()
        assertEquals(ChatComposerMode.CustomAction, room.chat.sent.single().mode)
        assertEquals(text, room.chat.sent.single().text)
        room.viewModel.close()

        val direct = replyVmFixture(backgroundScope, BcConversationId("message:1001"))
        runCurrent()
        direct.viewModel.onComposerModeChange(ChatComposerMode.Whisper)
        direct.viewModel.onTextChange(text)
        direct.viewModel.send()
        runCurrent()
        assertEquals(ChatComposerValidationError.MessageTooLong, direct.ready().validationError)
        assertTrue(direct.chat.sent.isEmpty())
        direct.viewModel.onComposerModeChange(ChatComposerMode.DirectMessage)
        direct.viewModel.send()
        runCurrent()
        assertEquals(ChatComposerMode.DirectMessage, direct.chat.sent.single().mode)
        assertEquals(text, direct.chat.sent.single().text)
        direct.viewModel.close()
    }
}

private const val ReplyVmRoomName = "Test room"

private data class ReplyVmFixture(
    val viewModel: ChatViewModel,
    val chat: ReplyVmChat,
    val connection: ReplyVmConnection,
) {
    fun ready(): ChatComposerState.Ready = viewModel.uiState.value.composer as ChatComposerState.Ready
}

private fun replyVmFixture(
    scope: CoroutineScope,
    conversationId: BcConversationId = bcRoomConversationId(ReplyVmRoomName),
    messages: List<BcMessage> = emptyList(),
): ReplyVmFixture {
    val chat = ReplyVmChat()
    val connection = ReplyVmConnection()
    return ReplyVmFixture(
        ChatViewModel(conversationId, chat, ReplyVmTimeline(messages), connection, scope = scope),
        chat,
        connection,
    )
}

private fun replyVmRoomMessage(id: String): BcMessage = BcMessage(
    id = BcMessageId(id),
    conversationId = bcRoomConversationId(ReplyVmRoomName),
    sender = BcSender(1001, "Alice"),
    kind = BcMessageKind.Chat,
    text = "original $id",
    timestamp = Instant.EPOCH,
    raw = BcRawMessage(
        sourceEvent = "ChatRoomMessage",
        wireType = "Chat",
        dictionaryJson = "[{\"Tag\":\"MsgId\",\"MsgId\":\"$id\"}]",
        roomName = ReplyVmRoomName,
    ),
)

private fun replyVmWhisperMessage(id: String): BcMessage = replyVmRoomMessage(id).let {
    it.copy(
        conversationId = BcConversationId("message:1001"),
        address = BcMessageAddress.Direct(1001),
        kind = BcMessageKind.Whisper,
        transport = BcMessageTransport.RoomWhisper,
        raw = it.raw!!.copy(wireType = "Whisper"),
    )
}

private class ReplyVmTimeline(initialMessages: List<BcMessage>) : BcSessionTimeline {
    override val conversations = MutableStateFlow(BcConversationSnapshot())
    override val persistenceFailure = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
    private val messages = MutableStateFlow(initialMessages)

    override fun observeMessages(conversationId: BcConversationId, limit: Int): Flow<BcMessageSnapshot> =
        messages.map { rows -> BcMessageSnapshot(9999, 1, rows.filter { it.conversationId == conversationId }.takeLast(limit)) }

    override fun selectConversation(conversationId: BcConversationId, owner: Any) = Unit
    override fun clearSelectedConversation(owner: Any) = Unit
    override fun isConversationVisible(conversationId: BcConversationId): Boolean = false
    override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) = Unit
}

private data class ReplyVmSent(
    val mode: ChatComposerMode,
    val text: String,
    val target: Int? = null,
    val reply: BcMessageReplyReference? = null,
)

private class ReplyVmChat : BcSessionChat {
    val sent = mutableListOf<ReplyVmSent>()
    var failure: Throwable? = null
    var beforeSend: CompletableDeferred<Unit>? = null

    private suspend fun record(value: ReplyVmSent) {
        beforeSend?.await()
        failure?.let { throw it }
        sent += value
    }

    override suspend fun sendChat(text: String) = record(ReplyVmSent(ChatComposerMode.RoomMessage, text))
    override suspend fun sendChat(text: String, replyTo: BcMessageReplyReference) =
        record(ReplyVmSent(ChatComposerMode.RoomMessage, text, reply = replyTo))
    override suspend fun sendEmote(text: String) = record(ReplyVmSent(ChatComposerMode.RoomEmote, text))
    override suspend fun sendEmote(text: String, replyTo: BcMessageReplyReference) =
        record(ReplyVmSent(ChatComposerMode.RoomEmote, text, reply = replyTo))
    override suspend fun sendAction(text: String) = record(ReplyVmSent(ChatComposerMode.CustomAction, text))
    override suspend fun sendWhisper(targetMemberNumber: Int, text: String) =
        record(ReplyVmSent(ChatComposerMode.Whisper, text, targetMemberNumber))
    override suspend fun sendWhisper(targetMemberNumber: Int, text: String, replyTo: BcMessageReplyReference) =
        record(ReplyVmSent(ChatComposerMode.Whisper, text, targetMemberNumber, replyTo))
    override suspend fun sendBeep(targetMemberNumber: Int, text: String) =
        record(ReplyVmSent(ChatComposerMode.DirectMessage, text, targetMemberNumber))
}

private class ReplyVmConnection : ConnectionStatusReader {
    override val snapshot = MutableStateFlow(
        BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = BcAccount(9999, "Me", "me"),
            currentRoom = BcRoom(BcRoomName(ReplyVmRoomName), "", 2, 10, false, null),
            roomPresenceEpoch = 1,
            roomMembers = listOf(BcMember(1001, "Alice", false)),
            roomCharacters = listOf(
                BcCharacterSnapshot(
                    id = "self",
                    memberNumber = 9999,
                    name = "Me",
                    displayName = "Me",
                    isSelf = true,
                ),
            ),
            relationships = BcRelationshipSnapshot().nextLoaded(
                memberNumber = 9999,
                relations = BcSelfRelations(friends = setOf(1001)),
            ),
        ),
    )
}
