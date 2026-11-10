package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.chat.ChatMessageType
import bot.inker.bc.razor.protocol.chat.DictionaryEntry
import bot.inker.bc.razor.protocol.chat.OutgoingChatMessage
import bot.inker.bc.razor.protocol.common.ItemBundle
import bot.inker.bc.razor.state.RoomState
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcEchoCompatibilityCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawOrderCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawRelation
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import java.util.UUID

internal const val BCX_PLAYER_CUSTOM_DIALOG = "BCX_PLAYER_CUSTOM_DIALOG"
internal const val ECHO_INFO_TAG = "ECHO_INFO2"
internal const val ECHO_DRAW_ORDER_TAG = "Luzi_XCharacterDrawState"

internal fun buildActivityChatPayload(
    command: BcActivityCommand,
    sourceMemberNumber: Int,
    msgId: String? = null,
): JsonObject {
    val group = command.targetGroup.trim()
    val activityName = command.activityName.trim()
    val actualMsgId = msgId?.trim()?.takeIf { it.isNotEmpty() }
        ?: command.clientMsgId?.trim()?.takeIf { it.isNotEmpty() }
        ?: UUID.randomUUID().toString()
    val targetIsSelf = command.targetMemberNumber == sourceMemberNumber
    val contentPrefix = if (targetIsSelf) "ChatSelf" else "ChatOther"
    val content = "$contentPrefix-$group-$activityName"
    return JsonObject().apply {
        addProperty("Content", content)
        addProperty("Type", "Activity")
        add(
            "Dictionary",
            jsonArrayOf(
                JsonObject().apply { addProperty("SourceCharacter", sourceMemberNumber) },
                JsonObject().apply { addProperty("TargetCharacter", command.targetMemberNumber) },
                JsonObject().apply { addProperty("FocusGroupName", group) },
                command.activityAssetName?.trim()?.takeIf { it.isNotEmpty() }?.let { assetName ->
                    JsonObject().apply {
                        addProperty("Tag", "ActivityAsset")
                        addProperty("AssetName", assetName)
                        command.activityAssetGroup?.trim()?.takeIf { it.isNotEmpty() }?.let { assetGroup ->
                            addProperty("GroupName", assetGroup)
                        }
                    }
                },
                JsonObject().apply { addProperty("ActivityName", activityName) },
                command.fallbackText?.trim()?.takeIf { it.isNotEmpty() }?.let { fallbackText ->
                    JsonObject().apply {
                        addProperty("Tag", "MISSING TEXT IN \"ActivityDictionary.csv\": $content")
                        addProperty("Text", fallbackText)
                    }
                },
                JsonObject().apply {
                    addProperty("Tag", "MsgId")
                    addProperty("MsgId", actualMsgId)
                },
            )
        )
    }
}

internal fun buildCharacterItemUpdatePayload(command: BcCharacterItemUpdateCommand): JsonObject =
    JsonObject().apply {
        addProperty("Target", command.targetMemberNumber)
        addProperty("Group", command.group.trim())
        command.name?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("Name", it) }
        add("Color", command.colorJson.parseJsonElementOrDefault(JsonPrimitive("Default")))
        addProperty("Difficulty", command.difficulty ?: 0)
        command.propertyJson.parseJsonElementOrNull()?.let { add("Property", it) }
        command.craftJson.parseJsonElementOrNull()?.let { add("Craft", it) }
    }

internal fun buildPoseUpdatePayload(command: BcPoseUpdateCommand): JsonObject =
    JsonObject().apply {
        add("Pose", com.google.gson.JsonArray().apply {
            command.poses.map(String::trim).filter(String::isNotEmpty).distinct().forEach(::add)
        })
    }

internal fun buildExpressionUpdatePayload(command: BcExpressionUpdateCommand): JsonObject =
    JsonObject().apply {
        addProperty("Group", command.group.trim())
        command.name?.trim()?.takeIf(String::isNotEmpty)?.let { addProperty("Name", it) }
        command.appearance?.let { appearance ->
            add("Appearance", com.google.gson.JsonArray().apply {
                appearance.forEach { item ->
                    add(JsonObject().apply {
                        addProperty("Group", item.group)
                        addProperty("Name", item.name)
                        item.colorJson.parseJsonElementOrNull()?.let { add("Color", it) }
                        item.difficulty?.let { addProperty("Difficulty", it) }
                        item.propertyJson.parseJsonElementOrNull()?.let { add("Property", it) }
                        item.craftJson.parseJsonElementOrNull()?.let { add("Craft", it) }
                    })
                }
            })
        }
    }

internal fun buildEchoCompatibilityPayload(command: BcEchoCompatibilityCommand): JsonObject =
    JsonObject().apply {
        addProperty("Content", ECHO_INFO_TAG)
        addProperty("Type", "Hidden")
        command.targetMemberNumber?.let { addProperty("Target", it) }
        add(
            "Dictionary",
            jsonArrayOf(
                JsonObject().apply {
                    addProperty("Type", ECHO_INFO_TAG)
                    add(
                        "Content",
                        JsonObject().apply {
                            add(
                                command.modName.trim(),
                                JsonObject().apply {
                                    addProperty("version", command.version.trim())
                                    addProperty("beta", command.beta)
                                },
                            )
                        },
                    )
                },
            ),
        )
    }

