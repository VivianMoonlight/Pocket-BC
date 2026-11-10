package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.domain.bcpermission.keysDeposited

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.bcitemproperty.displayText
import dev.vivian.mobilebc.domain.bcitemproperty.allowsLock
import dev.vivian.mobilebc.domain.bcitemproperty.BcAfcHeartLock
import dev.vivian.mobilebc.domain.bcitemproperty.textValues
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcitemproperty.readBcPropertyTokens
import dev.vivian.mobilebc.domain.bcitemproperty.readBcBooleanProperty
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockInputKind
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfigurationMode
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerState
import dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange
import dev.vivian.mobilebc.domain.bcitemproperty.BcTimerInputChoice
import dev.vivian.mobilebc.domain.bcpermission.lockTimerAccess
import dev.vivian.mobilebc.domain.bcpermission.lockTimerState
import dev.vivian.mobilebc.domain.bcpermission.lockTimerMaximumSeconds
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor
import dev.vivian.mobilebc.domain.bccharacterstate.DefaultBcCharacterStateResolver
import dev.vivian.mobilebc.domain.bccharacterstate.BcCharacterItemSource
import dev.vivian.mobilebc.domain.bccharacterstate.lockInputConditions
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementEntry
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementOperation
import dev.vivian.mobilebc.domain.bcpermission.BcItemManagementPageRequest
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionContext
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcItemManagementQuery
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog

/** Owns item-picker paging, cache invalidation, and picker-only UI projection. */
internal class RoomDetailsItemPickerController(
    private val itemManagementQuery: DefaultBcItemManagementQuery,
    private val pageSize: Int = DefaultPageSize,
    private val catalogProvider: (String) -> BcInteractionCatalogSnapshot? = { null },
) {
    private var pageCacheKey: PageCacheKey? = null
    private var cachedEntries: List<InteractionItemPickerEntryUiState> = emptyList()
    private var nextOffset: Int? = null
    private var totalKnownEntries: Int = 0
    private val characterStateResolver = DefaultBcCharacterStateResolver()

    fun clear() {
        pageCacheKey = null
        cachedEntries = emptyList()
        nextOffset = null
        totalKnownEntries = 0
    }

    fun loadNext(
        selection: RoomInteractionSelectionState,
        target: BcCharacterSnapshot,
        group: String,
        version: String,
        context: BcPermissionContext,
        rules: BcRuleCatalog,
    ): Boolean {
        val operation = selection.catalogOperationFor(target, group)
        val key = pageKey(
            context = context,
            target = target,
            group = group,
            operation = operation,
            itemName = selection.itemPickerItemName,
            query = selection.itemPickerQuery,
            showUnavailable = selection.itemPickerShowUnavailable,
            version = version,
        )
        var changed = false
        if (pageCacheKey != key) {
            loadPage(context = context, rules = rules, key = key, offset = 0, append = false)
            changed = true
        }
        val offset = nextOffset ?: return changed
        loadPage(context = context, rules = rules, key = key, offset = offset, append = true)
        return true
    }

    fun buildUiState(
        selection: RoomInteractionSelectionState,
        target: BcCharacterSnapshot,
        selectedGroup: InteractionGroupUiState?,
        rules: BcRuleCatalog?,
        permissionContext: BcPermissionContext?,
        version: String,
    ): InteractionItemPickerUiState {
        if (selectedGroup == null) {
            return InteractionItemPickerUiState(
                step = InteractionItemPickerStep.BodyPart,
                emptyMessage = InteractionUserMessage.General(InteractionUserMessageKey.ChooseItemGroup),
            )
        }

        val currentItem = target.appearance
            .firstOrNull { it.group.equals(selectedGroup.name, ignoreCase = true) }
        val currentItemName = currentItem?.name
        val currentText = currentItem?.displayText()
        val afcLock = currentItem?.let { BcAfcHeartLock.identity(target, it) }
        val operation = selection.catalogOperationFor(target, selectedGroup.name)
        val selectedOperation = operation.toUiState()
        if (rules == null) {
            return unavailableItemStep(
                selection = selection,
                selectedOperation = selectedOperation,
                currentItemName = currentItemName,
                message = InteractionUserMessage.General(InteractionUserMessageKey.InteractionDataPreparing),
            )
        }

        if (permissionContext == null) {
            return unavailableItemStep(
                selection = selection,
                selectedOperation = selectedOperation,
                currentItemName = currentItemName,
                message = InteractionUserMessage.General(InteractionUserMessageKey.SelfCharacterUnavailable),
            )
        }

        val key = pageKey(
            context = permissionContext,
            target = target,
            group = selectedGroup.name,
            operation = operation,
            itemName = selection.itemPickerItemName,
            query = selection.itemPickerQuery,
            showUnavailable = selection.itemPickerShowUnavailable,
            version = version,
        )
        if (pageCacheKey != key) {
            loadPage(
                context = permissionContext,
                rules = rules,
                key = key,
                offset = 0,
                append = false,
            )
        }

        return InteractionItemPickerUiState(
            step = InteractionItemPickerStep.Item,
            selectedOperation = selectedOperation,
            selectedItemName = selection.itemPickerItemName,
            selectedItemDisplayName = selection.itemPickerItemName?.let { displayName(version, selectedGroup.name, it) },
            currentItemName = currentItemName,
            currentItemDisplayName = currentItemName?.let { currentText?.displayName(displayName(version, selectedGroup.name, it)) },
            currentItemDescription = currentText?.description,
            currentItemTextLines = currentText?.textLines.orEmpty(),
            currentItemTextFields = currentItem?.let { rules.extendedItem(it.group, it.name)?.textFields }.orEmpty(),
            currentItemTextValues = currentItem?.let { rules.extendedItem(it.group, it.name)?.textValues(it.propertyJson) }.orEmpty(),
            currentItemTextEditEntry = itemManagementQuery.textEditEntry(permissionContext, selectedGroup.name)?.toUiState(rules),
            currentItemIsAfcHeartLock = afcLock != null,
            currentItemLockOwnerNumber = afcLock?.owner,
            currentItemCanLock = currentItem?.let { item ->
                val asset = rules.asset(item.group, item.name)
                item.propertyJson.readBcStringProperty("LockedBy") == null && "Lock" !in item.propertyJson.readBcPropertyTokens("Effect") &&
                    "Lock" !in asset?.effects.orEmpty() && asset?.allowsLock(item.propertyJson, rules.extendedItem(item.group, item.name)) == true
            } == true,
            lockingCurrentItem = operation == BcItemManagementOperation.Lock,
            currentItemLockInputKind = BcLockInputKind.forLock(currentItem?.propertyJson.readBcStringProperty("LockedBy")),
            lockInputConditions = characterStateResolver.resolve(permissionContext.actor, rules, BcCharacterItemSource.Actor).lockInputConditions(),
            currentItemLockHint = currentItem?.propertyJson.readBcStringProperty("Hint"),
            currentItemCredentialUnlockEntry = itemManagementQuery.credentialUnlockEntry(permissionContext, selectedGroup.name)?.toUiState(rules),
            currentItemLockConfigurationMode = BcLockConfigurationMode.forLock(currentItem?.propertyJson, permissionContext.actor.memberNumber).takeIf { afcLock == null },
            currentItemLockConfigurationEntry = itemManagementQuery.lockConfigurationEntry(permissionContext, selectedGroup.name)?.toUiState(rules),
            currentItemKeyholders = dev.vivian.mobilebc.domain.bcitemproperty.highSecurityKeyholderText(currentItem?.propertyJson).takeIf {
                afcLock == null && currentItem?.propertyJson.readBcStringProperty("LockedBy") == "HighSecurityPadlock" }.orEmpty(),
            currentItemKeysDeposited = permissionContext.keysDeposited() == true,
            currentItemLockIsTimerPassword = currentItem?.propertyJson.readBcStringProperty("LockedBy") == "TimerPasswordPadlock",
            currentItemRemoveOnUnlock = currentItem?.propertyJson.readBcBooleanProperty("RemoveOnUnlock") == true,
            currentItemTimer = currentItem?.let { timerUiState(permissionContext, rules, it) },
            currentItemUnlockEntry = itemManagementQuery.page(BcItemManagementPageRequest(
                context = permissionContext,
                group = selectedGroup.name,
                operation = BcItemManagementOperation.Unlock,
                limit = 1,
                includeDenied = true,
            )).entries.firstOrNull()?.toUiState(rules),
            currentItemRemoveEntry = currentItemName?.let {
                currentItemRemoveEntry(
                    context = permissionContext,
                    rules = rules,
                    group = selectedGroup.name,
                )
            },
            currentItemConfigurable = currentItemName?.let { itemName ->
                rules.extendedItem(selectedGroup.name, itemName)?.let { extended ->
                    extended.isConfigurable && extended.supportLevel != BcExtendedItemSupportLevel.Unsupported
                }
            } == true,
            configuringCurrentItem = operation == BcItemManagementOperation.Modify,
            query = selection.itemPickerQuery,
            showUnavailable = selection.itemPickerShowUnavailable,
            entries = cachedEntries,
            hasMore = nextOffset != null,
            nextOffset = nextOffset,
            loadedCount = cachedEntries.size,
            totalKnownEntries = totalKnownEntries,
            emptyMessage = if (cachedEntries.isEmpty()) {
                emptyMessage(selection.itemPickerQuery, selection.itemPickerShowUnavailable)
            } else {
                null
            },
        )
    }

    private fun timerUiState(context: BcPermissionContext, rules: BcRuleCatalog, item: dev.vivian.mobilebc.core.model.BcCharacterItem): InteractionItemTimerUiState? {
        val state = context.lockTimerState(item) ?: return null
        val access = context.lockTimerAccess(item, state)
        val actor = characterStateResolver.resolve(context.actor, rules, BcCharacterItemSource.Actor)
        val blind = actor.lockInputConditions().blind
        fun entry(change: BcLockTimerChange) = itemManagementQuery.timerControlEntry(context, item.group, change)
        val settings = if (access.editSettings) entry(BcLockTimerChange.Settings(state.removeItem,
            state.showTimer.takeUnless { state.basic }, state.enableRandomInput.takeUnless { state.basic })) else null
        val restart = if (state.basic) entry(BcLockTimerChange.Restart) else null
        val adjust = if (access.adjust) entry(BcLockTimerChange.Adjust(300)) else null
        val limited = if (!state.basic && !access.adjust) entry(BcLockTimerChange.LimitedInput(BcTimerInputChoice.Decrease)) else null
        val inspected = settings ?: restart ?: adjust ?: limited
        val canOpen = !blind && (inspected?.enabled == true || inspected?.permissionReasonCodes?.all {
            it in setOf(BcPermissionReasonCode.LockTimerPermissionDenied, BcPermissionReasonCode.LockTimerExpired,
                BcPermissionReasonCode.LockTimerUnavailable, BcPermissionReasonCode.ActorPrerequisiteNotMet, BcPermissionReasonCode.TargetZoneBlocked)
        } == true)
        val showTime = !blind && (state.basic || state.showTimer || actor.canInteract() && access.showTime)
        return InteractionItemTimerUiState(state.basic, state.removeItem, state.showTimer, state.enableRandomInput,
            state.deadlineMillis.takeIf { showTime }, context.serverTimeMillis?.let { BcServerTimeAnchor(it, System.nanoTime()) }, canOpen,
            permissionMessage = inspected?.permissionReasonCodes?.firstOrNull()?.let { InteractionUserMessage.Permission(it) },
            settings = settings?.toUiState(rules), restart = restart?.toUiState(rules), adjust = adjust?.toUiState(rules), limited = limited?.toUiState(rules),
            limitedStepSeconds = rules.asset("ItemMisc", state.lockName)?.removeTimerSeconds?.let(state::limitedStepSeconds),
            maximumSeconds = rules.asset("ItemMisc", state.lockName)?.maxTimerSeconds?.let(context::lockTimerMaximumSeconds)?.takeIf { !state.basic && it > 0 })
    }

    private fun unavailableItemStep(
        selection: RoomInteractionSelectionState,
        selectedOperation: InteractionItemPickerOperationUiState?,
        currentItemName: String?,
        message: InteractionUserMessage,
    ): InteractionItemPickerUiState = InteractionItemPickerUiState(
        step = InteractionItemPickerStep.Item,
        selectedOperation = selectedOperation,
        selectedItemName = selection.itemPickerItemName,
        currentItemName = currentItemName,
        configuringCurrentItem = selectedOperation?.name == BcItemManagementOperation.Modify.wireName,
        query = selection.itemPickerQuery,
        showUnavailable = selection.itemPickerShowUnavailable,
        emptyMessage = message,
    )

    private fun currentItemRemoveEntry(
        context: BcPermissionContext,
        rules: BcRuleCatalog,
        group: String,
    ): InteractionItemPickerEntryUiState? = itemManagementQuery.page(
        BcItemManagementPageRequest(
            context = context,
            group = group,
            operation = BcItemManagementOperation.Remove,
            offset = 0,
            limit = 1,
            includeDenied = true,
        ),
    ).entries.firstOrNull()?.toUiState(rules)

    private fun loadPage(
        context: BcPermissionContext,
        rules: BcRuleCatalog,
        key: PageCacheKey,
        offset: Int,
        append: Boolean,
    ) {
        val page = itemManagementQuery.page(
            BcItemManagementPageRequest(
                context = context,
                group = key.group,
                operation = key.operation,
                itemName = key.itemName,
                query = key.query,
                itemDisplayNames = key.itemDisplayNames,
                offset = offset,
                limit = pageSize,
                includeDenied = key.showUnavailable,
            ),
        )
        val entries = page.entries.map { it.toUiState(rules) }
        pageCacheKey = key
        cachedEntries = if (append) cachedEntries + entries else entries
        nextOffset = page.nextOffset
        totalKnownEntries = page.totalKnownEntries
    }

    private fun pageKey(
        context: BcPermissionContext,
        target: BcCharacterSnapshot,
        group: String,
        operation: BcItemManagementOperation,
        itemName: String?,
        query: String,
        showUnavailable: Boolean,
        version: String,
    ): PageCacheKey = PageCacheKey(
        context = context,
        targetMemberNumber = target.memberNumber,
        group = group.trim(),
        operation = operation,
        itemName = itemName?.trim()?.takeIf { it.isNotBlank() },
        query = query.trim(),
        showUnavailable = showUnavailable,
        version = version,
        itemDisplayNames = catalogProvider(version)?.itemsForGroup(if (operation == BcItemManagementOperation.Lock) "ItemMisc" else group)
            ?.associate { it.name to it.displayName }.orEmpty(),
    )

    private fun displayName(version: String, group: String, name: String): String =
        catalogProvider(version)?.itemsForGroup(group)?.firstOrNull { it.name.equals(name, true) }?.displayName
            ?: name.toRoomDetailsHumanLabel()

    private fun BcItemManagementOperation.toUiState(): InteractionItemPickerOperationUiState =
        InteractionItemPickerOperationUiState(
        name = wireName,
    )

    private fun RoomInteractionSelectionState.catalogOperationFor(
        target: BcCharacterSnapshot,
        group: String,
    ): BcItemManagementOperation = itemPickerOperation ?: if (
        target.appearance.any { it.group.equals(group, ignoreCase = true) }
    ) {
        BcItemManagementOperation.Replace
    } else {
        BcItemManagementOperation.Wear
    }

    private fun BcItemManagementEntry.toUiState(rules: BcRuleCatalog): InteractionItemPickerEntryUiState {
        val extended = itemName?.let { name -> rules.extendedItem(group, name) }
        val clothing = rules.group(group)?.kind == BcAppearanceGroupKind.Clothing
        val canOpenOptions = (operation == BcItemManagementOperation.Wear || operation == BcItemManagementOperation.Replace) &&
            extended?.isConfigurable == true &&
            extended.supportLevel != BcExtendedItemSupportLevel.Unsupported
        return InteractionItemPickerEntryUiState(
            operation = operation.wireName,
            group = group,
            itemName = itemName,
            currentItemName = currentItemName,
            label = label.toCatalogItemLabel(operation, clothing),
            subtitle = subtitle.takeIf { availabilityReason == null },
            propertyJson = propertyJson,
            difficulty = difficulty,
            selected = selected,
            enabled = enabled,
            permissionMessage = availabilityReason?.let { reason ->
                InteractionUserMessage.ItemAvailability(reason)
            } ?: permissionReasonCodes.firstOrNull()?.let { code ->
                InteractionUserMessage.Permission(code)
            },
            extendedKind = extendedKind,
            extendedStatusMessage = extendedStatusMessage,
            canOpenOptions = canOpenOptions,
            candidate = candidate,
            requiresTextInput = requiresTextInput,
            requiresUnlockInput = requiresUnlockInput,
        )
    }

    private fun String.toClothingOperationLabel(operation: BcItemManagementOperation): String = when (operation) {
        BcItemManagementOperation.Replace -> replaceFirst("Replace with ", "Change to ", ignoreCase = true)
        BcItemManagementOperation.Remove -> replaceFirst("Remove ", "Take off ", ignoreCase = true)
        BcItemManagementOperation.Wear,
        BcItemManagementOperation.Modify,
        BcItemManagementOperation.Unlock,
        BcItemManagementOperation.Lock,
        -> this
    }

    private fun String.toCatalogItemLabel(
        operation: BcItemManagementOperation,
        clothing: Boolean,
    ): String {
        val userLabel = if (clothing) toClothingOperationLabel(operation) else this
        return when (operation) {
            BcItemManagementOperation.Wear -> userLabel.replaceFirst("Wear ", "", ignoreCase = true)
            BcItemManagementOperation.Replace -> userLabel
                .replaceFirst("Replace with ", "", ignoreCase = true)
                .replaceFirst("Change to ", "", ignoreCase = true)
            BcItemManagementOperation.Modify,
            BcItemManagementOperation.Remove,
            BcItemManagementOperation.Unlock,
            BcItemManagementOperation.Lock,
            -> userLabel
        }
    }

    private fun emptyMessage(query: String, showUnavailable: Boolean): InteractionUserMessage {
        val normalizedQuery = query.trim()
        return when {
            normalizedQuery.isNotBlank() && showUnavailable -> InteractionUserMessage.General(
                InteractionUserMessageKey.NoMatchingItems,
                normalizedQuery,
            )
            normalizedQuery.isNotBlank() -> InteractionUserMessage.General(
                InteractionUserMessageKey.NoAllowedMatchingItems,
                normalizedQuery,
            )
            showUnavailable -> InteractionUserMessage.General(InteractionUserMessageKey.NoItems)
            else -> InteractionUserMessage.General(InteractionUserMessageKey.NoAllowedItems)
        }
    }

    private data class PageCacheKey(
        val context: BcPermissionContext,
        val targetMemberNumber: Int,
        val group: String,
        val operation: BcItemManagementOperation,
        val itemName: String?,
        val query: String,
        val showUnavailable: Boolean,
        val version: String,
        val itemDisplayNames: Map<String, String>,
    )

    private companion object {
        private const val DefaultPageSize = 40
    }
}

internal val BcItemManagementOperation.wireName: String
    get() = name

internal fun String.toItemManagementOperation(): BcItemManagementOperation? =
    BcItemManagementOperation.values().firstOrNull { it.name.equals(trim(), ignoreCase = true) }
