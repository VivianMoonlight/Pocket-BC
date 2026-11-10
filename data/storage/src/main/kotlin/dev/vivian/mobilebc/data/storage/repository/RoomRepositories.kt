package dev.vivian.mobilebc.data.storage

import androidx.room.withTransaction
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.model.isTimelineVisible
import dev.vivian.mobilebc.domain.messaging.BcInboundPersistenceCommand
import dev.vivian.mobilebc.domain.messaging.BcInboundPersistenceResult
import dev.vivian.mobilebc.domain.sync.BcOutboundDispatchReceipt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

internal class RoomConversationRepository(
    private val dao: ConversationDao,
    private val contactProfiles: RoomContactProfileRepository? = null,
) {
    suspend fun upsertConversation(ownerMemberNumber: Int, conversation: BcConversation) {
        val existing = dao.findConversation(ownerMemberNumber, conversation.id.value)
        dao.upsert(conversation.toEntity(ownerMemberNumber, existing))
    }

    suspend fun upsertRoomState(ownerMemberNumber: Int, room: BcRoom, members: List<BcMember>) {
        val conversationId = bcRoomConversationId(room.name.value).value
        val existing = dao.findConversation(ownerMemberNumber, conversationId)
            ?: dao.findRoomConversation(ownerMemberNumber, room.name.value)
        dao.upsert(room.toConversationEntity(ownerMemberNumber, conversationId, members, existing))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeConversations(ownerMemberNumber: Int, limit: Int): Flow<List<BcConversation>> =
        dao.observeConversations(ownerMemberNumber, limit).flatMapLatest { entities ->
            val rows = entities.map { it.toConversation() }
            val members = rows.filter { it.type == dev.vivian.mobilebc.core.model.ConversationType.Direct }
                .mapNotNull { it.id.value.removePrefix("message:").toIntOrNull() }.distinct()
            if (members.isEmpty() || contactProfiles == null) flowOf(rows)
            else contactProfiles.observeProfiles(ownerMemberNumber, members).map { profiles ->
                val avatars = profiles.associate { it.memberNumber to it.avatarUrl }
                rows.map { row -> row.copy(avatarUrl = avatars[row.id.value.removePrefix("message:").toIntOrNull()]) }
            }
        }

}

internal class RoomReadCursorRepository(
    private val database: BcColdDatabase,
    private val dao: MessageReadCursorDao,
) {
    suspend fun advance(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        throughMessage: BcMessage,
    ) {
        require(throughMessage.conversationId == conversationId) {
            "Read cursor message belongs to ${throughMessage.conversationId.value}, expected ${conversationId.value}."
        }
        database.withTransaction {
            dao.advanceIfNewer(
                ownerMemberNumber = ownerMemberNumber,
                conversationId = conversationId,
                throughMessage = throughMessage,
                updatedAtMillis = System.currentTimeMillis(),
            )
        }
    }
}

internal class RoomMessageRepository(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val contactProfileRepository: RoomContactProfileRepository? = null,
) {
    suspend fun upsertContactProfile(ownerMemberNumber: Int, profile: BcContactProfile) {
        contactProfileRepository?.upsertProfiles(ownerMemberNumber, listOf(profile))
    }
    suspend fun confirmOutgoing(ownerMemberNumber: Int, conversationId: String, clientMsgId: String) {
        val message = messageDao.find(ownerMemberNumber, conversationId, clientMsgId) ?: return
        if (!message.senderIsSelf || message.rawSourceEvent != "LocalOutgoing") return
        messageDao.upsert(message.copy(
            delivery = DeliveryState.Sent.name,
            visibility = dev.vivian.mobilebc.core.model.BcMessageVisibility.Visible.name,
        ))
    }

    suspend fun appendMessage(
        ownerMemberNumber: Int,
        message: BcMessage,
    ) {
        contactProfileRepository?.upsertProfiles(ownerMemberNumber, message.toContactProfiles(ownerMemberNumber))
        messageDao.upsert(message.toEntity(ownerMemberNumber))
        if (!message.isTimelineVisible()) return
        val existing = conversationDao.findConversation(ownerMemberNumber, message.conversationId.value)
        conversationDao.upsert(
            message.toConversationEntity(
                ownerMemberNumber = ownerMemberNumber,
                existing = existing,
            )
        )
    }

    fun observeRoomWhispers(
        ownerMemberNumber: Int,
        roomName: String,
        limit: Int,
    ): Flow<List<BcMessage>> = messageDao.observeRoomWhispers(ownerMemberNumber, roomName, limit)
        .map { entities -> entities.asReversed().mapNotNull { it.toMessageOrNull() } }
        .withSenderProfiles(ownerMemberNumber)

    fun observeHistory(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        query: dev.vivian.mobilebc.core.model.BcMessageHistoryQuery,
    ): Flow<List<BcMessage>> {
        val roomName = conversationId.value.takeIf { it.startsWith("room:") }?.let { id ->
            runCatching { String(java.util.Base64.getUrlDecoder().decode(id.removePrefix("room:")), Charsets.UTF_8) }
                .getOrNull()?.takeIf { dev.vivian.mobilebc.core.model.bcRoomConversationId(it) == conversationId }
        }
        return messageDao.observeHistory(
            ownerMemberNumber, conversationId.value, roomName, query.text.trim(),
            query.fromMillis, query.untilMillis, query.oldestFirst, query.limit,
        ).map { rows -> rows.mapNotNull { it.toMessageOrNull() } }.withSenderProfiles(ownerMemberNumber)
    }

    fun observeMessages(
        ownerMemberNumber: Int,
        conversationId: BcConversationId,
        limit: Int,
    ): Flow<List<BcMessage>> =
        messageDao.observeMessages(ownerMemberNumber, conversationId.value, limit)
            .map { entities ->
                entities.asReversed().mapNotNull { it.toMessageOrNull() }
            }
            .withSenderProfiles(ownerMemberNumber)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun Flow<List<BcMessage>>.withSenderProfiles(ownerMemberNumber: Int): Flow<List<BcMessage>> =
        flatMapLatest { messages ->
            val members = messages.mapNotNull { it.sender.memberNumber }.distinct()
            val profiles = contactProfileRepository
            if (profiles == null || members.isEmpty()) flowOf(messages)
            else profiles.observeProfiles(ownerMemberNumber, members).map { contacts ->
                val byMember = contacts.associateBy { it.memberNumber }
                messages.map { message ->
                    val contact = byMember[message.sender.memberNumber]
                    message.copy(sender = message.sender.copy(
                        labelColor = contact?.labelColor,
                        avatarUrl = contact?.avatarUrl,
                    ))
                }
            }
        }
}

internal class RoomOutboxRepository(
    private val database: BcColdDatabase,
    private val dao: OutboxDao,
    private val messageDao: MessageDao,
    private val messageRepository: RoomMessageRepository,
) {
    suspend fun enqueue(message: PersistedOutboxMessage, localMessage: BcMessage) {
        require(message.ownerMemberNumber >= 0) { "Outbound owner member number is invalid." }
        require(localMessage.id.value == message.clientMsgId) {
            "Optimistic message ID must equal clientMsgId."
        }
        database.withTransaction {
            messageRepository.appendMessage(
                ownerMemberNumber = message.ownerMemberNumber,
                message = localMessage,
            )
            dao.upsert(message.toEntity())
        }
    }

    suspend fun findDispatchable(
        ownerMemberNumber: Int,
        clientMsgId: String,
        nowMillis: Long,
    ): PersistedOutboxMessage? = database.withTransaction {
        expireStaleSubmitted(ownerMemberNumber, nowMillis)
        dao.findDispatchable(ownerMemberNumber, clientMsgId, nowMillis)?.toPersisted()
    }

    suspend fun loadPending(
        ownerMemberNumber: Int,
        nowMillis: Long,
        limit: Int,
    ): List<PersistedOutboxMessage> = database.withTransaction {
        expireStaleSubmitted(ownerMemberNumber, nowMillis)
        dao.loadPending(ownerMemberNumber, nowMillis, limit).map { it.toPersisted() }
    }

    suspend fun hasPendingConfirmation(ownerMemberNumber: Int): Boolean =
        dao.hasPendingConfirmation(ownerMemberNumber)

    suspend fun markSending(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long): Boolean =
        database.withTransaction {
            val claimed = dao.markSending(ownerMemberNumber, clientMsgId, updatedAtMillis) > 0
            if (claimed) {
                messageDao.updateDelivery(ownerMemberNumber, clientMsgId, DeliveryState.Sending.name)
            }
            claimed
        }

    suspend fun markSubmitted(
        ownerMemberNumber: Int,
        clientMsgId: String,
        updatedAtMillis: Long,
        receipt: BcOutboundDispatchReceipt = BcOutboundDispatchReceipt(),
    ) {
        database.withTransaction {
            if (dao.markSubmitted(ownerMemberNumber, clientMsgId, updatedAtMillis) > 0) {
                recordDispatchReceipt(ownerMemberNumber, clientMsgId, receipt, DeliveryState.Sending)
            }
        }
    }

    suspend fun markDeferred(ownerMemberNumber: Int, clientMsgId: String, updatedAtMillis: Long) {
        dao.markDeferred(ownerMemberNumber, clientMsgId, updatedAtMillis)
    }

    suspend fun markConfirmed(
        ownerMemberNumber: Int,
        clientMsgId: String,
        updatedAtMillis: Long,
        receipt: BcOutboundDispatchReceipt = BcOutboundDispatchReceipt(),
    ) {
        database.withTransaction {
            dao.markConfirmed(ownerMemberNumber, clientMsgId, updatedAtMillis)
            recordDispatchReceipt(ownerMemberNumber, clientMsgId, receipt, DeliveryState.Sent)
        }
    }

    private suspend fun recordDispatchReceipt(
        ownerMemberNumber: Int,
        clientMsgId: String,
        receipt: BcOutboundDispatchReceipt,
        delivery: DeliveryState,
    ) {
        val outbox = dao.find(ownerMemberNumber, clientMsgId) ?: return
        val message = messageDao.find(ownerMemberNumber, outbox.conversationId, clientMsgId) ?: return
        val prepared = message.withDispatchReceipt(receipt, delivery)
        if (prepared != message) messageDao.upsert(prepared)
    }

    suspend fun markRetryableFailure(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        nextAttemptAtMillis: Long,
        updatedAtMillis: Long,
    ) {
        database.withTransaction {
            if (dao.markRetryableFailure(
                    ownerMemberNumber,
                    clientMsgId,
                    error,
                    nextAttemptAtMillis,
                    updatedAtMillis,
                ) > 0
            ) {
                messageDao.updateDelivery(ownerMemberNumber, clientMsgId, DeliveryState.Sending.name)
            }
        }
    }

    suspend fun markTerminalFailure(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        updatedAtMillis: Long,
    ) {
        database.withTransaction {
            if (dao.markTerminalFailure(ownerMemberNumber, clientMsgId, error, updatedAtMillis) > 0) {
                messageDao.markFailedVisible(
                    ownerMemberNumber = ownerMemberNumber,
                    messageId = clientMsgId,
                    delivery = DeliveryState.Failed.name,
                    visibility = dev.vivian.mobilebc.core.model.BcMessageVisibility.LocalOnly.name,
                )
            }
        }
    }

    suspend fun markRejected(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String?,
        updatedAtMillis: Long,
    ) {
        database.withTransaction {
            if (dao.markRejected(ownerMemberNumber, clientMsgId, error, updatedAtMillis) > 0) {
                messageDao.markFailedVisible(
                    ownerMemberNumber = ownerMemberNumber,
                    messageId = clientMsgId,
                    delivery = DeliveryState.Failed.name,
                    visibility = dev.vivian.mobilebc.core.model.BcMessageVisibility.LocalOnly.name,
                )
            }
        }
    }

    suspend fun markInvalidPending(
        ownerMemberNumber: Int,
        clientMsgId: String,
        error: String,
        updatedAtMillis: Long,
    ) {
        database.withTransaction {
            if (dao.markInvalidPending(ownerMemberNumber, clientMsgId, error, updatedAtMillis) > 0) {
                messageDao.markFailedVisible(
                    ownerMemberNumber = ownerMemberNumber,
                    messageId = clientMsgId,
                    delivery = DeliveryState.Failed.name,
                    visibility = dev.vivian.mobilebc.core.model.BcMessageVisibility.LocalOnly.name,
                )
            }
        }
    }

    private suspend fun expireStaleSubmitted(ownerMemberNumber: Int, nowMillis: Long) {
        val cutoffUpdatedAtMillis = nowMillis - CONFIRMATION_TIMEOUT_MILLIS
        val staleClientMsgIds = dao.loadStaleSubmittedClientMsgIds(ownerMemberNumber, cutoffUpdatedAtMillis)
        dao.markStaleSubmittedFailed(
            ownerMemberNumber = ownerMemberNumber,
            cutoffUpdatedAtMillis = cutoffUpdatedAtMillis,
            error = "Server confirmation timed out; delivery is unknown.",
            updatedAtMillis = nowMillis,
        )
        staleClientMsgIds.forEach { clientMsgId ->
            messageDao.markFailedVisible(
                ownerMemberNumber = ownerMemberNumber,
                messageId = clientMsgId,
                delivery = DeliveryState.Failed.name,
                visibility = dev.vivian.mobilebc.core.model.BcMessageVisibility.LocalOnly.name,
            )
        }
    }

    suspend fun markStaleSendingRetryable(
        ownerMemberNumber: Int,
        cutoffUpdatedAtMillis: Long,
        error: String,
        nextAttemptAtMillis: Long,
        updatedAtMillis: Long,
    ) {
        database.withTransaction {
            val staleClientMsgIds = dao.loadStaleSendingClientMsgIds(
                ownerMemberNumber = ownerMemberNumber,
                cutoffUpdatedAtMillis = cutoffUpdatedAtMillis,
            )
            dao.markStaleSendingRetryable(
                ownerMemberNumber,
                cutoffUpdatedAtMillis,
                error,
                nextAttemptAtMillis,
                updatedAtMillis,
            )
            staleClientMsgIds.forEach { clientMsgId ->
                messageDao.updateDelivery(ownerMemberNumber, clientMsgId, DeliveryState.Sending.name)
            }
        }
    }

    private companion object {
        const val CONFIRMATION_TIMEOUT_MILLIS = 120_000L
    }
}

internal class RoomInboundPersistenceStore(
    private val database: BcColdDatabase,
    private val inboxDao: InboxDao,
    private val messageRepository: RoomMessageRepository,
    private val messageReadCursorDao: MessageReadCursorDao,
    private val outboxDao: OutboxDao,
    private val syncCursorDao: SyncCursorDao,
) : dev.vivian.mobilebc.domain.messaging.BcInboundPersistenceStore {
    override suspend fun confirmOutgoing(ownerMemberNumber: Int, clientMsgId: String, receivedAtMillis: Long) {
        database.withTransaction {
            val outgoing = outboxDao.find(ownerMemberNumber, clientMsgId) ?: return@withTransaction
            outboxDao.markConfirmed(ownerMemberNumber, clientMsgId, receivedAtMillis)
            messageRepository.confirmOutgoing(ownerMemberNumber, outgoing.conversationId, clientMsgId)
        }
    }

    override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult =
        database.withTransaction {
            val eventOwnerMemberNumber = command.ownerMemberNumber ?: UNSCOPED_OWNER_MEMBER_NUMBER
            val inserted = inboxDao.insert(command.event.toEntity(eventOwnerMemberNumber))
            if (inserted == -1L) {
                BcInboundPersistenceResult.Duplicate(command.event.packetId)
            } else {
                val ownerMemberNumber = command.ownerMemberNumber
                if (ownerMemberNumber != null) {
                    command.contactProfile?.let { messageRepository.upsertContactProfile(ownerMemberNumber, it) }
                }
                val message = command.message
                if (ownerMemberNumber != null && message != null) {
                    messageRepository.appendMessage(
                        ownerMemberNumber = ownerMemberNumber,
                        message = message,
                    )
                    if (command.markReadThroughMessage) {
                        messageReadCursorDao.advanceIfNewer(
                            ownerMemberNumber = ownerMemberNumber,
                            conversationId = message.conversationId,
                            throughMessage = message,
                            updatedAtMillis = command.cursorUpdatedAtMillis,
                        )
                    }
                    if (message.sender.isSelf) {
                        outboxDao.markConfirmed(
                            ownerMemberNumber = ownerMemberNumber,
                            clientMsgId = message.id.value,
                            updatedAtMillis = command.cursorUpdatedAtMillis,
                        )
                    }
                }
                val cursorConversationId = command.cursorConversationId
                if (ownerMemberNumber != null && cursorConversationId != null) {
                    syncCursorDao.upsert(
                        SyncCursorEntity(
                            ownerMemberNumber = ownerMemberNumber,
                            conversationId = cursorConversationId.value,
                            seq = command.cursorSeq,
                            updatedAtMillis = command.cursorUpdatedAtMillis,
                        )
                    )
                }
                BcInboundPersistenceResult.Recorded
            }
        }
}

internal class RoomContactProfileRepository(
    private val database: BcColdDatabase,
    private val dao: ContactProfileDao,
) {
    suspend fun upsertProfiles(ownerMemberNumber: Int, profiles: List<BcContactProfile>) {
        if (profiles.isEmpty()) return
        database.withTransaction {
            val existing = dao.findProfiles(ownerMemberNumber, profiles.map { it.memberNumber })
                .associateBy { it.memberNumber }
            dao.upsertAll(
                profiles.map { profile ->
                    profile.toEntity(
                        ownerMemberNumber = ownerMemberNumber,
                        existing = existing[profile.memberNumber],
                    )
                }
            )
        }
    }

    fun observeProfile(ownerMemberNumber: Int, memberNumber: Int) =
        dao.observeProfile(ownerMemberNumber, memberNumber).map { it?.toContactProfile() }

    fun observeProfiles(ownerMemberNumber: Int, memberNumbers: List<Int>) =
        dao.observeProfiles(ownerMemberNumber, memberNumbers).map { rows -> rows.map { it.toContactProfile() } }
}


private suspend fun MessageReadCursorDao.advanceIfNewer(
    ownerMemberNumber: Int,
    conversationId: BcConversationId,
    throughMessage: BcMessage,
    updatedAtMillis: Long,
) {
    require(throughMessage.conversationId == conversationId) {
        "Read cursor message belongs to ${throughMessage.conversationId.value}, expected ${conversationId.value}."
    }
    val candidate = MessageReadCursorEntity(
        ownerMemberNumber = ownerMemberNumber,
        conversationId = conversationId.value,
        readThroughTimestampMillis = throughMessage.timestamp.toEpochMilli(),
        readThroughMessageId = throughMessage.id.value,
        updatedAtMillis = updatedAtMillis,
    )
    val existing = find(ownerMemberNumber, conversationId.value)
    if (existing == null || candidate.order > existing.order) {
        upsert(candidate)
    }
}

private const val UNSCOPED_OWNER_MEMBER_NUMBER: Int = Int.MIN_VALUE

private val MessageReadCursorEntity.order: MessageReadOrder
    get() = MessageReadOrder(readThroughTimestampMillis, readThroughMessageId)
