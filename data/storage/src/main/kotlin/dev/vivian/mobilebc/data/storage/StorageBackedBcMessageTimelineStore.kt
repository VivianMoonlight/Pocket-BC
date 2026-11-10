package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import kotlinx.coroutines.flow.Flow

internal class StorageBackedBcMessageTimelineStore(
    private val conversations: RoomConversationRepository,
    private val messages: RoomMessageRepository,
    private val readCursors: RoomReadCursorRepository,
    private val contactProfiles: RoomContactProfileRepository,
) : BcMessageTimelineStore {
    override fun observeHistory(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        query: dev.vivian.mobilebc.core.model.BcMessageHistoryQuery,
    ): Flow<List<BcMessage>> = messages.observeHistory(ownerMemberNumber, conversationId, query)

    override suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation) {
        conversations.upsertConversation(ownerMemberNumber, conversation)
    }

    override suspend fun upsertRoomState(ownerMemberNumber: Int, room: BcRoom, members: List<BcMember>) {
        conversations.upsertRoomState(ownerMemberNumber, room, members)
    }

    override suspend fun upsertContactProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) {
        contactProfiles.upsertProfiles(ownerMemberNumber, profiles)
    }

    override fun observeContactProfile(ownerMemberNumber: Int, memberNumber: Int): Flow<BcContactProfile?> =
        contactProfiles.observeProfile(ownerMemberNumber, memberNumber)

    override fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
        conversations.observeConversations(ownerMemberNumber, limit)

    override suspend fun advanceReadCursor(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        throughMessage: BcMessage,
    ) {
        readCursors.advance(ownerMemberNumber, conversationId, throughMessage)
    }

    override fun observeRoomWhispers(
        ownerMemberNumber: Int,
        roomName: String,
        limit: Int,
    ): Flow<List<BcMessage>> = messages.observeRoomWhispers(ownerMemberNumber, roomName, limit)

    override fun observeMessages(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        limit: Int,
    ): Flow<List<BcMessage>> =
        messages.observeMessages(ownerMemberNumber, conversationId, limit)
}
