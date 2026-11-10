package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.common.ItemBundle
import bot.inker.bc.razor.protocol.sync.ArousalSyncMessage
import bot.inker.bc.razor.protocol.sync.ExpressionSyncMessage
import bot.inker.bc.razor.protocol.sync.ItemSyncMessage
import bot.inker.bc.razor.protocol.sync.MapDataSyncMessage
import bot.inker.bc.razor.protocol.sync.PoseSyncMessage
import bot.inker.bc.razor.protocol.sync.ReorderPlayersSyncMessage
import bot.inker.bc.razor.state.CharacterState
import bot.inker.bc.razor.state.RoomState
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

internal fun RoomState.applyExpressionSync(message: ExpressionSyncMessage): RoomState =
    withCharacterUpdated(message.memberNumber) { character ->
        val itemIndex = character.appearance.indexOfFirst { it.group == message.group }
        if (itemIndex < 0) return@withCharacterUpdated character
        val oldItem = character.appearance[itemIndex]
        val property = (oldItem.property?.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy()
            ?: JsonObject()).apply {
            add("Expression", message.name?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
        }
        character.copy(
            appearance = character.appearance.toMutableList().also { items ->
                items[itemIndex] = oldItem.copy(property = property)
            },
            rawData = null,
        )
    }

internal fun RoomState.applyPoseSync(message: PoseSyncMessage): RoomState =
    withCharacterUpdated(message.memberNumber) { character ->
        character.copy(activePose = message.pose.orEmpty().filterNotNull(), rawData = null)
    }

internal fun RoomState.applyArousalSync(message: ArousalSyncMessage): RoomState =
    withCharacterUpdated(message.memberNumber) { character ->
        character.copy(
            arousal = CharacterState.ArousalState(
                orgasmTimer = message.orgasmTimer,
                orgasmCount = message.orgasmCount,
                progress = message.progress,
                progressTimer = message.progressTimer,
            ),
            rawData = null,
        )
    }

internal fun RoomState.applyItemSync(message: ItemSyncMessage): RoomState =
    withCharacterUpdated(message.item.target) { character ->
        val item = message.item
        val itemName = item.name
        if (itemName == null) {
            character.copy(
                appearance = character.appearance.filter { it.group != item.group },
                rawData = null,
            )
        } else {
            val nextItem = ItemBundle(
                group = item.group,
                name = itemName,
                color = item.color,
                difficulty = item.difficulty,
                property = item.property,
                craft = item.craft,
            )
            val existingIndex = character.appearance.indexOfFirst { it.group == item.group }
            if (existingIndex >= 0) {
                character.copy(
                    appearance = character.appearance.toMutableList().also { items ->
                        items[existingIndex] = nextItem
                    },
                    rawData = null,
                )
            } else {
                character.copy(appearance = character.appearance + nextItem, rawData = null)
            }
        }
    }

internal fun RoomState.applyMapDataSync(message: MapDataSyncMessage): RoomState =
    withCharacterUpdated(message.memberNumber) { character ->
        character.copy(mapData = message.mapData, rawData = null)
    }

internal fun RoomState.applyReorderPlayers(message: ReorderPlayersSyncMessage): RoomState {
    val order = message.playerOrder.orEmpty().filterNotNull().withIndex().associate { (index, memberNumber) ->
        memberNumber to index
    }
    return copy(characters = characters.sortedBy { order[it.memberNumber] ?: Int.MAX_VALUE })
}
