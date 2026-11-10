package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcContactProfile
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcNotificationPolicy
import dev.vivian.mobilebc.core.model.BcRawMessage
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.core.model.ConversationType
import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.core.protocol.bcReplyIdFromDictionaryOrNull
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal fun BcConversation.toEntity(ownerMemberNumber: Int, existing: ConversationEntity?): ConversationEntity =
    ConversationEntity(
        ownerMemberNumber = ownerMemberNumber,
        conversationId = id.value,
        type = type.name,
        title = title,
        subtitle = subtitle,
        pinned = pinned || existing?.pinned == true,
        muted = existing?.muted ?: muted,
        unreadCount = existing?.unreadCount ?: 0,
        updatedAtMillis = updatedAt?.toEpochMilli() ?: existing?.updatedAtMillis,
        lastMessageId = existing?.lastMessageId,
        roomName = existing?.roomName,
        roomDescription = existing?.roomDescription,
        roomMemberCount = existing?.roomMemberCount,
        roomMemberLimit = existing?.roomMemberLimit,
        roomIsLocked = existing?.roomIsLocked,
        roomLanguage = existing?.roomLanguage,
        roomMapType = existing?.roomMapType,
        roomMembersJson = existing?.roomMembersJson,
        roomUpdatedAtMillis = existing?.roomUpdatedAtMillis,
    )

internal fun BcRoom.toConversationEntity(
    ownerMemberNumber: Int,
    conversationId: String,
    members: List<BcMember>,
    existing: ConversationEntity?,
): ConversationEntity {
    val now = System.currentTimeMillis()
    return ConversationEntity(
        ownerMemberNumber = ownerMemberNumber,
        conversationId = conversationId,
        type = ConversationType.CurrentRoom.name,
        title = name.value,
        subtitle = "${memberCount}/${memberLimit} members",
        pinned = existing?.pinned ?: true,
        muted = existing?.muted ?: false,
        unreadCount = existing?.unreadCount ?: 0,
        updatedAtMillis = existing?.updatedAtMillis ?: now,
        lastMessageId = existing?.lastMessageId,
        roomName = name.value,
        roomDescription = description,
        roomMemberCount = memberCount,
        roomMemberLimit = memberLimit,
        roomIsLocked = isLocked,
        roomLanguage = language,
        roomMapType = mapType.name,
        roomMembersJson = membersToJson(members),
        roomUpdatedAtMillis = now,
    )
}

internal fun ConversationEntity.toConversation(): BcConversation =
    BcConversation(
        id = BcConversationId(conversationId),
        type = enumValueOrDefault(type, ConversationType.System),
        title = title,
        subtitle = subtitle,
        unreadCount = unreadCount,
        muted = muted,
        pinned = pinned,
        updatedAt = updatedAtMillis?.let(Instant::ofEpochMilli),
        hasMessages = lastMessageId != null,
    )

internal fun BcMessage.toEntity(ownerMemberNumber: Int): MessageEntity =
    MessageEntity(
        ownerMemberNumber = ownerMemberNumber,
        conversationId = conversationId.value,
        messageId = id.value,
        addressType = address.typeName(),
        addressMemberNumber = (address as? BcMessageAddress.Direct)?.memberNumber,
        senderMemberNumber = sender.memberNumber,
        senderName = sender.displayName,
        senderIsSelf = sender.isSelf,
        kind = kind.name,
        transport = transport.name,
        text = text,
        timestampMillis = timestamp.toEpochMilli(),
        delivery = delivery.name,
        visibility = visibility.name,
        notificationPolicy = notificationPolicy.name,
        targetMemberNumber = targetMemberNumber,
        rawSourceEvent = raw?.sourceEvent,
        rawWireType = raw?.wireType,
        rawBeepType = raw?.beepType,
        rawPayloadJson = raw?.payloadJson,
        rawDictionaryJson = raw?.dictionaryJson,
        rawRoomName = raw?.roomName,
        resolutionType = when (resolution) {
            BcMessageResolution.Plain -> "Plain"
            is BcMessageResolution.NeedsTranslation -> "NeedsTranslation"
            is BcMessageResolution.Resolved -> "Resolved"
            is BcMessageResolution.Failed -> "Failed"
        },
        resolutionKey = when (val state = resolution) {
            BcMessageResolution.Plain -> null
            is BcMessageResolution.NeedsTranslation -> state.key
            is BcMessageResolution.Resolved -> state.key
            is BcMessageResolution.Failed -> state.key
        },
        resolutionText = when (val state = resolution) {
            BcMessageResolution.Plain,
            is BcMessageResolution.NeedsTranslation,
            -> null
            is BcMessageResolution.Resolved -> state.resolvedText
            is BcMessageResolution.Failed -> state.fallback
        },
    )

