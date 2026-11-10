package dev.vivian.mobilebc.domain.session.timeline

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionTimelinePersistenceTest {
    @Test
    fun writeFailuresStayInTimelineAndDoNotPublishUncommittedConversationChanges() {
        val operations = listOf<Pair<BcTimelinePersistenceOperation, (BcSessionTimelineController) -> Unit>>(
            BcTimelinePersistenceOperation.AdvanceReadCursor to { it.advanceReadCursor(10, 0, id, message) },
            BcTimelinePersistenceOperation.UpsertConversation to { it.upsertConversation(conversation.copy(title = "Unsaved")) },
            BcTimelinePersistenceOperation.UpsertRoomState to { it.upsertRoomState(dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot(account = BcAccount(10, "Self", "self"), currentRoom = room, roomMembers = listOf(BcMember(20, "Other", false)))) },
            BcTimelinePersistenceOperation.PersistCharacterProfile to { it.persistCharacterProfile(10, 0, character) },
        )
        operations.forEach { (operation, invoke) ->
            Fixture().use { fixture ->
                fixture.store.writeFailure = IllegalStateException("SQLite unavailable")
                invoke(fixture.controller)

                assertEquals(operation, fixture.controller.persistenceFailure.value?.operation)
                assertEquals(listOf(conversation), fixture.controller.conversations.value.rows)
                assertTrue(fixture.unhandled.isEmpty())
                assertTrue(fixture.scope.coroutineContext.job.isActive)
            }
        }
    }

    @Test
    fun readCursorFailureKeepsUnreadUntilThatConversationSucceeds() {
        Fixture().use { fixture ->
            fixture.store.writeFailure = IllegalStateException("read cursor unavailable")
            fixture.advanceReadCursor(id, message)
            assertEquals(5, fixture.controller.conversations.value.rows.single().unreadCount)

            fixture.store.writeFailure = null
            val otherId = BcConversationId("message:30")
            fixture.advanceReadCursor(otherId, message.copy(conversationId = otherId))
            assertEquals(id, fixture.controller.persistenceFailure.value?.conversationId)
            assertEquals(5, fixture.controller.conversations.value.rows.single().unreadCount)

            fixture.advanceReadCursor(id, message)
            assertNull(fixture.controller.persistenceFailure.value)
            assertEquals(0, fixture.controller.conversations.value.rows.single().unreadCount)
        }
    }

    @Test
    fun latePreviousAccountFailureCannotReplaceTheCurrentAccountFailure() = runBlocking {
        Fixture().use { fixture ->
            val oldWrite = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.store.beforeReadCursor = { owner ->
                if (owner == 10) {
                    oldWrite.complete(Unit)
                    release.await()
                }
                throw IllegalStateException("cursor unavailable")
            }
            fixture.advanceReadCursor(id, message)
            oldWrite.await()
            fixture.state.updateSession {
                it.copy(account = BcAccount(11, "New", "new"), relationships = it.relationships.copy(accountGeneration = 1))
            }
            fixture.advanceReadCursor(id, message)
            val currentFailure = fixture.controller.persistenceFailure.value
            assertEquals(11, currentFailure?.ownerMemberNumber)

            release.complete(Unit)

            assertEquals(currentFailure, fixture.controller.persistenceFailure.value)
            fixture.state.updateSession { it.copy(account = null) }
            assertNull(fixture.controller.persistenceFailure.value)
            assertTrue(fixture.unhandled.isEmpty())
        }
    }

    @Test
    fun cancellationDoesNotBecomeAStorageFailure() {
        Fixture().use { fixture ->
            var writeJob: Job? = null
            fixture.store.beforeReadCursor = { writeJob = currentCoroutineContext().job }
            fixture.store.writeFailure = CancellationException("cancelled read")
            fixture.advanceReadCursor(id, message)

            assertNull(fixture.controller.persistenceFailure.value)
            assertEquals(5, fixture.controller.conversations.value.rows.single().unreadCount)
            assertTrue(fixture.unhandled.isEmpty())
            assertTrue(writeJob?.isCancelled == true)
            assertTrue(fixture.scope.coroutineContext.job.isActive)
        }
    }

    @Test
    fun nonCooperativeWriteFailureAfterCancellationIsNotReported() = runBlocking {
        Fixture().use { fixture ->
            val started = CompletableDeferred<Job>()
            val release = CompletableDeferred<Unit>()
            fixture.store.beforeReadCursor = {
                started.complete(currentCoroutineContext().job)
                withContext(NonCancellable) { release.await() }
                throw IllegalStateException("late failed write")
            }
            fixture.advanceReadCursor(id, message)
            val writeJob = started.await()
            writeJob.cancel()
            release.complete(Unit)
            writeJob.join()

            assertTrue(writeJob.isCancelled)
            assertNull(fixture.controller.persistenceFailure.value)
            assertTrue(fixture.unhandled.isEmpty())
        }
    }

    @Test
    fun conversationObserverRetriesAfterFailureAndClearsItsErrorOnRecovery() = runTest {
        val state = loggedInState()
        val store = FailingTimelineStore().apply { observerFailuresRemaining = 1 }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        controller.start()
        runCurrent()
        assertEquals(BcTimelinePersistenceOperation.ObserveConversations, controller.persistenceFailure.value?.operation)
        assertEquals(1, store.observerStarts)

        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, store.observerStarts)
        assertEquals(listOf(conversation), controller.conversations.value.rows)
        assertNull(controller.persistenceFailure.value)
    }

    @Test
    fun observerBranchCancellationDoesNotReportOrRetryAndAccountChangeCanObserveAgain() = runTest {
        val state = loggedInState()
        val store = FailingTimelineStore().apply { profileFailure = CancellationException("cancelled observer") }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        val observation = backgroundScope.launch { controller.observeCharacterProfile(20).collect() }
        runCurrent()

        assertTrue(store.profileObserverJobs.single().isCancelled)
        assertTrue(observation.isActive)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, store.profileObserverStarts)
        assertNull(controller.persistenceFailure.value)

        store.profileFailure = null
        state.updateSession { it.copy(account = BcAccount(11, "New", "new")) }
        runCurrent()

        assertEquals(2, store.profileObserverStarts)
        assertTrue(observation.isActive)
        assertTrue(!store.profileObserverJobs.last().isCancelled)
        assertNull(controller.persistenceFailure.value)
    }

    @Test
    fun failureCarriesItsLoginGenerationAndIsReplacedOnlyByCurrentLogin() {
        Fixture().use { fixture ->
            fixture.store.writeFailure = IllegalStateException("storage unavailable")
            fixture.advanceReadCursor(id, message)
            val first = requireNotNull(fixture.controller.persistenceFailure.value)
            assertEquals(fixture.state.current.relationships.accountGeneration, first.accountGeneration)
            assertTrue(first.belongsTo(fixture.state.current))

            fixture.state.updateSession {
                it.copy(relationships = it.relationships.nextUnloaded())
            }
            assertNull(fixture.controller.persistenceFailure.value)
            assertTrue(!first.belongsTo(fixture.state.current))

            fixture.advanceReadCursor(id, message)
            val current = requireNotNull(fixture.controller.persistenceFailure.value)
            assertEquals(first.accountGeneration + 1, current.accountGeneration)
            assertTrue(current.belongsTo(fixture.state.current))
        }
    }

    @Test
    fun profileObservationLabelsTheQueryOwnerAndLoginGeneration() = runTest {
        val state = loggedInState()
        val store = FailingTimelineStore()
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        val snapshots = mutableListOf<BcContactProfileSnapshot>()
        backgroundScope.launch { controller.observeCharacterProfile(20).collect { snapshots += it } }
        runCurrent()
        assertEquals(10, snapshots.last().ownerMemberNumber)
        assertEquals(0L, snapshots.last().accountGeneration)
        state.updateSession { it.copy(relationships = it.relationships.nextUnloaded()) }
        runCurrent()
        assertEquals(10, snapshots.last().ownerMemberNumber)
        assertEquals(1L, snapshots.last().accountGeneration)
        state.updateSession { it.copy(account = BcAccount(11, "Other", "other")) }
        runCurrent()
        assertEquals(11, snapshots.last().ownerMemberNumber)
        state.updateSession { it.copy(account = null) }
        runCurrent()
        assertEquals(BcContactProfileSnapshot(), snapshots.last())
    }

    @Test
    fun readCursorRejectsPreviousAccountAndPreviousLoginAtTheWriteBoundary() {
        Fixture().use { fixture ->
            val owners = mutableListOf<Int>()
            fixture.store.beforeReadCursor = { owners += it }
            fixture.state.updateSession {
                it.copy(relationships = it.relationships.nextUnloaded())
            }
            fixture.controller.advanceReadCursor(10, 0, id, message)
            assertTrue(owners.isEmpty())
            assertEquals(5, fixture.controller.conversations.value.rows.single().unreadCount)
            fixture.state.updateSession { it.copy(account = BcAccount(11, "Other", "other")) }
            fixture.controller.advanceReadCursor(10, 1, id, message)
            assertTrue(owners.isEmpty())
            fixture.controller.advanceReadCursor(11, 1, id, message)
            assertEquals(listOf(11), owners)
            assertEquals(0, fixture.controller.conversations.value.rows.single().unreadCount)
        }
    }

    private class Fixture : AutoCloseable {
        val unhandled = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> unhandled += error })
        val state = loggedInState()
        val store = FailingTimelineStore()
        val controller = BcSessionTimelineController(state, scope, store, BcSessionMessageCoordinator()).apply { start() }
        fun advanceReadCursor(conversationId: BcConversationId, throughMessage: BcMessage) {
            controller.advanceReadCursor(
                requireNotNull(state.current.account).memberNumber,
                state.current.relationships.accountGeneration,
                conversationId,
                throughMessage,
            )
        }
        override fun close() = scope.cancel()
    }

    private class FailingTimelineStore : BcMessageTimelineStore {
        val conversations = MutableStateFlow(listOf(conversation))
        var writeFailure: Throwable? = null
        var beforeReadCursor: suspend (Int) -> Unit = {}
        var observerFailuresRemaining = 0
        var observerStarts = 0
        var profileFailure: Throwable? = null
        var profileObserverStarts = 0
        val profileObserverJobs = mutableListOf<Job>()

        override suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation) {
            writeFailure?.let { throw it }
            conversations.value = listOf(conversation)
        }
        override suspend fun upsertRoomState(ownerMemberNumber: Int, room: BcRoom, members: List<BcMember>) {
            writeFailure?.let { throw it }
        }
        override suspend fun upsertContactProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) {
            writeFailure?.let { throw it }
        }
        override fun observeContactProfile(ownerMemberNumber: Int, memberNumber: Int): Flow<BcContactProfile?> = flow {
            profileObserverStarts++
            profileObserverJobs += currentCoroutineContext().job
            profileFailure?.let { throw it }
            emit(null)
        }
        override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> = flow {
            observerStarts++
            if (observerFailuresRemaining > 0) {
                observerFailuresRemaining--
                throw IllegalStateException("query unavailable")
            }
            emitAll(conversations)
        }
        override suspend fun advanceReadCursor(ownerMemberNumber: Int, conversationId: BcConversationId, throughMessage: BcMessage) {
            beforeReadCursor(ownerMemberNumber)
            writeFailure?.let { throw it }
            conversations.value = conversations.value.map { if (it.id == conversationId) it.copy(unreadCount = 0) else it }
        }
        override fun observeMessages(ownerMemberNumber: Int, conversationId: BcConversationId, limit: Int) = flowOf(listOf(message))
    }

    private companion object {
        val id = BcConversationId("message:20")
        val conversation = BcConversation(id, ConversationType.Direct, "Other", "latest", unreadCount = 5, hasMessages = true)
        val room = BcRoom(BcRoomName("Room"), "", 2, 10, false, null)
        val character = BcCharacterSnapshot(id = "20", memberNumber = 20, name = "Changed", displayName = "Changed", isSelf = false)
        val message = BcMessage(
            id = BcMessageId("message"),
            conversationId = id,
            sender = BcSender(20, "Other", false),
            kind = BcMessageKind.Chat,
            text = "latest",
            timestamp = Instant.EPOCH,
        )
        fun loggedInState() = BcSessionRuntimeState().apply { updateSession { it.copy(account = BcAccount(10, "Self", "self")) } }
    }
}
