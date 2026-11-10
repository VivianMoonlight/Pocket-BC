package dev.vivian.mobilebc.feature.conversations

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.ConversationAvailability
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.relationship.BcRelationshipMutationResult
import dev.vivian.mobilebc.domain.session.relationship.BcSessionRelationships
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.label
import dev.vivian.mobilebc.domain.session.timeline.BcConversationSnapshot
import dev.vivian.mobilebc.domain.session.timeline.BcSessionTimeline
import dev.vivian.mobilebc.domain.session.timeline.BcTimelinePersistenceFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update

data class ConversationsUiState(
    val accountTitle: String = "Not logged in",
    val accountSubtitle: String = "Lightweight BC session",
    val statusText: String = "",
    val conversations: List<BcConversation> = emptyList(),
    val currentRoomConversationId: BcConversationId? = null,
    val directPresenceKnown: Boolean = false,
    val firstVisibleConversationIndex: Int = 0,
    val firstVisibleConversationScrollOffset: Int = 0,
    val friendRequests: List<FriendRequestUiState> = emptyList(),
    val acceptingFriendRequestMemberNumber: Int? = null,
    val friendRequestMessage: String? = null,
    val persistenceFailure: BcTimelinePersistenceFailure? = null,
)

data class FriendRequestUiState(
    val memberNumber: Int,
    val displayName: String,
    val receivedAtMillis: Long,
)

