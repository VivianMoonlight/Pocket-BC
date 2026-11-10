package dev.vivian.mobilebc.domain.bcwardrobe

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcCharacterBodyState
import dev.vivian.mobilebc.core.model.BcWardrobeItem
import dev.vivian.mobilebc.core.model.BcWardrobeSlotCount
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.model.BcWardrobeState
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcitemproperty.readBcPropertyTokens
import dev.vivian.mobilebc.domain.bcitemproperty.readBcStringProperty
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionContext
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionDecision
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReason
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRoomContext
import dev.vivian.mobilebc.domain.bcpermission.DefaultBcInteractionPermissionEngine
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemSupportLevel
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemArchetype
import dev.vivian.mobilebc.domain.bcrules.BcExtendedItemRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.estimatedServerTimeMillis

/** Native wardrobe bundles and defaults, with conservative per-group protection for bulk updates. */
class BcWardrobePlanner(
    private val serverTimeMillis: (BcSessionSnapshot) -> Long? = { it.estimatedServerTimeMillis() },
    private val permissionEngine: DefaultBcInteractionPermissionEngine = DefaultBcInteractionPermissionEngine(),
) {
    fun validateName(name: String): Boolean = name.length <= 20 && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' }

    fun unavailableReason(snapshot: BcSessionSnapshot, rules: BcRuleCatalog?): BcWardrobeUnavailableReason? {
        if (!snapshot.isVerifiedOnline) return BcWardrobeUnavailableReason.Offline
        if (snapshot.currentRoom == null) return BcWardrobeUnavailableReason.NotInRoom
        val self = snapshot.self() ?: return BcWardrobeUnavailableReason.SelfUnavailable
        if (self.assetFamily != "Female3DCG") return BcWardrobeUnavailableReason.UnsupportedAssetFamily
        val version = snapshot.bcVersion
        if (rules == null || rules.groups.isEmpty() || rules.assets.isEmpty() || version?.normalizedBcVersionOrNull() != rules.version) {
            return BcWardrobeUnavailableReason.CatalogPreparing
        }
        return unavailableReason(checkNotNull(context(snapshot)), rules)
    }

    fun unavailableReason(context: BcWardrobeContext, rules: BcRuleCatalog): BcWardrobeUnavailableReason? {
        val self = context.self
        if (rules.groups.isEmpty() || rules.assets.isEmpty()) return BcWardrobeUnavailableReason.CatalogPreparing
        if (self.assetFamily != "Female3DCG") return BcWardrobeUnavailableReason.UnsupportedAssetFamily
        if (self.appearance.map(BcCharacterItem::group).toSet().size != self.appearance.size) return BcWardrobeUnavailableReason.PrivateStateUnavailable
        if (context.roomGame == null) return BcWardrobeUnavailableReason.PrivateStateUnavailable
        val wardrobe = context.wardrobeState ?: return BcWardrobeUnavailableReason.PrivateStateUnavailable
        if (wardrobe.memberNumber != self.memberNumber || context.loginSessionId == null ||
            wardrobe.selfRuleLogSessionId != context.loginSessionId || context.privateLog == null ||
            !wardrobe.wardrobeAvailable || !wardrobe.privateInputs.gameAvailable || !wardrobe.privateInputs.inventoryAvailable ||
            wardrobe.privateInputs.inventoryItems == null && wardrobe.privateInputs.inventoryIds == null
        ) return BcWardrobeUnavailableReason.PrivateStateUnavailable

        val time = context.serverTimeMillis
        fun activeLog(name: String, group: String): Boolean? {
            val entry = context.privateLog?.firstOrNull { it.name == name && it.group == group } ?: return false
            return entry.expiresAtMillis?.let { expiry -> time?.let { expiry >= it } } ?: if (entry.expiresAtMillis == null) true else null
        }
        when (activeLog("Wardrobe", "PrivateRoom")) {
            null -> return BcWardrobeUnavailableReason.PrivateStateUnavailable
            false -> return BcWardrobeUnavailableReason.WardrobeLocked
            true -> Unit
        }
        if (self.appearance.any { item ->
                val effects = rules.asset(item.group, item.name)?.effects.orEmpty() + item.propertyJson.readBcPropertyTokens("Effect")
                effects.any { it == "Freeze" || it == "Block" || it == "BlockWardrobe" }
            }) return BcWardrobeUnavailableReason.Restrained
        if (self.appearance.any { it.group == "ItemNeck" && it.name == "ClubSlaveCollar" }) return BcWardrobeUnavailableReason.ClubSlave
        when (activeLog("BlockChange", "Rule")) {
            null -> return BcWardrobeUnavailableReason.PrivateStateUnavailable
            true -> return BcWardrobeUnavailableReason.BlockChangeRule
            false -> Unit
        }

        val game = wardrobe.privateInputs.gameJson
        val ggts = nestedObject(game, "GGTS")
        if (!ggts.valid) return BcWardrobeUnavailableReason.PrivateStateUnavailable
        val level = ggtsLevel(game) ?: return BcWardrobeUnavailableReason.PrivateStateUnavailable
        val npcOwnership = npcOwnership(wardrobe)
        if (npcOwnership == null && context.privateLog.any { it.group == "OwnerRule" && (it.name == "BlockChange" || it.name.startsWith("BlockAppearance")) }) {
            return BcWardrobeUnavailableReason.PrivateStateUnavailable
        }
        when (activeLog("BlockChange", "OwnerRule")) {
            null -> return BcWardrobeUnavailableReason.PrivateStateUnavailable
            true -> {
                val fullyOwned = when {
                    (self.ownership?.memberNumber ?: 0) > 0 -> (self.ownership?.stage ?: 0) >= 1
                    hasNpcOwner(self, wardrobe) || npcOwnership?.owned == true -> npcOwnership?.fullyOwned == true
                    else -> level >= 6
                }
                if (fullyOwned) return BcWardrobeUnavailableReason.BlockChangeOwnerRule
            }
            false -> Unit
        }
        if (context.roomGame == "LARP" || context.roomGame == "MagicBattle") {
            val onlineGame = nestedObject(game, context.roomGame)
            if (!onlineGame.valid) return BcWardrobeUnavailableReason.PrivateStateUnavailable
            val rawStatus = BcItemPropertyJson.readPropertyValue(onlineGame.json, "Status")
            val status = BcItemPropertyJson.readStringValue(rawStatus)
            if (rawStatus != null && rawStatus != "null" && status != "" && status != "Running") {
                // Native setters support only these two values; do not treat corrupt private state as permission.
                return BcWardrobeUnavailableReason.PrivateStateUnavailable
            }
            if (status == "Running") return BcWardrobeUnavailableReason.GameBlocksChange
        }
        val isolated = context.privateLog?.firstOrNull { it.name == "Isolated" && it.group == "Asylum" }?.expiresAtMillis
        if (isolated != null) {
            if (time == null) return BcWardrobeUnavailableReason.PrivateStateUnavailable
            if (isolated >= time) return BcWardrobeUnavailableReason.AsylumBlocksChange
        }
        if (level >= 6 && context.roomSpace == null) return BcWardrobeUnavailableReason.PrivateStateUnavailable
        if (level >= 6 && context.roomSpace == "Asylum") return BcWardrobeUnavailableReason.AsylumBlocksChange
        return null
    }

    fun rejection(request: BcWardrobeRequest, snapshot: BcSessionSnapshot): BcWardrobeUnavailableReason? {
        if (snapshot.account?.memberNumber != request.targetMemberNumber || snapshot.relationships.accountGeneration != request.accountGeneration) {
            return BcWardrobeUnavailableReason.AccountChanged
        }
        if (snapshot.currentRoom?.name != request.roomName || snapshot.roomPresenceEpoch != request.roomPresenceEpoch) {
            return BcWardrobeUnavailableReason.RoomChanged
        }
        unavailableReason(snapshot, request.rules)?.let { return it }
        if (request.slotIndex !in 0 until BcWardrobeSlotCount) return BcWardrobeUnavailableReason.InvalidSlot
        if (snapshot.wardrobeState?.revision != request.expectedRevision) return BcWardrobeUnavailableReason.RevisionChanged
        return null
    }

    /** Save the authenticated player's complete Appearance category, exactly as WardrobeAssetBundle. */
    fun saveAppearance(snapshot: BcSessionSnapshot, rules: BcRuleCatalog): List<BcWardrobeItem> {
        return saveAppearance(checkNotNull(snapshot.self()), rules)
    }

    fun saveAppearance(self: BcCharacterSnapshot, rules: BcRuleCatalog): List<BcWardrobeItem> {
        check(self.appearance.all { rules.group(it.group) != null }) { "The current appearance contains an unknown group." }
        return self.appearance.filter { rules.group(it.group)?.kind != BcAppearanceGroupKind.Item }.map { item ->
            BcWardrobeItem(item.group, item.name, item.colorJson, BcItemPropertyJson.withoutProperty(item.propertyJson, "Expression"))
        }
    }

    fun plan(request: BcWardrobeRequest, snapshot: BcSessionSnapshot): BcWardrobePlan {
        val self = snapshot.self()
        rejection(request, snapshot)?.let { return BcWardrobePlan(self?.appearance.orEmpty(), emptyList(), it) }
        return plan(checkNotNull(context(snapshot)), request.slotIndex, request.rules)
    }

    fun plan(context: BcWardrobeContext, slotIndex: Int, rules: BcRuleCatalog): BcWardrobePlan {
        val source = context.self
        unavailableReason(context, rules)?.let { return BcWardrobePlan(source.appearance, emptyList(), it) }
        val wardrobe = checkNotNull(context.wardrobeState)
        val slot = wardrobe.slots.firstOrNull { it.index == slotIndex }
            ?: return BcWardrobePlan(source.appearance, emptyList(), BcWardrobeUnavailableReason.InvalidSlot)
        if (slot.status == BcWardrobeSlotStatus.Empty || slot.appearance?.isEmpty() == true) {
            return BcWardrobePlan(source.appearance, emptyList(), BcWardrobeUnavailableReason.EmptySlot)
        }
        if (slot.status != BcWardrobeSlotStatus.Saved || slot.appearance == null) {
            return BcWardrobePlan(source.appearance, emptyList(), BcWardrobeUnavailableReason.UnsupportedSlot)
        }
        val current = source.appearance.associateBy(BcCharacterItem::group)
        val desired = slot.appearance!!.groupBy(BcWardrobeItem::group)
        val finalItems = current.toMutableMap()
        val entries = linkedMapOf<String, BcWardrobePlanEntry>()
        val groups = (current.keys + desired.keys + rules.groups.values.filter { it.kind != BcAppearanceGroupKind.Item && !it.allowNone }.map { it.name }).sorted()
        val blockedGroups = appearanceBlockedGroups(context, source, wardrobe)

        fun preserve(group: String, wanted: String?, reason: BcWardrobeEntryReason, detail: String? = null) {
            current[group]?.let { finalItems[group] = it } ?: finalItems.remove(group)
            entries[group] = BcWardrobePlanEntry(group, current[group]?.name, wanted, BcWardrobeEntryAction.Preserved, reason, detail)
        }
        groups.forEach { name ->
            val group = rules.group(name)
            val previous = current[name]
            val saved = desired[name]
            val wanted = saved?.firstOrNull()?.name
            when {
                group == null -> preserve(name, wanted, BcWardrobeEntryReason.UnknownGroup)
                group.kind == BcAppearanceGroupKind.Item -> preserve(name, wanted, BcWardrobeEntryReason.ItemGroupPreserved)
                saved?.size?.let { it > 1 } == true -> preserve(name, wanted, BcWardrobeEntryReason.DuplicateGroup)
                group.unsupportedWardrobeMetadata.isNotEmpty() -> preserve(name, wanted, BcWardrobeEntryReason.UnsupportedMetadata)
                previous != null && rules.asset(name, previous.name) == null -> preserve(name, wanted, BcWardrobeEntryReason.UnknownAsset)
                previous != null && isLocked(previous, rules) -> preserve(name, wanted, BcWardrobeEntryReason.LockedCurrentItem)
                name in blockedGroups -> preserve(name, wanted, BcWardrobeEntryReason.BlockAppearanceOwnerRule)
                !group.allowCustomize -> preserve(name, wanted, BcWardrobeEntryReason.GroupNotCustomizable)
                saved != null -> {
                    val bundle = saved.single()
                    val asset = rules.asset(name, bundle.name)
                    val reason = if (asset == null) BcWardrobeEntryReason.UnknownAsset else itemReason(wardrobe, context, group, asset, bundle, rules)
                    if (reason != null) preserve(name, wanted, reason)
                    else {
                        val next = bundleToItem(bundle, checkNotNull(asset), previous, rules)
                        if (next == null) preserve(name, wanted, BcWardrobeEntryReason.UnsupportedItemProperty)
                        else {
                            finalItems[name] = next
                            entries[name] = BcWardrobePlanEntry(name, previous?.name, wanted, BcWardrobeEntryAction.Applied)
                        }
                    }
                }
                group.allowNone -> {
                    finalItems.remove(name)
                    entries[name] = BcWardrobePlanEntry(name, previous?.name, null, BcWardrobeEntryAction.Removed)
                }
                else -> finalItems.remove(name)
            }
        }
        // Defaults use the native group order so mirrored groups see already-created counterparts.
        rules.groups.values.filter { it.kind != BcAppearanceGroupKind.Item && !it.allowNone && it.name !in finalItems && entries[it.name]?.reason == null }.forEach { group ->
            val mirror = group.mirrorGroup?.let(finalItems::get)
            val name = mirror?.name ?: group.defaultAssetName
            val asset = name?.let { rules.asset(group.name, it) }
            val bundle = asset?.let { BcWardrobeItem(group.name, it.name, mirror?.colorJson) }
            if (asset == null || bundle == null || itemReason(wardrobe, context, group, asset, bundle, rules) != null) {
                preserve(group.name, name, BcWardrobeEntryReason.MandatoryFallbackUnavailable)
            } else {
                val next = bundleToItem(bundle, asset, current[group.name], rules)
                if (next == null) preserve(group.name, name, BcWardrobeEntryReason.MandatoryFallbackUnavailable)
                else {
                    finalItems[group.name] = next
                    entries[group.name] = BcWardrobePlanEntry(group.name, current[group.name]?.name, name, BcWardrobeEntryAction.RestoredDefault)
                }
            }
        }

        // The editor protects existing restraints/locked clothes too: a body change must not make
        // a retained item newly invalid. Rollbacks are simultaneous, making the outcome deterministic.
        val evaluationRules = rules.copy(assets = rules.assets.mapValues { (_, asset) -> asset.copy(enable = true) })
        var remainingPasses = groups.size + 1
        while (remainingPasses-- > 0) {
            val proposedAppearance = finalItems.values.sortedBy(BcCharacterItem::group)
            val proposed = source.copy(appearance = proposedAppearance, body = BcCharacterBodyState.fromAppearance(proposedAppearance, source.body))
            val changed = finalItems.keys.union(current.keys).filter { finalItems[it] != current[it] }.toSet()
            if (changed.isEmpty()) break
            val failures = changed.mapNotNull { name ->
                val target = if (finalItems[name] == null && current[name] != null) proposed.copy(appearance = proposed.appearance + checkNotNull(current[name])) else proposed
                val reasons = permissionReasons(context, target, name, finalItems[name], evaluationRules)
                reasons.takeIf { it.isNotEmpty() }?.let { name to it }
            }.toMap()
            val retainedConflicts = finalItems.values.flatMap { item ->
                if (item.group in changed || rules.asset(item.group, item.name) == null) emptyList()
                else (permissionReasons(context, proposed, item.group, item, evaluationRules).toSet() -
                    permissionReasons(context, source, item.group, item, evaluationRules).toSet()).toList()
            }
            val rollbacks = if (retainedConflicts.isNotEmpty()) {
                val exactBodyDependencies = retainedConflicts.map { reason ->
                    if (reason.code == BcPermissionReasonCode.TargetPrerequisiteNotMet) BODY_PREREQUISITE_GROUPS[reason.arguments["prerequisite"]] else null
                }
                val bodyChanges = if (exactBodyDependencies.all { it != null }) {
                    exactBodyDependencies.filterNotNull().filter { it in changed }.toSet()
                } else changed.filter { rules.group(it)?.kind == BcAppearanceGroupKind.Appearance }.toSet()
                if (bodyChanges.isEmpty()) changed else bodyChanges + failures.keys
            } else failures.keys
            if (rollbacks.isEmpty()) break
            rollbacks.forEach { name ->
                preserve(name, desired[name]?.firstOrNull()?.name, if (name in failures) BcWardrobeEntryReason.PermissionDenied else BcWardrobeEntryReason.FinalAppearanceIncompatible,
                    failures[name]?.joinToString(" ") { it.message })
            }
        }
        val appearance = finalItems.values.sortedBy(BcCharacterItem::group)
        return BcWardrobePlan(appearance, entries.values.toList(), hasChanges = appearance != source.appearance.sortedBy(BcCharacterItem::group), sourceState = context.sourceState())
    }

    private fun itemReason(wardrobe: BcWardrobeState, context: BcWardrobeContext, group: BcGroupRule, asset: BcAssetRule, bundle: BcWardrobeItem, rules: BcRuleCatalog): BcWardrobeEntryReason? = when {
        asset.unsupportedWardrobeMetadata.isNotEmpty() -> BcWardrobeEntryReason.UnsupportedMetadata
        !inventoryAvailable(wardrobe, context, asset, rules) -> BcWardrobeEntryReason.InventoryUnavailable
        BcItemPropertyJson.propertyKeys(bundle.propertyJson).any { it in LOCK_KEYS } || bundle.propertyJson.readBcPropertyTokens("Effect").contains("Lock") -> BcWardrobeEntryReason.UnsafeSlotProperty
        !group.allowColorize && bundle.colorJson != null && bundle.colorJson != asset.defaultColor?.asJsonArray() -> BcWardrobeEntryReason.ColorNotCustomizable
        bundle.colorJson == null && asset.defaultColor == null -> BcWardrobeEntryReason.UnsupportedMetadata
        else -> null
    }

    private fun inventoryAvailable(wardrobe: BcWardrobeState, context: BcWardrobeContext, asset: BcAssetRule, rules: BcRuleCatalog): Boolean {
        val inputs = wardrobe.privateInputs
        if (inputs.inventoryItems.orEmpty().any { it.group == asset.group && it.name == asset.name }) return true
        if (asset.inventoryId != null && asset.inventoryId in inputs.inventoryIds.orEmpty()) {
            val first = rules.assets.values.firstOrNull { it.inventoryId == asset.inventoryId }
            if (first != null && first.group == asset.group && first.name == asset.name) return true
        }
        if (asset.availableLocations.any { "ChatRoom".startsWith(it) || context.roomSpace == it }) return true
        if (asset.value == 0.0) return true
        return asset.buyGroup != null && rules.assets.values.any { it.buyGroup == asset.buyGroup && it.value == 0.0 && it.unsupportedWardrobeMetadata.isEmpty() }
    }

    private fun bundleToItem(bundle: BcWardrobeItem, asset: BcAssetRule, previous: BcCharacterItem?, rules: BcRuleCatalog): BcCharacterItem? {
        val extended = rules.extendedItem(bundle.group, bundle.name)
        if (extended != null && extended.supportLevel != BcExtendedItemSupportLevel.Supported) return null
        val migratedProperty = migrateLegacyType(bundle.propertyJson, extended) ?: return null
        val savedProperty = BcItemPropertyJson.withoutProperty(migratedProperty, "Expression")
        val property = BcItemPropertyJson.overlayRawProperties(BcItemPropertyJson.withoutProperty(extended?.defaultPropertyJson, "Expression"), savedProperty)
        val expression = BcItemPropertyJson.readPropertyValue(previous?.propertyJson, "Expression")
        val finalProperty = if (expression != null && expression != "null" && rules.group(bundle.group)?.allowExpression?.isNotEmpty() == true) {
            BcItemPropertyJson.overlayRawProperties(property, "{\"Expression\":$expression}")
        } else property
        return BcCharacterItem(bundle.group, bundle.name, bundle.colorJson ?: asset.defaultColor?.asJsonArray(), 0, finalProperty)
    }

    private fun appearanceBlockedGroups(context: BcWardrobeContext, self: BcCharacterSnapshot, wardrobe: BcWardrobeState): Set<String> {
        val level = ggtsLevel(wardrobe.privateInputs.gameJson) ?: 0.0
        if ((self.ownership?.memberNumber ?: 0) <= 0 && !hasNpcOwner(self, wardrobe) && level < 6 && npcOwnership(wardrobe)?.owned != true) return emptySet()
        val entry = context.privateLog?.firstOrNull { it.group == "OwnerRule" && it.name.startsWith("BlockAppearance") } ?: return emptySet()
        val codes = entry.name.removePrefix("BlockAppearance")
        return APPEARANCE_CODES.filterValues { it in codes }.keys
    }

    private fun permissionReasons(context: BcWardrobeContext, self: BcCharacterSnapshot, group: String, item: BcCharacterItem?, rules: BcRuleCatalog): List<BcPermissionReason> {
        val candidate = item?.let { BcInteractionCandidate.WearItem(self.memberNumber, group, it.name, it.propertyJson, it.difficulty) }
            ?: BcInteractionCandidate.RemoveItem(self.memberNumber, group)
        return when (val decision = permissionEngine.evaluate(BcPermissionRequest(
            BcPermissionContext(self, self, BcPermissionRoomContext(context.roomName.value, context.roomMemberNumbers, context.roomBlockCategories), rules, true), candidate,
        ))) {
            is BcPermissionDecision.Denied -> decision.reasons
            else -> emptyList()
        }
    }

    private fun isLocked(item: BcCharacterItem, rules: BcRuleCatalog): Boolean =
        item.propertyJson.readBcStringProperty("LockedBy") != null || "Lock" in item.propertyJson.readBcPropertyTokens("Effect") || "Lock" in rules.asset(item.group, item.name)?.effects.orEmpty()

    private fun hasNpcOwner(self: BcCharacterSnapshot, wardrobe: BcWardrobeState): Boolean =
        (wardrobe.privateInputs.owner ?: self.owner)?.trim()?.startsWith("NPC-") == true

    /** R129 leaves legacy Type in place when the compressed wardrobe migrates to TypeRecord. */
    private fun migrateLegacyType(propertyJson: String?, extended: BcExtendedItemRule?): String? {
        if ("Type" !in BcItemPropertyJson.propertyKeys(propertyJson)) return propertyJson ?: "{}"
        val rawRecord = BcItemPropertyJson.readPropertyValue(propertyJson, "TypeRecord")
        if (rawRecord?.startsWith("{") == true && rawRecord.endsWith("}")) {
            val record = BcItemPropertyJson.readTypeRecord(propertyJson)
            val keys = BcItemPropertyJson.propertyKeys(rawRecord)
            return propertyJson.takeIf { record.keys == keys && record.values.all { it >= 0 } }
        }
        val type = propertyJson.readBcStringProperty("Type") ?: return null
        if (extended == null || extended.supportLevel != BcExtendedItemSupportLevel.Supported) return null
        val record = when (extended.archetype) {
            BcExtendedItemArchetype.Typed,
            BcExtendedItemArchetype.Vibrating,
            -> {
                val option = extended.typedOptions.singleOrNull { it.name == type && it.index >= 0 } ?: return null
                mapOf(extended.recordKey to option.index)
            }
            BcExtendedItemArchetype.Modular -> {
                val tokens = Regex("([a-zA-Z]+)([0-9]+)").findAll(type).toList()
                if (tokens.isEmpty() || tokens.joinToString("") { it.value } != type) return null
                val modules = linkedMapOf<String, Int>()
                tokens.forEach { token ->
                    val key = token.groupValues[1]
                    val index = token.groupValues[2].toIntOrNull() ?: return null
                    val module = extended.modules.singleOrNull { it.key == key } ?: return null
                    if (module.options.none { it.index == index } || modules.put(key, index) != null) return null
                }
                modules
            }
            else -> return null
        }
        val json = record.entries.joinToString(",", "{", "}") { (key, index) -> "${key.asJsonString()}:$index" }
        return BcItemPropertyJson.overlayRawProperties(propertyJson, "{\"TypeRecord\":$json}")
    }

    private fun BcSessionSnapshot.self(): BcCharacterSnapshot? = roomCharacters.firstOrNull { it.isSelf && it.memberNumber == account?.memberNumber }

    fun context(snapshot: BcSessionSnapshot): BcWardrobeContext? {
        val self = snapshot.self() ?: return null
        val room = snapshot.currentRoom ?: return null
        return BcWardrobeContext(self, room.name, room.space, room.game, snapshot.roomCharacters.map { it.memberNumber }.toSet(),
            room.blockCategories, snapshot.wardrobeState, snapshot.selfRuleLog, snapshot.selfRuleLogSessionId, serverTimeMillis(snapshot))
    }
    private data class NestedObject(val json: String?, val valid: Boolean)
    private fun nestedObject(json: String?, key: String): NestedObject {
        val value = BcItemPropertyJson.readPropertyValue(json, key)
        return NestedObject(value?.takeUnless { it == "null" }, value == null || value == "null" || value.startsWith("{") && value.endsWith("}"))
    }

    private fun ggtsLevel(game: String?): Double? {
        val objectValue = nestedObject(game, "GGTS")
        if (!objectValue.valid) return null
        val raw = BcItemPropertyJson.readPropertyValue(objectValue.json, "Level")
        if (raw == null || raw == "null") return 0.0
        val level = raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return level.takeIf { it >= 1.0 && it <= 10.0 } ?: 0.0
    }

    private data class NpcOwnership(val owned: Boolean, val fullyOwned: Boolean)

    private fun npcOwnership(wardrobe: BcWardrobeState): NpcOwnership? {
        val inputs = wardrobe.privateInputs
        if (!inputs.privateCharacterAvailable) return null
        val characters = objectArray(inputs.privateCharacterJson) ?: return null
        var trial = false
        var collar = false
        characters.forEach { character ->
            val events = objectArray(BcItemPropertyJson.readPropertyValue(character, "Event")) ?: return null
            fun value(name: String): Double? {
                val event = events.firstOrNull { it.readBcStringProperty("Name") == name } ?: return 0.0
                return BcItemPropertyJson.readPropertyValue(event, "Value")?.toDoubleOrNull()?.takeIf { it.isFinite() }
            }
            val trialValue = value("EndSubTrial") ?: return null
            val collarValue = value("PlayerCollaring") ?: return null
            trial = trial || trialValue > 0
            collar = collar || collarValue > 0
        }
        return NpcOwnership(trial, collar)
    }

    /** Private NPC Event arrays are JSON data, never text searched for rule names. */
    private fun objectArray(json: String?): List<String>? {
        if (json == null || json.trim() == "null") return emptyList()
        val source = json.trim()
        if (!source.startsWith("[") || !source.endsWith("]")) return null
        val items = mutableListOf<String>()
        var index = 1
        while (index < source.lastIndex) {
            while (index < source.lastIndex && source[index].isWhitespace()) index++
            if (index == source.lastIndex) break
            if (source[index] != '{') return null
            val start = index
            var depth = 0
            var quoted = false
            var escaped = false
            do {
                val char = source[index++]
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
                if (index >= source.lastIndex && depth != 0) return null
            } while (depth != 0)
            if (quoted || source[index - 1] != '}') return null
            items += source.substring(start, index)
            while (index < source.lastIndex && source[index].isWhitespace()) index++
            if (index < source.lastIndex) {
                if (source[index++] != ',') return null
                if (source.substring(index, source.lastIndex).isBlank()) return null
            }
        }
        return items
    }

    private fun String.asJsonString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    private fun List<String>.asJsonArray(): String = joinToString(",", "[", "]") { it.asJsonString() }

    private companion object {
        val BODY_PREREQUISITE_GROUPS = mapOf(
            "HasVagina" to "Pussy", "HasVulva" to "Pussy", "HasPenis" to "Pussy",
            "HasBreasts" to "BodyUpper", "HasFlatChest" to "BodyUpper",
            "CanHaveErection" to "Pussy", "CanBeLimp" to "Pussy",
        )
        val LOCK_KEYS = setOf("LockedBy", "LockMemberNumber", "LockMemberName", "LockMessage", "EnableRandomInput", "RemoveItem", "ShowTimer", "CombinationNumber", "Password", "Hint", "LockSet", "LockPickSeed", "MemberNumberList", "RemoveTimer", "MemberNumberListKeys")
        val APPEARANCE_CODES = mapOf(
            "Cloth" to 'A', "ClothAccessory" to 'B', "Necklace" to 'C', "Suit" to 'D', "ClothLower" to 'E', "SuitLower" to 'F', "Bra" to 'G', "Corset" to 'H', "Panties" to 'I', "Socks" to 'J', "SocksRight" to '(', "SocksLeft" to ')', "AnkletRight" to 'K', "AnkletLeft" to 'L', "Garters" to 'M', "Shoes" to 'N', "Hat" to 'O', "HairAccessory3" to 'P', "HairAccessory1" to 'Q', "HairAccessory2" to 'R', "Gloves" to 'S', "HandAccessoryLeft" to '!', "HandAccessoryRight" to '$', "Bracelet" to 'T', "Glasses" to 'U', "Jewelry" to '[', "Mask" to 'V', "TailStraps" to 'W', "Wings" to 'X', "Height" to '0', "BodyUpper" to '1', "BodyLower" to '2', "HairFront" to '3', "FacialHair" to '?', "HairBack" to '4', "Eyebrows" to '*', "Head" to ']', "Eyes" to '5', "Eyes2" to '6', "Mouth" to '7', "Nipples" to '8', "Pussy" to '9', "Pronouns" to '%', "EyeShadow" to '^', "ClothOuter" to '!', "Decals" to '\'',
        )
    }
}
