package dev.vivian.mobilebc.domain.session.chat

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageReplyReference
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomChatRejectedException
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.bcMessageIdFromDictionaryOrNull
import dev.vivian.mobilebc.core.protocol.bcReplyIdFromDictionaryOrNull
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessage
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionChatControllerReplyTest {
    @Test
    fun ordinaryWhisperQueuesItsOriginRoomWithoutCreatingAReply() = runTest {
        val fixture = Fixture()
        fixture.controller.sendWhisper(20, "A room secret")
        val (command, local) = fixture.queued.single()
        assertEquals("A", command.originRoomName)
        assertEquals("A", local.raw?.roomName)
        assertEquals(null, command.replyToId)
        assertEquals(null, command.replyRoomName)
    }

    @Test
    fun roomChatAndEmoteRepliesQueueTheNativeIdAndCapturedRoom() = runTest {
        listOf(SendPath.ChatReply, SendPath.EmoteReply).forEach { path ->
            val fixture = Fixture()

            fixture.send(path, "Reply text")

            val (command, local) = fixture.queued.single()
            assertEquals(if (path == SendPath.ChatReply) BcMessageKind.Chat else BcMessageKind.Emote, command.kind)
            assertEquals(BcMessageTransport.RoomChat, command.transport)
            assertEquals(bcRoomConversationId("A"), command.conversationId)
            assertEquals("native-target", command.replyToId)
            assertEquals("A", command.replyRoomName)
            assertNull(command.originRoomName)
            assertEquals(10, command.ownerMemberNumber)
            assertEquals(command.clientMsgId, local.id.value)
            assertEquals("native-target", local.replyToId)
            assertEquals("A", local.raw?.roomName)
            assertEquals(command.clientMsgId, bcMessageIdFromDictionaryOrNull(local.raw?.dictionaryJson))
            assertEquals("native-target", bcReplyIdFromDictionaryOrNull(local.raw?.dictionaryJson))
            assertEquals(DeliveryState.Sending, local.delivery)
            assertEquals(listOf(command.clientMsgId), fixture.dispatched)
        }
    }

    @Test
    fun whisperCanQuoteAPublicMessageFromTheCurrentRoom() = runTest {
        val fixture = Fixture()

        fixture.controller.sendWhisper(20, "Private reply", roomReply())

        assertWhisperReply(fixture)
    }

    @Test
    fun whisperCanReplyWithinTheSamePrivateConversation() = runTest {
        val fixture = Fixture()

        fixture.controller.sendWhisper(20, "Private reply", whisperReply())

        assertWhisperReply(fixture)
    }

    @Test
    fun whisperRepliesCannotMoveToAnotherPrivateConversation() = runTest {
        val fixture = Fixture()

        assertRejected { fixture.controller.sendWhisper(30, "Wrong peer", whisperReply()) }

        fixture.assertNothingQueued()
    }

    @Test
    fun publicRepliesAndWhisperRepliesCannotMoveToAnotherRoom() = runTest {
        listOf(roomReply(), whisperReply()).forEach { reply ->
            val fixture = Fixture()
            fixture.state.updateSession { it.copy(currentRoom = room("B")) }

            assertRejected { fixture.controller.sendWhisper(20, "Wrong room", reply) }

            fixture.assertNothingQueued()
        }
        val fixture = Fixture()
        fixture.state.updateSession { it.copy(currentRoom = room("B")) }
        assertRejected { fixture.controller.sendChat("Wrong room", roomReply()) }
        fixture.assertNothingQueued()
    }

    @Test
    fun privateReplyReferencesCannotBeSentPublicly() = runTest {
        val fixture = Fixture()

        assertRejected { fixture.controller.sendChat("Public leak", whisperReply()) }
        assertRejected { fixture.controller.sendEmote("Public leak", whisperReply()) }

        fixture.assertNothingQueued()
    }

    @Test
    fun malformedReplyReferencesAreRejectedBeforeTheyReachTheQueue() = runTest {
        val fixture = Fixture()
        val references = listOf(
            roomReply().copy(messageId = " "),
            roomReply().copy(conversationId = bcRoomConversationId("B")),
            roomReply().copy(transport = BcMessageTransport.AccountBeep),
            roomReply().copy(transport = BcMessageTransport.LocalSystem),
        )
        references.forEach { reply -> assertRejected { fixture.controller.sendChat("Invalid reply", reply) } }

        fixture.assertNothingQueued()
    }

    @Test
    fun loggedOutRoomAndWhisperSendPathsCannotQueueMessages() = runTest {
        SendPath.entries.forEach { path ->
            val fixture = Fixture()
            fixture.state.updateSession { it.copy(account = null) }

            assertRejected { fixture.send(path, "Hello") }

            fixture.assertNothingQueued()
        }
    }

    @Test
    fun unverifiedConnectionRejectsEveryRoomAndWhisperSendPathAndStartsReconnect() = runTest {
        SendPath.entries.forEach { path ->
            val fixture = Fixture()
            fixture.state.updateSession { it.copy(connectionHealth = BcConnectionHealth.Stale) }

            assertRejected { fixture.send(path, "Hello") }

            fixture.assertNothingQueued()
            assertEquals(listOf(ReconnectReason.SilentDisconnect), fixture.failures)
        }
    }

    @Test
    fun expiredVerificationRejectsEveryRoomAndWhisperSendPathBeforeQueueing() = runTest {
        SendPath.entries.forEach { path ->
            val fixture = Fixture().apply { fresh = false }

            assertRejected { fixture.send(path, "Hello") }

            fixture.assertNothingQueued()
            assertEquals(listOf(ReconnectReason.SilentDisconnect), fixture.failures)
        }
    }

    @Test
    fun roomAndWhisperSendPathsRequireCurrentRoomMembership() = runTest {
        SendPath.entries.forEach { path ->
            val fixture = Fixture()
            fixture.state.updateSession { it.copy(currentRoom = null) }

            assertRejected { fixture.send(path, "Hello") }

            fixture.assertNothingQueued()
        }
        listOf(SendPath.Whisper, SendPath.WhisperReply).forEach { path ->
            val fixture = Fixture()
            fixture.state.updateSession { it.copy(roomMembers = emptyList()) }

            assertRejected { fixture.send(path, "Hello") }

            fixture.assertNothingQueued()
        }
    }

    @Test
    fun ordinaryAndReplySendPathsAccept2000Utf16UnitsAndReject2001() = runTest {
        assertTextBoundary("x".repeat(2_000), "x".repeat(2_001))
    }

    @Test
    fun emojiConsumeTwoUtf16UnitsForAllOrdinaryAndReplySendPaths() = runTest {
        val emoji = "\uD83D\uDE00"

        assertTextBoundary(emoji.repeat(1_000), emoji.repeat(1_000) + "a")
    }

    @Test
    fun actionKeepsItsExistingLengthBehavior() = runTest {
        val fixture = Fixture()
        val text = "x".repeat(2_001)

        fixture.controller.sendAction(text)

        assertEquals(text, fixture.queued.single().first.text)
        assertEquals(BcMessageKind.Action, fixture.queued.single().first.kind)
        assertEquals(1, fixture.dispatched.size)
    }

    @Test
    fun chatEmoteAndWhisperPreflightReceivesTheOriginalTextAndTargetBeforeQueueing() = runTest {
        val text = "  /me remains literal at the session boundary  "
        SendPath.entries.forEach { path ->
            lateinit var fixture: Fixture
            val validations = mutableListOf<Triple<BcMessageKind, String, Int?>>()
            fixture = Fixture(validateRoomChat = { kind, originalText, target ->
                fixture.assertNothingQueued()
                validations += Triple(kind, originalText, target)
            })

            fixture.send(path, text)

            val expectedKind = when (path) {
                SendPath.Chat, SendPath.ChatReply -> BcMessageKind.Chat
                SendPath.Emote, SendPath.EmoteReply -> BcMessageKind.Emote
                SendPath.Whisper, SendPath.WhisperReply -> BcMessageKind.Whisper
            }
            assertEquals(
                listOf(Triple(expectedKind, text, 20.takeIf { expectedKind == BcMessageKind.Whisper })),
                validations,
            )
            assertEquals(text, fixture.queued.single().first.text)
            assertEquals(text, fixture.queued.single().second.text)
            assertEquals(1, fixture.dispatched.size)
        }
    }

    @Test
    fun preflightRejectionCannotCreateLocalMessagesQueueCommandsOrStartReconnect() = runTest {
        SendPath.entries.forEach { path ->
            val rejection = BcRoomChatRejection(BcRoomChatRejectionReason.ForbiddenWord, "BAD")
            val expected = BcRoomChatRejectedException(rejection)
            val fixture = Fixture(validateRoomChat = { _, _, _ -> throw expected })

            val actual = runCatching { fixture.send(path, "bad") }.exceptionOrNull()

            assertSame(expected, actual)
            assertSame(rejection, (actual as BcRoomChatRejectedException).rejection)
            fixture.assertNothingQueued()
            assertTrue(fixture.failures.isEmpty())
        }
    }

    @Test
    fun cancellingPreflightPropagatesWithoutQueueingOrStartingReconnect() = runTest {
        val expected = CancellationException("Caller closed")
        val fixture = Fixture(validateRoomChat = { _, _, _ -> throw expected })

        assertSame(expected, runCatching { fixture.controller.sendChat("Hello") }.exceptionOrNull())

        fixture.assertNothingQueued()
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun changingAccountWhilePreflightIsSuspendedCannotQueueThePreviousAccountsMessage() = runTest {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val fixture = Fixture(validateRoomChat = { _, _, _ ->
            entered.complete(Unit)
            resume.await()
        })
        val sending = async { runCatching { fixture.controller.sendChat("Previous account's text") } }
        runCurrent()
        assertTrue(entered.isCompleted)
        fixture.assertNothingQueued()

        fixture.state.updateSession { it.copy(account = BcAccount(99, "Other", "other")) }
        resume.complete(Unit)
        val failure = sending.await().exceptionOrNull()

        assertTrue("Expected account scope rejection; got $failure", failure is IllegalStateException)
        fixture.assertNothingQueued()
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun changingRoomsOrRevisitingTheSameRoomDuringPreflightCannotQueueThePreviousVisitsMessage() = runTest {
        val changes: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(currentRoom = room("B")) },
            { it.copy(roomPresenceEpoch = it.roomPresenceEpoch + 1) },
        )
        changes.forEach { change ->
            SendPath.entries.forEach { path -> assertSuspendedPreflightScopeRejected(path, change) }
        }
    }

    @Test
    fun loggingInAgainAsTheSameAccountDuringPreflightRejectsThePreviousLoginMessage() = runTest {
        SendPath.entries.forEach { path ->
            assertSuspendedPreflightScopeRejected(path) { it.copy(selfRuleLogSessionId = "new-login") }
        }
    }

    @Test
    fun whisperTargetLeavingDuringPreflightCannotCreateAPrivateQueuedMessage() = runTest {
        listOf(SendPath.Whisper, SendPath.WhisperReply).forEach { path ->
            assertSuspendedPreflightScopeRejected(path) {
                it.copy(roomMembers = it.roomMembers.filterNot { member -> member.memberNumber == 20 })
            }
        }
    }

    @Test
    fun actionsAndBeepsKeepTheirOwnBehaviorWithoutInvokingRoomChatPreflight() = runTest {
        val fixture = Fixture(validateRoomChat = { _, _, _ -> error("Room preflight must not run") })
        val text = "x".repeat(2_001)

        fixture.controller.sendAction(text)
        fixture.controller.sendBeep(20, text)

        assertEquals(listOf(BcMessageKind.Action, BcMessageKind.Beep), fixture.queued.map { it.first.kind })
        assertEquals(listOf(text, text), fixture.queued.map { it.first.text })
        assertEquals(2, fixture.dispatched.size)
        assertTrue(fixture.failures.isEmpty())
    }

    @Test
    fun finalRuleRejectionPropagatesItsTypedReasonWithoutTreatingItAsConnectionFailure() = runTest {
        SendPath.entries.forEach { path ->
            val rejection = BcRoomChatRejection(BcRoomChatRejectionReason.TalkBlockedByOwner)
            val fixture = Fixture().apply {
                dispatchResult = QueuedOutboundDispatchResult.Failed("Rule changed before emit", rejection)
            }

            val failure = runCatching { fixture.send(path, "Hello") }.exceptionOrNull()

            assertTrue("Expected typed rule rejection; got $failure", failure is BcRoomChatRejectedException)
            assertSame(rejection, (failure as BcRoomChatRejectedException).rejection)
            assertEquals(1, fixture.queued.size)
            assertEquals(1, fixture.dispatched.size)
            assertTrue(fixture.failures.isEmpty())
            assertEquals(BcSessionState.Ready, fixture.state.current.state)
            assertEquals(BcConnectionHealth.VerifiedOnline, fixture.state.current.connectionHealth)
        }
    }

    @Test
    fun ordinaryFinalDispatchFailureStillStartsReconnectWithItsExistingMessage() = runTest {
        listOf("Socket write failed", null).forEach { message ->
            val fixture = Fixture().apply { dispatchResult = QueuedOutboundDispatchResult.Failed(message) }

            assertNull(runCatching { fixture.controller.sendChat("Hello") }.exceptionOrNull())

            assertEquals(1, fixture.queued.size)
            assertEquals(1, fixture.dispatched.size)
            assertEquals(listOf(ReconnectReason.OperationFailed), fixture.failures)
            assertEquals(message ?: "Queued message dispatch failed.", fixture.failureCauses.single()?.message)
        }
    }

    @Test
    fun pendingSubmittedAndConfirmedDispatchResultsRemainSuccessfulWithoutReconnect() = runTest {
        listOf(
            QueuedOutboundDispatchResult.NotDispatched,
            QueuedOutboundDispatchResult.Submitted,
            QueuedOutboundDispatchResult.Confirmed,
        ).forEach { result ->
            val fixture = Fixture().apply { dispatchResult = result }

            assertNull(runCatching { fixture.controller.sendWhisper(20, "Hello") }.exceptionOrNull())

            assertEquals(1, fixture.queued.size)
            assertEquals(1, fixture.dispatched.size)
            assertTrue(fixture.failures.isEmpty())
        }
    }

    private suspend fun assertTextBoundary(accepted: String, rejected: String) {
        SendPath.entries.forEach { path ->
            val fixture = Fixture()
            fixture.send(path, accepted)
            assertEquals(accepted, fixture.queued.single().first.text)
            assertEquals(accepted, fixture.queued.single().second.text)
            assertEquals(1, fixture.dispatched.size)

            val rejectedFixture = Fixture()
            assertRejected { rejectedFixture.send(path, rejected) }
            rejectedFixture.assertNothingQueued()
        }
    }

    private suspend fun TestScope.assertSuspendedPreflightScopeRejected(
        path: SendPath,
        change: (BcSessionSnapshot) -> BcSessionSnapshot,
    ) {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val fixture = Fixture(validateRoomChat = { _, _, _ ->
            entered.complete(Unit)
            resume.await()
        })
        val sending = async { runCatching { fixture.send(path, "Previous session's text") } }
        runCurrent()
        assertTrue(entered.isCompleted)
        fixture.assertNothingQueued()

        fixture.state.updateSession(change)
        resume.complete(Unit)
        val failure = sending.await().exceptionOrNull()

        assertTrue("Expected $path scope rejection; got $failure", failure is IllegalStateException)
        fixture.assertNothingQueued()
        assertTrue(fixture.failures.isEmpty())
    }

    private fun assertWhisperReply(fixture: Fixture) {
        val (command, local) = fixture.queued.single()
        assertEquals(BcMessageKind.Whisper, command.kind)
        assertEquals(BcMessageTransport.RoomWhisper, command.transport)
        assertEquals(BcConversationId("message:20"), command.conversationId)
        assertEquals(20, command.targetMemberNumber)
        assertEquals("native-target", command.replyToId)
        assertEquals("A", command.replyRoomName)
        assertEquals("A", command.originRoomName)
        assertEquals(BcMessageAddress.Direct(20), local.address)
        assertEquals("A", local.raw?.roomName)
        assertEquals("native-target", local.replyToId)
        assertEquals("native-target", bcReplyIdFromDictionaryOrNull(local.raw?.dictionaryJson))
        assertEquals(listOf(command.clientMsgId), fixture.dispatched)
    }

    private suspend fun assertRejected(action: suspend () -> Unit) {
        val failure = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected rejection before queueing; got $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }

    private enum class SendPath { Chat, ChatReply, Emote, EmoteReply, Whisper, WhisperReply }

    private class Fixture(
        validateRoomChat: suspend (BcMessageKind, String, Int?) -> Unit = { _, _, _ -> },
    ) {
        val state = BcSessionRuntimeState().apply {
            updateSession {
                it.copy(
                    state = BcSessionState.Ready,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    desiredOnline = true,
                    account = BcAccount(10, "Self", "self"),
                    currentRoom = room("A"),
                    selfRuleLogSessionId = "original-login",
                    roomMembers = listOf(BcMember(10, "Self", true), BcMember(20, "Alice", false), BcMember(30, "Bob", false)),
                    relationships = it.relationships.nextLoaded(
                        memberNumber = 10,
                        relations = BcSelfRelations(friends = setOf(20)),
                    ),
                )
            }
        }
        val queued = mutableListOf<Pair<BcOutboundMessage, BcMessage>>()
        val dispatched = mutableListOf<String>()
        val failures = mutableListOf<ReconnectReason>()
        val failureCauses = mutableListOf<Throwable?>()
        var fresh = true
        var dispatchResult: QueuedOutboundDispatchResult = QueuedOutboundDispatchResult.Submitted
        val controller = BcSessionChatController(
            stateHolder = state,
            outboundStore = object : BcOutboundMessageStore {
                override suspend fun enqueue(message: BcOutboundMessage, localMessage: BcMessage) {
                    queued += message to localMessage
                }
            },
            dispatchQueuedOutbound = { clientMsgId ->
                dispatched += clientMsgId
                dispatchResult
            },
            messageCoordinator = BcSessionMessageCoordinator(),
            onOperationFailure = { reason, cause ->
                failures += reason
                failureCauses += cause
            },
            isVerifiedFresh = { fresh },
            validateRoomChat = validateRoomChat,
        )

        suspend fun send(path: SendPath, text: String) {
            when (path) {
                SendPath.Chat -> controller.sendChat(text)
                SendPath.ChatReply -> controller.sendChat(text, roomReply())
                SendPath.Emote -> controller.sendEmote(text)
                SendPath.EmoteReply -> controller.sendEmote(text, roomReply())
                SendPath.Whisper -> controller.sendWhisper(20, text)
                SendPath.WhisperReply -> controller.sendWhisper(20, text, whisperReply())
            }
        }

        fun assertNothingQueued() {
            assertTrue(queued.isEmpty())
            assertTrue(dispatched.isEmpty())
        }
    }

    private companion object {
        fun roomReply() = BcMessageReplyReference("native-target", bcRoomConversationId("A"), BcMessageTransport.RoomChat, "A")

        fun whisperReply() = BcMessageReplyReference("native-target", BcConversationId("message:20"), BcMessageTransport.RoomWhisper, "A")

        fun room(name: String) = BcRoom(
            name = BcRoomName(name),
            description = "",
            memberCount = 3,
            memberLimit = 10,
            isLocked = false,
            language = null,
        )
    }
}
