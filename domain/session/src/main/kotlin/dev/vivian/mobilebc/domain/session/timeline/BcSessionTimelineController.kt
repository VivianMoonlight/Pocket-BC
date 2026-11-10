package dev.vivian.mobilebc.domain.session.timeline

import dev.vivian.mobilebc.core.model.BcProfilePortrait
import dev.vivian.mobilebc.core.model.profileOwner
import dev.vivian.mobilebc.core.model.profileLovers
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.isTimelineVisible
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BcSessionTimelineController(
    private val stateHolder: BcSessionRuntimeState,
    private val scope: CoroutineScope,
    private val timelineStore: BcMessageTimelineStore,
    private val messageCoordinator: BcSessionMessageCoordinator,
    private val conversationListLimit: Int = 100,
) : BcSessionTimeline {
    private val conversationRows = MutableStateFlow(BcConversationSnapshot())
    override val conversations: StateFlow<BcConversationSnapshot> = conversationRows.asStateFlow()
    private var observationJob: Job? = null
    private var presentationOwner = stateHolder.current.timelineOwner()
    private data class ConversationSelection(val id: BcConversationId, val owner: Any)
    private var selection: ConversationSelection? = null
    private val failedOperation = MutableStateFlow<BcTimelinePersistenceFailure?>(null)
    override val persistenceFailure: StateFlow<BcTimelinePersistenceFailure?> = combine(
        stateHolder.snapshot,
        failedOperation,
    ) { snapshot, failure ->
        failure?.takeIf { it.belongsTo(snapshot) }
    }.stateIn(scope, SharingStarted.Eagerly, null)
    private var storedConversations: List<BcConversation> = emptyList()
    private var onlineConversations: List<BcConversation> = emptyList()
    private var roomStatuses: Map<BcConversationId, String> = emptyMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCharacterProfile(memberNumber: Int): Flow<BcContactProfileSnapshot> =
        stateHolder.snapshot.map { it.timelineOwner() }.distinctUntilChanged().flatMapLatest { owner ->
            if (owner == null) {
                flowOf(BcContactProfileSnapshot())
            } else {
                observeWithRetry(
                    operation = BcTimelinePersistenceOperation.ObserveCharacterProfile,
                    owner = owner,
                    conversationId = messageCoordinator.directConversationId(memberNumber),
                ) { timelineStore.observeContactProfile(owner.memberNumber, memberNumber) }
                    .map { BcContactProfileSnapshot(owner.memberNumber, owner.generation, it) }
            }
        }.distinctUntilChanged()

    private var lastProfileSnapshotMillis = 0L

    @Synchronized
    fun persistCharacterProfile(
        ownerMemberNumber: Int,
        accountGeneration: Long,
        snapshot: BcCharacterSnapshot,
        version: String? = stateHolder.current.bcVersion,
    ) {
        val owner = TimelineOwner(ownerMemberNumber, accountGeneration)
        if (synchronizeOwner() != owner) return
        val displayName = snapshot.nickname?.takeIf { it.isNotBlank() }
            ?: snapshot.name.takeIf { it.isNotBlank() }
            ?: "#${snapshot.memberNumber}"
        onlineConversations = onlineConversations.map { conversation ->
            if (messageCoordinator.directMemberNumber(conversation) == snapshot.memberNumber) {
                conversation.copy(title = displayName)
            } else {
                conversation
            }
        }
        publishConversations()
        val now = maxOf(System.currentTimeMillis(), lastProfileSnapshotMillis + 1)
        lastProfileSnapshotMillis = now
        launchOperation(
            operation = BcTimelinePersistenceOperation.PersistCharacterProfile,
            owner = owner,
            conversationId = messageCoordinator.directConversationId(snapshot.memberNumber),
        ) {
            timelineStore.upsertContactProfiles(
                owner.memberNumber,
                listOf(
                    BcContactProfile(
                        memberNumber = snapshot.memberNumber,
                        name = snapshot.name.ifBlank { "#${snapshot.memberNumber}" },
                        labelColor = snapshot.labelColor,
                        avatarUrl = snapshot.avatarUrl,
                        signature = snapshot.signature,
                        accountName = snapshot.accountName,
                        characterName = snapshot.name,
                        nickname = snapshot.nickname,
                        title = snapshot.title,
                        description = snapshot.description,
                        pronouns = snapshot.pronouns,
                        ownerRelation = snapshot.profileOwner(),
                        loverRelations = snapshot.profileLovers(),
                        portrait = version?.let {
                            BcProfilePortrait(
                                version = it,
                                assetFamily = snapshot.assetFamily,
                                activePose = snapshot.activePose,
                                appearance = snapshot.appearance.map { item -> item.copy(difficulty = null, craftJson = null) },
                                updatedAtMillis = now,
                            )
                        },
                        ownerName = snapshot.ownerName,
                        loverNames = snapshot.loverNames,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now,
                        snapshotUpdatedAtMillis = now,
                    ),
                ),
            )
        }
    }

    @Synchronized
    fun start() {
        if (observationJob != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            stateHolder.snapshot.map { it.timelineOwner() }.distinctUntilChanged().collectLatest { owner ->
                failedOperation.update { failure ->
                    failure?.takeIf {
                        owner != null && it.ownerMemberNumber == owner.memberNumber &&
                            it.accountGeneration == owner.generation
                    }
                }
                if (owner != stateHolder.current.timelineOwner()) return@collectLatest
                synchronized(this@BcSessionTimelineController) { synchronizeOwner() }
                if (owner != null) {
                    observeWithRetry(BcTimelinePersistenceOperation.ObserveConversations, owner) {
                        timelineStore.observeConversations(owner.memberNumber, conversationListLimit)
                    }
                        .collect { conversations ->
                            coroutineContext.ensureActive()
                            synchronized(this@BcSessionTimelineController) {
                                if (owner == stateHolder.current.timelineOwner()) {
                                    synchronizeOwner()
                                    storedConversations = conversations
                                    publishConversations()
                                }
                            }
                        }
                }
            }
        }
        observationJob = job
        job.invokeOnCompletion {
            synchronized(this@BcSessionTimelineController) {
                if (observationJob === job) observationJob = null
            }
        }
        job.start()
    }

    @Synchronized
    override fun selectConversation(conversationId: BcConversationId, owner: Any) {
        if (synchronizeOwner() == null) return
        selection = ConversationSelection(conversationId, owner)
    }

    @Synchronized
    override fun clearSelectedConversation(owner: Any) {
        if (selection?.owner === owner) selection = null
    }

    @Synchronized
    override fun isConversationVisible(conversationId: BcConversationId): Boolean =
        presentationOwner != null && presentationOwner == stateHolder.current.timelineOwner() &&
            selection?.id == conversationId

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeHistory(
        conversationId: BcConversationId,
        query: dev.vivian.mobilebc.core.model.BcMessageHistoryQuery,
    ): Flow<BcMessageSnapshot> = stateHolder.snapshot
        .map { it.timelineOwner() }
        .distinctUntilChanged()
        .flatMapLatest { owner ->
            if (owner == null) flowOf(BcMessageSnapshot())
            else observeWithRetry(BcTimelinePersistenceOperation.ObserveMessages, owner, conversationId) {
                timelineStore.observeHistory(owner.memberNumber, conversationId, query)
            }.map { rows ->
                BcMessageSnapshot(owner.memberNumber, owner.generation, rows.filter(BcMessage::isTimelineVisible))
            }
        }.flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeMessages(conversationId: BcConversationId, limit: Int): Flow<BcMessageSnapshot> =
        stateHolder.snapshot
            .map { snapshot -> snapshot.timelineOwner() }
            .distinctUntilChanged()
            .flatMapLatest { owner ->
                if (owner == null) {
                    flowOf(BcMessageSnapshot())
                } else {
                    observeWithRetry(BcTimelinePersistenceOperation.ObserveMessages, owner, conversationId) {
                        timelineStore.observeMessages(owner.memberNumber, conversationId, limit)
                    }
                        .map { messages ->
                            messages.filter(BcMessage::isTimelineVisible)
                                .let(messageCoordinator::deduplicateMessages)
                                .let { BcMessageSnapshot(owner.memberNumber, owner.generation, it) }
                        }
                }
            }.flowOn(Dispatchers.Default)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeRoomWhispers(roomName: String, limit: Int): Flow<BcMessageSnapshot> =
        stateHolder.snapshot
            .map { it.timelineOwner() }
            .distinctUntilChanged()
            .flatMapLatest { owner ->
                if (owner == null || roomName.isBlank()) {
                    flowOf(BcMessageSnapshot())
                } else {
                    observeWithRetry(
                        BcTimelinePersistenceOperation.ObserveMessages,
                        owner,
                        dev.vivian.mobilebc.core.model.bcRoomConversationId(roomName),
                    ) {
                        timelineStore.observeRoomWhispers(owner.memberNumber, roomName, limit)
                    }.map { rows ->
                        rows.filter(BcMessage::isTimelineVisible).let(messageCoordinator::deduplicateMessages)
                            .let { BcMessageSnapshot(owner.memberNumber, owner.generation, it) }
                    }
                }
            }.flowOn(Dispatchers.Default)

    @Synchronized
    override fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage) {
        require(throughMessage.conversationId == conversationId) {
            "Read cursor message belongs to ${throughMessage.conversationId.value}, expected ${conversationId.value}."
        }
        val owner = TimelineOwner(ownerMemberNumber, accountGeneration)
        if (synchronizeOwner() != owner) return
        launchOperation(BcTimelinePersistenceOperation.AdvanceReadCursor, owner, conversationId) {
            timelineStore.advanceReadCursor(owner.memberNumber, conversationId, throughMessage)
        }
    }

    @Synchronized
    fun upsertConversation(conversation: BcConversation) {
        val owner = synchronizeOwner() ?: return
        launchOperation(BcTimelinePersistenceOperation.UpsertConversation, owner, conversation.id) {
            timelineStore.upsertConversation(owner.memberNumber, conversation)
        }
    }

    @Synchronized
    fun upsertRoomConversation(room: BcRoom) {
        if (synchronizeOwner() == null) return
        val conversation = messageCoordinator.roomConversation(room)
        roomStatuses = roomStatuses - conversation.id
        onlineConversations = messageCoordinator.mergeConversation(onlineConversations, conversation)
        publishConversations()
    }

    @Synchronized
    fun renameRoomConversation(oldRoom: BcRoom, newRoom: BcRoom) {
        if (synchronizeOwner() == null) return
        val oldConversationId = messageCoordinator.roomConversationId(oldRoom)
        val newConversationId = messageCoordinator.roomConversationId(newRoom)
        if (oldConversationId == newConversationId) return
        setRoomStatus(oldConversationId, "Disconnected, reconnecting...")
        publishConversations()
        conversationRows.value.rows.firstOrNull { it.id == oldConversationId }?.let(::upsertConversation)
        if (selection?.id == oldConversationId) selection = selection?.copy(id = newConversationId)
    }

    @Synchronized
    fun upsertRoomState(source: BcSessionSnapshot) {
        val owner = source.timelineOwner() ?: return
        val room = source.currentRoom ?: return
        val members = source.roomMembers
        if (synchronizeOwner() != owner) return
        val now = System.currentTimeMillis()
        launchOperation(
            BcTimelinePersistenceOperation.UpsertRoomState,
            owner,
            messageCoordinator.currentRoomConversationId(room),
        ) {
            timelineStore.upsertRoomState(owner.memberNumber, room, members)
            timelineStore.upsertContactProfiles(
                owner.memberNumber,
                members.filterNot { it.isSelf }.map { member ->
                    BcContactProfile(
                        memberNumber = member.memberNumber,
                        name = member.name,
                        statusText = member.statusText,
                        role = member.role,
                        firstSeenAtMillis = now,
                        lastSeenAtMillis = now,
                    )
                },
            )
        }
    }

    @Synchronized
    fun upsertSameRoomDirectConversations(source: BcSessionSnapshot) {
        val current = stateHolder.current
        val owner = source.timelineOwner() ?: return
        if (current.timelineOwner() != owner || current.roomPresenceEpoch != source.roomPresenceEpoch ||
            current.gatewayRoomPresenceEpoch != source.gatewayRoomPresenceEpoch ||
            current.currentRoom?.name != source.currentRoom?.name || current.roomMembers != source.roomMembers
        ) return
        if (synchronizeOwner() != owner) return
        replaceSameRoomDirectConversations(source.roomMembers)
    }

    private fun replaceSameRoomDirectConversations(members: List<BcMember>) {
        val withoutSameRoomAvailability = onlineConversations.mapNotNull { conversation ->
            val remaining = conversation.availability - ConversationAvailability.SameRoom
            if (conversation.type == dev.vivian.mobilebc.core.model.ConversationType.Direct && remaining.isEmpty()) {
                null
            } else {
                conversation.copy(availability = remaining)
            }
        }
        onlineConversations = messageCoordinator.sameRoomDirectConversations(members)
            .fold(withoutSameRoomAvailability) { rows, conversation ->
                messageCoordinator.mergePresenceConversation(rows, conversation)
            }
        publishConversations()
    }

    @Synchronized
    fun upsertFriendConversations(relationships: BcRelationshipSnapshot) {
        val memberNumber = relationships.accountMemberNumber ?: return
        if (synchronizeOwner() != TimelineOwner(memberNumber, relationships.accountGeneration)) return
        onlineConversations = messageCoordinator.friendConversations(
            memberNumbers = relationships.self.friends,
            selfMemberNumber = memberNumber,
        ).fold(onlineConversations) { rows, conversation ->
            messageCoordinator.addFriendConversationIfMissing(rows, conversation)
        }
        publishConversations()
    }

    @Synchronized
    fun replaceOnlineFriendConversations(
        ownerMemberNumber: Int,
        accountGeneration: Long,
        conversations: List<BcConversation>,
    ) {
        if (synchronizeOwner() != TimelineOwner(ownerMemberNumber, accountGeneration)) return
        onlineConversations = onlineConversations.mapNotNull { conversation ->
            val remaining = conversation.availability - ConversationAvailability.OnlineFriend
            if (conversation.type == dev.vivian.mobilebc.core.model.ConversationType.Direct && remaining.isEmpty()) {
                null
            } else {
                conversation.copy(availability = remaining, onlineRoomName = null)
            }
        }
        conversations.forEach { conversation ->
            onlineConversations = messageCoordinator.mergePresenceConversation(
                onlineConversations,
                conversation.copy(
                    updatedAt = null,
                    availability = setOf(ConversationAvailability.OnlineFriend),
                ),
            )
        }
        publishConversations()
    }

    @Synchronized
    fun markRoomConversationDisconnected() {
        if (synchronizeOwner() == null) return
        val conversationId = messageCoordinator.currentRoomConversationId(stateHolder.current.currentRoom)
            ?: messageCoordinator.roomConversationId
        setRoomStatus(conversationId, "Disconnected, reconnecting...")
        replaceSameRoomDirectConversations(emptyList())
    }

    @Synchronized
    fun markRoomConversationDisconnected(room: BcRoom) {
        if (synchronizeOwner() == null) return
        val conversationId = messageCoordinator.roomConversationId(room)
        setRoomStatus(conversationId, "No longer in this room")
        publishConversations()
        conversationRows.value.rows.firstOrNull { it.id == conversationId }?.let(::upsertConversation)
    }

    // Presence callbacks can run before the snapshot collector resumes after an account change.
    // Reset here as well so new-owner updates never merge with the previous owner's cache.
    private fun synchronizeOwner(): TimelineOwner? {
        val owner = stateHolder.current.timelineOwner()
        if (presentationOwner != owner) {
            presentationOwner = owner
            storedConversations = emptyList()
            onlineConversations = emptyList()
            roomStatuses = emptyMap()
            selection = null
            conversationRows.value = conversationSnapshot(emptyList())
        }
        return owner
    }

    private fun setRoomStatus(conversationId: BcConversationId, subtitle: String) {
        roomStatuses = roomStatuses + (conversationId to subtitle)
    }

    private fun publishConversations() {
        val merged = onlineConversations.fold(storedConversations) { rows, conversation ->
            messageCoordinator.mergePresenceConversation(rows, conversation)
        }
        conversationRows.value = conversationSnapshot(merged.take(conversationListLimit).map { conversation ->
            roomStatuses[conversation.id]?.let { status ->
                conversation.copy(subtitle = status)
            } ?: conversation
        })
    }

    private fun conversationSnapshot(rows: List<BcConversation>) = BcConversationSnapshot(
        ownerMemberNumber = presentationOwner?.memberNumber,
        accountGeneration = presentationOwner?.generation ?: 0,
        rows = rows,
    )

    private fun launchOperation(
        operation: BcTimelinePersistenceOperation,
        owner: TimelineOwner,
        conversationId: BcConversationId? = null,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                block()
                coroutineContext.ensureActive()
                clearFailure(operation, owner, conversationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                coroutineContext.ensureActive()
                recordFailure(operation, owner, conversationId)
            }
        }
    }

    private fun <T> observeWithRetry(
        operation: BcTimelinePersistenceOperation,
        owner: TimelineOwner,
        conversationId: BcConversationId? = null,
        observation: () -> Flow<T>,
    ): Flow<T> = flow { emitAll(observation()) }
        .retryWhen { failure, _ ->
            if (failure is CancellationException || failure !is Exception) throw failure
            coroutineContext.ensureActive()
            recordFailure(operation, owner, conversationId)
            delay(1_000)
            coroutineContext.ensureActive()
            true
        }
        .onEach { clearFailure(operation, owner, conversationId) }

    private fun recordFailure(
        operation: BcTimelinePersistenceOperation,
        owner: TimelineOwner,
        conversationId: BcConversationId?,
    ) {
        failedOperation.update { existing ->
            if (stateHolder.current.timelineOwner() == owner) {
                BcTimelinePersistenceFailure(operation, owner.memberNumber, owner.generation, conversationId)
            } else {
                existing
            }
        }
    }

    private fun clearFailure(
        operation: BcTimelinePersistenceOperation,
        owner: TimelineOwner,
        conversationId: BcConversationId?,
    ) {
        failedOperation.update { existing ->
            existing?.takeUnless {
                it.ownerMemberNumber == owner.memberNumber && it.accountGeneration == owner.generation &&
                    it.operation == operation && it.conversationId == conversationId
            }
        }
    }

    private fun BcSessionSnapshot.timelineOwner(): TimelineOwner? = account?.let {
        TimelineOwner(it.memberNumber, relationships.accountGeneration)
    }

    private data class TimelineOwner(val memberNumber: Int, val generation: Long)
}
