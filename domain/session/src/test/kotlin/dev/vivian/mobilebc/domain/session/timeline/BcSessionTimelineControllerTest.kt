package dev.vivian.mobilebc.domain.session.timeline

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import java.time.Instant
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BcSessionTimelineControllerTest {
    @Test
    fun roomPersistenceUsesOneSourceSnapshotAndRejectsPreviousLogin() = runTest {
        val state = loggedInState()
        val room = BcRoom(BcRoomName("Original room"), "", 2, 10, false, null)
        state.updateSession { it.copy(currentRoom = room, roomMembers = listOf(BcMember(20, "Original member", false))) }
        val source = state.current
        val roomWrites = mutableListOf<Triple<Int, BcRoom, List<BcMember>>>()
        val profileWrites = mutableListOf<Pair<Int, List<BcContactProfile>>>()
        val store = object : BcMessageTimelineStore by FakeTimelineStore() {
            override suspend fun upsertRoomState(ownerMemberNumber: Int, room: BcRoom, members: List<BcMember>) {
                roomWrites += Triple(ownerMemberNumber, room, members)
            }
            override suspend fun upsertContactProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) {
                profileWrites += ownerMemberNumber to profiles
            }
        }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        controller.upsertRoomState(source)
        state.updateSession { it.copy(account = BcAccount(11, "Other", "other"), roomMembers = emptyList(), currentRoom = null) }
        runCurrent()
        assertEquals(listOf(Triple(10, room, source.roomMembers)), roomWrites)
        assertEquals(10, profileWrites.single().first)
        assertEquals("Original member", profileWrites.single().second.single().name)
        controller.upsertRoomState(source)
        runCurrent()
        assertEquals(1, roomWrites.size)
        state.updateSession { source.copy(relationships = source.relationships.nextUnloaded()) }
        controller.upsertRoomState(source)
        runCurrent()
        assertEquals(1, profileWrites.size)
    }

    @Test
    fun characterUpdatesPersistVersionedOutfitsAndStructuredRelations() = runTest {
        val state = loggedInState()
        val writes = mutableListOf<BcContactProfile>()
        val store = object : BcMessageTimelineStore by FakeTimelineStore() {
            override suspend fun upsertContactProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) { writes += profiles }
        }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        val first = BcCharacterSnapshot("20", 20, "Other", "Other", false,
            appearance = listOf(dev.vivian.mobilebc.core.model.BcCharacterItem("Cloth", "Shirt")),
            ownership = dev.vivian.mobilebc.core.model.BcCharacterOwnership("Owner", 30))
        controller.persistCharacterProfile(10, state.current.relationships.accountGeneration, first, "R133")
        controller.persistCharacterProfile(10, state.current.relationships.accountGeneration,
            first.copy(appearance = listOf(dev.vivian.mobilebc.core.model.BcCharacterItem("Cloth", "Dress"))), "R133")
        runCurrent()
        assertEquals(2, writes.size)
        assertEquals("R133", writes.last().portrait!!.version)
        assertEquals("Dress", writes.last().portrait!!.appearance.single().name)
        assertEquals(30, writes.last().ownerRelation!!.memberNumber)
        assertTrue(writes.last().snapshotUpdatedAtMillis!! > writes.first().snapshotUpdatedAtMillis!!)
    }

    @Test
    fun characterPersistenceRejectsAnOldQueryOwnerBeforeWriting() = runTest {
        val state = loggedInState()
        val writes = mutableListOf<Pair<Int, List<BcContactProfile>>>()
        val store = object : BcMessageTimelineStore by FakeTimelineStore() {
            override suspend fun upsertContactProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) {
                writes += ownerMemberNumber to profiles
            }
        }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        val character = BcCharacterSnapshot(id = "20", memberNumber = 20, name = "Other", displayName = "Other", isSelf = false)
        state.updateSession { it.copy(relationships = it.relationships.nextUnloaded()) }
        controller.persistCharacterProfile(10, 0, character)
        controller.persistCharacterProfile(99, 1, character)
        runCurrent()
        assertTrue(writes.isEmpty())
        controller.persistCharacterProfile(10, 1, character)
        runCurrent()
        assertEquals(1, writes.size)
        assertEquals(10, writes.single().first)
        assertEquals(20, writes.single().second.single().memberNumber)
        state.updateSession { it.copy(account = BcAccount(11, "New account", "new")) }
        controller.persistCharacterProfile(10, 1, character.copy(name = "Stale"))
        runCurrent()
        assertEquals(1, writes.size)
    }

    @Test
    fun repeatedStartKeepsOneStorageObserverAcrossAccountChanges() = runTest {
        val state = loggedInState()
        val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.test.StandardTestDispatcher(testScheduler))
        val started = mutableListOf<Int>()
        var active = 0
        var maximumActive = 0
        val store = object : BcMessageTimelineStore by FakeTimelineStore() {
            override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
                kotlinx.coroutines.flow.flow {
                    started += ownerMemberNumber
                    active++
                    maximumActive = maxOf(maximumActive, active)
                    try {
                        emit(emptyList())
                        awaitCancellation()
                    } finally { active-- }
                }
        }
        val controller = BcSessionTimelineController(state, scope, store, BcSessionMessageCoordinator())
        try {
            controller.start()
            controller.start()
            runCurrent()
            controller.start()
            runCurrent()
            assertEquals(listOf(10), started)
            assertEquals(1, active)
            state.updateSession { it.copy(account = BcAccount(11, "Other", "other")) }
            runCurrent()
            assertEquals(listOf(10, 11), started)
            assertEquals(1, active)
            assertEquals(1, maximumActive)
            scope.cancel()
            runCurrent()
            assertEquals(0, active)
            controller.start()
            runCurrent()
            assertEquals(listOf(10, 11), started)
        } finally { scope.cancel(); runCurrent() }
    }

    @Test
    fun renamedRoomSelectionCanOnlyBeReleasedByItsCurrentOwner() = runTest {
        val state = loggedInState()
        val coordinator = BcSessionMessageCoordinator()
        val controller = BcSessionTimelineController(state, backgroundScope, FakeTimelineStore(), coordinator)
        val oldRoom = BcRoom(BcRoomName("Old room"), "", 2, 10, false, null)
        val newRoom = oldRoom.copy(name = BcRoomName("New room"))
        val oldId = coordinator.roomConversationId(oldRoom)
        val newId = coordinator.roomConversationId(newRoom)
        val oldPage = Any()
        val newPage = Any()
        controller.selectConversation(oldId, oldPage)
        controller.renameRoomConversation(oldRoom, newRoom)
        assertTrue(controller.isConversationVisible(newId))
        controller.clearSelectedConversation(oldPage)
        assertTrue(!controller.isConversationVisible(newId))

        controller.selectConversation(oldId, oldPage)
        controller.renameRoomConversation(oldRoom, newRoom)
        controller.selectConversation(newId, newPage)
        controller.clearSelectedConversation(oldPage)
        assertTrue(controller.isConversationVisible(newId))
        controller.clearSelectedConversation(newPage)
        assertTrue(!controller.isConversationVisible(newId))
    }

    @Test
    fun oldPageOwnerCannotClearAReplacementSelectionForTheSameConversation() = runTest {
        val state = loggedInState()
        val controller = BcSessionTimelineController(state, backgroundScope, FakeTimelineStore(), BcSessionMessageCoordinator())
        val oldPage = Any()
        val newPage = Any()
        controller.selectConversation(conversationId, oldPage)
        controller.selectConversation(conversationId, newPage)
        controller.clearSelectedConversation(oldPage)
        assertTrue(controller.isConversationVisible(conversationId))
        controller.clearSelectedConversation(newPage)
        assertTrue(!controller.isConversationVisible(conversationId))

        controller.selectConversation(conversationId, oldPage)
        state.updateSession { it.copy(relationships = it.relationships.nextUnloaded()) }
        controller.selectConversation(conversationId, newPage)
        controller.clearSelectedConversation(oldPage)
        assertTrue(controller.isConversationVisible(conversationId))
    }

    @Test
    fun concurrentOldPresenceCannotOverwriteNewAccountPresence() {
        val state = loggedInState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, FakeTimelineStore(), BcSessionMessageCoordinator())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val newStarted = java.util.concurrent.CountDownLatch(1)
        val newFinished = java.util.concurrent.CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val oldMembers = object : AbstractList<BcMember>() {
            override val size = 1
            override fun get(index: Int): BcMember {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                return BcMember(20, "Old member", isSelf = false)
            }
        }
        state.updateSession { it.copy(roomMembers = oldMembers) }
        val oldSource = state.current
        val oldWorker = Thread {
            runCatching { controller.upsertSameRoomDirectConversations(oldSource) }.exceptionOrNull()?.let(failures::add)
        }
        val newWorker = Thread {
            newStarted.countDown()
            try {
                controller.upsertSameRoomDirectConversations(state.current)
            } catch (failure: Throwable) { failures.add(failure) }
            finally { newFinished.countDown() }
        }
        try {
            oldWorker.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            state.updateSession { it.copy(account = BcAccount(11, "New account", "new"), roomMembers = listOf(BcMember(30, "New member", isSelf = false))) }
            newWorker.start()
            assertTrue(newStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            newFinished.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            release.countDown()
            oldWorker.join(5_000)
            newWorker.join(5_000)
            assertTrue(!oldWorker.isAlive && !newWorker.isAlive)
            assertTrue(failures.toString(), failures.isEmpty())
            assertEquals(11, controller.conversations.value.ownerMemberNumber)
            assertEquals(listOf(BcConversationId("message:30")), controller.conversations.value.rows.map { it.id })
        } finally {
            release.countDown()
            oldWorker.join(5_000)
            newWorker.join(5_000)
            scope.cancel()
        }
    }

    @Test
    fun staleRoomSnapshotCannotReplaceOrClearCurrentPresence() = runTest {
        val state = loggedInState()
        val controller = BcSessionTimelineController(state, backgroundScope, FakeTimelineStore(), BcSessionMessageCoordinator())
        val old = state.current.copy(roomMembers = listOf(BcMember(20, "Old member", isSelf = false)))
        state.updateSession { old }
        controller.upsertSameRoomDirectConversations(old)
        state.updateSession { it.copy(roomPresenceEpoch = 2, roomMembers = listOf(BcMember(30, "Current member", isSelf = false))) }
        controller.upsertSameRoomDirectConversations(state.current)
        val expected = controller.conversations.value
        assertEquals(listOf(BcConversationId("message:30")), expected.rows.map { it.id })
        controller.upsertSameRoomDirectConversations(old)
        controller.upsertSameRoomDirectConversations(old.copy(roomMembers = emptyList()))
        controller.upsertSameRoomDirectConversations(state.current.copy(roomMembers = old.roomMembers))
        controller.upsertSameRoomDirectConversations(state.current.copy(account = BcAccount(99, "Other", "other")))
        controller.upsertSameRoomDirectConversations(state.current.copy(relationships = state.current.relationships.nextUnloaded()))
        assertEquals(expected, controller.conversations.value)
        state.updateSession { it.copy(roomMembers = emptyList()) }
        controller.upsertSameRoomDirectConversations(state.current)
        assertTrue(controller.conversations.value.rows.isEmpty())
    }

    @Test
    fun loginFriendPlaceholdersUseTheRelationshipSnapshotOwner() = runTest {
        val state = loggedInState()
        val controller = BcSessionTimelineController(state, backgroundScope, FakeTimelineStore(), BcSessionMessageCoordinator())
        val original = BcRelationshipSnapshot(
            accountMemberNumber = 10,
            self = BcSelfRelations(friends = setOf(10, 20)),
        )
        controller.upsertFriendConversations(original)
        assertEquals(listOf(BcConversationId("message:20")), controller.conversations.value.rows.map { it.id })
        state.updateSession { it.copy(relationships = it.relationships.nextUnloaded()) }
        controller.upsertFriendConversations(original)
        assertTrue(controller.conversations.value.rows.isEmpty())
        val current = original.copy(accountGeneration = 1, self = BcSelfRelations(friends = setOf(10, 30)))
        controller.upsertFriendConversations(current)
        val expected = controller.conversations.value
        assertEquals(listOf(BcConversationId("message:30")), expected.rows.map { it.id })
        controller.upsertFriendConversations(original)
        controller.upsertFriendConversations(current.copy(accountMemberNumber = 99))
        controller.upsertFriendConversations(current.copy(accountMemberNumber = null))
        assertEquals(expected, controller.conversations.value)
    }

    @Test
    fun onlineFriendResultsKeepTheirQueryOwnerAtTheWriteBoundary() = runTest {
        val changes: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(account = BcAccount(11, "Other", "other")) },
            { it.copy(relationships = it.relationships.nextUnloaded()) },
        )
        changes.forEach { change ->
            val state = loggedInState()
            val controller = BcSessionTimelineController(state, backgroundScope, FakeTimelineStore(), BcSessionMessageCoordinator())
            val oldFriend = BcConversation(
                id = BcConversationId("message:40"), type = ConversationType.Direct,
                title = "Old friend", subtitle = "Friend", onlineRoomName = "Old room",
            )
            controller.replaceOnlineFriendConversations(10, 0, listOf(oldFriend))
            assertEquals("Old friend", controller.conversations.value.rows.single().title)
            state.updateSession(change)
            controller.replaceOnlineFriendConversations(10, 0, listOf(oldFriend))
            assertTrue(controller.conversations.value.rows.isEmpty())

            val current = state.current
            val member = requireNotNull(current.account).memberNumber
            val generation = current.relationships.accountGeneration
            val newFriend = oldFriend.copy(title = "Current friend", onlineRoomName = "Current room")
            controller.replaceOnlineFriendConversations(member, generation, listOf(newFriend))
            val expected = controller.conversations.value
            controller.replaceOnlineFriendConversations(10, 0, listOf(oldFriend))
            assertEquals(expected, controller.conversations.value)
            controller.replaceOnlineFriendConversations(10, 0, emptyList())
            assertEquals(expected, controller.conversations.value)
            controller.replaceOnlineFriendConversations(member, generation, emptyList())
            assertTrue(controller.conversations.value.rows.isEmpty())
        }
    }

    @Test
    fun messageSnapshotsRetainQueryAccountAndGeneration() = runBlocking {
        val state = loggedInState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, FakeTimelineStore(), BcSessionMessageCoordinator())
        try {
            val old = controller.observeMessages(conversationId).first()
            assertEquals(10, old.ownerMemberNumber)
            assertEquals(0L, old.accountGeneration)
            state.updateSession { it.copy(relationships = it.relationships.nextUnloaded()) }
            val current = controller.observeMessages(conversationId).first()
            assertEquals(1L, current.accountGeneration)
            assertTrue(old.rowsFor(10, 1).isEmpty())
            assertEquals(current.rows, current.rowsFor(10, 1))
            val whispers = controller.observeRoomWhispers("Room").first()
            assertEquals(10, whispers.ownerMemberNumber)
            assertEquals(1L, whispers.accountGeneration)
            assertTrue(whispers.rowsFor(11, 1).isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    fun storageRefreshKeepsConnectionStatusButSuppliesUnreadAndMutedValues() = runTest {
        val room = BcRoom(BcRoomName("Room"), "", 2, 10, false, null)
        val coordinator = BcSessionMessageCoordinator()
        val original = coordinator.roomConversation(room).copy(hasMessages = true, unreadCount = 4)
        val store = FakeTimelineStore(listOf(original))
        val state = loggedInState().apply { updateSession { it.copy(currentRoom = room) } }
        val controller = BcSessionTimelineController(state, backgroundScope, store, coordinator)
        controller.start()
        runCurrent()
        controller.upsertRoomConversation(room)
        controller.markRoomConversationDisconnected()
        val disconnected = controller.conversations.value.rows.single()
        assertEquals("Disconnected, reconnecting...", disconnected.subtitle)
        assertEquals(listOf(original), store.rows.value)

        val latestMessageTime = requireNotNull(original.updatedAt).plusSeconds(10)
        store.rows.value = listOf(original.copy(unreadCount = 7, muted = true, updatedAt = latestMessageTime))
        runCurrent()

        val refreshed = controller.conversations.value.rows.single()
        assertEquals(disconnected.subtitle, refreshed.subtitle)
        assertEquals(original.updatedAt, disconnected.updatedAt)
        assertEquals(latestMessageTime, refreshed.updatedAt)
        assertEquals(7, refreshed.unreadCount)
        assertTrue(refreshed.muted)
        controller.upsertRoomConversation(room)
        assertEquals(original.subtitle, controller.conversations.value.rows.single().subtitle)
        assertEquals(7, controller.conversations.value.rows.single().unreadCount)
        assertTrue(store.savedConversations.isEmpty())
    }

    @Test
    fun departedRoomStatusSurvivesFailedPersistenceWithoutFreezingStoredMetadata() = runTest {
        val room = BcRoom(BcRoomName("Room"), "", 2, 10, false, null)
        val coordinator = BcSessionMessageCoordinator()
        val original = coordinator.roomConversation(room).copy(hasMessages = true, unreadCount = 4)
        val delegate = FakeTimelineStore(listOf(original))
        val store = object : BcMessageTimelineStore by delegate {
            override suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation) {
                error("storage unavailable")
            }
        }
        val controller = BcSessionTimelineController(loggedInState(), backgroundScope, store, coordinator)
        controller.start()
        runCurrent()
        controller.markRoomConversationDisconnected(room)
        runCurrent()
        assertEquals(BcTimelinePersistenceOperation.UpsertConversation, controller.persistenceFailure.value?.operation)
        assertEquals(listOf(original), delegate.rows.value)

        delegate.rows.value = listOf(original.copy(unreadCount = 0, muted = true))
        runCurrent()
        val displayed = controller.conversations.value.rows.single()
        assertEquals("No longer in this room", displayed.subtitle)
        assertEquals(0, displayed.unreadCount)
        assertTrue(displayed.muted)
    }

    @Test
    fun accountReplacementClearsHistoryAndSelectionBeforeNewStorageEmits() = runTest {
        val state = loggedInState()
        val delegate = FakeTimelineStore()
        val store = object : BcMessageTimelineStore by delegate {
            override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
                if (ownerMemberNumber == 10) delegate.rows else emptyFlow()
        }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        controller.start()
        runCurrent()
        upsertSameRoom(controller, state, listOf(BcMember(20, "Other", isSelf = false)))
        controller.selectConversation(conversationId, this)
        assertTrue(controller.conversations.value.rows.isNotEmpty())

        state.updateSession { it.copy(account = BcAccount(11, "Second", "second")) }
        assertTrue(!controller.isConversationVisible(conversationId))
        runCurrent()

        assertTrue(controller.conversations.value.rows.isEmpty())
        assertEquals(11, controller.conversations.value.ownerMemberNumber)
        delegate.rows.value = listOf(conversation.copy(title = "late old-account update"))
        runCurrent()
        assertTrue(controller.conversations.value.rows.isEmpty())
    }

    @Test
    fun newAccountPresenceBeforeCollectorResumesDoesNotMergeOldHistory() = runTest {
        val state = loggedInState()
        val delegate = FakeTimelineStore()
        val store = object : BcMessageTimelineStore by delegate {
            override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
                if (ownerMemberNumber == 10) delegate.rows else emptyFlow()
        }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        controller.start()
        runCurrent()
        controller.selectConversation(conversationId, this)

        state.updateSession { it.copy(account = BcAccount(11, "Second", "second")) }
        upsertSameRoom(controller, state, listOf(BcMember(30, "New member", isSelf = false)))
        assertEquals(listOf(BcConversationId("message:30")), controller.conversations.value.rows.map { it.id })
        runCurrent()
        assertEquals(listOf(BcConversationId("message:30")), controller.conversations.value.rows.map { it.id })
        assertTrue(!controller.isConversationVisible(conversationId))
    }

    @Test
    fun sameAccountNewGenerationDropsPresenceAndSelection() = runTest {
        val state = loggedInState()
        val delegate = FakeTimelineStore()
        var observations = 0
        val store = object : BcMessageTimelineStore by delegate {
            override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
                if (observations++ == 0) delegate.rows else emptyFlow()
        }
        val controller = BcSessionTimelineController(state, backgroundScope, store, BcSessionMessageCoordinator())
        controller.start()
        runCurrent()
        upsertSameRoom(controller, state, listOf(BcMember(20, "Other", isSelf = false)))
        controller.selectConversation(conversationId, this)

        state.updateSession { it.copy(relationships = it.relationships.nextUnloaded()) }
        runCurrent()

        assertEquals(2, observations)
        assertEquals(10, controller.conversations.value.ownerMemberNumber)
        assertEquals(1L, controller.conversations.value.accountGeneration)
        assertTrue(controller.conversations.value.rows.isEmpty())
        assertTrue(!controller.isConversationVisible(conversationId))
    }

    @Test
    fun removingAccountEmitsEmptyMessagesAndClearsConversations() = runBlocking {
        val stateHolder = loggedInState()
        val store = FakeTimelineStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(stateHolder, scope, store, BcSessionMessageCoordinator())
        val emissions = Channel<List<BcMessage>>(Channel.UNLIMITED)
        try {
            controller.start()
            scope.launch { controller.observeMessages(conversationId).collect { emissions.send(it.rows) } }
            assertEquals(listOf(message("first")), withTimeout(5_000) { emissions.receive() })
            assertEquals(listOf(conversation), controller.conversations.value.rows)
            upsertSameRoom(controller, stateHolder, listOf(BcMember(20, "Other", isSelf = false)))
            assertTrue(ConversationAvailability.SameRoom in controller.conversations.value.rows.single().availability)

            stateHolder.updateSession { it.copy(account = null) }

            assertTrue(withTimeout(5_000) { emissions.receive() }.isEmpty())
            assertTrue(controller.conversations.value.rows.isEmpty())
            store.messages.value = listOf(message("after logout"))
            assertTrue(controller.observeMessages(conversationId).first().rows.isEmpty())
            stateHolder.updateSession { it.copy(account = BcAccount(10, "Self", "self")) }
            assertEquals(listOf(conversation), controller.conversations.value.rows)
            assertTrue(controller.conversations.value.rows.single().availability.isEmpty())
        } finally {
            scope.cancel()
            emissions.close()
        }
    }

    @Test
    fun roomWhisperObservationUsesTheCurrentOwnerAndClearsOnLogout() = runBlocking {
        val state = loggedInState()
        val store = FakeTimelineStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, store, BcSessionMessageCoordinator())
        val emissions = Channel<List<BcMessage>>(Channel.UNLIMITED)
        try {
            scope.launch { controller.observeRoomWhispers("Room", limit = 25).collect { emissions.send(it.rows) } }
            assertEquals(listOf(message("first")), withTimeout(5_000) { emissions.receive() })
            assertEquals(listOf(Triple(10, "Room", 25)), store.observedWhisperScopes)
            state.updateSession { it.copy(account = null) }
            assertTrue(withTimeout(5_000) { emissions.receive() }.isEmpty())
            assertTrue(controller.observeRoomWhispers("Room").first().rows.isEmpty())
            state.updateSession { it.copy(account = BcAccount(11, "Second", "second")) }
            assertEquals(listOf(message("first")), withTimeout(5_000) { emissions.receive() })
            assertEquals(Triple(11, "Room", 25), store.observedWhisperScopes.last())
        } finally {
            scope.cancel()
            emissions.close()
        }
    }

    @Test
    fun disconnectKeepsTheAccountTimelineSubscribed() = runBlocking {
        val stateHolder = loggedInState()
        val store = FakeTimelineStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(stateHolder, scope, store, BcSessionMessageCoordinator())
        val emissions = Channel<List<BcMessage>>(Channel.UNLIMITED)
        try {
            scope.launch { controller.observeMessages(conversationId).collect { emissions.send(it.rows) } }
            assertEquals(listOf(message("first")), withTimeout(5_000) { emissions.receive() })

            stateHolder.updateSession { it.copy(connectionHealth = BcConnectionHealth.Offline, currentRoom = null) }
            store.messages.value = listOf(message("still available"))

            assertEquals(listOf(message("still available")), withTimeout(5_000) { emissions.receive() })
            assertEquals(listOf(10), store.observedOwners)
        } finally {
            scope.cancel()
            emissions.close()
        }
    }

    @Test
    fun roomDepartureClearsSameRoomPresenceAndKeepsFriendsAndHistory() {
        assertRoomPresenceCleared { controller, state ->
            state.updateSession { it.copy(currentRoom = null, roomMembers = emptyList(), roomCharacters = emptyList()) }
            upsertSameRoom(controller, state, emptyList())
        }
    }

    @Test
    fun disconnectedRoomDoesNotRestoreOldSameRoomContactsWhenAccountRecovers() {
        assertRoomPresenceCleared { controller, state ->
            controller.markRoomConversationDisconnected()
            state.updateSession {
                it.copy(connectionHealth = BcConnectionHealth.Offline, currentRoom = null, roomMembers = emptyList())
            }
            state.updateSession { it.copy(connectionHealth = BcConnectionHealth.VerifiedOnline) }
        }
    }

    @Test
    fun roomRenameKeepsOldHistoryAndMovesTheSelectedRoomAfterSnapshotChanges() = runBlocking {
        val oldRoom = BcRoom(BcRoomName("Old room"), "", 3, 10, false, null)
        val newRoom = oldRoom.copy(name = BcRoomName("New room"))
        val coordinator = BcSessionMessageCoordinator()
        val oldId = coordinator.roomConversationId(oldRoom)
        val newId = coordinator.roomConversationId(newRoom)
        val oldConversation = coordinator.roomConversation(oldRoom).copy(hasMessages = true, unreadCount = 3, muted = true)
        val oldMessage = message("room history").copy(conversationId = oldId)
        val store = FakeTimelineStore(listOf(oldConversation), listOf(oldMessage))
        val state = loggedInState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, store, coordinator)
        try {
            controller.start()
            controller.upsertRoomConversation(oldRoom)
            upsertSameRoom(controller, state, listOf(BcMember(20, "Other", isSelf = false)))
            controller.selectConversation(oldId, this)
            state.updateSession { it.copy(currentRoom = newRoom) }

            controller.renameRoomConversation(oldRoom, newRoom)
            controller.upsertRoomConversation(newRoom)

            assertTrue(controller.isConversationVisible(newId))
            val history = controller.conversations.value.rows.first { it.id == oldId }
            assertEquals("Disconnected, reconnecting...", history.subtitle)
            assertTrue(history.hasMessages)
            assertEquals(3, history.unreadCount)
            assertTrue(history.muted)
            assertEquals(listOf(oldMessage), controller.observeMessages(oldId).first().rows)
            assertEquals(listOf(10 to history), store.savedConversations)
            assertEquals("3/10 members", controller.conversations.value.rows.first { it.id == newId }.subtitle)
            assertEquals(
                setOf(ConversationAvailability.SameRoom),
                controller.conversations.value.rows.first { it.id == conversationId }.availability,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun roomRenameKeepsAnUnrelatedSelectionAndTheNewRoomsMetadata() {
        val oldRoom = BcRoom(BcRoomName("Old room"), "", 3, 10, false, null)
        val newRoom = oldRoom.copy(name = BcRoomName("New room"))
        val coordinator = BcSessionMessageCoordinator()
        val oldId = coordinator.roomConversationId(oldRoom)
        val newConversation = coordinator.roomConversation(newRoom)
        val store = FakeTimelineStore(listOf(coordinator.roomConversation(oldRoom), newConversation))
        val state = loggedInState().apply { updateSession { it.copy(currentRoom = newRoom) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, store, coordinator)
        try {
            controller.start()
            controller.selectConversation(conversationId, this)

            controller.renameRoomConversation(oldRoom, newRoom)

            assertTrue(controller.isConversationVisible(conversationId))
            assertEquals(newConversation, controller.conversations.value.rows.first { it.id == newConversation.id })
            assertEquals("Disconnected, reconnecting...", controller.conversations.value.rows.first { it.id == oldId }.subtitle)
            assertEquals(oldId, store.savedConversations.single().second.id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aPropertyUpdateWithoutARenameKeepsHistoryAndSelection() {
        val room = BcRoom(BcRoomName("Room"), "Before", 3, 10, false, null)
        val coordinator = BcSessionMessageCoordinator()
        val row = coordinator.roomConversation(room)
        val store = FakeTimelineStore(listOf(row))
        val state = loggedInState()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, store, coordinator)
        try {
            controller.start()
            controller.selectConversation(row.id, this)

            controller.renameRoomConversation(room, room.copy(description = "After"))

            assertTrue(controller.isConversationVisible(row.id))
            assertEquals(listOf(row), controller.conversations.value.rows)
            assertTrue(store.savedConversations.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun explicitRoomDepartureKeepsHistoryAfterTheCurrentRoomWasCleared() {
        val removedRoom = BcRoom(BcRoomName("Removed room"), "", 3, 10, false, null)
        val otherRoom = removedRoom.copy(name = BcRoomName("Other room"))
        val coordinator = BcSessionMessageCoordinator()
        val removedRow = coordinator.roomConversation(removedRoom).copy(hasMessages = true, unreadCount = 4, muted = true)
        val otherRow = coordinator.roomConversation(otherRoom)
        val store = FakeTimelineStore(listOf(removedRow, otherRow))
        val state = loggedInState().apply { updateSession { it.copy(currentRoom = null) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, store, coordinator)
        try {
            controller.start()
            controller.selectConversation(otherRow.id, this)

            controller.markRoomConversationDisconnected(removedRoom)

            val history = controller.conversations.value.rows.first { it.id == removedRow.id }
            assertEquals("No longer in this room", history.subtitle)
            assertTrue(history.hasMessages)
            assertTrue(history.muted)
            assertEquals(4, history.unreadCount)
            assertEquals(listOf(10 to history), store.savedConversations)
            assertEquals(otherRow, controller.conversations.value.rows.first { it.id == otherRow.id })
            assertTrue(controller.isConversationVisible(otherRow.id))
        } finally {
            scope.cancel()
        }
    }

    private fun assertRoomPresenceCleared(
        clear: (BcSessionTimelineController, BcSessionRuntimeState) -> Unit,
    ) {
        val state = loggedInState().apply {
            updateSession {
                it.copy(
                    state = BcSessionState.Ready,
                    desiredOnline = true,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    currentRoom = BcRoom(BcRoomName("Room"), "", 3, 10, false, null),
                )
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = BcSessionTimelineController(state, scope, FakeTimelineStore(), BcSessionMessageCoordinator())
        try {
            controller.start()
            controller.replaceOnlineFriendConversations(
                ownerMemberNumber = 10,
                accountGeneration = 0,
                conversations = listOf(20, 40).map { memberNumber ->
                    BcConversation(
                        id = BcConversationId("message:$memberNumber"),
                        type = ConversationType.Direct,
                        title = "Friend $memberNumber",
                        subtitle = "Friend",
                        availability = setOf(ConversationAvailability.OnlineFriend),
                        onlineRoomName = "Friend room",
                    )
                },
            )
            upsertSameRoom(controller, state,
                listOf(BcMember(20, "Other", isSelf = false), BcMember(30, "Guest", isSelf = false)),
            )
            assertEquals(
                setOf(ConversationAvailability.SameRoom, ConversationAvailability.OnlineFriend),
                controller.conversations.value.rows.first { it.id == conversationId }.availability,
            )

            clear(controller, state)

            val rows = controller.conversations.value.rows
            assertTrue(rows.none { ConversationAvailability.SameRoom in it.availability })
            assertTrue(rows.none { it.id == BcConversationId("message:30") })
            val history = rows.first { it.id == conversationId }
            assertEquals(setOf(ConversationAvailability.OnlineFriend), history.availability)
            assertEquals("first", history.subtitle)
            assertTrue(history.hasMessages)
            val friend = rows.first { it.id == BcConversationId("message:40") }
            assertEquals(setOf(ConversationAvailability.OnlineFriend), friend.availability)
            assertEquals("Friend room", friend.onlineRoomName)
        } finally {
            scope.cancel()
        }
    }

    private fun upsertSameRoom(controller: BcSessionTimelineController, state: BcSessionRuntimeState, members: List<BcMember>) {
        state.updateSession { it.copy(roomMembers = members) }
        controller.upsertSameRoomDirectConversations(state.current)
    }

    private fun loggedInState() = BcSessionRuntimeState().apply {
        updateSession { it.copy(account = BcAccount(10, "Self", "self")) }
    }

    private class FakeTimelineStore(
        initialConversations: List<BcConversation> = listOf(conversation),
        initialMessages: List<BcMessage> = listOf(message("first")),
    ) : BcMessageTimelineStore {
        val messages = MutableStateFlow(initialMessages)
        val rows = MutableStateFlow(initialConversations)
        val observedOwners = mutableListOf<Int>()
        val observedWhisperScopes = mutableListOf<Triple<Int, String, Int>>()
        val savedConversations = mutableListOf<Pair<Int, BcConversation>>()

        override suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation) {
            savedConversations += ownerMemberNumber to conversation
            rows.value = rows.value.filterNot { it.id == conversation.id } + conversation
        }
        override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
            rows
        override suspend fun advanceReadCursor(ownerMemberNumber: Int, conversationId: BcConversationId, throughMessage: BcMessage) = Unit
        override fun observeRoomWhispers(ownerMemberNumber: Int, roomName: String, limit: Int): Flow<List<BcMessage>> {
            observedWhisperScopes += Triple(ownerMemberNumber, roomName, limit)
            return messages
        }
        override fun observeMessages(ownerMemberNumber: Int, conversationId: BcConversationId, limit: Int): Flow<List<BcMessage>> {
            observedOwners += ownerMemberNumber
            return messages.map { rows -> rows.filter { it.conversationId == conversationId } }
        }
    }

    private companion object {
        val conversationId = BcConversationId("message:20")
        val conversation = BcConversation(conversationId, ConversationType.Direct, "Other", "first", hasMessages = true)

        fun message(text: String) = BcMessage(
            id = BcMessageId(text),
            conversationId = conversationId,
            sender = BcSender(20, "Other", isSelf = false),
            kind = BcMessageKind.Chat,
            text = text,
            timestamp = Instant.ofEpochMilli(1_000),
        )
    }
}
