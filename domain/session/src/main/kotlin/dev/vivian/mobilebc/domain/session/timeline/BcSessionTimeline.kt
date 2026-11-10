package dev.vivian.mobilebc.domain.session.timeline

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class BcConversationSnapshot(
    val ownerMemberNumber: Int? = null,
    val accountGeneration: Long = 0,
    val rows: List<BcConversation> = emptyList(),
) {
    fun rowsFor(session: BcSessionSnapshot): List<BcConversation> =
        rows.takeIf {
            ownerMemberNumber != null && ownerMemberNumber == session.account?.memberNumber &&
                accountGeneration == session.relationships.accountGeneration
        }.orEmpty()
}

data class BcContactProfileSnapshot(
    val ownerMemberNumber: Int? = null,
    val accountGeneration: Long = 0,
    val profile: BcContactProfile? = null,
) {
    fun profileFor(session: BcSessionSnapshot): BcContactProfile? =
        profile.takeIf {
            ownerMemberNumber != null && ownerMemberNumber == session.account?.memberNumber &&
                accountGeneration == session.relationships.accountGeneration
        }
}

data class BcMessageSnapshot(
    val ownerMemberNumber: Int? = null,
    val accountGeneration: Long = 0,
    val rows: List<BcMessage> = emptyList(),
) {
    fun rowsFor(owner: Int?, generation: Long): List<BcMessage> = rows.takeIf {
        ownerMemberNumber != null && ownerMemberNumber == owner && accountGeneration == generation
    }.orEmpty()
}

interface BcSessionTimeline {
    fun observeHistory(
        conversationId: BcConversationId,
        query: dev.vivian.mobilebc.core.model.BcMessageHistoryQuery,
    ): Flow<BcMessageSnapshot> = kotlinx.coroutines.flow.flowOf(BcMessageSnapshot())

    val conversations: StateFlow<BcConversationSnapshot>
    val persistenceFailure: StateFlow<BcTimelinePersistenceFailure?>

    fun observeMessages(conversationId: BcConversationId, limit: Int = 500): Flow<BcMessageSnapshot>
    fun observeRoomWhispers(roomName: String, limit: Int = 500): Flow<BcMessageSnapshot> =
        kotlinx.coroutines.flow.flowOf(BcMessageSnapshot())
    fun observeCharacterProfile(memberNumber: Int): Flow<BcContactProfileSnapshot> =
        kotlinx.coroutines.flow.flowOf(BcContactProfileSnapshot())
    fun selectConversation(conversationId: BcConversationId, owner: Any)
    fun clearSelectedConversation(owner: Any)
    fun isConversationVisible(conversationId: BcConversationId): Boolean
    fun advanceReadCursor(ownerMemberNumber: Int, accountGeneration: Long, conversationId: BcConversationId, throughMessage: BcMessage)
}

enum class BcTimelinePersistenceOperation {
    ObserveConversations,
    ObserveMessages,
    ObserveCharacterProfile,
    AdvanceReadCursor,
    UpsertConversation,
    UpsertRoomState,
    PersistCharacterProfile,
}

data class BcTimelinePersistenceFailure(
    val operation: BcTimelinePersistenceOperation,
    val ownerMemberNumber: Int,
    val accountGeneration: Long,
    val conversationId: BcConversationId? = null,
) {
    fun belongsTo(session: BcSessionSnapshot): Boolean =
        ownerMemberNumber == session.account?.memberNumber &&
            accountGeneration == session.relationships.accountGeneration
}
