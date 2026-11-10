package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.Difficulty
import bot.inker.bc.razor.protocol.account.Lovership
import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.Reputation
import bot.inker.bc.razor.protocol.common.ItemBundle
import bot.inker.bc.razor.state.CharacterState
import com.google.gson.Gson
import com.google.gson.JsonElement
import dev.vivian.mobilebc.core.model.BcCharacterArousal
import dev.vivian.mobilebc.core.model.BcCharacterBodyState
import dev.vivian.mobilebc.core.model.BcCharacterDifficulty
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterLovership
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcCharacterPoseState
import dev.vivian.mobilebc.core.model.BcCharacterReputation
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot

internal fun parseCharacterFromJson(json: JsonElement?, gson: Gson): CharacterState? {
    if (json == null || !json.isJsonObject) return null
    val obj = json.asJsonObject
    val memberNumber = obj.get("MemberNumber")?.takeUnless { it.isJsonNull }?.asInt ?: return null
    val name = obj.stringOrNull("Name") ?: return null
    val id = obj.stringOrNull("ID") ?: memberNumber.toString()

    return CharacterState(
        id = id,
        memberNumber = memberNumber,
        name = name,
        accountName = obj.stringOrNull("AccountName"),
        type = obj.stringOrNull("Type") ?: "online",
        assetFamily = obj.stringOrNull("AssetFamily") ?: "Female3DCG",
        nickname = obj.stringOrNull("Nickname"),
        appearance = obj.arrayOrEmpty("Appearance").mapNotNull { element ->
            runCatching { gson.fromJson(element, ItemBundle::class.java) }.getOrNull()
                ?.takeIf { !it.group.isNullOrBlank() && !it.name.isNullOrBlank() }
        },
        activePose = obj.arrayOrEmpty("ActivePose").mapNotNull { element ->
            runCatching { element.asString }.getOrNull()
        },
        owner = obj.stringOrNull("Owner"),
        lover = obj.stringOrNull("Lover"),
        ownership = obj.objectOrNull("Ownership")?.let { runCatching { gson.fromJson(it, Ownership::class.java) }.getOrNull() },
        lovership = obj.arrayOrEmpty("Lovership").mapNotNull { element ->
            runCatching { gson.fromJson(element, Lovership::class.java) }.getOrNull()
        },
        reputation = obj.arrayOrEmpty("Reputation").mapNotNull { element ->
            runCatching { gson.fromJson(element, Reputation::class.java) }.getOrNull()
                ?.takeIf { !it.type.isNullOrBlank() }
        },
        whiteList = obj.arrayOrEmpty("WhiteList").mapNotNull { element ->
            runCatching { element.asInt }.getOrNull()
        },
        blackList = obj.arrayOrEmpty("BlackList").mapNotNull { element ->
            runCatching { element.asInt }.getOrNull()
        },
        labelColor = obj.stringOrNull("LabelColor"),
        description = obj.stringOrNull("Description"),
        title = obj.stringOrNull("Title"),
        creation = obj.get("Creation")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
        difficulty = obj.objectOrNull("Difficulty")?.let { runCatching { gson.fromJson(it, Difficulty::class.java) }.getOrNull() },
        allowedInteractions = obj.get("AllowedInteractions")?.takeIf { !it.isJsonNull }?.asInt
            ?: obj.get("ItemPermission")?.takeIf { !it.isJsonNull }?.asInt,
        arousalSettings = obj.get("ArousalSettings")?.takeIf { !it.isJsonNull },
        onlineSharedSettings = obj.get("OnlineSharedSettings")?.takeIf { !it.isJsonNull },
        game = obj.get("Game")?.takeIf { !it.isJsonNull },
        crafting = obj.get("Crafting")?.takeIf { !it.isJsonNull },
        inventoryData = obj.get("InventoryData")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString,
        blockItems = obj.itemPermissions("Block", "BlockItems"),
        limitedItems = obj.itemPermissions("Limited", "LimitedItems"),
        favoriteItems = obj.itemPermissions("Favorite", "FavoriteItems"),
        hiddenItems = obj.get("HiddenItems")?.takeIf { !it.isJsonNull },
        mapData = obj.get("MapData")?.takeIf { !it.isJsonNull },
        rawData = json,
    )
}

/** R133 replaces the three legacy lists with per-asset and per-type PermissionItems. */
private fun com.google.gson.JsonObject.itemPermissions(permission: String, legacyField: String): JsonElement? {
    val modern = get("PermissionItems")?.takeIf { it.isJsonObject }?.asJsonObject
        ?: return get(legacyField)?.takeUnless { it.isJsonNull }
    return com.google.gson.JsonArray().apply {
        modern.entrySet().forEach { (key, value) ->
            val parts = key.split('/', limit = 2)
            if (parts.size != 2 || parts.any(String::isBlank) || !value.isJsonObject) return@forEach
            fun append(type: String? = null) {
                add(com.google.gson.JsonObject().apply {
                    addProperty("Group", parts[0])
                    addProperty("Name", parts[1])
                    type?.let { addProperty("Type", it) }
                })
            }
            val entry = value.asJsonObject
            if (entry.get("Permission")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString == permission) append()
            entry.get("TypePermissions")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (type, mode) ->
                if (type.isNotBlank() && mode.isJsonPrimitive && mode.asJsonPrimitive.isString && mode.asString == permission) append(type)
            }
        }
    }
}

