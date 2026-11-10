package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.common.ItemBundle
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterItem

internal fun List<BcCharacterItem>.toAppearanceItemBundles(): List<ItemBundle> = map { item ->
    ItemBundle(
        group = item.group,
        name = item.name,
        color = item.colorJson?.let(JsonParser::parseString),
        difficulty = item.difficulty,
        property = item.propertyJson?.let(JsonParser::parseString),
        craft = item.craftJson?.let(JsonParser::parseString),
    )
}

internal fun buildSelfAppearanceUpdatePayload(
    appearance: List<ItemBundle>,
    activePose: List<String>,
    gson: Gson,
): JsonObject = JsonObject().apply {
    addProperty("ID", 0)
    add("ActivePose", gson.toJsonTree(activePose))
    add("Appearance", gson.toJsonTree(appearance))
}