internal fun buildEchoDrawOrderPayload(command: BcEchoDrawOrderCommand): JsonObject =
    JsonObject().apply {
        addProperty("Content", ECHO_DRAW_ORDER_TAG)
        addProperty("Type", "Hidden")
        add(
            "Dictionary",
            jsonArrayOf(
                JsonObject().apply {
                    val relationKey = when (command.relation) {
                        BcEchoDrawRelation.PreviousCharacter -> "prevCharacter"
                        BcEchoDrawRelation.NextCharacter -> "nextCharacter"
                    }
                    addProperty(relationKey, command.otherMemberNumber)
                    add(
                        "associatedAsset",
                        JsonObject().apply {
                            addProperty("group", command.associatedAssetGroup.trim())
                            addProperty("asset", command.associatedAssetName.trim())
                        },
                    )
                    addProperty("leash", command.leash.wireValue)
                },
            ),
        )
    }

internal fun buildItemActionChatPayload(
    command: BcCharacterItemUpdateCommand,
    sourceMemberNumber: Int,
    msgId: String = UUID.randomUUID().toString(),
): JsonObject {
    val action = command.publishAction ?: error("publishAction is required.")
    val group = command.group.trim()
    return JsonObject().apply {
        addProperty("Content", action.content)
        addProperty("Type", "Action")
        add(
            "Dictionary",
            jsonArrayOf(
                JsonObject().apply { addProperty("SourceCharacter", sourceMemberNumber) },
                JsonObject().apply { addProperty("TargetCharacter", command.targetMemberNumber) },
                JsonObject().apply { addProperty("DestinationCharacter", command.targetMemberNumber) },
                JsonObject().apply { addProperty("FocusGroupName", group) },
                action.destinationCharacterName?.let { name ->
                    JsonObject().apply {
                        addProperty("Tag", "DestinationCharacterName")
                        addProperty("MemberNumber", command.targetMemberNumber)
                        addProperty("Text", name)
                    }
                },
                action.lockName?.let { name ->
                    JsonObject().apply {
                        addProperty("Tag", "LockName")
                        addProperty("GroupName", "ItemMisc")
                        addProperty("AssetName", name)
                    }
                },
                action.timerTime?.let { time ->
                    JsonObject().apply { addProperty("Tag", "TimerTime"); addProperty("Text", time) }
                },
                action.previousAssetName?.trim()?.takeIf { it.isNotEmpty() }?.let { assetName ->
                    JsonObject().apply {
                        addProperty("Tag", "PrevAsset")
                        addProperty("GroupName", group)
                        addProperty("AssetName", assetName)
                        action.previousCraftName?.takeIf(String::isNotBlank)?.let { addProperty("CraftName", it) }
                    }
                },
                action.nextAssetName?.trim()?.takeIf { it.isNotEmpty() }?.let { assetName ->
                    JsonObject().apply {
                        addProperty("Tag", "NextAsset")
                        addProperty("GroupName", action.nextAssetGroup ?: group)
                        addProperty("AssetName", assetName)
                        action.nextCraftName?.takeIf(String::isNotBlank)?.let { addProperty("CraftName", it) }
                    }
                },
                JsonObject().apply {
                    addProperty("Tag", "MsgId")
                    addProperty("MsgId", msgId)
                },
            )
        )
    }
}

internal fun RoomState.accountAppearanceFor(memberNumber: Int?): List<ItemBundle>? =
    memberNumber?.let { number ->
        characters.firstOrNull { it.memberNumber == number }?.appearance?.map { item ->
            val property = item.property?.takeIf { it.isJsonObject }?.asJsonObject
            if (property?.has("Expression") == true) {
                item.copy(property = property.deepCopy().apply { remove("Expression") })
            } else item
        }
    }

internal fun RoomState.hasDifferentAppearanceFor(memberNumber: Int?, next: RoomState): Boolean {
    val number = memberNumber ?: return false
    return accountAppearanceFor(number) != next.accountAppearanceFor(number)
}

internal fun buildRoomMessage(
    text: String,
    type: ChatMessageType,
    target: Int?,
    msgId: String = UUID.randomUUID().toString(),
    replyToId: String? = null,
): OutgoingChatMessage =
    OutgoingChatMessage(
        content = text,
        type = type,
        target = target,
        dictionary = buildList {
            add(DictionaryEntry.msgId(msgId))
            replyToId?.let { replyId ->
                require(replyId.isNotBlank()) { "Reply message ID is missing." }
                require(type == ChatMessageType.CHAT || type == ChatMessageType.EMOTE || type == ChatMessageType.WHISPER) {
                    "This message type does not support replies."
                }
                add(DictionaryEntry.replyId(replyId))
            }
        },
    )

internal fun buildCustomActionMessage(
    text: String,
    msgId: String = UUID.randomUUID().toString(),
): OutgoingChatMessage =
    OutgoingChatMessage(
        content = BCX_PLAYER_CUSTOM_DIALOG,
        type = ChatMessageType.ACTION,
        dictionary = listOf(
            DictionaryEntry.text("""MISSING TEXT IN "Interface.csv": $BCX_PLAYER_CUSTOM_DIALOG""", text),
            DictionaryEntry.msgId(msgId),
        ),
    )

private fun jsonArrayOf(vararg elements: JsonElement?): com.google.gson.JsonArray =
    com.google.gson.JsonArray().apply {
        elements.filterNotNull().forEach(::add)
    }

private fun String?.parseJsonElementOrNull(): JsonElement? =
    this?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { raw ->
            runCatching { JsonParser.parseString(raw) }
                .getOrElse { throw IllegalArgumentException("Invalid JSON value: $raw", it) }
        }
        ?.takeUnless { it.isJsonNull }

private fun String?.parseJsonElementOrDefault(defaultValue: JsonElement): JsonElement =
    parseJsonElementOrNull() ?: defaultValue
