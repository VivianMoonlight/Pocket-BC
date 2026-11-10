package dev.vivian.mobilebc.domain.bcpermission

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.domain.bcitemproperty.modificationOptionsPage
import dev.vivian.mobilebc.domain.bcitemproperty.wearOptionsPage
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcitemproperty.BcAfcHeartLock
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerState
import dev.vivian.mobilebc.domain.bcitemproperty.textValues
import dev.vivian.mobilebc.domain.bcitemproperty.validBcItemText
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel

enum class BcItemManagementOperation {
    Wear,
    Replace,
    Modify,
    Remove,
    Unlock,
    Lock,
}

enum class BcItemManagementPermissionStatus {
    Allowed,
    NeedsServerCheck,
    Denied,
    Informational,
}

enum class BcItemManagementAvailabilityReason {
    SlotAlreadyOccupied,
    SlotEmpty,
    ItemAlreadyWorn,
    ItemNoLongerWorn,
    NoConfigurableOptions,
    ExtendedItemUnsupported,
    CurrentSelection,
}

data class BcItemManagementPageRequest(
    val context: BcPermissionContext,
    val group: String,
    val operation: BcItemManagementOperation,
    /**
     * Optional selected catalog/current item.
     *
     * - Wear + null: page catalog item rows in an empty [group].
     * - Wear + value: page wear options for that extended item.
     * - Replace + null: page catalog item rows for replacing the current item in [group].
     * - Replace + value: page wear options for the replacement item.
     * - Modify + null: page options for the currently worn item in [group].
     * - Modify + value: page options for the currently worn item, requiring the name to match.
     */
    val itemName: String? = null,
    val query: String? = null,
    val itemDisplayNames: Map<String, String> = emptyMap(),
    val offset: Int = 0,
    val limit: Int = 50,
    val includeDenied: Boolean = false,
    val includeNeedsServerCheck: Boolean = true,
) {
    init {
        require(group.isNotBlank()) { "group must not be blank." }
        require(offset >= 0) { "offset must be non-negative." }
        require(limit >= 0) { "limit must be non-negative." }
    }
}

data class BcItemManagementPageResult(
    val entries: List<BcItemManagementEntry>,
    val offset: Int,
    val nextOffset: Int?,
    val totalKnownEntries: Int,
    val scannedEntryCount: Int,
) {
    val hasMore: Boolean get() = nextOffset != null

    companion object {
        fun empty(offset: Int = 0): BcItemManagementPageResult = BcItemManagementPageResult(
            entries = emptyList(),
            offset = offset,
            nextOffset = null,
            totalKnownEntries = 0,
            scannedEntryCount = 0,
        )
    }
}

data class BcItemManagementEntry(
    val operation: BcItemManagementOperation,
    val group: String,
    val itemName: String?,
    val currentItemName: String? = null,
    val label: String,
    val subtitle: String? = null,
    val propertyJson: String? = null,
    val difficulty: Int? = null,
    val selected: Boolean = false,
    val extendedKind: String? = null,
    val extendedStatusMessage: String? = null,
    val candidate: BcInteractionCandidate? = null,
    val executable: BcExecutableInteraction? = null,
    val permissionStatus: BcItemManagementPermissionStatus,
    val enabled: Boolean,
    val permissionMessage: String? = null,
    val permissionReasonCodes: List<BcPermissionReasonCode> = emptyList(),
    val availabilityReason: BcItemManagementAvailabilityReason? = null,
    val warnings: List<String> = emptyList(),
    val requiresTextInput: Boolean = false,
    val requiresUnlockInput: Boolean = false,
)

