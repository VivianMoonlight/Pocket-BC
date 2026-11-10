package dev.vivian.mobilebc.domain.bcrules

import dev.vivian.mobilebc.core.model.BcEchoDrawRelation
import dev.vivian.mobilebc.core.model.BcEchoLeashRole

/**
 * Permission-oriented BC rule catalog.
 *
 * This is intentionally richer than the display catalog introduced in M3C. The catalog is still a
 * static text extraction of BC JavaScript resources; it does not execute Inventory screens.
 */
data class BcRuleCatalog(
    val version: String,
    val groups: Map<String, BcGroupRule>,
    val activities: Map<String, BcActivityRule>,
    val assets: Map<BcAssetKey, BcAssetRule>,
    val extendedItems: Map<BcAssetKey, BcExtendedItemRule> = emptyMap(),
    val health: BcCatalogHealthReport = BcCatalogHealthReport.Empty,
    val poses: Map<String, BcPoseRule> = emptyMap(),
) {
    companion object {
        val Empty = BcRuleCatalog(
            version = "unknown",
            groups = emptyMap(),
            activities = emptyMap(),
            assets = emptyMap(),
            extendedItems = emptyMap(),
        )
    }

    fun group(name: String): BcGroupRule? = groups[name]

    fun activity(name: String): BcActivityRule? = activities[name]

    fun pose(name: String): BcPoseRule? = poses[name]

    fun asset(group: String, name: String): BcAssetRule? =
        assets[BcAssetKey(group = group, name = name)]

    fun extendedItem(group: String, name: String): BcExtendedItemRule? =
        extendedItems[BcAssetKey(group = group, name = name)]
}

data class BcCatalogHealthReport(
    val nativeGroupCount: Int,
    val nativeActivityCount: Int,
    val nativeAssetCount: Int,
    val echoNativeCollisions: Set<String> = emptySet(),
    val issues: List<String> = emptyList(),
) {
    val isCompatible: Boolean get() = issues.isEmpty()

    companion object {
        val Empty = BcCatalogHealthReport(0, 0, 0)

        fun fromNative(catalog: BcRuleCatalog): BcCatalogHealthReport {
            val issues = buildList {
                if (catalog.groups.isEmpty()) add("No native asset groups were parsed.")
                if (catalog.activities.isEmpty()) add("No native activities were parsed.")
                if (catalog.assets.isEmpty()) add("No native assets were parsed.")
            }
            return BcCatalogHealthReport(
                nativeGroupCount = catalog.groups.size,
                nativeActivityCount = catalog.activities.size,
                nativeAssetCount = catalog.assets.size,
                issues = issues,
            )
        }
    }

    fun comparedWith(previous: BcCatalogHealthReport?): BcCatalogHealthReport {
        previous ?: return this
        val regressions = buildList {
            fun checkDrop(label: String, current: Int, baseline: Int) {
                if (baseline >= 10 && current * 2 < baseline) {
                    add("$label count dropped from $baseline to $current.")
                }
            }
            checkDrop("Native group", nativeGroupCount, previous.nativeGroupCount)
            checkDrop("Native activity", nativeActivityCount, previous.nativeActivityCount)
            checkDrop("Native asset", nativeAssetCount, previous.nativeAssetCount)
        }
        return copy(issues = issues + regressions)
    }
}

data class BcGroupRule(
    val name: String,
    val category: String? = null,
    val clothing: Boolean = false,
    val allowNone: Boolean = true,
    val isItem: Boolean = name.startsWith("Item"),
    val arousalZoneId: Int? = null,
    val mirrorActivitiesFrom: String? = null,
    val zones: List<BcInteractionZoneRect> = emptyList(),
    val allowExpression: List<String?>? = null,
    val expressionPrerequisites: List<String?>? = null,
    val unsupportedExpressionMetadata: Set<String> = emptySet(),
    val effects: Set<String> = emptySet(),
    val unsupportedSpeechMetadata: Set<String> = emptySet(),
    val mirrorGroup: String? = null,
    val defaultAssetName: String? = null,
    val colorSchema: List<String> = listOf("Default"),
    val defaultColor: String = "Default",
    val bodyCosplay: Boolean = false,
    val underwear: Boolean = false,
    val allowCustomize: Boolean = true,
    val allowColorize: Boolean = true,
    val isDefault: Boolean = true,
    val unsupportedWardrobeMetadata: Set<String> = emptySet(),
) {
    val kind: BcAppearanceGroupKind
        get() = BcAppearanceGroupKind.from(
            name = name,
            category = category,
            clothing = clothing,
            isItem = isItem,
        )
}

data class BcInteractionZoneRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

data class BcPoseRule(
    val name: String,
    val category: String? = null,
    val allowMenu: Boolean = false,
    val allowMenuTransient: Boolean = false,
)

/**
 * Native BC uses the same appearance item/wire update for restraints and wardrobe assets, while
 * the group metadata defines which user-facing workflow applies.
 */
enum class BcAppearanceGroupKind {
    Item,
    Clothing,
    Appearance;

    companion object {
        fun from(
            name: String,
            category: String?,
            clothing: Boolean,
            isItem: Boolean,
        ): BcAppearanceGroupKind = when {
            category.equals("Item", ignoreCase = true) -> Item
            category.equals("Appearance", ignoreCase = true) && clothing -> Clothing
            category.equals("Appearance", ignoreCase = true) -> Appearance
            clothing -> Clothing
            isItem || name.startsWith("Item", ignoreCase = true) -> Item
            else -> Appearance
        }
    }
}

enum class BcActivitySource(val label: String?) {
    Native(null),
    Echo("Echo"),
}

data class BcActivityRule(
    val name: String,
    val targetGroups: Set<String> = emptySet(),
    val selfTargetGroups: Set<String> = emptySet(),
    val prerequisites: Set<String> = emptySet(),
    val makeSound: Boolean = false,
    val stimulationAction: String? = null,
    val activityId: Int? = null,
    val source: BcActivitySource = BcActivitySource.Native,
    val displayName: String = name,
    val chatText: String? = null,
    val chatTextByTargetGroup: Map<String, String> = emptyMap(),
    val selfChatText: String? = null,
    val selfChatTextByTargetGroup: Map<String, String> = emptyMap(),
    val stateRequirements: List<BcActivityStateRequirement> = emptyList(),
    val effects: List<BcActivityEffect> = emptyList(),
) {
    fun chatTextFor(targetGroup: String, isSelfTarget: Boolean): String? =
        if (isSelfTarget) {
            selfChatTextByTargetGroup[targetGroup] ?: selfChatText
        } else {
            chatTextByTargetGroup[targetGroup] ?: chatText
        }
}

sealed interface BcActivityEffect {
    data class Pose(val poses: List<String>) : BcActivityEffect
    data class Expression(val group: String, val name: String?) : BcActivityEffect
    data class EchoPairedItem(
        val group: String,
        val name: String,
        val actorRelation: BcEchoDrawRelation,
        val actorLeash: BcEchoLeashRole,
        val targetGroup: String = group,
        val targetName: String = name,
        val targetRelation: BcEchoDrawRelation = actorRelation.opposite(),
        val targetLeash: BcEchoLeashRole = actorLeash.opposite(),
        val targetRequiresAllowItem: Boolean = true,
    ) : BcActivityEffect
}

private fun BcEchoDrawRelation.opposite(): BcEchoDrawRelation = when (this) {
    BcEchoDrawRelation.PreviousCharacter -> BcEchoDrawRelation.NextCharacter
    BcEchoDrawRelation.NextCharacter -> BcEchoDrawRelation.PreviousCharacter
}

private fun BcEchoLeashRole.opposite(): BcEchoLeashRole = when (this) {
    BcEchoLeashRole.Lead -> BcEchoLeashRole.Follow
    BcEchoLeashRole.Follow -> BcEchoLeashRole.Lead
}

enum class BcActivityParticipant {
    Actor,
    Target,
}

/** Structured runtime conditions extracted from Echo prerequisite callbacks. */
sealed interface BcActivityStateRequirement {
    val participant: BcActivityParticipant
    val group: String

    data class GroupEmpty(
        override val participant: BcActivityParticipant,
        override val group: String,
    ) : BcActivityStateRequirement

    data class GroupContainsAny(
        override val participant: BcActivityParticipant,
        override val group: String,
        val names: Set<String>,
    ) : BcActivityStateRequirement

    data class GroupEmptyOrContainsAny(
        override val participant: BcActivityParticipant,
        override val group: String,
        val names: Set<String>,
    ) : BcActivityStateRequirement
}

data class BcAssetKey(
    val group: String,
    val name: String,
)

data class BcAssetRule(
    val group: String,
    val name: String,
    val effects: Set<String> = emptySet(),
    val blocks: Set<String> = emptySet(),
    val expose: Set<String> = emptySet(),
    val allowActivity: Set<String> = emptySet(),
    val allowActivityOn: Set<String> = emptySet(),
    val prerequisites: Set<String> = emptySet(),
    /** Runtime asset values after BC's AssetParsePosePrerequisite normalization. */
    val allowActivePose: Set<String> = emptySet(),
    val setPose: Set<String> = emptySet(),
    val ownerOnly: Boolean = false,
    val loverOnly: Boolean = false,
    val familyOnly: Boolean = false,
    val enable: Boolean = true,
    val allowRemoveExclusive: Boolean = false,
    val selfUnlock: Boolean = true,
    val exclusiveUnlock: Boolean = false,
    val isLock: Boolean = false,
    val isRestraint: Boolean = false,
    val allowLock: Boolean = false,
    val removeTimerSeconds: Int = 0,
    val maxTimerSeconds: Int = 0,
    val allowExpression: List<String?>? = null,
    val expressionPrerequisites: List<String?>? = null,
    val unsupportedExpressionMetadata: Set<String> = emptySet(),
    val allowedEffects: Set<String> = emptySet(),
    val unsupportedSpeechMetadata: Set<String> = emptySet(),
    /** Null means the price has not been verified; it must not be treated as a free asset. */
    val value: Double? = null,
    val inventoryId: Int? = null,
    val buyGroup: String? = null,
    val availableLocations: Set<String> = emptySet(),
    val bodyCosplay: Boolean = false,
    /** Native AssetParseDefaultColor output, after colorable layers have been indexed. */
    val defaultColor: List<String>? = null,
    val unsupportedWardrobeMetadata: Set<String> = emptySet(),
)

enum class BcExtendedItemArchetype(val wireValue: String) {
    Typed("typed"),
    Modular("modular"),
    Vibrating("vibrating"),
    VariableHeight("variableheight"),
    Text("text"),
    NoArch("noarch"),
    Unknown("unknown");

    companion object {
        fun from(raw: String?): BcExtendedItemArchetype = when (raw?.trim()?.lowercase()) {
            "typed" -> Typed
            "modular" -> Modular
            "vibrating" -> Vibrating
            "variableheight" -> VariableHeight
            "text" -> Text
            "noarch" -> NoArch
            else -> Unknown
        }
    }
}

enum class BcExtendedItemSupportLevel {
    /** The mobile client can build a usable propertyJson without executing Inventory JS. */
    Supported,

    /** The mobile client can build a conservative base property, but some subscreen/script details are skipped. */
    Partial,

    /** The item is recognized, but the mobile client cannot safely build a propertyJson for it yet. */
    Unsupported,
}

enum class BcExtendedItemOptionKind {
    Typed,
    Modular,
    Vibrating,
    Default,
    Unsupported,
}

/**
 * Parsed, non-executable extended-item metadata.
 *
 * The mobile client does not execute BC's Inventory screens. Instead it parses the static
 * typed/modular/vibrating definitions and prebuilds conservative JSON property payloads containing
 * the TypeRecord shape used by BC's TypedItem.js, ModularItem.js, and VibratorMode.js.
 */
data class BcExtendedItemRule(
    val group: String,
    val name: String,
    val archetype: BcExtendedItemArchetype,
    val recordKey: String,
    val typedOptions: List<BcExtendedItemOptionRule> = emptyList(),
    val modules: List<BcExtendedItemModuleRule> = emptyList(),
    val defaultPropertyJson: String? = null,
    val defaultDifficulty: Int? = null,
    val baselinePropertyJson: String? = null,
    val assetPropertyJson: String? = null,
    val supportLevel: BcExtendedItemSupportLevel = BcExtendedItemSupportLevel.Supported,
    val unsupportedReason: String? = null,
    val warnings: List<String> = emptyList(),
    val textFields: Map<String, Int> = emptyMap(),
) {
    val isConfigurable: Boolean
        get() = typedOptions.any { it.propertyJson != null } || modules.any { module ->
            module.options.any { it.propertyJson != null }
        }

    val statusMessage: String?
        get() = unsupportedReason ?: warnings.firstOrNull()
}

data class BcExtendedItemOptionRule(
    val name: String,
    val index: Int,
    val displayName: String = name,
    val propertyJson: String? = null,
    val difficulty: Int? = null,
    val kind: BcExtendedItemOptionKind = BcExtendedItemOptionKind.Typed,
    val propertyKeys: Set<String> = emptySet(),
    val propertySummary: String? = null,
    val warnings: List<String> = emptyList(),
    val supportLevel: BcExtendedItemSupportLevel = BcExtendedItemSupportLevel.Supported,
    val allowLock: Boolean? = null,
)

data class BcExtendedItemModuleRule(
    val name: String,
    val key: String,
    val options: List<BcExtendedItemModuleOptionRule>,
)

data class BcExtendedItemModuleOptionRule(
    val name: String,
    val index: Int,
    val displayName: String = name,
    val propertyJson: String? = null,
    /** This module's own static Property, before combining the other selected modules. */
    val modulePropertyJson: String = "{}",
    val difficulty: Int? = null,
    val propertyKeys: Set<String> = emptySet(),
    val propertySummary: String? = null,
    val warnings: List<String> = emptyList(),
    val supportLevel: BcExtendedItemSupportLevel = BcExtendedItemSupportLevel.Supported,
    val allowLock: Boolean? = null,
)

data class BcExtendedItemCoverageReport(
    val total: Int,
    val byArchetype: Map<BcExtendedItemArchetype, Int>,
    val supported: Int,
    val partial: Int,
    val unsupported: Int,
    val configurable: Int,
    val typedOptions: Int,
    val modules: Int,
    val moduleOptions: Int,
) {
    fun toDisplayText(): String = buildString {
        appendLine("Extended item coverage:")
        appendLine("Total parsed: $total")
        BcExtendedItemArchetype.entries.forEach { archetype ->
            val count = byArchetype[archetype] ?: 0
            if (count > 0) appendLine("${archetype.name}: $count")
        }
        appendLine("Supported: $supported")
        appendLine("Partial: $partial")
        appendLine("Unsupported: $unsupported")
        appendLine("Configurable: $configurable")
        appendLine("Typed/Vibrating options: $typedOptions")
        appendLine("Modules: $modules")
        appendLine("Module options: $moduleOptions")
    }

    companion object {
        fun from(catalog: BcRuleCatalog): BcExtendedItemCoverageReport {
            val values = catalog.extendedItems.values
            return BcExtendedItemCoverageReport(
                total = values.size,
                byArchetype = values.groupingBy { it.archetype }.eachCount(),
                supported = values.count { it.supportLevel == BcExtendedItemSupportLevel.Supported },
                partial = values.count { it.supportLevel == BcExtendedItemSupportLevel.Partial },
                unsupported = values.count { it.supportLevel == BcExtendedItemSupportLevel.Unsupported },
                configurable = values.count { it.isConfigurable },
                typedOptions = values.sumOf { it.typedOptions.size },
                modules = values.sumOf { it.modules.size },
                moduleOptions = values.sumOf { rule -> rule.modules.sumOf { it.options.size } },
            )
        }
    }
}