internal fun CharacterState.toBcCharacterSnapshot(
    isSelf: Boolean,
    gson: Gson,
): BcCharacterSnapshot {
    val character = this
    val lianSettings = character.onlineSharedSettings
        ?.takeIf { it.isJsonObject }?.asJsonObject
        ?.objectOrNull("LCData")?.asJsonObject
        ?.objectOrNull("MessageSetting")?.asJsonObject
    fun lianString(key: String): String? = lianSettings?.get(key)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
    val appearance = character.appearance.map { item ->
        BcCharacterItem(
            group = item.group,
            name = item.name,
            colorJson = item.color.toJsonStringOrNull(gson),
            difficulty = item.difficulty,
            propertyJson = item.property.toJsonStringOrNull(gson),
            craftJson = item.craft.toJsonStringOrNull(gson),
        )
    }

    return BcCharacterSnapshot(
            id = character.id,
            memberNumber = character.memberNumber,
            name = character.name,
            displayName = character.nickname?.takeIf { it.isNotBlank() } ?: character.name,
            isSelf = isSelf,
            type = character.type,
            assetFamily = character.assetFamily,
            accountName = character.accountName,
            nickname = character.nickname,
            title = character.title,
            labelColor = character.labelColor,
            avatarUrl = lianString("Avatar"),
            signature = lianString("Signature"),
            description = character.description.decodeOnlineProfileDescription(),
            creation = character.creation,
            activePose = character.activePose,
            poseState = BcCharacterPoseState.fromActivePose(character.activePose),
            pronouns = character.appearance.firstOrNull { it.group == "Pronouns" }?.name ?: "SheHer",
            appearance = appearance,
            body = BcCharacterBodyState.fromAppearance(appearance),
            owner = character.owner,
            ownerName = character.parsedOwnerName(),
            ownerMemberNumber = character.ownership?.memberNumber,
            lover = character.lover,
            loverNames = character.parsedLoverNames(),
            ownership = character.ownership?.let { ownership ->
                BcCharacterOwnership(
                    name = ownership.name.orEmpty(),
                    memberNumber = ownership.memberNumber,
                    notes = ownership.notes,
                    stage = ownership.stage,
                    start = ownership.start,
                )
            },
            lovership = character.lovership.map { lovership ->
                BcCharacterLovership(
                    name = lovership.name.orEmpty(),
                    memberNumber = lovership.memberNumber,
                    stage = lovership.stage,
                    start = lovership.start,
                )
            },
            reputation = character.reputation.map { reputation ->
                BcCharacterReputation(
                    type = reputation.type,
                    value = reputation.value,
                )
            },
            difficulty = character.difficulty?.let { difficulty ->
                BcCharacterDifficulty(
                    level = difficulty.level,
                    lastChange = difficulty.lastChange,
                )
            },
            allowedInteractions = character.allowedInteractions,
            whiteList = character.whiteList,
            blackList = character.blackList,
            arousal = character.arousal?.let { arousal ->
                BcCharacterArousal(
                    orgasmTimer = arousal.orgasmTimer,
                    orgasmCount = arousal.orgasmCount,
                    progress = arousal.progress,
                    progressTimer = arousal.progressTimer,
                )
            },
            arousalSettingsJson = character.arousalSettings.toJsonStringOrNull(gson),
            onlineSharedSettingsJson = character.onlineSharedSettings.toJsonStringOrNull(gson),
            gameJson = character.game.toJsonStringOrNull(gson),
            craftingJson = character.crafting.toJsonStringOrNull(gson),
            inventoryData = character.inventoryData,
            blockItemsJson = character.blockItems.toJsonStringOrNull(gson),
            limitedItemsJson = character.limitedItems.toJsonStringOrNull(gson),
            favoriteItemsJson = character.favoriteItems.toJsonStringOrNull(gson),
            hiddenItemsJson = character.hiddenItems.toJsonStringOrNull(gson),
            characterMapDataJson = character.mapData.toJsonStringOrNull(gson),
            rawDataJson = character.rawData.toJsonStringOrNull(gson),
        )
}

private fun JsonElement?.toJsonStringOrNull(gson: Gson): String? =
    this?.takeUnless { it.isJsonNull }?.let(gson::toJson)

private fun CharacterState.parsedOwnerName(): String? =
    ownership?.name?.takeIf { it.isNotBlank() }
        ?: owner?.removeNpcPrefix()?.takeIf { it.isNotBlank() }

private fun CharacterState.parsedLoverNames(): List<String> {
    val onlineLovers = lovership.mapNotNull { it.name?.removeNpcPrefix()?.takeIf(String::isNotBlank) }
    if (onlineLovers.isNotEmpty()) return onlineLovers
    return lover?.removeNpcPrefix()?.takeIf { it.isNotBlank() }?.let(::listOf).orEmpty()
}

private fun String.removeNpcPrefix(): String = removePrefix("NPC-").trim()

private fun com.google.gson.JsonObject.stringOrNull(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

private fun com.google.gson.JsonObject.objectOrNull(name: String): JsonElement? =
    get(name)?.takeIf { it.isJsonObject }

private fun com.google.gson.JsonObject.arrayOrEmpty(name: String): List<JsonElement> =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
