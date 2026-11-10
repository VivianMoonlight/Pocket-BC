package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import kotlinx.coroutines.flow.Flow

interface BcMessageTimelineStore {
    fun observeHistory(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        query: dev.vivian.mobilebc.core.model.BcMessageHistoryQuery,
    ): Flow<List<BcMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation)
    suspend fun upsertRoomState(ownerMemberNumber: Int, room: BcRoom, members: List<BcMember>) = Unit
    suspend fun upsertContactProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) = Unit
    fun observeContactProfile(ownerMemberNumber: Int, memberNumber: Int): Flow<BcContactProfile?> =
        kotlinx.coroutines.flow.flowOf(null)
    fun observeConversations(ownerMemberNumber: Int, limit: Int = 100): Flow<List<BcConversation>>
    suspend fun advanceReadCursor(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        throughMessage: BcMessage,
    )
    fun observeRoomWhispers(
        ownerMemberNumber: Int,
        roomName: String,
        limit: Int = 500,
    ): Flow<List<BcMessage>> = kotlinx.coroutines.flow.flowOf(emptyList())
    fun observeMessages(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        limit: Int = 500,
    ): Flow<List<BcMessage>>
}
