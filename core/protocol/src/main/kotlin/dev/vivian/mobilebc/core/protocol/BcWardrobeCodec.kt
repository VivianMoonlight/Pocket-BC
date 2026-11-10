package dev.vivian.mobilebc.core.protocol

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dev.vivian.mobilebc.core.model.BcWardrobeEncodingRejectionReason
import dev.vivian.mobilebc.core.model.BcWardrobeEncodingResult
import dev.vivian.mobilebc.core.model.BcWardrobeInventoryItem
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobePrivateInputs
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSlot
import dev.vivian.mobilebc.core.model.BcWardrobeSlotCount
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import java.io.StringReader

object BcWardrobeCodec {
    fun decodeState(
        memberNumber: Int,
        selfRuleLogSessionId: String,
        revision: Long,
        rawWardrobeJson: String?,
        rawNamesJson: String?,
        fallbackName: String? = null,
        privateInputs: BcWardrobePrivateInputs = BcWardrobePrivateInputs(),
    ): BcWardrobeState {
        val wardrobe = decodeWardrobe(rawWardrobeJson)
        val names = decodeArray(rawNamesJson)
        return BcWardrobeState(
            memberNumber = memberNumber,
            selfRuleLogSessionId = selfRuleLogSessionId,
            revision = revision,
            rawWardrobeJson = rawWardrobeJson,
            rawNamesJson = rawNamesJson,
            slots = List(BcWardrobeSlotCount) { index ->
                val rawSlot = wardrobe?.slots?.getOrNull(index)
                val items = if (wardrobe == null) null else decodeSlot(rawSlot, wardrobe.compact)
                BcWardrobeSlot(
                    index = index,
                    name = if (names == null) null else names.getOrNull(index)?.stringValue() ?: fallbackName,
                    status = when {
                        items == null -> BcWardrobeSlotStatus.Unsupported
                        items.isEmpty() -> BcWardrobeSlotStatus.Empty
                        else -> BcWardrobeSlotStatus.Saved
                    },
                    appearance = items,
                    rawSlotJson = rawSlot?.toString(),
                )
            },
            wardrobeAvailable = wardrobe != null,
            namesAvailable = names != null,
            privateInputs = privateInputs,
        )
    }

    fun decodePrivateInputs(
        gameJson: String?,
        inventoryJson: String?,
        inventoryData: String?,
        privateCharacterJson: String? = null,
        owner: String? = null,
    ): BcWardrobePrivateInputs {
        val game = parseNullable(gameJson)
        val privateCharacters = parseNullable(privateCharacterJson)
        val inventory = if (inventoryData.isNullOrEmpty()) decodeInventory(inventoryJson) else emptyList()
        val inventoryIds = inventoryData?.takeIf { it.isNotEmpty() }?.let(::decodeInventoryIds)
        return BcWardrobePrivateInputs(
            gameJson = gameJson,
            inventoryJson = inventoryJson,
            inventoryData = inventoryData,
            gameAvailable = game != null && (game.isJsonNull || game.isJsonObject),
            inventoryAvailable = inventory != null,
            inventoryItems = inventory,
            inventoryIds = inventoryIds,
            privateCharacterJson = privateCharacterJson,
            privateCharacterAvailable = privateCharacters != null && (privateCharacters.isJsonNull || privateCharacters.isJsonArray),
            owner = owner,
        )
    }

    fun prepareSave(
        state: BcWardrobeState,
        request: BcWardrobeSaveRequest,
        appearance: List<BcWardrobeItem>,
    ): BcWardrobeEncodingResult {
        if (request.slotIndex !in 0 until BcWardrobeSlotCount) return rejected(BcWardrobeEncodingRejectionReason.InvalidSlot)
        if (request.expectedRevision != state.revision) return rejected(BcWardrobeEncodingRejectionReason.RevisionChanged)
        if (!state.wardrobeAvailable) return rejected(BcWardrobeEncodingRejectionReason.WardrobeUnavailable)
        val current = decodeWardrobe(state.rawWardrobeJson)
            ?: return rejected(BcWardrobeEncodingRejectionReason.WardrobeUnavailable)
        val replacement = JsonArray()
        for (item in appearance) {
            replacement.add(encodeItem(item) ?: return rejected(BcWardrobeEncodingRejectionReason.UnsafeItemData))
        }
        val compact = JsonArray()
        for (index in 0 until maxOf(BcWardrobeSlotCount, current.slots.size())) {
            if (index == request.slotIndex) {
                compact.add(replacement)
            } else {
                val raw = current.slots.getOrNull(index)
                val slot = when {
                    raw == null -> JsonArray()
                    current.compact -> raw.deepCopy()
                    raw.isJsonNull -> JsonArray()
                    else -> legacySlotToCompact(raw)
                        ?: return rejected(BcWardrobeEncodingRejectionReason.UnsupportedWardrobeData)
                }
                compact.add(slot)
            }
        }
        return BcWardrobeEncodingResult.Encoded(JsonPrimitive(LzStringUtf16Codec.compressToUtf16(compact.toString())).toString())
    }

    fun prepareRename(
        state: BcWardrobeState,
        request: BcWardrobeRenameRequest,
        defaultName: String,
    ): BcWardrobeEncodingResult {
        if (request.slotIndex !in 0 until BcWardrobeSlotCount) return rejected(BcWardrobeEncodingRejectionReason.InvalidSlot)
        if (request.expectedRevision != state.revision) return rejected(BcWardrobeEncodingRejectionReason.RevisionChanged)
        if (request.name.length > 20 || request.name.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != ' ' }) {
            return rejected(BcWardrobeEncodingRejectionReason.InvalidName)
        }
        if (!state.namesAvailable) return rejected(BcWardrobeEncodingRejectionReason.NamesUnavailable)
        val names = decodeArray(state.rawNamesJson)?.deepCopy()
            ?: return rejected(BcWardrobeEncodingRejectionReason.NamesUnavailable)
        while (names.size() <= BcWardrobeSlotCount) names.add(defaultName)
        names.set(request.slotIndex, JsonPrimitive(request.name))
        return BcWardrobeEncodingResult.Encoded(names.toString())
    }

    private data class DecodedWardrobe(val slots: JsonArray, val compact: Boolean)

    private fun decodeWardrobe(rawJson: String?): DecodedWardrobe? {
        val raw = parseNullable(rawJson) ?: return null
        return when {
            raw.isJsonNull -> DecodedWardrobe(JsonArray(), compact = false)
            raw.isJsonArray -> DecodedWardrobe(raw.asJsonArray, compact = false)
            raw.stringValue() != null -> {
                val compressed = raw.asString
                val text = runCatching { LzStringUtf16Codec.decompressFromUtf16(compressed) }.getOrNull() ?: return null
                val slots = parseNullable(text)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
                DecodedWardrobe(slots, compact = true)
            }
            else -> null
        }
    }

    private fun decodeSlot(raw: JsonElement?, compact: Boolean): List<BcWardrobeItem>? {
        if (raw == null) return emptyList()
        if (raw.isJsonNull) return if (compact) null else emptyList()
        if (!raw.isJsonArray) return null
        return raw.asJsonArray.map { item ->
            val values = if (compact) {
                if (!item.isJsonArray || item.asJsonArray.size() > 4) return null
                item.asJsonArray
            } else {
                if (!item.isJsonObject || item.asJsonObject.keySet().any { it !in LEGACY_BUNDLE_FIELDS }) return null
                bundleToCompact(item.asJsonObject)
            }
            val name = values.getOrNull(0)?.stringValue() ?: return null
            val group = values.getOrNull(1)?.stringValue() ?: return null
            val color = values.getOrNull(2)
            val property = values.getOrNull(3)
            if (!validColor(color) || !validProperty(property)) return null
            BcWardrobeItem(
                group = group,
                name = name,
                colorJson = color?.takeUnless { it.isJsonNull }?.toString(),
                propertyJson = property?.takeUnless { it.isJsonNull }?.toString(),
            )
        }
    }

    private fun legacySlotToCompact(raw: JsonElement): JsonArray? {
        if (!raw.isJsonArray) return null
        val result = JsonArray()
        for (item in raw.asJsonArray) {
            if (!item.isJsonObject || item.asJsonObject.keySet().any { it !in LEGACY_BUNDLE_FIELDS }) return null
            result.add(bundleToCompact(item.asJsonObject))
        }
        return result
    }

    private fun bundleToCompact(bundle: JsonObject): JsonArray = JsonArray().apply {
        for (field in listOf("Name", "Group", "Color", "Property")) add(bundle.get(field)?.deepCopy() ?: JsonNull.INSTANCE)
    }

    private fun encodeItem(item: BcWardrobeItem): JsonArray? {
        if (item.group.isEmpty()) return null
        val color = parseNullable(item.colorJson) ?: return null
        val property = parseNullable(item.propertyJson) ?: return null
        if (!validColor(color) || !validProperty(property)) return null
        val savedProperty = property.takeIf { it.isJsonObject }?.asJsonObject?.deepCopy()?.apply { remove("Expression") }
        return JsonArray().apply {
            add(item.name)
            add(item.group)
            add(color)
            add(savedProperty?.takeIf { it.size() > 0 } ?: JsonNull.INSTANCE)
        }
    }

    private fun decodeInventory(rawJson: String?): List<BcWardrobeInventoryItem>? {
        val raw = parseNullable(rawJson) ?: return null
        return when {
            raw.isJsonNull -> emptyList()
            raw.isJsonArray -> raw.asJsonArray.map { item ->
                if (!item.isJsonObject) return null
                val group = item.asJsonObject.get("Group")?.stringValue() ?: return null
                val name = item.asJsonObject.get("Name")?.stringValue() ?: return null
                BcWardrobeInventoryItem(group, name)
            }
            raw.isJsonObject -> buildList {
                for ((group, names) in raw.asJsonObject.entrySet()) {
                    if (names.isJsonNull) continue
                    if (!names.isJsonArray) return null
                    for (name in names.asJsonArray) add(BcWardrobeInventoryItem(group, name.stringValue() ?: return null))
                }
            }
            raw.stringValue() != null -> {
                val text = runCatching { LzStringUtf16Codec.decompressFromUtf16(raw.asString) }.getOrNull() ?: return null
                val items = parseNullable(text)?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
                items.map { item ->
                    if (!item.isJsonArray) return null
                    val name = item.asJsonArray.getOrNull(0)?.stringValue() ?: return null
                    val group = item.asJsonArray.getOrNull(1)?.stringValue() ?: return null
                    BcWardrobeInventoryItem(group, name)
                }
            }
            else -> null
        }
    }

    private fun decodeInventoryIds(data: String): List<Int> = buildList {
        var position = 0
        var lastId = -1
        while (position < data.length) {
            var id = data[position].code
            if (id == 58) {
                id = lastId + 1
                if (position + 1 < data.length && id + 1 < data[position + 1].code) position--
            }
            add(id)
            lastId = id
            position++
        }
    }

    private fun validColor(value: JsonElement?): Boolean = value == null || value.isJsonNull ||
        value.stringValue() != null || (value.isJsonArray && value.asJsonArray.all { it.stringValue() != null })

    private fun validProperty(value: JsonElement?): Boolean = value == null || value.isJsonNull || value.isJsonObject

    private fun decodeArray(rawJson: String?): JsonArray? = parseNullable(rawJson)?.let {
        when {
            it.isJsonNull -> JsonArray()
            it.isJsonArray -> it.asJsonArray
            else -> null
        }
    }

    private fun parseNullable(rawJson: String?): JsonElement? = if (rawJson == null) JsonNull.INSTANCE else {
        runCatching {
            JsonReader(StringReader(rawJson)).use { reader ->
                reader.strictness = Strictness.STRICT
                check(reader.peek() != JsonToken.END_DOCUMENT)
                JsonParser.parseReader(reader).also { check(reader.peek() == JsonToken.END_DOCUMENT) }
            }
        }.getOrNull()
    }

    private fun JsonElement.stringValue(): String? = takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index < size()) get(index) else null

    private fun rejected(reason: BcWardrobeEncodingRejectionReason) = BcWardrobeEncodingResult.Rejected(reason)

    private val LEGACY_BUNDLE_FIELDS = setOf("Name", "Group", "Color", "Property")
}
