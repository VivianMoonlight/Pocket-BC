package bot.inker.bc.razor.state

import bot.inker.bc.razor.protocol.room.*
import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import com.google.gson.JsonElement

data class RoomState(
    val name: String,
    val description: String,
    val admin: List<Int>,
    val ban: List<Int>,
    val whitelist: List<Int>,
    val background: String,
    val limit: Int,
    val locked: Boolean,
    val game: ChatRoomGame,
    val visibility: List<ChatRoomAccessRole>,
    val access: List<ChatRoomAccessRole>,
    val blockCategory: List<ChatRoomBlockCategory>,
    val language: ChatRoomLanguage,
    val space: ChatRoomSpace,
    val mapData: JsonElement?,
    val custom: ChatRoomCustomData?,
    val characters: List<CharacterState> = emptyList(),
) {
    companion object {
        fun fromSyncMessage(msg: RoomSyncMessage, characters: List<CharacterState> = emptyList()): RoomState {
            return RoomState(
                name = msg.name,
                description = msg.description ?: "",
                admin = msg.admin ?: emptyList(),
                ban = msg.ban ?: emptyList(),
                whitelist = msg.whitelist ?: emptyList(),
                background = msg.background ?: "MainHall",
                limit = msg.limit ?: 10,
                locked = msg.locked ?: false,
                game = ChatRoomGame.fromWireValue(msg.game ?: ""),
                visibility = msg.visibility?.mapNotNull { ChatRoomAccessRole.fromWireValue(it) } ?: emptyList(),
                access = msg.access?.mapNotNull { ChatRoomAccessRole.fromWireValue(it) } ?: emptyList(),
                blockCategory = msg.blockCategory?.mapNotNull { ChatRoomBlockCategory.fromWireValue(it) }
                    ?: emptyList(),
                language = ChatRoomLanguage.fromWireValue(msg.language ?: ""),
                space = ChatRoomSpace.fromWireValue(msg.space ?: ""),
                mapData = msg.mapData,
                custom = msg.custom,
                characters = characters,
            )
        }
    }

    fun findCharacter(memberNumber: Int): CharacterState? {
        return characters.find { it.memberNumber == memberNumber }
    }

    fun withCharacterUpdated(memberNumber: Int, update: (CharacterState) -> CharacterState): RoomState {
        val index = characters.indexOfFirst { it.memberNumber == memberNumber }
        if (index == -1) return this
        return copy(characters = characters.toMutableList().also { it[index] = update(it[index]) })
    }

    fun withCharacterUpserted(character: CharacterState): RoomState {
        val index = characters.indexOfFirst { it.memberNumber == character.memberNumber }
        if (index == -1) return copy(characters = characters + character)
        return copy(characters = characters.toMutableList().also { it[index] = character })
    }

    fun withMemberJoinRelationshipHints(
        joinedMemberNumber: Int,
        whiteListedBy: Collection<Int>,
        blackListedBy: Collection<Int>,
        selfMemberNumber: Int?,
    ): RoomState {
        val whiteSources = whiteListedBy.toSet()
        val blackSources = blackListedBy.toSet()
        var changed = false
        val updatedCharacters = characters.map { character ->
            if (character.memberNumber == joinedMemberNumber || character.memberNumber == selfMemberNumber) {
                return@map character
            }

            val whiteList = if (character.memberNumber in whiteSources) {
                (character.whiteList + joinedMemberNumber).distinct().sorted()
            } else {
                character.whiteList
            }
            val blackList = if (character.memberNumber in blackSources) {
                (character.blackList + joinedMemberNumber).distinct().sorted()
            } else {
                character.blackList
            }
            if (whiteList == character.whiteList && blackList == character.blackList) {
                character
            } else {
                changed = true
                character.copy(whiteList = whiteList, blackList = blackList)
            }
        }
        return if (changed) copy(characters = updatedCharacters) else this
    }
}