internal fun MessageEntity.toMessageOrNull(): BcMessage? {
    if (messageId.isBlank() || conversationId.isBlank() || timestampMillis <= 0L) return null
    val address = when (addressType) {
        "Direct" -> BcMessageAddress.Direct(
            addressMemberNumber
                ?: conversationId.removePrefix("message:").toIntOrNull()
                ?: targetMemberNumber
                ?: return null,
        )
        "CurrentRoom" -> BcMessageAddress.CurrentRoom
        "System" -> BcMessageAddress.System
        "Protocol" -> BcMessageAddress.Protocol(rawSourceEvent.orEmpty())
        else -> BcMessageAddress.System
    }
    return BcMessage(
        id = BcMessageId(messageId),
        conversationId = BcConversationId(conversationId),
        address = address,
        sender = BcSender(senderMemberNumber, senderName.ifBlank { "Unknown" }, senderIsSelf),
        kind = enumValueOrDefault(kind, BcMessageKind.Beep),
        transport = enumValueOrDefault(transport, BcMessageTransport.AccountBeep),
        text = text,
        timestamp = Instant.ofEpochMilli(timestampMillis),
        delivery = enumValueOrDefault(delivery, DeliveryState.Sent),
        visibility = enumValueOrDefault(visibility, BcMessageVisibility.Visible),
        notificationPolicy = enumValueOrDefault(notificationPolicy, BcNotificationPolicy.None),
        targetMemberNumber = targetMemberNumber,
        resolution = messageResolution(),
        raw = rawMessageOrNull(),
        replyToId = bcReplyIdFromDictionaryOrNull(rawDictionaryJson),
    )
}

private fun MessageEntity.messageResolution(): BcMessageResolution =
    when (resolutionType) {
        "NeedsTranslation" -> resolutionKey?.let(BcMessageResolution::NeedsTranslation)
        "Resolved" -> resolutionKey?.let { BcMessageResolution.Resolved(it, resolutionText ?: text) }
        "Failed" -> resolutionKey?.let { BcMessageResolution.Failed(it, resolutionText ?: text) }
        else -> null
    } ?: BcMessageResolution.Plain

internal fun BcContactProfile.toEntity(ownerMemberNumber: Int, existing: ContactProfileEntity?): ContactProfileEntity {
    val incomingSnapshotTime = snapshotUpdatedAtMillis
    val acceptsSnapshot = incomingSnapshotTime != null &&
        incomingSnapshotTime >= (existing?.snapshotUpdatedAtMillis ?: Long.MIN_VALUE)
    val staleSnapshot = incomingSnapshotTime != null && !acceptsSnapshot
    val observedName = name.takeIf { it.isNotBlank() && it != "#$memberNumber" }
    val acceptsObservation = lastSeenAtMillis >= (existing?.lastSeenAtMillis ?: Long.MIN_VALUE)
    val incomingLianTime = lianUpdatedAtMillis ?: incomingSnapshotTime
    val acceptsLian = incomingLianTime != null &&
        incomingLianTime >= (existing?.lianUpdatedAtMillis ?: existing?.snapshotUpdatedAtMillis ?: Long.MIN_VALUE)
    return ContactProfileEntity(
        ownerMemberNumber = ownerMemberNumber,
        memberNumber = memberNumber,
        name = observedName.takeIf { acceptsSnapshot || (!staleSnapshot && acceptsObservation) }
            ?: existing?.name.orEmpty().ifBlank { "#$memberNumber" },
        accountName = if (acceptsSnapshot) accountName else existing?.accountName,
        characterName = if (acceptsSnapshot) characterName else existing?.characterName,
        labelColor = if (acceptsSnapshot) labelColor else existing?.labelColor,
        avatarUrl = if (acceptsLian) avatarUrl else existing?.avatarUrl,
        signature = if (acceptsLian) signature else existing?.signature,
        nickname = if (acceptsSnapshot || (acceptsLian && lianUpdatedAtMillis != null)) nickname else existing?.nickname,
        lianUpdatedAtMillis = if (acceptsLian) incomingLianTime else existing?.lianUpdatedAtMillis,
        title = if (acceptsSnapshot) title else existing?.title,
        description = if (acceptsSnapshot) description else existing?.description,
        pronouns = if (acceptsSnapshot) pronouns else existing?.pronouns,
        statusText = statusText.takeIf { !staleSnapshot && acceptsObservation } ?: existing?.statusText,
        role = role?.name.takeIf { !staleSnapshot && acceptsObservation } ?: existing?.role,
        ownerRelationJson = if (acceptsSnapshot) ownerRelation?.toProfileJson()?.toString() else existing?.ownerRelationJson,
        loverRelationsJson = if (acceptsSnapshot) loverRelations?.let { relations -> JSONArray(relations.map { it.toProfileJson() }).toString() } else existing?.loverRelationsJson,
        portraitJson = if (acceptsSnapshot) portrait?.toProfileJson()?.toString() ?: existing?.portraitJson else existing?.portraitJson,
        ownerName = if (acceptsSnapshot) ownerName else existing?.ownerName,
        loverNamesJson = if (acceptsSnapshot) JSONArray(loverNames).toString() else existing?.loverNamesJson,
        firstSeenAtMillis = minOf(firstSeenAtMillis, existing?.firstSeenAtMillis ?: firstSeenAtMillis),
        lastSeenAtMillis = maxOf(lastSeenAtMillis, existing?.lastSeenAtMillis ?: 0L),
        snapshotUpdatedAtMillis = if (acceptsSnapshot) incomingSnapshotTime else existing?.snapshotUpdatedAtMillis,
    )
}

internal fun ContactProfileEntity.toContactProfile(): BcContactProfile = BcContactProfile(
    memberNumber = memberNumber,
    name = name,
    labelColor = labelColor,
    avatarUrl = avatarUrl,
    signature = signature,
    lianUpdatedAtMillis = lianUpdatedAtMillis,
    accountName = accountName,
    characterName = characterName,
    nickname = nickname,
    title = title,
    description = description,
    pronouns = pronouns,
    statusText = statusText,
    role = role?.let { enumValueOrDefault(it, dev.vivian.mobilebc.core.model.RoomRole.Member) },
    ownerRelation = decodeProfileOwner(ownerRelationJson),
    loverRelations = decodeProfileLovers(loverRelationsJson),
    portrait = decodeProfilePortrait(portraitJson),
    ownerName = ownerName,
    loverNames = loverNamesJson?.let { raw ->
        runCatching {
            JSONArray(raw).let { array -> List(array.length()) { index -> array.optString(index) }.filter(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }.orEmpty(),
    firstSeenAtMillis = firstSeenAtMillis,
    lastSeenAtMillis = lastSeenAtMillis,
    snapshotUpdatedAtMillis = snapshotUpdatedAtMillis,
)

internal fun PersistedOutboxMessage.toEntity(): OutboxMessageEntity =
    OutboxMessageEntity(
        clientMsgId = clientMsgId,
        ownerMemberNumber = ownerMemberNumber,
        conversationId = conversationId.value,
        transport = transport,
        payloadJson = payloadJson,
        state = state.name,
        attempts = attempts,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        lastError = lastError,
        nextAttemptAtMillis = nextAttemptAtMillis,
    )

internal fun OutboxMessageEntity.toPersisted(): PersistedOutboxMessage =
    PersistedOutboxMessage(
        clientMsgId = clientMsgId,
        ownerMemberNumber = ownerMemberNumber,
        conversationId = BcConversationId(conversationId),
        transport = transport,
        payloadJson = payloadJson,
        state = enumValueOrDefault(state, OutboxMessageState.Failed),
        attempts = attempts,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis,
        lastError = lastError,
        nextAttemptAtMillis = nextAttemptAtMillis,
    )

internal fun dev.vivian.mobilebc.domain.messaging.BcInboxEvent.toEntity(ownerMemberNumber: Int): InboxEventEntity =
    InboxEventEntity(
        ownerMemberNumber = ownerMemberNumber,
        packetId = packetId,
        eventName = eventName,
        conversationId = conversationId?.value,
        receivedAtMillis = receivedAtMillis,
        rawJson = rawJson,
    )

internal fun PersistedDeadLetter.toEntity(): DeadLetterEntity =
    DeadLetterEntity(
        id = id,
        stage = stage,
        eventName = eventName,
        codecName = codecName,
        rawPayload = rawPayload,
        errorCode = errorCode,
        errorMessage = errorMessage,
        createdAtMillis = createdAtMillis,
    )

internal fun BcMessage.toConversationEntity(
    ownerMemberNumber: Int,
    existing: ConversationEntity?,
): ConversationEntity {
    val type = when (address) {
        is BcMessageAddress.Direct -> ConversationType.Direct
        BcMessageAddress.CurrentRoom -> ConversationType.CurrentRoom
        BcMessageAddress.System,
        is BcMessageAddress.Protocol,
        -> ConversationType.System
    }
    val title = when (type) {
        ConversationType.Direct -> {
            val other = (address as? BcMessageAddress.Direct)?.memberNumber
                ?: targetMemberNumber?.takeIf { sender.isSelf }
                ?: sender.memberNumber
            if (sender.isSelf) "#${other ?: "unknown"}" else sender.displayName
        }
        ConversationType.CurrentRoom -> existing?.title?.takeIf { it.isNotBlank() } ?: "Current room"
        ConversationType.System -> "System"
    }
    val messageTimestampMillis = timestamp.toEpochMilli()
    val updatesLatestProjection = existing?.updatedAtMillis?.let { messageTimestampMillis >= it } ?: true
    return ConversationEntity(
        ownerMemberNumber = ownerMemberNumber,
        conversationId = conversationId.value,
        type = type.name,
        title = existing?.title?.takeIf { it.isNotBlank() } ?: title,
        subtitle = if (updatesLatestProjection) text else existing?.subtitle.orEmpty(),
        pinned = existing?.pinned ?: (type == ConversationType.CurrentRoom),
        muted = existing?.muted ?: false,
        unreadCount = existing?.unreadCount ?: 0,
        updatedAtMillis = if (updatesLatestProjection) messageTimestampMillis else existing?.updatedAtMillis,
        lastMessageId = if (updatesLatestProjection) id.value else existing?.lastMessageId,
        roomName = existing?.roomName,
        roomDescription = existing?.roomDescription,
        roomMemberCount = existing?.roomMemberCount,
        roomMemberLimit = existing?.roomMemberLimit,
        roomIsLocked = existing?.roomIsLocked,
        roomLanguage = existing?.roomLanguage,
        roomMapType = existing?.roomMapType,
        roomMembersJson = existing?.roomMembersJson,
        roomUpdatedAtMillis = existing?.roomUpdatedAtMillis,
    )
}

internal fun BcMessage.toContactProfiles(ownerMemberNumber: Int): List<BcContactProfile> {
    val now = timestamp.toEpochMilli().takeIf { it > 0L } ?: System.currentTimeMillis()
    val profiles = linkedMapOf<Int, BcContactProfile>()
    sender.memberNumber
        ?.takeIf { it != ownerMemberNumber }
        ?.let { memberNumber ->
            profiles[memberNumber] = BcContactProfile(
                memberNumber = memberNumber,
                name = sender.displayName.ifBlank { "#$memberNumber" },
                firstSeenAtMillis = now,
                lastSeenAtMillis = now,
            )
        }
    listOfNotNull(targetMemberNumber, (address as? BcMessageAddress.Direct)?.memberNumber)
        .filterNot { it == ownerMemberNumber }
        .forEach { memberNumber ->
            profiles.putIfAbsent(
                memberNumber,
                BcContactProfile(
                    memberNumber = memberNumber,
                    name = "#$memberNumber",
                    firstSeenAtMillis = now,
                    lastSeenAtMillis = now,
                )
            )
        }
    return profiles.values.toList()
}

private fun membersToJson(members: List<BcMember>): String {
    val array = JSONArray()
    members.sortedBy { it.memberNumber }.forEach { member ->
        array.put(
            JSONObject()
                .put("memberNumber", member.memberNumber)
                .put("name", member.name)
                .put("isSelf", member.isSelf)
                .put("statusText", member.statusText)
                .put("role", member.role?.name)
        )
    }
    return array.toString()
}

private fun MessageEntity.rawMessageOrNull(): BcRawMessage? {
    val sourceEvent = rawSourceEvent ?: return null
    return BcRawMessage(
        sourceEvent = sourceEvent,
        wireType = rawWireType,
        beepType = rawBeepType,
        payloadJson = rawPayloadJson,
        dictionaryJson = rawDictionaryJson,
        roomName = rawRoomName,
    )
}

private fun BcMessageAddress.typeName(): String = when (this) {
    BcMessageAddress.CurrentRoom -> "CurrentRoom"
    is BcMessageAddress.Direct -> "Direct"
    is BcMessageAddress.Protocol -> "Protocol"
    BcMessageAddress.System -> "System"
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(raw: String?, default: T): T =
    runCatching { enumValueOf<T>(raw.orEmpty()) }.getOrDefault(default)