class DefaultBcItemManagementQuery(
    private val permissionEngine: DefaultBcInteractionPermissionEngine = DefaultBcInteractionPermissionEngine(),
) {
    fun textEditEntry(context: BcPermissionContext, group: String): BcItemManagementEntry? {
        val item = context.target.appearance.firstOrNull { it.group.sameItemToken(group) } ?: return null
        val extended = context.rules.extendedItem(item.group, item.name)?.takeIf {
            it.textFields.isNotEmpty() && it.supportLevel != BcExtendedItemSupportLevel.Unsupported } ?: return null
        // Valid prospective input lets users repair old invalid text without executing a mode change.
        val prospective = extended.textValues(item.propertyJson).mapValues { (key, value) ->
            value.takeIf { validBcItemText(it, extended.textFields.getValue(key)) }.orEmpty()
        }
        val candidate = BcInteractionCandidate.ModifyItem(context.target.memberNumber, item.group, item.name,
            BcItemPropertyJson.overlayStrings(item.propertyJson, prospective))
        return entryFromDecision(BcItemManagementPageRequest(context, group, BcItemManagementOperation.Modify),
            BcItemManagementOperation.Modify, item.group, item.name, item.name, "Edit text", candidate = candidate)
            .copy(requiresTextInput = true, candidate = null, executable = null)
    }

    fun timerControlEntry(context: BcPermissionContext, group: String, change: BcLockTimerChange): BcItemManagementEntry? {
        val item = context.target.appearance.firstOrNull { it.group.sameItemToken(group) } ?: return null
        val timer = context.lockTimerState(item) ?: return null
        return entryFromDecision(BcItemManagementPageRequest(context, group, BcItemManagementOperation.Modify),
            BcItemManagementOperation.Modify, item.group, item.name, item.name, item.name,
            candidate = BcInteractionCandidate.ChangeLockTimer(context.target.memberNumber, item.group, item.name, timer.lockName, change))
            .copy(candidate = null, executable = null)
    }

    fun credentialUnlockEntry(context: BcPermissionContext, group: String): BcItemManagementEntry? {
        val item = context.target.appearance.firstOrNull { it.group.sameItemToken(group) } ?: return null
        val lock = item.propertyJson.readBcStringProperty("LockedBy") ?: return null
        val kind = BcLockInputKind.forLock(lock) ?: return null
        val expected = item.propertyJson.readBcStringProperty(kind.propertyKey) ?: return null
        return entryFromDecision(BcItemManagementPageRequest(context, group, BcItemManagementOperation.Unlock),
            BcItemManagementOperation.Unlock, item.group, item.name, item.name, item.name,
            candidate = BcInteractionCandidate.UnlockItem(context.target.memberNumber, item.group, item.name, lock, BcLockCredential(expected)))
            .copy(requiresUnlockInput = true, candidate = null, executable = null)
    }

    fun lockConfigurationEntry(context: BcPermissionContext, group: String): BcItemManagementEntry? {
        val item = context.target.appearance.firstOrNull { it.group.sameItemToken(group) } ?: return null
        if (BcAfcHeartLock.identity(context.target, item) != null) return null
        val lock = item.propertyJson.readBcStringProperty("LockedBy") ?: return null
        val configuration = when (BcLockConfigurationMode.forLock(item.propertyJson, context.actor.memberNumber)) {
            BcLockConfigurationMode.InitializePassword -> BcLockConfiguration.InitializePassword(BcLockCredential(""), "")
            BcLockConfigurationMode.ChangeCombination -> {
                val expected = item.propertyJson.readBcStringProperty("CombinationNumber") ?: return null
                BcLockConfiguration.ChangeCombination(BcLockCredential(expected), BcLockCredential(expected))
            }
            BcLockConfigurationMode.UpdateKeyholders -> BcLockConfiguration.UpdateKeyholders(dev.vivian.mobilebc.domain.bcitemproperty.highSecurityKeyholderText(item.propertyJson))
            null -> return null
        }
        return entryFromDecision(BcItemManagementPageRequest(context, group, BcItemManagementOperation.Modify),
            BcItemManagementOperation.Modify, item.group, item.name, item.name, item.name,
            candidate = BcInteractionCandidate.ConfigureLock(context.target.memberNumber, item.group, item.name, lock, configuration))
            .copy(candidate = null, executable = null)
    }

    /**
     * Lazily evaluates only the requested item page instead of expanding every item and option.
     */
    fun page(request: BcItemManagementPageRequest): BcItemManagementPageResult = when (request.operation) {
        BcItemManagementOperation.Wear -> wearPage(request)
        BcItemManagementOperation.Replace -> replacePage(request)
        BcItemManagementOperation.Modify -> modifyPage(request)
        BcItemManagementOperation.Remove -> removePage(request)
        BcItemManagementOperation.Unlock -> unlockPage(request)
        BcItemManagementOperation.Lock -> lockPage(request)
    }

    private fun wearPage(request: BcItemManagementPageRequest): BcItemManagementPageResult {
        val currentItem = request.context.target.appearance.firstOrNull { it.group.sameItemToken(request.group) }
        if (currentItem != null) {
            return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Wear,
                    group = request.group,
                    itemName = request.itemName,
                    currentItemName = currentItem.name,
                    label = request.group.toHumanLabel(),
                    message = "A current item is already worn in this group. Use Replace to swap it.",
                    availabilityReason = BcItemManagementAvailabilityReason.SlotAlreadyOccupied,
                ),
            )
        }

        val selectedItemName = request.itemName?.trim()?.takeIf { it.isNotBlank() }
        return if (selectedItemName == null) {
            catalogItemPage(request) { buildWearCatalogEntry(request, it) }
        } else {
            wearOptionPage(request, selectedItemName)
        }
    }

    private fun catalogItemPage(
        request: BcItemManagementPageRequest,
        excludedItemName: String? = null,
        buildEntry: (BcAssetRule) -> BcItemManagementEntry?,
    ): BcItemManagementPageResult {
        if (request.limit == 0) return BcItemManagementPageResult.empty(request.offset)
        val query = request.query.normalizedQuery()
        val afcLocks = if (request.operation == BcItemManagementOperation.Lock) listOf(BcAfcHeartLock.asset) else emptyList()
        val allAssets = (request.context.rules.assets.values + afcLocks)
            .asSequence()
            .distinctBy { it.group to it.name }
            .filter { it.group.sameItemToken(if (request.operation == BcItemManagementOperation.Lock) "ItemMisc" else request.group) }
            .filter { request.operation != BcItemManagementOperation.Lock || it.isLock }
            .filterNot { excludedItemName != null && it.name.sameItemToken(excludedItemName) }
            .filter { asset -> query == null || asset.name.contains(query, ignoreCase = true) ||
                request.itemLabel(asset.name).contains(query, ignoreCase = true) ||
                asset.name == BcAfcHeartLock.Name && listOf("Liko", "AFC", "心形锁", "心形鎖", "拓展恋人", "拓展戀人").any { it.contains(query, true) } }
            .sortedBy { it.name.lowercase() }
            .toList()

        val entries = mutableListOf<BcItemManagementEntry>()
        var scanned = 0
        var index = request.offset
        while (index < allAssets.size && entries.size < request.limit) {
            val asset = allAssets[index]
            scanned++
            buildEntry(asset)?.let { entry ->
                if (entry.shouldInclude(request)) entries += entry
            }
            index++
        }

        return BcItemManagementPageResult(
            entries = entries,
            offset = request.offset,
            nextOffset = index.takeIf { it < allAssets.size },
            totalKnownEntries = allAssets.size,
            scannedEntryCount = scanned,
        )
    }

    private fun buildWearCatalogEntry(
        request: BcItemManagementPageRequest,
        asset: BcAssetRule,
    ): BcItemManagementEntry? {
        val extended = request.context.rules.extendedItem(asset.group, asset.name)
        if (extended?.supportLevel == BcExtendedItemSupportLevel.Unsupported) {
            return disabledEntry(
                operation = BcItemManagementOperation.Wear,
                group = asset.group,
                itemName = asset.name,
                label = request.itemLabel(asset.name),
                subtitle = extended.unsupportedReason,
                extendedKind = extended.archetype.wireValue,
                extendedStatusMessage = extended.statusMessage,
                message = extended.unsupportedReason ?: "This extended item is not supported by the mobile configurator yet.",
                availabilityReason = BcItemManagementAvailabilityReason.ExtendedItemUnsupported,
            )
        }

        val candidate = BcInteractionCandidate.WearItem(
            targetMemberNumber = request.context.target.memberNumber,
            group = asset.group,
            itemName = asset.name,
            propertyJson = extended?.defaultPropertyJson,
            difficulty = extended?.defaultDifficulty,
        )
        return entryFromDecision(
            request = request,
            operation = BcItemManagementOperation.Wear,
            group = asset.group,
            itemName = asset.name,
            currentItemName = currentItemName(request.context, asset.group),
            label = request.itemLabel(asset.name),
            subtitle = null,
            propertyJson = extended?.defaultPropertyJson,
            difficulty = extended?.defaultDifficulty,
            extendedKind = extended?.archetype?.wireValue,
            extendedStatusMessage = extended?.statusMessage,
            warnings = extended?.warnings.orEmpty(),
            candidate = candidate,
        )
    }

    private fun wearOptionPage(
        request: BcItemManagementPageRequest,
        itemName: String,
    ): BcItemManagementPageResult {
        val extended = request.context.rules.extendedItem(request.group, itemName)
        if (extended == null) return BcItemManagementPageResult.empty(request.offset)
        if (extended.supportLevel == BcExtendedItemSupportLevel.Unsupported) {
            val entry = disabledEntry(
                operation = BcItemManagementOperation.Wear,
                group = request.group,
                itemName = itemName,
                label = request.itemLabel(itemName),
                extendedKind = extended.archetype.wireValue,
                extendedStatusMessage = extended.statusMessage,
                message = extended.unsupportedReason ?: "This extended item is not supported by the mobile configurator yet.",
                availabilityReason = BcItemManagementAvailabilityReason.ExtendedItemUnsupported,
            )
            return singleEntryResult(request, entry)
        }

        val optionPage = extended.wearOptionsPage(
            offset = request.offset,
            limit = request.limit,
            query = request.query,
        )
        val entries = optionPage.options.mapNotNull { option ->
            val candidate = BcInteractionCandidate.WearItem(
                targetMemberNumber = request.context.target.memberNumber,
                group = request.group,
                itemName = itemName,
                propertyJson = option.propertyJson,
                difficulty = option.difficulty,
            )
            entryFromDecision(
                request = request,
                operation = BcItemManagementOperation.Wear,
                group = request.group,
                itemName = itemName,
                currentItemName = currentItemName(request.context, request.group),
                label = option.label,
                subtitle = option.subtitle,
                propertyJson = option.propertyJson,
                difficulty = option.difficulty,
                // Wear options represent choices for a new item, not the current worn selection.
                // Do not mark the default option as selected, otherwise the UI disables the
                // perfectly valid default wear update.
                selected = false,
                extendedKind = extended.archetype.wireValue,
                extendedStatusMessage = extended.statusMessage,
                warnings = option.warnings + extended.warnings,
                candidate = candidate,
            ).takeIf { it.shouldInclude(request) }
        }
        return BcItemManagementPageResult(
            entries = entries,
            offset = request.offset,
            nextOffset = optionPage.nextOffset,
            totalKnownEntries = optionPage.totalKnownOptions,
            scannedEntryCount = optionPage.options.size,
        )
    }

    private fun replacePage(request: BcItemManagementPageRequest): BcItemManagementPageResult {
        val currentItem = request.context.target.appearance.firstOrNull { it.group.sameItemToken(request.group) }
            ?: return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Replace,
                    group = request.group,
                    itemName = request.itemName,
                    label = request.group.toHumanLabel(),
                    message = "No current item is worn in this group.",
                    availabilityReason = BcItemManagementAvailabilityReason.SlotEmpty,
                ),
            )
        val selectedItemName = request.itemName?.trim()?.takeIf { it.isNotBlank() }
        return if (selectedItemName == null) {
            catalogItemPage(request, currentItem.name) { buildReplaceCatalogEntry(request, currentItem, it) }
        } else {
            replaceOptionPage(request, currentItem, selectedItemName)
        }
    }

    private fun buildReplaceCatalogEntry(
        request: BcItemManagementPageRequest,
        currentItem: BcCharacterItem,
        asset: BcAssetRule,
    ): BcItemManagementEntry? {
        val extended = request.context.rules.extendedItem(asset.group, asset.name)
        if (extended?.supportLevel == BcExtendedItemSupportLevel.Unsupported) {
            return disabledEntry(
                operation = BcItemManagementOperation.Replace,
                group = asset.group,
                itemName = asset.name,
                currentItemName = currentItem.name,
                label = "Replace with ${request.itemLabel(asset.name)}",
                subtitle = extended.unsupportedReason,
                extendedKind = extended.archetype.wireValue,
                extendedStatusMessage = extended.statusMessage,
                message = extended.unsupportedReason ?: "This extended item is not supported by the mobile configurator yet.",
                availabilityReason = BcItemManagementAvailabilityReason.ExtendedItemUnsupported,
            )
        }

        val candidate = BcInteractionCandidate.WearItem(
            targetMemberNumber = request.context.target.memberNumber,
            group = asset.group,
            itemName = asset.name,
            propertyJson = extended?.defaultPropertyJson,
            difficulty = extended?.defaultDifficulty,
        )
        return entryFromDecision(
            request = request,
            operation = BcItemManagementOperation.Replace,
            group = asset.group,
            itemName = asset.name,
            currentItemName = currentItem.name,
            label = "Replace with ${request.itemLabel(asset.name)}",
            subtitle = null,
            propertyJson = extended?.defaultPropertyJson,
            difficulty = extended?.defaultDifficulty,
            extendedKind = extended?.archetype?.wireValue,
            extendedStatusMessage = extended?.statusMessage,
            warnings = extended?.warnings.orEmpty(),
            candidate = candidate,
        )
    }

    private fun replaceOptionPage(
        request: BcItemManagementPageRequest,
        currentItem: BcCharacterItem,
        itemName: String,
    ): BcItemManagementPageResult {
        if (itemName.sameItemToken(currentItem.name)) {
            return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Replace,
                    group = request.group,
                    itemName = itemName,
                    currentItemName = currentItem.name,
                    label = request.itemLabel(itemName),
                    message = "This item is already worn. Use Modify to configure it.",
                    availabilityReason = BcItemManagementAvailabilityReason.ItemAlreadyWorn,
                ),
            )
        }
        val extended = request.context.rules.extendedItem(request.group, itemName)
        if (extended == null) return BcItemManagementPageResult.empty(request.offset)
        if (extended.supportLevel == BcExtendedItemSupportLevel.Unsupported) {
            val entry = disabledEntry(
                operation = BcItemManagementOperation.Replace,
                group = request.group,
                itemName = itemName,
                currentItemName = currentItem.name,
                label = "Replace with ${request.itemLabel(itemName)}",
                extendedKind = extended.archetype.wireValue,
                extendedStatusMessage = extended.statusMessage,
                message = extended.unsupportedReason ?: "This extended item is not supported by the mobile configurator yet.",
                availabilityReason = BcItemManagementAvailabilityReason.ExtendedItemUnsupported,
            )
            return singleEntryResult(request, entry)
        }

        val optionPage = extended.wearOptionsPage(
            offset = request.offset,
            limit = request.limit,
            query = request.query,
        )
        val entries = optionPage.options.mapNotNull { option ->
            val candidate = BcInteractionCandidate.WearItem(
                targetMemberNumber = request.context.target.memberNumber,
                group = request.group,
                itemName = itemName,
                propertyJson = option.propertyJson,
                difficulty = option.difficulty,
            )
            entryFromDecision(
                request = request,
                operation = BcItemManagementOperation.Replace,
                group = request.group,
                itemName = itemName,
                currentItemName = currentItem.name,
                label = option.label,
                subtitle = option.subtitle,
                propertyJson = option.propertyJson,
                difficulty = option.difficulty,
                selected = false,
                extendedKind = extended.archetype.wireValue,
                extendedStatusMessage = extended.statusMessage,
                warnings = option.warnings + extended.warnings,
                candidate = candidate,
            ).takeIf { it.shouldInclude(request) }
        }
        return BcItemManagementPageResult(
            entries = entries,
            offset = request.offset,
            nextOffset = optionPage.nextOffset,
            totalKnownEntries = optionPage.totalKnownOptions,
            scannedEntryCount = optionPage.options.size,
        )
    }

    private fun modifyPage(request: BcItemManagementPageRequest): BcItemManagementPageResult {
        val currentItem = request.context.target.appearance.firstOrNull { it.group.sameItemToken(request.group) }
            ?: return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Modify,
                    group = request.group,
                    itemName = request.itemName,
                    label = request.group.toHumanLabel(),
                    message = "No current item is worn in this group.",
                    availabilityReason = BcItemManagementAvailabilityReason.SlotEmpty,
                ),
            )
        val requestedName = request.itemName?.trim()?.takeIf { it.isNotBlank() }
        if (requestedName != null && !currentItem.name.sameItemToken(requestedName)) {
            return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Modify,
                    group = request.group,
                    itemName = requestedName,
                    currentItemName = currentItem.name,
                    label = request.itemLabel(requestedName),
                    message = "The selected item is no longer worn in this group.",
                    availabilityReason = BcItemManagementAvailabilityReason.ItemNoLongerWorn,
                ),
            )
        }

        val extended = request.context.rules.extendedItem(currentItem.group, currentItem.name)
            ?: return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Modify,
                    group = currentItem.group,
                    itemName = currentItem.name,
                    currentItemName = currentItem.name,
                    label = request.itemLabel(currentItem.name),
                    message = "The current item has no mobile-configurable options.",
                    availabilityReason = BcItemManagementAvailabilityReason.NoConfigurableOptions,
                ),
            )
        if (extended.supportLevel == BcExtendedItemSupportLevel.Unsupported || !extended.isConfigurable) {
            if (extended.textFields.isNotEmpty() && extended.supportLevel != BcExtendedItemSupportLevel.Unsupported) {
                return singleEntryResult(request, checkNotNull(textEditEntry(request.context, currentItem.group)))
            }
            return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Modify,
                    group = currentItem.group,
                    itemName = currentItem.name,
                    currentItemName = currentItem.name,
                    label = request.itemLabel(currentItem.name),
                    extendedKind = extended.archetype.wireValue,
                    extendedStatusMessage = extended.statusMessage,
                    message = extended.unsupportedReason ?: "The current item has no mobile-configurable options.",
                    availabilityReason = if (extended.supportLevel == BcExtendedItemSupportLevel.Unsupported) {
                        BcItemManagementAvailabilityReason.ExtendedItemUnsupported
                    } else {
                        BcItemManagementAvailabilityReason.NoConfigurableOptions
                    },
                ),
            )
        }

        val optionPage = extended.modificationOptionsPage(
            currentPropertyJson = currentItem.propertyJson,
            currentDifficulty = currentItem.difficulty,
            offset = request.offset,
            limit = request.limit,
            query = request.query,
        )
        val entries = optionPage.options.mapNotNull { option ->
            if (option.selected) {
                BcItemManagementEntry(
                    operation = BcItemManagementOperation.Modify,
                    group = currentItem.group,
                    itemName = currentItem.name,
                    currentItemName = currentItem.name,
                    label = option.label,
                    subtitle = listOfNotNull(option.subtitle, "Current selection").joinToString(" • "),
                    propertyJson = option.propertyJson,
                    difficulty = option.difficulty,
                    selected = true,
                    extendedKind = extended.archetype.wireValue,
                    extendedStatusMessage = extended.statusMessage,
                    permissionStatus = BcItemManagementPermissionStatus.Informational,
                    enabled = false,
                    permissionMessage = "Current selection",
                    availabilityReason = BcItemManagementAvailabilityReason.CurrentSelection,
                    warnings = option.warnings + extended.warnings,
                )
            } else {
                val candidate = BcInteractionCandidate.ModifyItem(
                    targetMemberNumber = request.context.target.memberNumber,
                    group = currentItem.group,
                    itemName = currentItem.name,
                    propertyJson = option.propertyJson,
                    difficulty = option.difficulty,
                )
                entryFromDecision(
                    request = request,
                    operation = BcItemManagementOperation.Modify,
                    group = currentItem.group,
                    itemName = currentItem.name,
                    currentItemName = currentItem.name,
                    label = option.label,
                    subtitle = option.subtitle,
                    propertyJson = option.propertyJson,
                    difficulty = option.difficulty,
                    selected = false,
                    extendedKind = extended.archetype.wireValue,
                    extendedStatusMessage = extended.statusMessage,
                    warnings = option.warnings + extended.warnings,
                    candidate = candidate,
                )
            }?.takeIf { it.shouldInclude(request) }
        }
        return BcItemManagementPageResult(
            entries = entries,
            offset = request.offset,
            nextOffset = optionPage.nextOffset,
            totalKnownEntries = optionPage.totalKnownOptions,
            scannedEntryCount = optionPage.options.size,
        )
    }

    private fun lockPage(request: BcItemManagementPageRequest): BcItemManagementPageResult {
        val item = request.context.target.appearance.firstOrNull { it.group.sameItemToken(request.group) }
            ?: return BcItemManagementPageResult.empty(request.offset)
        return catalogItemPage(request) { lock ->
            entryFromDecision(request = request, operation = BcItemManagementOperation.Lock,
                group = item.group, itemName = lock.name, currentItemName = item.name, label = request.itemLabel(lock.name),
                candidate = BcInteractionCandidate.LockItem(request.context.target.memberNumber, item.group, item.name, lock.name))
        }
    }

    private fun unlockPage(request: BcItemManagementPageRequest): BcItemManagementPageResult {
        val item = request.context.target.appearance.firstOrNull { it.group.sameItemToken(request.group) }
            ?: return BcItemManagementPageResult.empty(request.offset)
        val lock = item.propertyJson.readBcStringProperty("LockedBy")
            ?: return BcItemManagementPageResult.empty(request.offset)
        val candidate = BcInteractionCandidate.UnlockItem(request.context.target.memberNumber, item.group, item.name, lock)
        fun entry(candidate: BcInteractionCandidate.UnlockItem) = entryFromDecision(
            request = request,
            operation = BcItemManagementOperation.Unlock,
            group = item.group,
            itemName = item.name,
            currentItemName = item.name,
            label = request.itemLabel(item.name),
            candidate = candidate,
        )
        val keyEntry = entry(candidate)
        if (keyEntry.enabled) return singleEntryResult(request, keyEntry)
        // Like the text editor, this projects input availability without exposing a sendable action.
        val inputEntry = credentialUnlockEntry(request.context, item.group) ?: return singleEntryResult(request, keyEntry)
        return singleEntryResult(request, inputEntry)
    }

    private fun removePage(request: BcItemManagementPageRequest): BcItemManagementPageResult {
        val currentItem = request.context.target.appearance.firstOrNull { it.group.sameItemToken(request.group) }
            ?: return singleEntryResult(
                request,
                disabledEntry(
                    operation = BcItemManagementOperation.Remove,
                    group = request.group,
                    itemName = null,
                    label = request.group.toHumanLabel(),
                    message = "No current item is worn in this group.",
                    availabilityReason = BcItemManagementAvailabilityReason.SlotEmpty,
                ),
            )

        val candidate = BcInteractionCandidate.RemoveItem(
            targetMemberNumber = request.context.target.memberNumber,
            group = currentItem.group,
        )
        val entry = entryFromDecision(
            request = request,
            operation = BcItemManagementOperation.Remove,
            group = currentItem.group,
            itemName = null,
            currentItemName = currentItem.name,
            label = "Remove ${request.itemLabel(currentItem.name)}",
            subtitle = currentItem.name,
            candidate = candidate,
        )
        return singleEntryResult(request, entry)
    }

    private fun entryFromDecision(
        request: BcItemManagementPageRequest,
        operation: BcItemManagementOperation,
        group: String,
        itemName: String?,
        currentItemName: String? = null,
        label: String,
        subtitle: String? = null,
        propertyJson: String? = null,
        difficulty: Int? = null,
        selected: Boolean = false,
        extendedKind: String? = null,
        extendedStatusMessage: String? = null,
        warnings: List<String> = emptyList(),
        candidate: BcInteractionCandidate,
    ): BcItemManagementEntry {
        val decision = permissionEngine.evaluate(BcPermissionRequest(request.context, candidate))
        return when (decision) {
            is BcPermissionDecision.Allowed -> BcItemManagementEntry(
                operation = operation,
                group = group,
                itemName = itemName,
                currentItemName = currentItemName,
                label = label,
                subtitle = subtitle,
                propertyJson = propertyJson,
                difficulty = difficulty,
                selected = selected,
                extendedKind = extendedKind,
                extendedStatusMessage = extendedStatusMessage,
                candidate = candidate,
                executable = decision.executable,
                permissionStatus = BcItemManagementPermissionStatus.Allowed,
                enabled = !selected,
                permissionMessage = null,
                permissionReasonCodes = emptyList(),
                warnings = warnings,
            )
            is BcPermissionDecision.NeedsServerCheck -> BcItemManagementEntry(
                operation = operation,
                group = group,
                itemName = itemName,
                currentItemName = currentItemName,
                label = label,
                subtitle = subtitle,
                propertyJson = propertyJson,
                difficulty = difficulty,
                selected = selected,
                extendedKind = extendedKind,
                extendedStatusMessage = extendedStatusMessage,
                candidate = candidate,
                executable = null,
                permissionStatus = BcItemManagementPermissionStatus.NeedsServerCheck,
                enabled = !selected,
                permissionMessage = decision.reasons.toManagementMessageOrNull() ?: "Server permission check required on send.",
                permissionReasonCodes = decision.reasons.map(BcPermissionReason::code),
                warnings = warnings,
            )
            is BcPermissionDecision.Denied -> BcItemManagementEntry(
                operation = operation,
                group = group,
                itemName = itemName,
                currentItemName = currentItemName,
                label = label,
                subtitle = subtitle,
                propertyJson = propertyJson,
                difficulty = difficulty,
                selected = selected,
                extendedKind = extendedKind,
                extendedStatusMessage = extendedStatusMessage,
                candidate = candidate,
                executable = null,
                permissionStatus = BcItemManagementPermissionStatus.Denied,
                enabled = false,
                permissionMessage = decision.reasons.toManagementMessageOrNull() ?: "Item update is not allowed.",
                permissionReasonCodes = decision.reasons.map(BcPermissionReason::code),
                warnings = warnings,
            )
        }
    }

    private fun disabledEntry(
        operation: BcItemManagementOperation,
        group: String,
        itemName: String?,
        currentItemName: String? = null,
        label: String,
        subtitle: String? = null,
        extendedKind: String? = null,
        extendedStatusMessage: String? = null,
        message: String,
        availabilityReason: BcItemManagementAvailabilityReason,
    ): BcItemManagementEntry = BcItemManagementEntry(
        operation = operation,
        group = group,
        itemName = itemName,
        currentItemName = currentItemName,
        label = label,
        subtitle = subtitle,
        extendedKind = extendedKind,
        extendedStatusMessage = extendedStatusMessage,
        permissionStatus = BcItemManagementPermissionStatus.Denied,
        enabled = false,
        permissionMessage = message,
        availabilityReason = availabilityReason,
    )

    private fun singleEntryResult(
        request: BcItemManagementPageRequest,
        entry: BcItemManagementEntry,
    ): BcItemManagementPageResult {
        val entries = if (entry.shouldInclude(request) && request.offset == 0 && request.limit > 0) listOf(entry) else emptyList()
        return BcItemManagementPageResult(
            entries = entries,
            offset = request.offset,
            nextOffset = null,
            totalKnownEntries = 1,
            scannedEntryCount = if (request.offset == 0) 1 else 0,
        )
    }

    private fun currentItemName(context: BcPermissionContext, group: String): String? =
        context.target.appearance.firstOrNull { it.group.sameItemToken(group) }?.name

    private fun BcItemManagementEntry.shouldInclude(request: BcItemManagementPageRequest): Boolean = when (permissionStatus) {
        BcItemManagementPermissionStatus.Allowed,
        BcItemManagementPermissionStatus.Informational,
        -> true
        BcItemManagementPermissionStatus.NeedsServerCheck -> request.includeNeedsServerCheck
        BcItemManagementPermissionStatus.Denied -> request.includeDenied
    }
}

private fun String.sameItemToken(other: String): Boolean = trim().equals(other.trim(), ignoreCase = true)

private fun String?.normalizedQuery(): String? = this?.trim()?.takeIf { it.isNotBlank() }

private fun String.toHumanLabel(): String =
    replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .ifBlank { this }

private fun List<BcPermissionReason>.toManagementMessageOrNull(): String? =
    joinToString(separator = "\n") { it.message }.ifBlank { null }

private fun BcItemManagementPageRequest.itemLabel(name: String): String =
    itemDisplayNames[name] ?: name.toHumanLabel()
