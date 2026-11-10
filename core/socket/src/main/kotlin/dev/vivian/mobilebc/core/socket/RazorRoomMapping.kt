package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.room.ChatRoomAccessRole
import bot.inker.bc.razor.protocol.room.ChatRoomBlockCategory
import bot.inker.bc.razor.protocol.room.ChatRoomGame
import bot.inker.bc.razor.protocol.room.ChatRoomLanguage
import bot.inker.bc.razor.protocol.room.ChatRoomSearchResult
import bot.inker.bc.razor.protocol.room.ChatRoomSpace
import bot.inker.bc.razor.protocol.sync.RoomPropertiesSyncMessage
import bot.inker.bc.razor.state.CharacterState
import bot.inker.bc.razor.state.RoomState
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomFriend
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.RoomRole

internal fun ChatRoomSearchResult.toBcRoomSummary(): BcRoomSummary =
    BcRoomSummary(
        name = BcRoomName(name),
        description = description.orEmpty(),
        memberCount = memberCount ?: 0,
        memberLimit = memberLimit ?: 0,
        isLocked = locked ?: false,
        canJoin = canJoin,
        language = language,
        creatorName = creator,
        creatorMemberNumber = creatorMemberNumber,
        game = game?.takeIf { it.isNotBlank() },
        mapType = mapType.toBcRoomMapType(),
        friends = friends.orEmpty().filterNotNull().map {
            BcRoomFriend(
                memberNumber = it.memberNumber,
                name = it.memberName.orEmpty(),
                relationshipType = it.type,
            )
        },
    )

internal fun RoomState.toBcRoom(): BcRoom =
    BcRoom(
        name = BcRoomName(name),
        description = description,
        memberCount = characters.size,
        memberLimit = limit,
        isLocked = locked,
        language = language.wireValue.takeIf { it.isNotBlank() },
        mapType = mapData?.takeIf { it.isJsonObject }?.asJsonObject?.stringOrNull("Type").toBcRoomMapType(),
        blockCategories = blockCategory.mapTo(linkedSetOf()) { it.wireValue },
    )

internal fun CharacterState.toBcMember(isSelf: Boolean, isAdmin: Boolean): BcMember =
    BcMember(
        memberNumber = memberNumber,
        name = nickname?.takeIf { it.isNotBlank() } ?: name,
        isSelf = isSelf,
        statusText = title,
        role = if (isAdmin) RoomRole.Admin else RoomRole.Member,
    )

internal fun RoomState.withProperties(message: RoomPropertiesSyncMessage): RoomState =
    copy(
        name = message.name ?: name,
        description = message.description ?: description,
        admin = message.admin ?: admin,
        ban = message.ban ?: ban,
        whitelist = message.whitelist ?: whitelist,
        background = message.background ?: background,
        limit = message.limit ?: limit,
        locked = message.locked ?: locked,
        game = message.game?.let(ChatRoomGame::fromWireValue) ?: game,
        visibility = message.visibility?.map(ChatRoomAccessRole::fromWireValue) ?: visibility,
        access = message.access?.map(ChatRoomAccessRole::fromWireValue) ?: access,
        blockCategory = message.blockCategory?.mapNotNull(ChatRoomBlockCategory::fromWireValue) ?: blockCategory,
        language = message.language?.let(ChatRoomLanguage::fromWireValue) ?: language,
        space = message.space?.let(ChatRoomSpace::fromWireValue) ?: space,
        mapData = message.mapData ?: mapData,
        custom = message.custom ?: custom,
    )

private fun String?.toBcRoomMapType(): BcRoomMapType =
    when (this) {
        BcRoomMapType.ChatRoom.wireValue -> BcRoomMapType.ChatRoom
        BcRoomMapType.Hybrid.wireValue -> BcRoomMapType.Hybrid
        BcRoomMapType.Map.wireValue -> BcRoomMapType.Map
        null,
        "",
        -> BcRoomMapType.ChatRoom
        else -> BcRoomMapType.Unknown
    }

private fun com.google.gson.JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString
