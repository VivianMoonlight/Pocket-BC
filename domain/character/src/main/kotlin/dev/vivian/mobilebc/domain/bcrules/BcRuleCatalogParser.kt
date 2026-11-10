package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.model.BcCharacterPoseState
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson

/**
 * Text-level parser for the BC Female3DCG rule definitions.
 *
 * We intentionally do not execute the source JavaScript. This parser extracts the subset needed by
 * the permission engine: asset groups, assets, activity targets, and rule-ish fields such as
 * prerequisites, effects, blocks, AllowActivity, and AllowActivityOn. It is conservative: fields it
 * cannot parse are omitted rather than guessed.
 */
class BcRuleCatalogParser {
    fun parse(
        version: String,
        female3dJs: String,
        female3dExtendedJs: String? = null,
    ): BcRuleCatalog {
        val groups = linkedMapOf<String, BcGroupRule>()
        val assets = linkedMapOf<BcAssetKey, BcAssetRule>()
        val activities = linkedMapOf<String, BcActivityRule>()
        val poses = linkedMapOf<String, BcPoseRule>()
        val extendedItems = linkedMapOf<BcAssetKey, BcExtendedItemRule>()
        val assetProperties = linkedMapOf<BcAssetKey, String>()

        parsePoseFemale3DCG(female3dJs, poses)
        parseAssetFemale3DCG(female3dJs, groups, assets, assetProperties, poses)
        parseActivityFemale3DCG(female3dJs, activities)

        if (!female3dExtendedJs.isNullOrBlank()) {
            parseExtendedItems(female3dExtendedJs, extendedItems, assetProperties)
            mergeExtendedItemActivityHints(female3dExtendedJs, assets)
            mergeExtendedSpeechEffects(female3dExtendedJs, female3dJs.readEffectConstants(), assets)
        }

        return BcRuleCatalog(
            version = version.normalizedBcVersion(),
            groups = groups.toMap(),
            activities = activities.toMap(),
            assets = assets.toMap(),
            extendedItems = extendedItems.toMap(),
            poses = poses.toMap(),
        )
    }

    private fun parsePoseFemale3DCG(
        source: String,
        poses: MutableMap<String, BcPoseRule>,
    ) {
        val array = source.extractVariableArray("PoseFemale3DCG") ?: return
        array.splitTopLevelObjects().forEach { block ->
            val name = block.readStringProperty("Name") ?: return@forEach
            poses[name] = BcPoseRule(
                name = name,
                category = block.readStringProperty("Category"),
                allowMenu = block.readBooleanProperty("AllowMenu") ?: false,
                allowMenuTransient = block.readBooleanProperty("AllowMenuTransient") ?: false,
            )
        }
    }

    private fun parseActivityFemale3DCG(
        source: String,
        activities: MutableMap<String, BcActivityRule>,
    ) {
        val array = source.extractVariableArray("ActivityFemale3DCG") ?: return
        array.splitTopLevelObjects().forEach { block ->
            val name = block.readStringProperty("Name") ?: return@forEach
            val targetGroups = block.readStringSetProperty("Target")
            val selfTargetGroups = block.readStringSetProperty("TargetSelf")
            val prerequisites = block.readStringSetProperty("Prerequisite")
            activities[name] = BcActivityRule(
                name = name,
                targetGroups = targetGroups,
                selfTargetGroups = selfTargetGroups,
                prerequisites = prerequisites,
                makeSound = block.readBooleanProperty("MakeSound") ?: false,
                stimulationAction = block.readStringProperty("StimulationAction"),
                activityId = block.readIntPropertyOrNull("ActivityID"),
            )
        }
    }

    private fun parseAssetFemale3DCG(
        source: String,
        groups: MutableMap<String, BcGroupRule>,
        assets: MutableMap<BcAssetKey, BcAssetRule>,
        assetProperties: MutableMap<BcAssetKey, String>,
        poses: Map<String, BcPoseRule>,
    ) {
        val array = source.extractVariableArray("AssetFemale3DCG") ?: return
        val effectConstants = source.readEffectConstants()
        val groupBlocks = array.splitTopLevelObjects()
        val assetBlocks = buildMap {
            groupBlocks.forEach { groupBlock ->
                val group = groupBlock.readStringProperty("Group") ?: return@forEach
                groupBlock.readPropertyValue("Asset")?.splitAssetDefinitions()?.forEach { assetBlock ->
                    val name = assetBlock.readStringProperty("Name") ?: return@forEach
                    put(BcAssetKey(group, name), assetBlock)
                }
            }
        }.toMutableMap()
        val unsupportedCopies = linkedSetOf<BcAssetKey>()
        assetBlocks.keys.toList().forEach { key ->
            val resolved = resolveAssetEntries(key, assetBlocks)
            if (resolved == null) {
                assetBlocks.remove(key)
            } else {
                assetBlocks[key] = assignNativeBuyGroup(key, resolved, assetBlocks, unsupportedCopies).toJsonObject()
            }
        }
        groupBlocks.forEach { groupBlock ->
            val groupName = groupBlock.readStringProperty("Group") ?: return@forEach
            val groupEffect = groupBlock.readPropertyValue("Effect")
            val groupEffects = groupEffect.readStaticEffects(effectConstants)
            val groupUnsupported = linkedSetOf<String>()
            if (!groupBlock.hasVerifiedObjectShape()) groupUnsupported += "Definition"
            val assetDefinitions = groupBlock.readPropertyValue("Asset")?.readStaticArrayValues()
            if (assetDefinitions == null || assetDefinitions.any { !it.hasVerifiedObjectShape() && it.readStaticString() == null }) groupUnsupported += "Asset"
            val colorSchema = groupBlock.readWardrobeStrings("Color", listOf("Default"), groupUnsupported)
            if (colorSchema.isEmpty()) groupUnsupported += "Color"
            groups[groupName] = BcGroupRule(
                name = groupName,
                category = groupBlock.readWardrobeString("Category", "Appearance", groupUnsupported),
                clothing = groupBlock.readWardrobeBoolean("Clothing", false, groupUnsupported),
                allowNone = groupBlock.readWardrobeBoolean("AllowNone", true, groupUnsupported),
                isItem = groupName.startsWith("Item"),
                arousalZoneId = groupBlock.readIntPropertyOrNull("ArousalZoneID"),
                mirrorActivitiesFrom = groupBlock.readStringProperty("MirrorActivitiesFrom"),
                zones = groupBlock.readInteractionZones(),
                allowExpression = groupBlock.readNullableStringListProperty("AllowExpression"),
                expressionPrerequisites = groupBlock.readNullableStringListProperty("ExpressionPrerequisite"),
                unsupportedExpressionMetadata = groupBlock.readUnsupportedExpressionMetadata(),
                effects = groupEffects ?: emptySet(),
                unsupportedSpeechMetadata = if (groupEffects == null) setOf("Effect") else emptySet(),
                mirrorGroup = groupBlock.readWardrobeString("MirrorGroup", "", groupUnsupported).takeUnless { it.isNullOrEmpty() },
                defaultAssetName = assetBlocks.keys.firstOrNull { it.group == groupName }?.name,
                colorSchema = colorSchema,
                defaultColor = colorSchema.firstOrNull() ?: "Default",
                bodyCosplay = groupBlock.readWardrobeBoolean("BodyCosplay", false, groupUnsupported),
                underwear = groupBlock.readWardrobeBoolean("Underwear", false, groupUnsupported),
                allowCustomize = groupBlock.readWardrobeBoolean("AllowCustomize", true, groupUnsupported),
                allowColorize = groupBlock.readWardrobeBoolean("AllowColorize", true, groupUnsupported),
                isDefault = groupBlock.readWardrobeBoolean("Default", true, groupUnsupported),
                unsupportedWardrobeMetadata = groupUnsupported.toSet(),
            )

            val assetArray = groupBlock.readPropertyValue("Asset") ?: return@forEach
            assetArray.splitAssetDefinitions().forEach { assetBlock ->
                val assetName = assetBlock.readStringProperty("Name") ?: return@forEach
                val key = BcAssetKey(group = groupName, name = assetName)
                val speechEntries = assetBlocks[key]?.readRawPropertyEntries()
                val normalizedBlock = speechEntries?.toJsonObject() ?: assetBlock
                val group = groups.getValue(groupName)
                val assetUnsupported = linkedSetOf<String>()
                if (speechEntries == null) assetUnsupported += "CopyConfig"
                if (key in unsupportedCopies) assetUnsupported += "CopyConfig.BuyGroup"
                val value = normalizedBlock.readWardrobeNumber("Value", 0.0, assetUnsupported)
                val baseEntries = linkedMapOf<String, String>()
                listOf("Block", "Effect", "Hide", "HideItem", "AllowActivity", "Attribute", "CustomBlindBackground").forEach { property ->
                    val assetValue = if (property == "Effect") speechEntries?.get(property) else assetBlock.readPropertyValue(property)
                    val value = if (property in setOf("Block", "Effect", "Hide") && (assetValue == null || assetValue == "null")) {
                        groupBlock.readPropertyValue(property)
                    } else {
                        assetValue
                    }
                    value?.toJsonValueOrNull()?.let { baseEntries[property] = it }
                }
                assetProperties[key] = baseEntries.toJsonObject()
                val rawEffect = speechEntries?.get("Effect")
                val inheritedEffect = rawEffect == null || rawEffect.isNullRuleValue()
                val effects = if (inheritedEffect) groupEffects else rawEffect.readStaticEffects(effectConstants)
                val rawAllowEffect = speechEntries?.get("AllowEffect")
                val allowedEffects = rawAllowEffect.readStaticEffects(effectConstants)
                assets[key] = BcAssetRule(
                    group = groupName,
                    name = assetName,
                    effects = effects ?: (if (inheritedEffect) groupEffect else rawEffect).extractRuleNames(),
                    blocks = assetBlock.readStringSetProperty("Block") + assetBlock.readStringSetProperty("HideItem"),
                    expose = assetBlock.readStringSetProperty("Expose"),
                    allowActivity = assetBlock.readAnyStringSetProperty("AllowActivity"),
                    allowActivityOn = assetBlock.readAnyStringSetProperty("AllowActivityOn"),
                    prerequisites = assetBlock.readStringSetProperty("Prerequisite"),
                    allowActivePose = assetBlock.readStringSetProperty("AllowActivePose"),
                    setPose = assetBlock.readStringSetProperty("SetPose"),
                    ownerOnly = assetBlock.readBooleanProperty("OwnerOnly") ?: false,
                    selfUnlock = assetBlock.readBooleanProperty("SelfUnlock") ?: true,
                    exclusiveUnlock = assetBlock.readBooleanProperty("ExclusiveUnlock") ?: false,
                    isLock = normalizedBlock.readBooleanProperty("IsLock") ?: false,
                    isRestraint = group.category == "Item" && (normalizedBlock.readBooleanProperty("IsRestraint")
                        ?: groupBlock.readBooleanProperty("IsRestraint") ?: false),
                    allowLock = normalizedBlock.readBooleanProperty("AllowLock") ?: false,
                    removeTimerSeconds = normalizedBlock.readIntPropertyOrNull("RemoveTimer") ?: 0,
                    maxTimerSeconds = normalizedBlock.readIntPropertyOrNull("MaxTimer") ?: 0,
                    loverOnly = assetBlock.readBooleanProperty("LoverOnly") ?: false,
                    familyOnly = assetBlock.readBooleanProperty("FamilyOnly") ?: false,
                    enable = normalizedBlock.readWardrobeBoolean("Enable", true, assetUnsupported),
                    allowRemoveExclusive = assetBlock.readBooleanProperty("AllowRemoveExclusive") ?: false,
                    allowExpression = assetBlock.readNullableStringListProperty("AllowExpression"),
                    expressionPrerequisites = assetBlock.readNullableStringListProperty("ExpressionPrerequisite"),
                    unsupportedExpressionMetadata = assetBlock.readUnsupportedExpressionMetadata(),
                    allowedEffects = allowedEffects ?: emptySet(),
                    unsupportedSpeechMetadata = buildSet {
                        if (speechEntries == null) add("CopyConfig")
                        if (effects == null) add("Effect")
                        if (allowedEffects == null) add("AllowEffect")
                    },
                    value = value.takeUnless { speechEntries == null || key in unsupportedCopies },
                    inventoryId = normalizedBlock.readPropertyValue("InventoryID")?.takeUnless { it.isNullRuleValue() }?.let {
                        normalizedBlock.readWardrobeNumber("InventoryID", 0.0, assetUnsupported)?.takeIf { number ->
                            number >= Int.MIN_VALUE && number <= Int.MAX_VALUE && number % 1.0 == 0.0
                        }?.toInt().also { id -> if (id == null) assetUnsupported += "InventoryID" }
                    },
                    buyGroup = normalizedBlock.readWardrobeString("BuyGroup", null, assetUnsupported),
                    availableLocations = normalizedBlock.readWardrobeStrings("AvailableLocations", emptyList(), assetUnsupported).toSet(),
                    bodyCosplay = if (group.category == "Item") false else normalizedBlock.readWardrobeBoolean("BodyCosplay", group.bodyCosplay, assetUnsupported),
                    defaultColor = normalizedBlock.readNativeDefaultColor(group, assetUnsupported).takeUnless { speechEntries == null },
                    unsupportedWardrobeMetadata = assetUnsupported.toSet(),
                ).withNativePoseRules(assetBlock, groupBlock, poses)
            }
        }
    }

    private fun resolveAssetSpeechEntries(
        key: BcAssetKey,
        assetBlocks: Map<BcAssetKey, String>,
    ): Map<String, String>? = resolveAssetEntries(key, assetBlocks)?.entries

    private data class ResolvedAssetEntries(
        val entries: Map<String, String>,
        val copiedKeys: List<BcAssetKey>,
    )

    private fun resolveAssetEntries(
        key: BcAssetKey,
        assetBlocks: Map<BcAssetKey, String>,
    ): ResolvedAssetEntries? {
        val block = assetBlocks[key]?.takeIf { it.hasVerifiedObjectShape() } ?: return null
        var entries: Map<String, String> = block.readRawPropertyEntries()
        val visiting = mutableSetOf(key)
        val copiedKeys = mutableListOf<BcAssetKey>()
        while (true) {
            val copy = entries["CopyConfig"]?.takeUnless { it.isNullRuleValue() } ?: return ResolvedAssetEntries(entries, copiedKeys)
            if (!copy.hasVerifiedObjectShape()) return null
            // Native _Resolve retains the original destination group throughout the chain.
            val sourceGroup = copy.readPropertyValue("GroupName")?.takeUnless { it.isNullRuleValue() }
                ?.let { it.readStaticString() ?: return null } ?: key.group
            val sourceName = copy.readPropertyValue("AssetName")?.readStaticString() ?: return null
            val sourceKey = BcAssetKey(sourceGroup, sourceName)
            if (!visiting.add(sourceKey)) return null
            val inherited = assetBlocks[sourceKey]?.takeIf { it.hasVerifiedObjectShape() }?.readRawPropertyEntries() ?: return null
            copiedKeys += sourceKey
            entries = inherited + entries.filterKeys { it != "CopyConfig" }
        }
    }

    private fun assignNativeBuyGroup(
        key: BcAssetKey,
        resolved: ResolvedAssetEntries,
        assetBlocks: MutableMap<BcAssetKey, String>,
        unsupported: MutableSet<BcAssetKey>,
    ): Map<String, String> {
        val copy = assetBlocks[key]?.readPropertyValue("CopyConfig") ?: return resolved.entries
        val rawBuyGroup = copy.readPropertyValue("BuyGroup")
        if (rawBuyGroup == null || rawBuyGroup.isNullRuleValue() || rawBuyGroup.staticRuleToken() == "false") return resolved.entries
        val keys = listOf(key) + resolved.copiedKeys
        if (rawBuyGroup.staticRuleToken() != "true") {
            unsupported += keys
            return resolved.entries
        }
        val configs = listOf(resolved.entries) + resolved.copiedKeys.map { assetBlocks.getValue(it).readRawPropertyEntries() }
        var stop = configs.size
        var buyGroup: String? = null
        configs.forEachIndexed { index, config ->
            if (buyGroup != null) return@forEachIndexed
            val raw = config["BuyGroup"]?.takeUnless { it.isNullRuleValue() } ?: return@forEachIndexed
            val name = raw.readStaticString()
            if (name == null) {
                unsupported += keys
                return resolved.entries
            }
            if (name.isNotEmpty()) {
                buyGroup = name
                stop = maxOf(1, index)
            }
        }
        buyGroup = buyGroup ?: configs.last()["Name"]?.readStaticString() ?: return resolved.entries
        var result = resolved.entries
        configs.take(stop).forEachIndexed { index, config ->
            val updated = config.toMutableMap().apply {
                put("BuyGroup", buyGroup!!.toJsonStringLiteral())
                if (index != configs.lastIndex) put("Value", "-1")
            }
            if (index == 0) result = updated else assetBlocks[keys[index]] = updated.toJsonObject()
        }
        return result
    }

    private fun String.staticRuleToken(): String? {
        val start = indexOfNextNonSpace(0) ?: return null
        val end = (start until length).firstOrNull { this[it].isWhitespace() || this[it] == '/' } ?: length
        return substring(start, end).takeIf { indexOfNextNonSpace(end) == null }
    }

    private fun String.readWardrobeBoolean(property: String, default: Boolean, unsupported: MutableSet<String>): Boolean {
        val raw = readPropertyValue(property)?.takeUnless { it.isNullRuleValue() } ?: return default
        return when (raw.staticRuleToken()) {
            "true" -> true
            "false" -> false
            else -> { unsupported += property; default }
        }
    }

    private fun String.readWardrobeString(property: String, default: String?, unsupported: MutableSet<String>): String? {
        val raw = readPropertyValue(property)?.takeUnless { it.isNullRuleValue() } ?: return default
        return raw.readStaticString() ?: run { unsupported += property; default }
    }

    private fun String.readWardrobeStrings(property: String, default: List<String>, unsupported: MutableSet<String>): List<String> {
        readPropertyValue(property)?.takeUnless { it.isNullRuleValue() } ?: return default
        val values = readNullableStringListProperty(property)
        if (values == null || values.any { it == null }) {
            unsupported += property
            return default
        }
        return values.filterNotNull()
    }

    private fun String.readWardrobeNumber(property: String, default: Double, unsupported: MutableSet<String>): Double? {
        val raw = readPropertyValue(property)?.takeUnless { it.isNullRuleValue() } ?: return default
        val token = raw.staticRuleToken()
        val number = token?.takeIf { it.matches(Regex("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?")) }?.toDoubleOrNull()?.takeIf(Double::isFinite)
        if (number == null) unsupported += property
        return number
    }

    private fun String.readNativeDefaultColor(group: BcGroupRule, unsupported: MutableSet<String>): List<String>? {
        if ("Color" in group.unsupportedWardrobeMetadata || "AllowColorize" in group.unsupportedWardrobeMetadata) {
            unsupported += "DefaultColor"
            return null
        }
        val allowColorize = readWardrobeBoolean("AllowColorize", group.allowColorize, unsupported)
        val rawLayers = readPropertyValue("Layer")?.takeUnless { it.isNullRuleValue() }
        val layers = if (rawLayers == null) listOf("{}") else rawLayers.readStaticObjectArray()
        if (layers == null) { unsupported += "Layer"; return null }
        val colorKeys = linkedSetOf<String?>()
        layers.forEach { layer ->
            if (!layer.hasVerifiedObjectShape()) { unsupported += "Layer"; return null }
            val texture = layer.readPropertyValue("TextureMask")
            val hasTexture = when {
                texture == null || texture.isNullRuleValue() -> false
                texture.readStaticString() != null -> texture.readStaticString()!!.isNotEmpty()
                texture.staticRuleToken() == "true" -> true
                texture.staticRuleToken() == "false" -> false
                texture.hasCompleteContainer('{', '}') || texture.hasCompleteContainer('[', ']') -> true
                else -> { unsupported += "Layer.TextureMask"; return null }
            }
            if (hasTexture || !layer.readWardrobeBoolean("AllowColorize", allowColorize, unsupported)) return@forEach
            val copyColor = layer.readWardrobeString("CopyLayerColor", null, unsupported)
            val name = layer.readWardrobeString("Name", null, unsupported)
            colorKeys += copyColor?.takeIf(String::isNotEmpty) ?: name?.takeIf(String::isNotEmpty)
        }
        if (unsupported.any { it in setOf("AllowColorize", "Name", "CopyLayerColor", "Layer.TextureMask") }) return null
        val colors = MutableList(colorKeys.size) { group.defaultColor }
        val raw = readPropertyValue("DefaultColor")?.takeUnless { it.isNullRuleValue() } ?: return colors
        val singleColor = raw.readStaticString()
        if (singleColor != null) return colors.map { singleColor }
        val defaults = readNullableStringListProperty("DefaultColor")
        if (defaults == null || defaults.any { it == null }) { unsupported += "DefaultColor"; return null }
        defaults.take(colors.size).forEachIndexed { index, color -> colors[index] = color!! }
        return colors
    }

    private fun String.splitAssetDefinitions(): List<String> = removeOuter('[', ']').splitTopLevelValues().mapNotNull { raw ->
        if (raw.startsWith("{")) raw else raw.readStaticString()?.let { "{Name:${it.toJsonStringLiteral()}}" }
    }

    private fun String.readStaticString(): String? {
        val start = indexOfNextNonSpace(0) ?: return null
        if (this[start] != '"' && this[start] != '\'') return null
        val end = findStringEnd(start) ?: return null
        if (indexOfNextNonSpace(end + 1) != null) return null
        return BcItemPropertyJson.readStringValue(substring(start, end + 1))
    }

    private fun mergeExtendedSpeechEffects(
        source: String,
        constants: Map<String, String>,
        assets: MutableMap<BcAssetKey, BcAssetRule>,
    ) {
        val root = source.extractVariableObject("AssetFemale3DCGExtended") ?: return
        val blocks = buildMap {
            root.readTopLevelNamedBlocks().forEach { (group, groupBlock) ->
                groupBlock.readTopLevelNamedBlocks().forEach { (name, block) -> put(BcAssetKey(group, name), block) }
            }
        }
        blocks.forEach { (key, _) ->
            val asset = assets[key] ?: return@forEach
            val entries = resolveAssetSpeechEntries(key, blocks)
            val effects = entries?.let { collectExtendedSpeechEffects(it.toJsonObject(), key, blocks, constants, asset.effects) }
            assets[key] = if (effects == null) asset.copy(unsupportedSpeechMetadata = asset.unsupportedSpeechMetadata + "AllowEffect")
            else asset.copy(allowedEffects = asset.allowedEffects + effects)
        }
    }

    private fun collectExtendedSpeechEffects(
        block: String,
        key: BcAssetKey,
        blocks: Map<BcAssetKey, String>,
        constants: Map<String, String>,
        assetEffects: Set<String>,
        depth: Int = 0,
    ): Set<String>? {
        if (depth > 32) return null
        val rawArchetype = block.readPropertyValue("Archetype") ?: return null
        val archetypeName = rawArchetype.readStaticString()
            ?: Regex("ExtendedArchetype\\.([A-Z]+)").matchEntire(rawArchetype.trim())?.groupValues?.get(1)
            ?: return null
        val archetype = BcExtendedItemArchetype.from(archetypeName)
        if (archetype == BcExtendedItemArchetype.Unknown) return null
        val config = block
        val effects = config.readPropertyValue("AllowEffect").readStaticEffects(constants)?.toMutableSet() ?: return null
        if (archetype == BcExtendedItemArchetype.Vibrating) {
            effects += assetEffects
            val rawSets = config.readPropertyValue("Options")
            val sets = if (rawSets == null || rawSets.isNullRuleValue()) setOf("Standard", "Advanced")
            else rawSets.readStaticEffects(mapOf("STANDARD" to "Standard", "ADVANCED" to "Advanced"), namespace = "VibratorModeSet") ?: return null
            if (sets.any { it != "Standard" && it != "Advanced" }) return null
            if (sets.isNotEmpty()) effects += setOf("Egged", "Vibrating")
            if ("Advanced" in sets) effects += "Edged"
            return effects
        }
        if (archetype in setOf(BcExtendedItemArchetype.Typed, BcExtendedItemArchetype.Modular)) {
            effects += assetEffects
            val options = if (archetype == BcExtendedItemArchetype.Modular) {
                config.readPropertyValue("Modules")?.readStaticObjectArray()?.flatMap { module ->
                    module.readPropertyValue("Options")?.readStaticObjectArray() ?: return null
                } ?: return null
            } else {
                val rawOptions = config.readPropertyValue("Options")
                if (rawOptions != null) rawOptions.readStaticObjectArray() ?: return null
                else return null
            }
            options.forEach { option ->
                val raw = option.readPropertyValue("Property")?.readPropertyValue("Effect")
                effects += raw.readStaticEffects(constants) ?: return null
                val sub = option.readPropertyValue("ArchetypeConfig")
                if (sub != null) {
                    val entries = resolveAssetSpeechEntries(key, blocks + (key to sub)) ?: return null
                    effects += collectExtendedSpeechEffects(entries.toJsonObject(), key, blocks, constants, assetEffects, depth + 1) ?: return null
                }
            }
        }
        return effects
    }

    private fun String.readStaticObjectArray(): List<String>? {
        val values = readStaticArrayValues() ?: return null
        if (values.any { !it.hasCompleteContainer('{', '}') }) return null
        return values
    }

    private fun String.hasCompleteContainer(open: Char, close: Char): Boolean {
        val start = indexOfNextNonSpace(0) ?: return false
        if (this[start] != open) return false
        val end = findMatching(start, open, close) ?: return false
        return indexOfNextNonSpace(end + 1) == null
    }

    private fun String.hasVerifiedObjectShape(): Boolean {
        if (!hasCompleteContainer('{', '}')) return false
        val start = indexOfNextNonSpace(0)!!
        val end = findMatching(start, '{', '}')!!
        val body = substring(start + 1, end)
        var index = 0
        while (index < body.length) {
            val keyStart = body.indexOfNextNonSpace(index) ?: return true
            val keyEnd = when {
                body[keyStart] == '"' || body[keyStart] == '\'' -> body.findStringEnd(keyStart)?.plus(1) ?: return false
                body[keyStart].isIdentifierStart() -> (keyStart + 1 until body.length).firstOrNull { !body[it].isIdentifierPart() } ?: body.length
                else -> return false
            }
            val colon = body.indexOfNextNonSpace(keyEnd) ?: return false
            if (body[colon] != ':') return false
            val valueStart = body.indexOfNextNonSpace(colon + 1) ?: return false
            index = body.findEndOfValue(valueStart)
            if (index < body.length) index++
        }
        return true
    }

    private fun String.readStaticArrayValues(): List<String>? {
        val start = indexOfNextNonSpace(0) ?: return null
        if (this[start] != '[') return null
        val end = findMatching(start, '[', ']') ?: return null
        if (indexOfNextNonSpace(end + 1) != null) return null
        val body = substring(start + 1, end)
        val values = mutableListOf<String>()
        var index = 0
        while (index < body.length) {
            val valueStart = body.indexOfNextNonSpace(index) ?: break
            if (body[valueStart] == ',') return null
            val valueEnd = body.findEndOfValue(valueStart)
            values += body.substring(valueStart, valueEnd).trim()
            index = valueEnd
            if (index < body.length && body[index] == ',') index++
        }
        return values
    }

    private fun String.readEffectConstants(): Map<String, String> {
        val assignment = Regex("\\b(?:var|let|const)\\s+E\\s*=").find(this) ?: return emptyMap()
        var start = indexOfNextNonSpace(assignment.range.last + 1) ?: return emptyMap()
        if (this[start] == '(') start = indexOfNextNonSpace(start + 1) ?: return emptyMap()
        if (this[start] != '{') return emptyMap()
        val end = findMatching(start, '{', '}') ?: return emptyMap()
        return substring(start, end + 1).readRawPropertyEntries().mapNotNull { (key, raw) ->
            BcItemPropertyJson.readStringValue(raw)?.let { key to it }
        }.toMap()
    }

    private fun String?.readStaticEffects(constants: Map<String, String>, namespace: String = "E"): Set<String>? {
        if (this == null || isNullRuleValue()) return emptySet()
        val arrayStart = indexOfNextNonSpace(0) ?: return null
        if (this[arrayStart] != '[') return null
        val arrayEnd = findMatching(arrayStart, '[', ']') ?: return null
        if (indexOfNextNonSpace(arrayEnd + 1) != null) return null
        val body = substring(arrayStart + 1, arrayEnd)
        val effects = linkedSetOf<String>()
        var index = 0
        while (index < body.length) {
            val valueStart = body.indexOfNextNonSpace(index) ?: break
            if (body[valueStart] == ',') return null
            val valueEnd = body.findEndOfValue(valueStart)
            val raw = body.substring(valueStart, valueEnd).trim()
            val literalEnd = raw.takeIf { it.firstOrNull() == '"' || it.firstOrNull() == '\'' }?.findStringEnd(0)
            val literal = literalEnd?.takeIf { raw.indexOfNextNonSpace(it + 1) == null }
                ?.let { BcItemPropertyJson.readStringValue(raw.substring(0, it + 1)) }
            val constant = Regex("${Regex.escape(namespace)}\\.([A-Za-z_$][A-Za-z0-9_$]*)").find(raw)?.takeIf { it.range.first == 0 }
                ?.takeIf { raw.indexOfNextNonSpace(it.range.last + 1) == null }
                ?.groupValues?.get(1)?.let(constants::get)
            val value = literal ?: constant
                ?: return null
            effects += value
            index = valueEnd
            if (index < body.length && body[index] == ',') index++
        }
        return effects
    }


    private fun BcAssetRule.withNativePoseRules(
        assetBlock: String,
        groupBlock: String,
        poses: Map<String, BcPoseRule>,
    ): BcAssetRule {
        val assetSetPose = assetBlock.readPropertyValue("SetPose")?.takeUnless { it.isNullRuleValue() }
        val groupSetPose = groupBlock.readPropertyValue("SetPose")?.takeUnless { it.isNullRuleValue() }
        val rawSetPose = assetSetPose ?: groupSetPose
        val resolvedSetPose = if (rawSetPose != null) {
            rawSetPose.extractRuleNames()
        } else {
            if (allowActivePose.isEmpty()) return this
            linkedSetOf(allowActivePose.first())
        }
        val permitted = linkedSetOf<String>().apply {
            addAll(resolvedSetPose)
            addAll(allowActivePose)
        }
        val upper = permitted.filter { (poses[it]?.category ?: BcCharacterPoseState.categoryOf(it)) == "BodyUpper" }
        val lower = permitted.filter { (poses[it]?.category ?: BcCharacterPoseState.categoryOf(it)) == "BodyLower" }
        // BC's AssetParsePosePrerequisite adds these full-body combinations to the asset at load time.
        if ((upper.isNotEmpty() || lower.isNotEmpty()) &&
            (upper.isEmpty() || "BackElbowTouch" in upper) &&
            (lower.isEmpty() || "Kneel" in lower)
        ) {
            permitted += "Hogtied"
        }
        if (upper.isEmpty() && "Kneel" in lower) permitted += "AllFours"
        return copy(setPose = resolvedSetPose, allowActivePose = permitted)
    }


    private fun parseExtendedItems(
        source: String,
        extendedItems: MutableMap<BcAssetKey, BcExtendedItemRule>,
        assetProperties: Map<BcAssetKey, String>,
    ) {
        val objectText = source.extractVariableObject("AssetFemale3DCGExtended") ?: return
        val explicit = linkedMapOf<BcAssetKey, String>()
        val copies = linkedMapOf<BcAssetKey, BcAssetKey>()
        val copyBlocks = linkedMapOf<BcAssetKey, String>()

        objectText.readTopLevelNamedBlocks().forEach { (groupName, groupBlock) ->
            groupBlock.readTopLevelNamedBlocks().forEach { (assetName, assetBlock) ->
                val key = BcAssetKey(group = groupName, name = assetName)
                val copyConfig = assetBlock.readPropertyValue("CopyConfig")
                if (copyConfig != null) {
                    val copyGroup = copyConfig.readStringProperty("GroupName") ?: groupName
                    val copyAsset = copyConfig.readStringProperty("AssetName")
                    if (copyAsset != null) {
                        copies[key] = BcAssetKey(copyGroup, copyAsset)
                        copyBlocks[key] = assetBlock
                    }
                } else {
                    explicit[key] = assetBlock
                }
            }
        }

        explicit.forEach { (key, block) ->
            extendedItems[key] = parseExtendedItemBlock(key.group, key.name, block, assetProperties[key])
        }

        var changed = true
        while (changed) {
            changed = false
            copies.forEach { (key, sourceKey) ->
                if (key !in extendedItems) {
                    val sourceBlock = explicit[sourceKey]
                    if (sourceBlock != null) {
                        val localBlock = copyBlocks.getValue(key)
                        val sourceArchetype = sourceBlock.readIdentifierOrStringProperty("Archetype")
                        val localArchetype = localBlock.readIdentifierOrStringProperty("Archetype")
                        if (sourceArchetype != localArchetype) {
                            extendedItems[key] = unsupportedExtendedItem(key.group, key.name, reason = "CopyConfig Archetype does not match its source.")
                        } else {
                            val mergedEntries = sourceBlock.readRawPropertyEntries()
                            mergedEntries.putAll(localBlock.readRawPropertyEntries().filterKeys { it != "CopyConfig" })
                            val mergedBlock = mergedEntries.toJsonObject()
                            explicit[key] = mergedBlock
                            extendedItems[key] = parseExtendedItemBlock(key.group, key.name, mergedBlock, assetProperties[key])
                        }
                        changed = true
                    }
                }
            }
        }

        copies.forEach { (key, sourceKey) ->
            if (key !in extendedItems) {
                extendedItems[key] = unsupportedExtendedItem(
                    groupName = key.group,
                    assetName = key.name,
                    reason = "CopyConfig source '${sourceKey.group}/${sourceKey.name}' was not parsed.",
                )
            }
        }
    }

    private fun parseExtendedItemBlock(
        groupName: String,
        assetName: String,
        block: String,
        assetPropertyJson: String? = null,
    ): BcExtendedItemRule {
        val archetype = BcExtendedItemArchetype.from(block.readIdentifierOrStringProperty("Archetype"))
        if (archetype == BcExtendedItemArchetype.Unknown) {
            return unsupportedExtendedItem(
                groupName = groupName,
                assetName = assetName,
                reason = "Extended item has no statically recognized Archetype.",
            )
        }

        return when (archetype) {
            BcExtendedItemArchetype.Typed -> parseTypedLikeExtendedItem(groupName, assetName, block, archetype)

            BcExtendedItemArchetype.Vibrating -> parseVibratingExtendedItem(groupName, assetName, block)

            BcExtendedItemArchetype.Modular -> parseModularExtendedItem(groupName, assetName, block, assetPropertyJson)

            BcExtendedItemArchetype.VariableHeight -> unsupportedExtendedItem(
                groupName = groupName,
                assetName = assetName,
                archetype = archetype,
                reason = "Variable-height items require a custom height editor.",
            )

            BcExtendedItemArchetype.Text -> parseTextItem(groupName, assetName, block)

            BcExtendedItemArchetype.NoArch -> unsupportedExtendedItem(
                groupName = groupName,
                assetName = assetName,
                archetype = archetype,
                reason = "NoArch items use a custom Inventory screen and cannot be statically configured yet.",
            ).copy(baselinePropertyJson = block.readPropertyValue("BaselineProperty")?.toJsonValueOrNull())

            BcExtendedItemArchetype.Unknown -> unsupportedExtendedItem(
                groupName = groupName,
                assetName = assetName,
                reason = "Extended item has an unknown Archetype.",
            )
        }
    }

    private fun parseTextItem(group: String, name: String, block: String): BcExtendedItemRule {
        fun unsupported() = unsupportedExtendedItem(group, name, BcExtendedItemArchetype.Text,
            "This text item has custom configuration or validation that is not supported yet.")
        val hooks = block.readPropertyValue("ScriptHooks")?.readRawPropertyEntries().orEmpty()
        if (hooks.keys.any { it !in setOf("BeforeDraw", "AfterDraw", "Draw") }) return unsupported()
        if (block.readPropertyValue("Validate") != null || block.readPropertyValue("EventListeners") != null || block.readBooleanProperty("PushOnPublish") == false) return unsupported()
        val lengths = block.readPropertyValue("MaxLength")?.readRawPropertyEntries() ?: return unsupported()
        if (lengths.isEmpty() || lengths.any { (key, value) -> !key.matches(Regex("Text[0-9]*")) || value.trim().toIntOrNull()?.let { it >= 0 } != true }) return unsupported()
        val baseline = block.readPropertyValue("BaselineProperty")?.let {
            if (!it.isStaticModularProperty()) return unsupported()
            it.readRawPropertyEntries().mapValues { (_, value) -> value.toJsonValueOrNull() ?: return unsupported() }
        }.orEmpty().toMutableMap()
        lengths.keys.forEach { key -> if (baseline[key]?.trim()?.startsWith('"') != true) baseline[key] = "\"\"" }
        val property = baseline.filterKeys { it in lengths }.toJsonObject()
        return BcExtendedItemRule(group, name, BcExtendedItemArchetype.Text, "text",
            defaultPropertyJson = property, baselinePropertyJson = property,
            textFields = lengths.mapValues { (_, value) -> value.trim().toInt() })
    }

    private fun parseTypedLikeExtendedItem(
        groupName: String,
        assetName: String,
        block: String,
        archetype: BcExtendedItemArchetype,
    ): BcExtendedItemRule {
        val recordKey = block.readStringProperty("Name") ?: archetype.wireValue
        val baselinePropertyBlock = block.readPropertyValue("BaselineProperty")
        val optionBlocks = block.readPropertyValue("Options")?.splitTopLevelObjects().orEmpty()
        val options = optionBlocks.mapIndexed { index, optionBlock ->
            val name = optionBlock.readStringProperty("Name") ?: index.toString()
            val propertyBlock = optionBlock.readPropertyValue("Property")
            val metadata = optionBlock.optionMetadata(listOfNotNull(baselinePropertyBlock, propertyBlock))
            BcExtendedItemOptionRule(
                name = name,
                index = index,
                allowLock = optionBlock.readBooleanProperty("AllowLock"),
                displayName = name.readableOptionName(),
                propertyJson = buildPropertyJson(
                    typeRecord = linkedMapOf(recordKey to index),
                    propertyBlocks = listOfNotNull(baselinePropertyBlock, propertyBlock),
                ),
                difficulty = listOfNotNull(baselinePropertyBlock, propertyBlock).mapNotNull { it.readIntPropertyOrNull("Difficulty") }.sum().takeIf { it != 0 },
                kind = BcExtendedItemOptionKind.Typed,
                propertyKeys = metadata.propertyKeys,
                propertySummary = metadata.summary,
                warnings = metadata.warnings,
                supportLevel = metadata.supportLevel,
            )
        }

        return BcExtendedItemRule(
            group = groupName,
            name = assetName,
            archetype = archetype,
            recordKey = recordKey,
            typedOptions = options,
            defaultPropertyJson = options.firstOrNull()?.propertyJson,
            defaultDifficulty = options.firstOrNull()?.difficulty,
            baselinePropertyJson = baselinePropertyBlock?.let { buildPropertyJson(emptyMap(), listOf(it)) },
            supportLevel = options.map { it.supportLevel }.combinedSupportLevel(),
            warnings = options.flatMap { it.warnings }.distinct(),
        )
    }

    private fun parseVibratingExtendedItem(
        groupName: String,
        assetName: String,
        block: String,
    ): BcExtendedItemRule {
        val recordKey = block.readStringProperty("Name") ?: BcExtendedItemArchetype.Vibrating.wireValue
        val baselinePropertyBlock = block.readPropertyValue("BaselineProperty")
        val options = vibratorModeOptions(block.readPropertyValue("Options")).mapIndexed { index, proto ->
            val propertyBlock = proto.propertyBlock
            val metadata = proto.name.optionMetadataFromPropertyBlocks(listOfNotNull(baselinePropertyBlock, propertyBlock))
            BcExtendedItemOptionRule(
                name = proto.name,
                index = index,
                displayName = proto.name.readableOptionName(),
                propertyJson = buildPropertyJson(
                    typeRecord = linkedMapOf(recordKey to index),
                    propertyBlocks = listOfNotNull(baselinePropertyBlock, propertyBlock),
                ),
                difficulty = listOfNotNull(baselinePropertyBlock, propertyBlock).mapNotNull { it.readIntPropertyOrNull("Difficulty") }.sum().takeIf { it != 0 },
                kind = BcExtendedItemOptionKind.Vibrating,
                propertyKeys = metadata.propertyKeys,
                propertySummary = metadata.summary,
                warnings = metadata.warnings,
                supportLevel = metadata.supportLevel,
            )
        }

        return BcExtendedItemRule(
            group = groupName,
            name = assetName,
            archetype = BcExtendedItemArchetype.Vibrating,
            recordKey = recordKey,
            typedOptions = options,
            defaultPropertyJson = options.firstOrNull()?.propertyJson,
            defaultDifficulty = options.firstOrNull()?.difficulty,
            baselinePropertyJson = baselinePropertyBlock?.let { buildPropertyJson(emptyMap(), listOf(it)) },
            supportLevel = options.map { it.supportLevel }.combinedSupportLevel(),
            warnings = options.flatMap { it.warnings }.distinct(),
        )
    }

    private fun parseModularExtendedItem(
        groupName: String,
        assetName: String,
        block: String,
        assetPropertyJson: String?,
    ): BcExtendedItemRule {
        val baselinePropertyBlock = block.readPropertyValue("BaselineProperty")
        val baselinePropertyJson = baselinePropertyBlock?.let { buildPropertyJson(emptyMap(), listOf(it)) }
        val moduleBlocks = block.readPropertyValue("Modules")?.splitTopLevelObjects().orEmpty()
        val parsedModules = moduleBlocks.mapIndexedNotNull { moduleIndex, moduleBlock ->
            val key = moduleBlock.readStringProperty("Key") ?: return@mapIndexedNotNull null
            val name = moduleBlock.readStringProperty("Name") ?: key
            val options = moduleBlock.readPropertyValue("Options")?.splitTopLevelObjects().orEmpty().mapIndexed { index, optionBlock ->
                ParsedModuleOption(
                    moduleKey = key,
                    moduleName = name,
                    index = index,
                    name = optionBlock.readStringProperty("Name") ?: "$key$index",
                    propertyBlock = optionBlock.readPropertyValue("Property"),
                    optionWarnings = optionBlock.extendedOptionWarnings(),
                    allowLock = optionBlock.readBooleanProperty("AllowLock"),
                )
            }
            ParsedModule(
                name = name,
                key = key,
                index = moduleIndex,
                options = options.ifEmpty { listOf(ParsedModuleOption(key, name, 0, "${key}0", null)) },
            )
        }

        val modules = parsedModules.map { module ->
            BcExtendedItemModuleRule(
                name = module.name,
                key = module.key,
                options = module.options.map { option ->
                    val selections = parsedModules.associate { parsedModule ->
                        parsedModule.key to if (parsedModule.key == module.key) option.index else 0
                    }
                    val selectedPropertyBlocks = parsedModules.mapNotNull { parsedModule ->
                        val selectedIndex = selections.getValue(parsedModule.key)
                        parsedModule.options.getOrNull(selectedIndex)?.propertyBlock
                    }
                    val propertyBlocks = listOfNotNull(baselinePropertyBlock) + selectedPropertyBlocks
                    val metadata = option.name.optionMetadataFromPropertyBlocks(propertyBlocks, option.optionWarnings)
                    val propertyJson = if (propertyBlocks.all { it.isStaticModularProperty() }) {
                        BcItemPropertyJson.composeModularProperty(
                            typeRecord = selections,
                            assetPropertyJson = assetPropertyJson,
                            propertyJsons = propertyBlocks.map { buildPropertyJson(emptyMap(), listOf(it)) },
                        )
                    } else {
                        null
                    }
                    BcExtendedItemModuleOptionRule(
                        name = option.name,
                        index = option.index,
                        allowLock = option.allowLock,
                        displayName = option.name.readableOptionName(),
                        propertyJson = propertyJson,
                        modulePropertyJson = buildPropertyJson(emptyMap(), listOfNotNull(option.propertyBlock)),
                        difficulty = propertyJson?.let { BcItemPropertyJson.readPropertyValue(it, "Difficulty")?.toIntOrNull() },
                        propertyKeys = metadata.propertyKeys,
                        propertySummary = metadata.summary,
                        warnings = metadata.warnings + if (propertyJson == null) listOf("This option requires unsupported modular property rules.") else emptyList(),
                        supportLevel = if (propertyJson == null) BcExtendedItemSupportLevel.Unsupported else metadata.supportLevel,
                    )
                },
            )
        }

        val defaultSelections = parsedModules.associate { it.key to 0 }
        val defaultPropertyBlocks = listOfNotNull(baselinePropertyBlock) + parsedModules.mapNotNull { it.options.firstOrNull()?.propertyBlock }
        val flattenedOptions = modules.flatMap { it.options }
        return BcExtendedItemRule(
            group = groupName,
            name = assetName,
            archetype = BcExtendedItemArchetype.Modular,
            recordKey = block.readStringProperty("Name") ?: BcExtendedItemArchetype.Modular.wireValue,
            modules = modules,
            defaultPropertyJson = if (modules.isNotEmpty() && defaultPropertyBlocks.all { it.isStaticModularProperty() }) {
                BcItemPropertyJson.composeModularProperty(
                    typeRecord = defaultSelections,
                    assetPropertyJson = assetPropertyJson,
                    propertyJsons = defaultPropertyBlocks.map { buildPropertyJson(emptyMap(), listOf(it)) },
                )
            } else {
                null
            },
            defaultDifficulty = defaultPropertyBlocks.mapNotNull { it.readIntPropertyOrNull("Difficulty") }.sum(),
            baselinePropertyJson = baselinePropertyJson,
            assetPropertyJson = assetPropertyJson,
            supportLevel = flattenedOptions.map { it.supportLevel }.combinedSupportLevel(),
            warnings = flattenedOptions.flatMap { it.warnings }.distinct(),
            textFields = unconditionalModularTextFields(groupName, assetName, block, moduleBlocks),
        )
    }

    private fun unconditionalModularTextFields(group: String, name: String, block: String, modules: List<String>): Map<String, Int> {
        if (block.readPropertyValue("Validate") != null || block.readPropertyValue("EventListeners") != null ||
            block.readPropertyValue("ScriptHooks")?.readRawPropertyEntries()?.keys?.any { it !in setOf("BeforeDraw", "AfterDraw", "Draw") } == true) return emptyMap()
        val fields = linkedMapOf<String, Int>()
        modules.forEach { module ->
            if (module.readRawPropertyEntries().keys.any { it !in setOf("Name", "Key", "Options") }) return@forEach
            val option = module.readPropertyValue("Options")?.readStaticObjectArray()?.singleOrNull() ?: return@forEach
            if (option.readRawPropertyEntries().keys.any { it !in setOf("Name", "Property", "HasSubscreen", "ArchetypeConfig") }) return@forEach
            val config = option.readPropertyValue("ArchetypeConfig") ?: return@forEach
            if (BcExtendedItemArchetype.from(config.readIdentifierOrStringProperty("Archetype")) != BcExtendedItemArchetype.Text) return@forEach
            val text = parseTextItem(group, name, config)
            if (text.supportLevel == BcExtendedItemSupportLevel.Unsupported) return@forEach
            text.textFields.forEach { (key, length) -> fields[key] = minOf(fields[key] ?: length, length) }
        }
        return fields
    }

    private data class ParsedModule(
        val name: String,
        val key: String,
        val index: Int,
        val options: List<ParsedModuleOption>,
    )

    private data class ParsedModuleOption(
        val moduleKey: String,
        val moduleName: String,
        val index: Int,
        val name: String,
        val propertyBlock: String?,
        val optionWarnings: List<String> = emptyList(),
        val allowLock: Boolean? = null,
    )

    private fun unsupportedExtendedItem(
        groupName: String,
        assetName: String,
        archetype: BcExtendedItemArchetype = BcExtendedItemArchetype.Unknown,
        reason: String,
    ): BcExtendedItemRule = BcExtendedItemRule(
        group = groupName,
        name = assetName,
        archetype = archetype,
        recordKey = archetype.wireValue,
        supportLevel = BcExtendedItemSupportLevel.Unsupported,
        unsupportedReason = reason,
    )

    private data class ProtoVibratorOption(
        val name: String,
        val propertyBlock: String,
    )

    private fun vibratorModeOptions(rawOptions: String?): List<ProtoVibratorOption> {
        val modeSets = rawOptions.readVibratorModeSets()
        return modeSets.flatMap { set ->
            when (set) {
                "Standard" -> StandardVibratorOptions
                "Advanced" -> AdvancedVibratorOptions
                else -> emptyList()
            }
        }
    }

    private fun String?.readVibratorModeSets(): List<String> {
        if (this.isNullOrBlank()) return listOf("Standard", "Advanced")
        val output = mutableListOf<String>()
        if (contains("STANDARD")) output += "Standard"
        if (contains("ADVANCED")) output += "Advanced"
        return output.ifEmpty { listOf("Standard", "Advanced") }
    }

    private data class OptionMetadata(
        val propertyKeys: Set<String>,
        val summary: String?,
        val warnings: List<String>,
        val supportLevel: BcExtendedItemSupportLevel,
    )

    private fun String.optionMetadata(propertyBlocks: List<String>): OptionMetadata =
        (readStringProperty("Name") ?: "option").optionMetadataFromPropertyBlocks(
            propertyBlocks = propertyBlocks,
            extraWarnings = extendedOptionWarnings(),
        )

    private fun String.optionMetadataFromPropertyBlocks(
        propertyBlocks: List<String>,
        extraWarnings: List<String> = emptyList(),
    ): OptionMetadata {
        val keys = propertyBlocks.flatMap { it.readRawPropertyEntries().keys }.filterNot { it == "TypeRecord" }.toCollection(linkedSetOf())
        val effectCount = propertyBlocks.flatMap { it.readStringSetProperty("Effect") }.distinct().size
        val blockCount = propertyBlocks.flatMap { it.readStringSetProperty("Block") }.distinct().size
        val allowActivityCount = propertyBlocks.flatMap { it.readAnyStringSetProperty("AllowActivity") }.distinct().size
        val details = buildList {
            if (keys.isNotEmpty()) add(keys.take(6).joinToString(prefix = "properties: "))
            if (effectCount > 0) add("effects: $effectCount")
            if (blockCount > 0) add("blocks: $blockCount")
            if (allowActivityCount > 0) add("activities: $allowActivityCount")
        }
        val warnings = extraWarnings.distinct()
        return OptionMetadata(
            propertyKeys = keys,
            summary = details.joinToString(" • ").ifBlank { null },
            warnings = warnings,
            supportLevel = if (warnings.isEmpty()) BcExtendedItemSupportLevel.Supported else BcExtendedItemSupportLevel.Partial,
        )
    }

    private fun String.extendedOptionWarnings(): List<String> = buildList {
        val archetypeConfig = readPropertyValue("ArchetypeConfig")
        if (readBooleanProperty("HasSubscreen") == true || archetypeConfig != null) {
            val subArchetype = BcExtendedItemArchetype.from(archetypeConfig?.readIdentifierOrStringProperty("Archetype"))
            val suffix = if (subArchetype != BcExtendedItemArchetype.Unknown) " (${subArchetype.wireValue})" else ""
            add("This option opens an extended subscreen$suffix; mobile applies only its static base property.")
        }
        if (readPropertyValue("ScriptHooks") != null) {
            add("This option has script hooks; mobile does not execute Inventory JS hooks.")
        }
        if (readPropertyValue("Validate") != null) {
            add("This option has custom validation; mobile only applies static permission checks.")
        }
    }

    private fun List<BcExtendedItemSupportLevel>.combinedSupportLevel(): BcExtendedItemSupportLevel = when {
        isEmpty() -> BcExtendedItemSupportLevel.Unsupported
        any { it == BcExtendedItemSupportLevel.Partial } -> BcExtendedItemSupportLevel.Partial
        any { it == BcExtendedItemSupportLevel.Unsupported } -> BcExtendedItemSupportLevel.Partial
        else -> BcExtendedItemSupportLevel.Supported
    }

    private fun buildPropertyJson(
        typeRecord: Map<String, Int>,
        propertyBlocks: List<String>,
    ): String {
        val entries = linkedMapOf<String, String>()
        entries["TypeRecord"] = typeRecord.entries.joinToString(
            separator = ",",
            prefix = "{",
            postfix = "}",
        ) { (key, value) -> "${key.toJsonStringLiteral()}:$value" }

        propertyBlocks.forEach { block ->
            block.readRawPropertyEntries().forEach { (key, rawValue) ->
                if (!key.equals("TypeRecord", ignoreCase = true)) {
                    rawValue.toJsonValueOrNull()?.let { entries[key] = it }
                }
            }
        }

        return entries.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "${key.toJsonStringLiteral()}:$value"
        }
    }

    private fun Map<String, String>.toJsonObject(): String =
        entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, value) ->
            "${key.toJsonStringLiteral()}:$value"
        }

    private fun String.isStaticModularProperty(): Boolean = readRawPropertyEntries().values.all { it.isStaticRuleValue() }

    private fun String.isStaticRuleValue(): Boolean {
        val value = trim()
        return when {
            value.startsWith("{") -> value.readRawPropertyEntries().values.all { it.isStaticRuleValue() }
            value.startsWith("[") -> value.removeOuter('[', ']').splitTopLevelValues().all { it.isStaticRuleValue() }
            else -> value == "true" || value == "false" || value == "null" ||
                NumberRegex.matches(value) || StringLiteralRegex.matches(value) || DottedIdentifierRegex.matches(value)
        }
    }

    private fun mergeExtendedItemActivityHints(
        source: String,
        assets: MutableMap<BcAssetKey, BcAssetRule>,
    ) {
        val objectText = source.extractVariableObject("AssetFemale3DCGExtended") ?: return
        objectText.readTopLevelNamedBlocks().forEach { (groupName, groupBlock) ->
            groupBlock.readTopLevelNamedBlocks().forEach { (assetName, assetBlock) ->
                val key = BcAssetKey(group = groupName, name = assetName)
                val existing = assets[key] ?: return@forEach
                val allowActivity = existing.allowActivity + assetBlock.readAnyStringSetProperty("AllowActivity")
                val allowActivityOn = existing.allowActivityOn + assetBlock.readAnyStringSetProperty("AllowActivityOn")
                if (allowActivity != existing.allowActivity || allowActivityOn != existing.allowActivityOn) {
                    assets[key] = existing.copy(
                        allowActivity = allowActivity,
                        allowActivityOn = allowActivityOn,
                    )
                }
            }
        }
    }

    private fun String.extractVariableArray(variableName: String): String? =
        extractAssignedBalanced(variableName, open = '[', close = ']')

    private fun String.extractVariableObject(variableName: String): String? =
        extractAssignedBalanced(variableName, open = '{', close = '}')

    private fun String.extractAssignedBalanced(
        variableName: String,
        open: Char,
        close: Char,
    ): String? {
        val match = Regex("\\b(?:var|let|const)\\s+${Regex.escape(variableName)}\\s*=").find(this) ?: return null
        val start = indexOf(open, startIndex = match.range.last + 1).takeIf { it >= 0 } ?: return null
        val end = findMatching(start, open, close) ?: return null
        return substring(start, end + 1)
    }

    private fun String.splitTopLevelObjects(): List<String> {
        val output = mutableListOf<String>()
        var index = 0
        while (index < length) {
            val start = indexOfNextCodeChar('{', index) ?: break
            val end = findMatching(start, '{', '}') ?: break
            output += substring(start, end + 1)
            index = end + 1
        }
        return output
    }

    private fun String.readTopLevelNamedBlocks(): List<Pair<String, String>> {
        val body = trim().removeOuter('{', '}')
        val output = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < body.length) {
            val keyRead = body.readKeyAtOrAfter(index) ?: break
            val (key, colonIndex) = keyRead
            val valueStart = body.indexOfNextNonSpace(colonIndex + 1) ?: break
            if (body[valueStart] == '{') {
                val end = body.findMatching(valueStart, '{', '}') ?: break
                output += key to body.substring(valueStart, end + 1)
                index = end + 1
            } else {
                index = body.findEndOfValue(valueStart)
            }
        }
        return output
    }


    private fun String.readIdentifierOrStringProperty(property: String): String? {
        val value = readPropertyValue(property)?.trim() ?: return null
        StringLiteralRegex.find(value)?.let { return it.stringContent() }
        DottedIdentifierRegex.find(value)?.let { return it.groupValues[1] }
        return IdentifierRegex.find(value)?.value
    }

    private fun String?.readIntPropertyOrNull(property: String): Int? =
        this?.readPropertyValue(property)?.trim()?.let { value ->
            IntRegex.matchEntire(value)?.value?.toIntOrNull()
        }

    private fun String.readInteractionZones(): List<BcInteractionZoneRect> =
        readPropertyValue("Zone")
            ?.let { value ->
                ZoneRectRegex.findAll(value).mapNotNull { match ->
                    val values = match.groupValues.drop(1).mapNotNull(String::toIntOrNull)
                    if (values.size != 4 || values[2] <= 0 || values[3] <= 0) {
                        null
                    } else {
                        BcInteractionZoneRect(values[0], values[1], values[2], values[3])
                    }
                }.toList()
            }
            .orEmpty()

    private fun String.readRawPropertyEntries(): LinkedHashMap<String, String> {
        val body = trim().removeOuter('{', '}')
        val output = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            val keyRead = body.readKeyAtOrAfter(index) ?: break
            val (key, colonIndex) = keyRead
            val valueStart = body.indexOfNextNonSpace(colonIndex + 1) ?: break
            val valueEnd = body.findEndOfValue(valueStart)
            output[key] = body.substring(valueStart, valueEnd).trim()
            index = valueEnd + 1
        }
        return output
    }

    private fun String.toJsonValueOrNull(): String? {
        val value = trim().trimEnd(',').trim()
        if (value.isBlank()) return null
        return when {
            value.startsWith("...") -> null
            value.startsWith("{") -> value.readRawPropertyEntries().entries.mapNotNull { (key, raw) ->
                raw.toJsonValueOrNull()?.let { "${key.toJsonStringLiteral()}:$it" }
            }.joinToString(separator = ",", prefix = "{", postfix = "}")
            value.startsWith("[") -> value.trim().removeOuter('[', ']').splitTopLevelValues().mapNotNull {
                it.toJsonValueOrNull()
            }.joinToString(separator = ",", prefix = "[", postfix = "]")
            value.startsWith("\"") || value.startsWith("'") ->
                StringLiteralRegex.find(value)?.stringContent()?.toJsonStringLiteral()
            value == "true" || value == "false" || value == "null" -> value
            NumberRegex.matchEntire(value) != null -> value
            DottedIdentifierRegex.matchEntire(value) != null ->
                DottedIdentifierRegex.matchEntire(value)!!.groupValues[1].toJsonStringLiteral()
            IdentifierRegex.matchEntire(value) != null -> value.toJsonStringLiteral()
            else -> value.toJsonStringLiteral()
        }
    }

    private fun String.splitTopLevelValues(): List<String> {
        val output = mutableListOf<String>()
        var index = 0
        while (index < length) {
            val start = indexOfNextNonSpace(index) ?: break
            val end = findEndOfValue(start)
            output += substring(start, end).trim()
            index = end + 1
        }
        return output.filter { it.isNotBlank() }
    }

    private fun String.toJsonStringLiteral(): String = buildString {
        append('\"')
        this@toJsonStringLiteral.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '\"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('\"')
    }

    private fun String.readableOptionName(): String =
        replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
            .replace('_', ' ')
            .trim()
            .ifBlank { this }

    private fun String.readPropertyValue(property: String): String? {
        val body = trim().removeOuter('{', '}')
        var index = 0
        while (index < body.length) {
            val keyRead = body.readKeyAtOrAfter(index) ?: return null
            val (key, colonIndex) = keyRead
            val valueStart = body.indexOfNextNonSpace(colonIndex + 1) ?: return null
            val valueEnd = body.findEndOfValue(valueStart)
            if (key == property) {
                return body.substring(valueStart, valueEnd).trim()
            }
            index = valueEnd + 1
        }
        return null
    }

    private fun String.readStringProperty(property: String): String? =
        readPropertyValue(property)?.let { StringLiteralRegex.find(it)?.stringContent() }

    private fun String.readBooleanProperty(property: String): Boolean? =
        readPropertyValue(property)?.trim()?.let { value ->
            when {
                value.startsWith("true") -> true
                value.startsWith("false") -> false
                else -> null
            }
        }

    private fun String.readStringSetProperty(property: String): Set<String> =
        readPropertyValue(property).extractRuleNames()

    private fun String.readNullableStringListProperty(property: String): List<String?>? {
        val value = readPropertyValue(property) ?: return null
        val arrayStart = value.indexOfNextNonSpace(0) ?: return null
        if (value[arrayStart] != '[') return null
        val arrayEnd = value.findMatching(arrayStart, '[', ']') ?: return null
        if (value.indexOfNextNonSpace(arrayEnd + 1) != null) return null
        val body = value.substring(arrayStart + 1, arrayEnd)
        val output = mutableListOf<String?>()
        var index = 0
        while (index < body.length) {
            val valueStart = body.indexOfNextNonSpace(index) ?: break
            if (body[valueStart] == ',') return null
            val valueEnd = body.findEndOfValue(valueStart)
            val raw = body.substring(valueStart, valueEnd)
            val start = raw.indexOfNextNonSpace(0) ?: return null
            output += if (raw.startsWith("null", start)) {
                if (raw.indexOfNextNonSpace(start + 4) != null) return null
                null
            } else {
                if (raw[start] != '"' && raw[start] != '\'') return null
                val end = raw.findStringEnd(start) ?: return null
                if (raw.indexOfNextNonSpace(end + 1) != null) return null
                StringLiteralRegex.matchEntire(raw.substring(start, end + 1))?.stringContent() ?: return null
            }
            index = valueEnd + 1
        }
        return output
    }

    private fun String.readUnsupportedExpressionMetadata(): Set<String> =
        listOf("AllowExpression", "ExpressionPrerequisite").filterTo(linkedSetOf()) { property ->
            val value = readPropertyValue(property)
            value != null && !value.isNullRuleValue() && readNullableStringListProperty(property) == null
        }

    private fun String.isNullRuleValue(): Boolean {
        val start = indexOfNextNonSpace(0) ?: return false
        return startsWith("null", start) && indexOfNextNonSpace(start + 4) == null
    }

    private fun String.readAnyStringSetProperty(property: String): Set<String> {
        val output = linkedSetOf<String>()
        val regex = Regex("\\b${Regex.escape(property)}\\s*:")
        regex.findAll(this).forEach { match ->
            val valueStart = indexOfNextNonSpace(match.range.last + 1) ?: return@forEach
            val valueEnd = findEndOfValue(valueStart)
            output += substring(valueStart, valueEnd).extractRuleNames()
        }
        return output
    }

    private fun String?.extractRuleNames(): Set<String> {
        if (this.isNullOrBlank()) return emptySet()
        val output = linkedSetOf<String>()
        StringLiteralRegex.findAll(this).forEach { match ->
            output += match.stringContent()
        }
        DottedIdentifierRegex.findAll(this).forEach { match ->
            output += match.groupValues[1]
        }
        return output.filterTo(linkedSetOf()) { it.isNotBlank() }
    }

    private fun String.readKeyAtOrAfter(startIndex: Int): Pair<String, Int>? {
        var index = startIndex
        while (index < length) {
            index = indexOfNextNonSpace(index) ?: return null
            if (this[index] == ',') {
                index++
                continue
            }
            val keyStart = index
            val key = when {
                this[index] == '"' || this[index] == '\'' -> {
                    val end = findStringEnd(index) ?: return null
                    index = end + 1
                    substring(keyStart + 1, end)
                }
                this[index].isIdentifierStart() -> {
                    index++
                    while (index < length && this[index].isIdentifierPart()) index++
                    substring(keyStart, index)
                }
                else -> {
                    index++
                    continue
                }
            }
            index = indexOfNextNonSpace(index) ?: return null
            if (index < length && this[index] == ':') return key to index
        }
        return null
    }

    private fun String.findEndOfValue(startIndex: Int): Int {
        var index = startIndex
        var braceDepth = 0
        var bracketDepth = 0
        var parenDepth = 0
        while (index < length) {
            when (val char = this[index]) {
                '"', '\'' -> index = findStringEnd(index) ?: return length
                '/' -> {
                    val skipped = skipComment(index)
                    if (skipped != null) {
                        index = skipped
                        continue
                    }
                }
                '{' -> braceDepth++
                '}' -> {
                    if (braceDepth == 0 && bracketDepth == 0 && parenDepth == 0) return index
                    braceDepth--
                }
                '[' -> bracketDepth++
                ']' -> {
                    if (bracketDepth == 0 && braceDepth == 0 && parenDepth == 0) return index
                    bracketDepth--
                }
                '(' -> parenDepth++
                ')' -> {
                    if (parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) return index
                    parenDepth--
                }
                ',' -> if (braceDepth == 0 && bracketDepth == 0 && parenDepth == 0) return index
            }
            index++
        }
        return length
    }

    private fun String.findMatching(startIndex: Int, open: Char, close: Char): Int? {
        var index = startIndex
        var depth = 0
        while (index < length) {
            when (val char = this[index]) {
                '"', '\'' -> index = findStringEnd(index) ?: return null
                '/' -> {
                    val skipped = skipComment(index)
                    if (skipped != null) {
                        index = skipped
                        continue
                    }
                }
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return null
    }

    private fun String.indexOfNextCodeChar(char: Char, startIndex: Int): Int? {
        var index = startIndex
        while (index < length) {
            when (this[index]) {
                '"', '\'' -> index = findStringEnd(index) ?: return null
                '/' -> {
                    val skipped = skipComment(index)
                    if (skipped != null) {
                        index = skipped
                        continue
                    }
                }
                char -> return index
            }
            index++
        }
        return null
    }

    private fun String.indexOfNextNonSpace(startIndex: Int): Int? {
        var index = startIndex
        while (index < length) {
            if (this[index].isWhitespace()) {
                index++
                continue
            }
            val skipped = if (this[index] == '/') skipComment(index) else null
            if (skipped != null) {
                index = skipped
                continue
            }
            return index
        }
        return null
    }

    private fun String.findStringEnd(startIndex: Int): Int? {
        val quote = this[startIndex]
        var index = startIndex + 1
        var escaped = false
        while (index < length) {
            val char = this[index]
            if (escaped) {
                escaped = false
            } else if (char == '\\') {
                escaped = true
            } else if (char == quote) {
                return index
            }
            index++
        }
        return null
    }

    private fun String.skipComment(startIndex: Int): Int? {
        if (startIndex + 1 >= length || this[startIndex] != '/') return null
        return when (this[startIndex + 1]) {
            '/' -> indexOf('\n', startIndex + 2).let { if (it >= 0) it + 1 else length }
            '*' -> indexOf("*/", startIndex + 2).let { if (it >= 0) it + 2 else length }
            else -> null
        }
    }

    private fun String.removeOuter(open: Char, close: Char): String {
        val trimmed = trim()
        return if (trimmed.length >= 2 && trimmed.first() == open && trimmed.last() == close) {
            trimmed.substring(1, trimmed.length - 1)
        } else {
            trimmed
        }
    }

    private fun Char.isIdentifierStart(): Boolean = this == '_' || this == '$' || isLetter()

    private fun Char.isIdentifierPart(): Boolean = isIdentifierStart() || isDigit()

    private companion object {
        val StandardVibratorOptions = listOf(
            ProtoVibratorOption("Off", "{ Mode: \"Off\", Intensity: -1, Effect: [\"Egged\"] }"),
            ProtoVibratorOption("Low", "{ Mode: \"Low\", Intensity: 0, Effect: [\"Egged\", \"Vibrating\"] }"),
            ProtoVibratorOption("Medium", "{ Mode: \"Medium\", Intensity: 1, Effect: [\"Egged\", \"Vibrating\"] }"),
            ProtoVibratorOption("High", "{ Mode: \"High\", Intensity: 2, Effect: [\"Egged\", \"Vibrating\"] }"),
            ProtoVibratorOption("Maximum", "{ Mode: \"Maximum\", Intensity: 3, Effect: [\"Egged\", \"Vibrating\"] }"),
        )
        val AdvancedVibratorOptions = listOf(
            ProtoVibratorOption("Random", "{ Mode: \"Random\", Intensity: 0, Effect: [\"Egged\"] }"),
            ProtoVibratorOption("Escalate", "{ Mode: \"Escalate\", Intensity: 0, Effect: [\"Egged\", \"Vibrating\"] }"),
            ProtoVibratorOption("Tease", "{ Mode: \"Tease\", Intensity: 0, Effect: [\"Egged\"] }"),
            ProtoVibratorOption("Deny", "{ Mode: \"Deny\", Intensity: 0, Effect: [\"Egged\", \"Edged\"] }"),
            ProtoVibratorOption("Edge", "{ Mode: \"Edge\", Intensity: 0, Effect: [\"Egged\", \"Vibrating\", \"Edged\"] }"),
        )

        val StringLiteralRegex = Regex(""""((?:\\.|[^\"])*)"|'((?:\\.|[^'])*)'""")
        val DottedIdentifierRegex = Regex("\\b[A-Za-z_$][A-Za-z0-9_$]*\\.([A-Za-z_$][A-Za-z0-9_$]*)")
        val IdentifierRegex = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        val NumberRegex = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
        val IntRegex = Regex("-?(?:0|[1-9][0-9]*)")
        val ZoneRectRegex = Regex(
            """\[\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*]""",
        )

        fun MatchResult.stringContent(): String = groups[1]?.value ?: groups[2]?.value.orEmpty()
    }
}