class ConversationsViewModel(
    private val timeline: BcSessionTimeline,
    private val connectionStatus: ConnectionStatusReader,
    private val relationships: BcSessionRelationships,
    private val messageCoordinator: BcSessionMessageCoordinator = BcSessionMessageCoordinator(),
    private val scope: CoroutineScope,
) {
    private data class ScrollPosition(
        val index: Int = 0,
        val offset: Int = 0,
    )

    private val scrollPosition = MutableStateFlow(ScrollPosition())
    private val friendRequestOperation = MutableStateFlow(FriendRequestOperationState())

    val uiState: StateFlow<ConversationsUiState> = combine(
        timeline.conversations,
        connectionStatus.snapshot,
        scrollPosition,
        friendRequestOperation,
        timeline.persistenceFailure,
    ) { conversations, session, scroll, operation, failure ->
        buildUiState(
            conversations = conversations,
            session = session,
            scroll = scroll,
            friendRequestOperation = operation,
            persistenceFailure = failure,
        )
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = buildUiState(
            conversations = timeline.conversations.value,
            session = connectionStatus.snapshot.value,
            scroll = scrollPosition.value,
            friendRequestOperation = friendRequestOperation.value,
            persistenceFailure = timeline.persistenceFailure.value,
        ),
    )

    fun saveScrollPosition(index: Int, scrollOffset: Int) {
        scrollPosition.value = ScrollPosition(
            index = index.coerceAtLeast(0),
            offset = scrollOffset.coerceAtLeast(0),
        )
    }

    fun acceptFriendRequest(memberNumber: Int) {
        val session = connectionStatus.snapshot.value
        val request = AcceptFriendRequest(memberNumber)
        val operation = FriendRequestOperationState(
            ownerMemberNumber = session.account?.memberNumber,
            accountGeneration = session.relationships.accountGeneration,
            request = request,
        )
        while (true) {
            val current = friendRequestOperation.value
            if (current.belongsTo(session) && current.request != null) return
            if (friendRequestOperation.compareAndSet(current, operation)) break
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                currentCoroutineContext().ensureActive()
                if (!operation.belongsTo(connectionStatus.snapshot.value)) return@launch
                val message = relationships.acceptFriendRequest(memberNumber).toFriendRequestMessage()
                currentCoroutineContext().ensureActive()
                if (operation.belongsTo(connectionStatus.snapshot.value)) {
                    friendRequestOperation.update { current ->
                        if (current.request === request) current.copy(message = message) else current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                currentCoroutineContext().ensureActive()
                if (operation.belongsTo(connectionStatus.snapshot.value)) {
                    friendRequestOperation.update { current ->
                        if (current.request === request) current.copy(
                            message = failure.message ?: "Failed to accept friend request.",
                        ) else current
                    }
                }
            } finally {
                friendRequestOperation.update { current ->
                    if (current.request === request) current.copy(request = null) else current
                }
            }
        }
    }

    fun dismissFriendRequest(memberNumber: Int) {
        relationships.dismissFriendRequest(memberNumber)
        val session = connectionStatus.snapshot.value
        friendRequestOperation.update { latest ->
            val current = latest.takeIf { it.belongsTo(session) }
                ?: FriendRequestOperationState(
                    ownerMemberNumber = session.account?.memberNumber,
                    accountGeneration = session.relationships.accountGeneration,
                )
            current.copy(message = "Friend request dismissed.")
        }
    }

    fun clearFriendRequestMessage() {
        val session = connectionStatus.snapshot.value
        friendRequestOperation.update { current ->
            if (current.belongsTo(session)) current.copy(message = null) else current
        }
    }

    private fun buildUiState(
        conversations: BcConversationSnapshot,
        session: BcSessionSnapshot,
        scroll: ScrollPosition,
        friendRequestOperation: FriendRequestOperationState,
        persistenceFailure: BcTimelinePersistenceFailure?,
    ): ConversationsUiState {
        val currentRoomConversationId =
            messageCoordinator.currentRoomConversationId(session.currentRoom)
        val sameRoomMemberNumbers = if (session.currentRoom != null && session.isVerifiedOnline) {
            session.roomMembers.asSequence()
                .map { it.memberNumber }
                .filter { it != session.account?.memberNumber }
                .toSet()
        } else {
            emptySet()
        }
        val currentFriendRequestOperation = friendRequestOperation.takeIf { it.belongsTo(session) }
        val displayConversations = conversations.rowsFor(session)
            .mapNotNull { conversation ->
                when {
                    conversation.type == ConversationType.Direct -> {
                        val memberNumber = messageCoordinator.directMemberNumber(conversation)
                        val presented = conversation.copy(
                            avatarUrl = conversation.avatarUrl ?: session.roomCharacters
                                .firstOrNull { it.memberNumber == memberNumber }?.avatarUrl,
                        )
                        val availability = if (memberNumber != null && memberNumber in sameRoomMemberNumbers) {
                            conversation.availability + ConversationAvailability.SameRoom
                        } else {
                            conversation.availability - ConversationAvailability.SameRoom
                        }
                        when {
                            availability.isEmpty() && !conversation.hasMessages -> null
                            availability == conversation.availability -> presented
                            else -> presented.copy(availability = availability)
                        }
                    }
                    conversation.type == ConversationType.CurrentRoom &&
                        conversation.id != currentRoomConversationId -> conversation.copy(pinned = false)
                    else -> conversation
                }
            }
            .sortedWith(
                compareBy<BcConversation> { conversation ->
                    when {
                        conversation.id == currentRoomConversationId -> 0
                        conversation.type == ConversationType.Direct && conversation.hasMessages -> 1
                        conversation.type == ConversationType.Direct &&
                            ConversationAvailability.SameRoom in conversation.availability -> 2
                        conversation.type == ConversationType.Direct &&
                            ConversationAvailability.OnlineFriend in conversation.availability -> 3
                        conversation.type == ConversationType.CurrentRoom -> 4
                        conversation.type == ConversationType.System -> 5
                        else -> 6
                    }
                }.thenByDescending { it.updatedAt }
                    .thenBy { it.title.lowercase() },
            )

        return ConversationsUiState(
            accountTitle = session.account?.displayName ?: "Not logged in",
            accountSubtitle = session.account?.memberNumber
                ?.let { "#$it" }
                ?: "Lightweight BC session",
            statusText = session.connectionHealth.label(),
            currentRoomConversationId = currentRoomConversationId,
            directPresenceKnown = session.isVerifiedOnline,
            conversations = displayConversations,
            firstVisibleConversationIndex = scroll.index,
            firstVisibleConversationScrollOffset = scroll.offset,
            friendRequests = session.relationships.friendRequests.values
                .filter { it.expiresAtMillis > System.currentTimeMillis() }
                .sortedByDescending { it.receivedAtMillis }
                .map { request ->
                    FriendRequestUiState(
                        memberNumber = request.senderMemberNumber,
                        displayName = session.roomMembers
                            .firstOrNull { it.memberNumber == request.senderMemberNumber }
                            ?.name
                            ?: "#${request.senderMemberNumber}",
                        receivedAtMillis = request.receivedAtMillis,
                    )
                },
            acceptingFriendRequestMemberNumber = currentFriendRequestOperation?.request?.memberNumber,
            friendRequestMessage = currentFriendRequestOperation?.message,
            persistenceFailure = persistenceFailure?.takeIf { it.belongsTo(session) },
        )
    }
}

private class AcceptFriendRequest(val memberNumber: Int)

private data class FriendRequestOperationState(
    val ownerMemberNumber: Int? = null,
    val accountGeneration: Long = 0L,
    val request: AcceptFriendRequest? = null,
    val message: String? = null,
) {
    fun belongsTo(session: BcSessionSnapshot): Boolean =
        ownerMemberNumber == session.account?.memberNumber &&
            accountGeneration == session.relationships.accountGeneration
}

private fun BcRelationshipMutationResult.toFriendRequestMessage(): String = when (this) {
    BcRelationshipMutationResult.Submitted -> "Friend request accepted."
    BcRelationshipMutationResult.Unchanged -> "This member is already in your friend list."
    is BcRelationshipMutationResult.Rejected -> "Friend request rejected: ${reason.name}"
    is BcRelationshipMutationResult.Failed -> message
}
