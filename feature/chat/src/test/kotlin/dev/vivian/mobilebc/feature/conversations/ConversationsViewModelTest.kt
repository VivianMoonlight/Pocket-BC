package dev.vivian.mobilebc.feature.conversations

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipCapabilityQueryResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipContractActionResult
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipMutationResult
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationships
import dev.vivian.mobilebc.domain.session.timeline.BcConversationSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcMessageSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceOperation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationsViewModelTest {
    @Test
    fun staleConversationRowsCannotBeRelabeledAsAnotherAccountOrLogin() = runTest {
        val row = conversation(1001).copy(hasMessages = true, unreadCount = 4)
        val timeline = EmptyTimeline(listOf(row))
        val connection = object : ConnectionStatusReader {
            override val snapshot = MutableStateFlow(onlineSession())
        }
        val model = ConversationsViewModel(timeline, connection, FakeRelationships(), scope = backgroundScope)
        runCurrent()
        assertEquals(listOf(row), model.uiState.value.conversations)
        connection.snapshot.value = connection.snapshot.value.copy(account = BcAccount(8888, "Second", "second"))
        runCurrent()
        assertTrue(model.uiState.value.conversations.isEmpty())
        timeline.conversations.value = BcConversationSnapshot(8888, 0, listOf(row))
        runCurrent()
        assertEquals(listOf(row), model.uiState.value.conversations)
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.nextUnloaded(),
        )
        runCurrent()
        assertTrue(model.uiState.value.conversations.isEmpty())
        timeline.conversations.value = BcConversationSnapshot(8888, 1, listOf(row))
        runCurrent()
        assertEquals(listOf(row), model.uiState.value.conversations)
    }

    @Test
    fun timelineStorageFailureIsProjectedWithoutChangingConnectionStatus() = runTest {
        val timeline = EmptyTimeline()
        val connection = object : ConnectionStatusReader {
            override val snapshot = MutableStateFlow(onlineSession())
        }
        val viewModel = ConversationsViewModel(timeline, connection, FakeRelationships(), scope = backgroundScope)
        runCurrent()
        val failure = BcTimelinePersistenceFailure(BcTimelinePersistenceOperation.ObserveConversations, 9999, 0)

        timeline.persistenceFailure.value = failure
        runCurrent()

        assertEquals(failure, viewModel.uiState.value.persistenceFailure)
        assertEquals(BcConnectionHealth.VerifiedOnline, connection.snapshot.value.connectionHealth)
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.nextUnloaded(),
        )
        runCurrent()
        assertNull(viewModel.uiState.value.persistenceFailure)
        val currentFailure = failure.copy(accountGeneration = 1)
        timeline.persistenceFailure.value = currentFailure
        runCurrent()
        assertEquals(currentFailure, viewModel.uiState.value.persistenceFailure)
        timeline.persistenceFailure.value = null
        runCurrent()
        assertNull(viewModel.uiState.value.persistenceFailure)
    }

    @Test
    fun leavingOrDisconnectingClearsSameRoomBeforeTimelineUpdates() = runTest {
        val conversation = conversation(
            memberNumber = 1001,
            availability = setOf(ConversationAvailability.SameRoom, ConversationAvailability.OnlineFriend),
        ).copy(hasMessages = true, unreadCount = 2, updatedAt = Instant.EPOCH)
        val timeline = EmptyTimeline(listOf(conversation))
        val connection = object : ConnectionStatusReader {
            override val snapshot = MutableStateFlow(onlineSession(1001))
        }
        val viewModel = ConversationsViewModel(
            timeline = timeline,
            connectionStatus = connection,
            relationships = FakeRelationships(),
            scope = backgroundScope,
        )
        runCurrent()
        assertEquals(conversation, viewModel.uiState.value.conversations.single())

        connection.snapshot.value = connection.snapshot.value.copy(currentRoom = null)
        runCurrent()
        val withoutSameRoom = conversation.copy(availability = setOf(ConversationAvailability.OnlineFriend))
        assertEquals(withoutSameRoom, viewModel.uiState.value.conversations.single())
        assertEquals(conversation, timeline.conversations.value.rows.single())

        connection.snapshot.value = onlineSession(1001).copy(connectionHealth = BcConnectionHealth.Stale)
        runCurrent()
        assertEquals(withoutSameRoom, viewModel.uiState.value.conversations.single())
        assertFalse(viewModel.uiState.value.directPresenceKnown)
    }

    @Test
    fun roomChangeUsesCurrentMembersAndExcludesAccountEvenIfSelfFlagIsMissing() = runTest {
        val oldMember = conversation(1001, setOf(ConversationAvailability.SameRoom))
        val newMember = conversation(1002, setOf(ConversationAvailability.OnlineFriend))
        val account = conversation(9999, setOf(ConversationAvailability.SameRoom))
        val timeline = EmptyTimeline(listOf(oldMember, newMember, account))
        val connection = object : ConnectionStatusReader {
            override val snapshot = MutableStateFlow(onlineSession(1001))
        }
        val viewModel = ConversationsViewModel(
            timeline = timeline,
            connectionStatus = connection,
            relationships = FakeRelationships(),
            scope = backgroundScope,
        )
        runCurrent()

        connection.snapshot.value = onlineSession(1002, 9999).let {
            it.copy(currentRoom = checkNotNull(it.currentRoom).copy(name = BcRoomName("New room")))
        }
        runCurrent()
        val displayed = viewModel.uiState.value.conversations.associateBy { it.id }
        assertFalse(oldMember.id in displayed)
        assertEquals(
            setOf(ConversationAvailability.SameRoom, ConversationAvailability.OnlineFriend),
            displayed.getValue(newMember.id).availability,
        )
        assertFalse(account.id in displayed)
        assertEquals(listOf(oldMember, newMember, account), timeline.conversations.value.rows)
    }

    @Test
    fun currentRoomMembershipAddsMissingFlagToHistoryAndSortsAvailableEmptyConversation() = runTest {
        val history = conversation(1001).copy(hasMessages = true, unreadCount = 3, updatedAt = Instant.EPOCH)
        val sameRoom = conversation(1002)
        val onlineFriend = conversation(1003, setOf(ConversationAvailability.OnlineFriend))
        val viewModel = ConversationsViewModel(
            timeline = EmptyTimeline(listOf(onlineFriend, sameRoom, history)),
            connectionStatus = object : ConnectionStatusReader {
                override val snapshot = MutableStateFlow(onlineSession(1001, 1002))
            },
            relationships = FakeRelationships(),
            scope = backgroundScope,
        )
        runCurrent()

        assertEquals(
            listOf(
                history.copy(availability = setOf(ConversationAvailability.SameRoom)),
                sameRoom.copy(availability = setOf(ConversationAvailability.SameRoom)),
                onlineFriend,
            ),
            viewModel.uiState.value.conversations,
        )
    }

    @Test
    fun cancelledFriendRequestPropagatesAndReleasesOperation() = runTest {
        val relationships = FakeRelationships()
        val viewModel = ConversationsViewModel(
            timeline = EmptyTimeline(),
            connectionStatus = object : ConnectionStatusReader {
                override val snapshot = MutableStateFlow(BcSessionSnapshot())
            },
            relationships = relationships,
            scope = backgroundScope,
        )

        viewModel.acceptFriendRequest(1001)
        runCurrent()

        assertTrue(checkNotNull(relationships.operationJob).isCancelled)
        assertNull(viewModel.uiState.value.acceptingFriendRequestMemberNumber)
        assertNull(viewModel.uiState.value.friendRequestMessage)

        viewModel.acceptFriendRequest(1001)
        runCurrent()

        assertNull(viewModel.uiState.value.acceptingFriendRequestMemberNumber)
        assertEquals("Friend request accepted.", viewModel.uiState.value.friendRequestMessage)
    }

    @Test
    fun friendRequestOperationRejectsDuplicatesAndKeepsFeedbackWithinItsAccountGeneration() = runTest {
        val acceptanceGate = CompletableDeferred<Unit>()
        val relationships = FakeRelationships(cancelFirst = false, acceptanceGate = acceptanceGate)
        val connection = object : ConnectionStatusReader {
            override val snapshot = MutableStateFlow(onlineSession())
        }
        val viewModel = ConversationsViewModel(
            timeline = EmptyTimeline(),
            connectionStatus = connection,
            relationships = relationships,
            scope = backgroundScope,
        )

        viewModel.acceptFriendRequest(1001)
        viewModel.acceptFriendRequest(1001)
        runCurrent()
        assertEquals(1001, viewModel.uiState.value.acceptingFriendRequestMemberNumber)
        assertEquals(listOf(1001), relationships.acceptedMemberNumbers)

        viewModel.dismissFriendRequest(1002)
        runCurrent()
        assertEquals(1001, viewModel.uiState.value.acceptingFriendRequestMemberNumber)
        assertEquals("Friend request dismissed.", viewModel.uiState.value.friendRequestMessage)

        acceptanceGate.complete(Unit)
        runCurrent()
        assertNull(viewModel.uiState.value.acceptingFriendRequestMemberNumber)
        assertEquals("Friend request accepted.", viewModel.uiState.value.friendRequestMessage)
        assertEquals(listOf(1001), relationships.acceptedMemberNumbers)

        listOf(
            10000 to null,
            10000 to IllegalStateException("Old account request failed"),
            9999 to null,
            9999 to IllegalStateException("Old generation request failed"),
        ).forEach { (nextOwner, failure) ->
            val oldSession = onlineSession().copy(
                relationships = connection.snapshot.value.relationships.nextLoaded(
                    9999,
                    connection.snapshot.value.relationships.self,
                ),
            )
            connection.snapshot.value = oldSession
            val oldRequestGate = CompletableDeferred<Unit>()
            relationships.acceptanceGate = oldRequestGate
            relationships.failure = failure
            viewModel.acceptFriendRequest(1003)
            runCurrent()
            assertEquals(1003, viewModel.uiState.value.acceptingFriendRequestMemberNumber)

            connection.snapshot.value = oldSession.copy(
                account = checkNotNull(oldSession.account).copy(memberNumber = nextOwner),
                relationships = oldSession.relationships.nextLoaded(nextOwner, oldSession.relationships.self),
            )
            runCurrent()
            assertNull(viewModel.uiState.value.acceptingFriendRequestMemberNumber)
            assertNull(viewModel.uiState.value.friendRequestMessage)
            viewModel.dismissFriendRequest(1004)
            runCurrent()
            assertEquals("Friend request dismissed.", viewModel.uiState.value.friendRequestMessage)

            oldRequestGate.complete(Unit)
            runCurrent()
            assertNull(viewModel.uiState.value.acceptingFriendRequestMemberNumber)
            assertEquals("Friend request dismissed.", viewModel.uiState.value.friendRequestMessage)

            relationships.acceptanceGate = null
            relationships.failure = null
            viewModel.acceptFriendRequest(1005)
            runCurrent()
            assertNull(viewModel.uiState.value.acceptingFriendRequestMemberNumber)
            assertEquals("Friend request accepted.", viewModel.uiState.value.friendRequestMessage)
        }
    }

    @Test
    fun oldLoginAcceptanceCannotBlockOrClearANewLoginRequest() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        val relationships = FakeRelationships(cancelFirst = false, acceptanceGate = oldGate)
        val connection = object : ConnectionStatusReader {
            override val snapshot = MutableStateFlow(onlineSession())
        }
        val model = ConversationsViewModel(EmptyTimeline(), connection, relationships, scope = backgroundScope)
        model.acceptFriendRequest(1001)
        runCurrent()
        assertEquals(1001, model.uiState.value.acceptingFriendRequestMemberNumber)
        connection.snapshot.value = connection.snapshot.value.copy(
            relationships = connection.snapshot.value.relationships.nextUnloaded(),
        )
        relationships.acceptanceGate = newGate
        model.acceptFriendRequest(1002)
        model.acceptFriendRequest(1002)
        runCurrent()
        assertEquals(listOf(1001, 1002), relationships.acceptedMemberNumbers)
        assertEquals(1002, model.uiState.value.acceptingFriendRequestMemberNumber)
        oldGate.complete(Unit)
        runCurrent()
        assertEquals(1002, model.uiState.value.acceptingFriendRequestMemberNumber)
        assertNull(model.uiState.value.friendRequestMessage)
        newGate.complete(Unit)
        runCurrent()
        assertNull(model.uiState.value.acceptingFriendRequestMemberNumber)
        assertEquals("Friend request accepted.", model.uiState.value.friendRequestMessage)
    }

    private class FakeRelationships(
        cancelFirst: Boolean = true,
        var acceptanceGate: CompletableDeferred<Unit>? = null,
    ) : BcSessionRelationships {
        var operationJob: Job? = null
        val acceptedMemberNumbers = mutableListOf<Int>()
        var failure: Throwable? = null
        private var cancelNext = cancelFirst

        override suspend fun acceptFriendRequest(senderMemberNumber: Int): BcRelationshipMutationResult {
            acceptedMemberNumbers += senderMemberNumber
            operationJob = currentCoroutineContext().job
            acceptanceGate?.await()
            failure?.let { throw it }
            if (cancelNext) {
                cancelNext = false
                throw CancellationException("Request cancelled")
            }
            return BcRelationshipMutationResult.Submitted
        }

        override suspend fun queryRelationshipCapabilities(memberNumber: Int): BcRelationshipCapabilityQueryResult =
            error("Unexpected capability query")

        override suspend fun submitRelationshipContractAction(
            memberNumber: Int,
            kind: BcRelationshipContractKind,
            action: BcRelationshipContractAction,
            destructiveActionConfirmed: Boolean,
        ): BcRelationshipContractActionResult = error("Unexpected contract action")

        override suspend fun updateRelationshipList(
            memberNumber: Int,
            kind: BcRelationshipListKind,
            enabled: Boolean,
        ): BcRelationshipMutationResult = error("Unexpected list update")

        override fun recordFriendRequest(senderMemberNumber: Int, receivedAtMillis: Long) = Unit
        override fun dismissFriendRequest(senderMemberNumber: Int) = Unit
    }

    private fun conversation(
        memberNumber: Int,
        availability: Set<ConversationAvailability> = emptySet(),
    ) = BcConversation(
        id = BcConversationId("message:$memberNumber"),
        type = ConversationType.Direct,
        title = "#$memberNumber",
        subtitle = "",
        availability = availability,
    )

    private fun onlineSession(vararg memberNumbers: Int) = BcSessionSnapshot(
        state = BcSessionState.Ready,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        desiredOnline = true,
        account = BcAccount(9999, "Me", "me"),
        currentRoom = BcRoom(
            name = BcRoomName("Room"),
            description = "",
            memberCount = memberNumbers.size,
            memberLimit = 10,
            isLocked = false,
            language = null,
        ),
        roomMembers = memberNumbers.map { BcMember(it, "#$it", isSelf = false) },
    )

    private class EmptyTimeline(initialConversations: List<BcConversation> = emptyList()) : BcSessionTimeline {
        override val conversations = MutableStateFlow(BcConversationSnapshot(9999, 0, initialConversations))
        override val persistenceFailure = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
        override fun observeMessages(conversationId: BcConversationId, limit: Int) =
            flowOf(BcMessageSnapshot())
        override fun selectConversation(conversationId: BcConversationId, owner: Any) = Unit
        override fun clearSelectedConversation(owner: Any) = Unit
        override fun isConversationVisible(conversationId: BcConversationId) = false
        override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) = Unit
    }
}
