package dev.vivian.mobilebc.domain.bcselfstate

import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterPoseState
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.bccharacterstate.BcCharacterItemSource
import dev.vivian.mobilebc.domain.bccharacterstate.DefaultBcCharacterStateResolver
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.estimatedServerTimeMillis

/** Captures the account and room visit that own the visible editor. */
data class BcSelfStateRequest(
    val targetMemberNumber: Int,
    val roomName: BcRoomName,
    val roomPresenceEpoch: Long,
    val accountGeneration: Long,
    val change: BcSelfStateChange,
    val rules: BcRuleCatalog,
)

data class BcSelfStateOption(
    val name: String?,
    val selected: Boolean,
    val unavailableReason: BcSelfStateUnavailableReason? = null,
)

data class BcSelfPoseGroup(
    val category: String,
    val currentName: String?,
    val options: List<BcSelfStateOption>,
)

data class BcSelfExpressionGroup(
    val group: String,
    val currentName: String?,
    val secondaryCurrentName: String? = null,
    val hasDifferentEyes: Boolean = false,
    val options: List<BcSelfStateOption>,
)

data class BcSelfStateProjection(
    val character: BcCharacterSnapshot?,
    val unavailableReason: BcSelfStateUnavailableReason? = null,
    val poseGroups: List<BcSelfPoseGroup> = emptyList(),
    val expressionGroups: List<BcSelfExpressionGroup> = emptyList(),
    val resetPoseReason: BcSelfStateUnavailableReason? = null,
    val resetExpressionsReason: BcSelfStateUnavailableReason? = null,
)

/** The same checks serve the visible options and the last snapshot immediately before sending. */
class BcSelfStateRules(
    private val serverTimeMillis: (BcSessionSnapshot) -> Long? = { it.estimatedServerTimeMillis() },
) {
    fun projection(snapshot: BcSessionSnapshot, rules: BcRuleCatalog?): BcSelfStateProjection {
        val self = snapshot.roomCharacters.firstOrNull { it.isSelf && it.memberNumber == snapshot.account?.memberNumber }
        val reason = sessionReason(snapshot) ?: catalogReason(snapshot, rules)
        if (reason != null || self == null || rules == null) {
            return BcSelfStateProjection(character = self, unavailableReason = reason ?: BcSelfStateUnavailableReason.SelfUnavailable)
        }
        val poseMapping = effectivePoseMapping(self, rules)
        val poseGroups = rules.poses.values.filter { it.allowMenu || it.allowMenuTransient }
            .groupBy { it.category }.mapNotNull { (category, poses) ->
                category ?: return@mapNotNull null
                BcSelfPoseGroup(
                    category = category,
                    currentName = poseMapping[category],
                    options = poses.map { pose ->
                        BcSelfStateOption(pose.name, poseMapping[category] == pose.name, poseReason(snapshot, self, rules, pose.name))
                    },
                )
            }
        val expressionGroups = editableExpressionGroups(self, rules).map { group ->
            val current = expressionName(self, group)
            val otherEye = expressionName(self, "Eyes2")
            val differentEyes = group == "Eyes" && current != otherEye
            BcSelfExpressionGroup(
                group = group,
                currentName = current,
                secondaryCurrentName = otherEye.takeIf { group == "Eyes" },
                hasDifferentEyes = differentEyes,
                options = (listOf(null) + rules.group(group)?.allowExpression.orEmpty()).distinct().map { name ->
                    BcSelfStateOption(name, current == name && !differentEyes, expressionReason(self, rules, group, name))
                },
            )
        }
        return BcSelfStateProjection(
            character = self,
            poseGroups = poseGroups,
            expressionGroups = expressionGroups,
            resetPoseReason = resetPoseReason(snapshot, self, rules),
            resetExpressionsReason = resetExpressionsReason(self, rules),
        )
    }

    fun rejection(request: BcSelfStateRequest, snapshot: BcSessionSnapshot): BcSelfStateUnavailableReason? {
        sessionReason(snapshot)?.let { return it }
        val self = snapshot.roomCharacters.first { it.isSelf && it.memberNumber == snapshot.account?.memberNumber }
        if (self.memberNumber != request.targetMemberNumber || snapshot.relationships.accountGeneration != request.accountGeneration) {
            return BcSelfStateUnavailableReason.AccountChanged
        }
        if (snapshot.currentRoom?.name != request.roomName || snapshot.roomPresenceEpoch != request.roomPresenceEpoch) {
            return BcSelfStateUnavailableReason.RoomChanged
        }
        catalogReason(snapshot, request.rules)?.let { return it }
        return when (val change = request.change) {
            is BcSelfStateChange.Pose -> poseReason(snapshot, self, request.rules, change.name)
            BcSelfStateChange.ResetPose -> resetPoseReason(snapshot, self, request.rules)
            is BcSelfStateChange.Expression -> expressionReason(self, request.rules, change.group, change.name)
            BcSelfStateChange.ResetExpressions -> resetExpressionsReason(self, request.rules)
        }
    }

    fun poseUpdate(self: BcCharacterSnapshot, rules: BcRuleCatalog, change: BcSelfStateChange): List<String> {
        if (change == BcSelfStateChange.ResetPose) return listOf("BaseLower", "BaseUpper")
        val pose = rules.pose((change as BcSelfStateChange.Pose).name) ?: error("Invalid pose")
        if (pose.category == "BodyFull") return listOf(pose.name)
        // PoseSetActive removes previous transient/full/item-only poses before editing one category.
        val retained = linkedMapOf<String, String>()
        self.activePose.forEach { name ->
            val previous = rules.pose(name)
            if (previous?.allowMenu == true && previous.category != null && previous.category != "BodyFull" && previous.category != pose.category) {
                retained[previous.category] = name
            }
        }
        return retained.values.toList() + pose.name
    }

    fun expressionUpdates(self: BcCharacterSnapshot, rules: BcRuleCatalog, change: BcSelfStateChange): List<BcExpressionUpdateCommand> {
        val entries = when (change) {
            is BcSelfStateChange.Expression -> expressionTargets(change.group).map { it to change.name }
            BcSelfStateChange.ResetExpressions -> resetExpressionTargets(self, rules).map { it to null }
            else -> return emptyList()
        }
        val changedAppearance = self.appearance.map { item ->
            val entry = entries.firstOrNull { it.first == item.group } ?: return@map item
            item.copy(propertyJson = BcItemPropertyJson.mergeForModification(
                currentPropertyJson = item.propertyJson,
                optionPropertyJson = "{\"Expression\":${entry.second?.let(::jsonString) ?: "null"}}",
            ))
        }
        return entries.map { (group, name) -> BcExpressionUpdateCommand(group, name, changedAppearance) }
    }

    private fun sessionReason(snapshot: BcSessionSnapshot): BcSelfStateUnavailableReason? = when {
        !snapshot.isVerifiedOnline -> BcSelfStateUnavailableReason.Offline
        snapshot.currentRoom == null -> BcSelfStateUnavailableReason.NotInRoom
        snapshot.roomCharacters.none { it.isSelf && it.memberNumber == snapshot.account?.memberNumber } -> BcSelfStateUnavailableReason.SelfUnavailable
        snapshot.roomCharacters.first { it.isSelf && it.memberNumber == snapshot.account?.memberNumber }.assetFamily != "Female3DCG" -> BcSelfStateUnavailableReason.UnsupportedAssetFamily
        else -> null
    }

    private fun catalogReason(snapshot: BcSessionSnapshot, rules: BcRuleCatalog?): BcSelfStateUnavailableReason? {
        val version = snapshot.bcVersion
        return if (rules == null || rules.poses.isEmpty() || version != null && version.normalizedBcVersionOrNull() != rules.version) {
            BcSelfStateUnavailableReason.CatalogPreparing
        } else null
    }

    private fun resetPoseReason(snapshot: BcSessionSnapshot, self: BcCharacterSnapshot, rules: BcRuleCatalog): BcSelfStateUnavailableReason? =
        poseReason(snapshot, self, rules, "BaseLower") ?: poseReason(snapshot, self, rules, "BaseUpper")

    private fun poseReason(snapshot: BcSessionSnapshot, self: BcCharacterSnapshot, rules: BcRuleCatalog, name: String): BcSelfStateUnavailableReason? {
        val pose = rules.pose(name)
        if (pose == null || pose.category == null || !pose.allowMenu && !pose.allowMenuTransient) return BcSelfStateUnavailableReason.InvalidPose
        ownerPoseReason(snapshot, self)?.let { return it }
        val allowed = allowedPoseMapping(self, rules)[pose.category]
        return if (allowed != null && name !in allowed) BcSelfStateUnavailableReason.PoseRestrictedByItem else null
    }

    private fun allowedPoseMapping(self: BcCharacterSnapshot, rules: BcRuleCatalog): Map<String, Set<String>> = buildMap {
        self.appearance.forEach { item ->
            val allowed = propertyTokens(item, "AllowActivePose") ?: rules.asset(item.group, item.name)?.allowActivePose.orEmpty()
            if (allowed.isEmpty()) return@forEach
            val categories = allowed.mapNotNull { pose -> poseCategory(pose, rules)?.let { it to pose } }.groupBy({ it.first }, { it.second })
            categories.forEach { (category, names) -> put(category, get(category)?.intersect(names.toSet()) ?: names.toSet()) }
            if (("BodyUpper" in categories || "BodyLower" in categories) && "BodyFull" !in categories) put("BodyFull", emptySet())
        }
    }

    private fun effectivePoseMapping(self: BcCharacterSnapshot, rules: BcRuleCatalog): Map<String, String> {
        val allowed = allowedPoseMapping(self, rules)
        val candidates = linkedMapOf<String, MutableList<String>>()
        val active = linkedMapOf<String, String>()
        self.activePose.forEach { name -> poseCategory(name, rules)?.let { active[it] = name } }
        active.forEach { (category, name) -> candidates.getOrPut(category, ::mutableListOf).add(name) }
        self.appearance.forEach { item ->
            val allow = propertyTokens(item, "AllowActivePose") ?: rules.asset(item.group, item.name)?.allowActivePose.orEmpty()
            if (allow.isEmpty()) return@forEach
            val set = propertyTokens(item, "SetPose") ?: rules.asset(item.group, item.name)?.setPose.orEmpty()
            set.forEach { name -> poseCategory(name, rules)?.let { candidates.getOrPut(it, ::mutableListOf).add(name) } }
        }
        return buildMap {
            candidates.forEach { (category, names) -> names.firstOrNull { allowed[category]?.contains(it) != false }?.let { put(category, it) } }
            if ("BodyFull" in this) {
                remove("BodyUpper")
                remove("BodyLower")
            } else {
                putIfAbsent("BodyUpper", "BaseUpper")
                putIfAbsent("BodyLower", "BaseLower")
            }
        }
    }

    private fun poseCategory(name: String, rules: BcRuleCatalog): String? = rules.pose(name)?.category ?: BcCharacterPoseState.categoryOf(name)

    private fun editableExpressionGroups(self: BcCharacterSnapshot, rules: BcRuleCatalog): List<String> = rules.groups.values
        .filter { it.allowExpression != null && it.name != "Eyes2" && (it.name != "Pussy" || self.body.hasPenis) }
        .map { it.name }

    private fun expressionReason(self: BcCharacterSnapshot, rules: BcRuleCatalog, group: String, name: String?): BcSelfStateUnavailableReason? {
        if (group !in editableExpressionGroups(self, rules) || name != null && name !in rules.group(group)?.allowExpression.orEmpty()) {
            return BcSelfStateUnavailableReason.InvalidExpression
        }
        return expressionTargets(group).firstNotNullOfOrNull { target -> expressionItemReason(self, rules, target, name) }
    }

    private fun resetExpressionTargets(self: BcCharacterSnapshot, rules: BcRuleCatalog): List<String> = self.appearance
        .filter { rules.group(it.group)?.allowExpression != null }.map { it.group }.distinct()

    private fun resetExpressionsReason(self: BcCharacterSnapshot, rules: BcRuleCatalog): BcSelfStateUnavailableReason? {
        val targets = resetExpressionTargets(self, rules)
        if (targets.isEmpty()) return BcSelfStateUnavailableReason.ExpressionItemMissing
        return targets.firstNotNullOfOrNull { expressionItemReason(self, rules, it, null) }
    }

    private fun expressionItemReason(self: BcCharacterSnapshot, rules: BcRuleCatalog, target: String, name: String?): BcSelfStateUnavailableReason? {
        val item = self.appearance.firstOrNull { it.group == target } ?: return BcSelfStateUnavailableReason.ExpressionItemMissing
        val asset = rules.asset(item.group, item.name)
        val groupRule = rules.group(item.group)
        val allowedValues = expressionValues(
            item, "AllowExpression", asset?.allowExpression, groupRule?.allowExpression,
            asset?.unsupportedExpressionMetadata.orEmpty(), groupRule?.unsupportedExpressionMetadata.orEmpty(),
        )
        val prerequisiteValues = expressionValues(
            item, "ExpressionPrerequisite", asset?.expressionPrerequisites, groupRule?.expressionPrerequisites,
            asset?.unsupportedExpressionMetadata.orEmpty(), groupRule?.unsupportedExpressionMetadata.orEmpty(),
        )
        if (allowedValues.invalid || prerequisiteValues.invalid) return BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite
        val allowed = allowedValues.values.orEmpty()
        if (name != null && name !in allowed) return BcSelfStateUnavailableReason.ExpressionRestrictedByItem
        val prerequisite = prerequisiteValues.values.orEmpty().getOrNull(allowed.indexOf(name))
        if (prerequisite != null) {
            val state = DefaultBcCharacterStateResolver().resolve(self, rules, BcCharacterItemSource.Actor)
            val permitted = when (prerequisite) {
                "CanBeLimp" -> self.body.hasPenis && !state.hasForcedErection()
                "CanHaveErection" -> self.body.hasPenis && !state.isVulvaChaste()
                else -> return BcSelfStateUnavailableReason.UnsupportedExpressionPrerequisite
            }
            if (!permitted) return BcSelfStateUnavailableReason.ExpressionPrerequisiteNotMet
        }
        return null
    }

    private fun expressionTargets(group: String): List<String> = if (group == "Eyes") listOf("Eyes", "Eyes2") else listOf(group)

    private fun expressionName(self: BcCharacterSnapshot, group: String): String? = self.appearance.firstOrNull { it.group == group }?.let { item ->
        BcItemPropertyJson.readStringValue(BcItemPropertyJson.readPropertyValue(item.propertyJson, "Expression"))
    }

    private fun propertyTokens(item: BcCharacterItem, key: String): Set<String>? =
        BcItemPropertyJson.readPropertyValue(item.propertyJson, key)?.takeUnless { it == "null" }?.let(BcItemPropertyJson::readTokenValues)

    private fun ownerPoseReason(snapshot: BcSessionSnapshot, self: BcCharacterSnapshot): BcSelfStateUnavailableReason? {
        val ownership = self.ownership ?: return null
        if ((ownership.stage ?: 0) < 1 || snapshot.roomCharacters.none { it.memberNumber == ownership.memberNumber }) return null
        val logs = snapshot.selfRuleLog ?: return BcSelfStateUnavailableReason.OwnerRulesUnavailable
        val matchingLogs = logs.filter { it.name == "BlockChangePose" && it.group == "OwnerRule" }
        if (matchingLogs.any { it.expiresAtMillis == null }) return BcSelfStateUnavailableReason.PoseBlockedByOwner
        if (matchingLogs.isEmpty()) return null
        val now = serverTimeMillis(snapshot) ?: return BcSelfStateUnavailableReason.OwnerRulesUnavailable
        return if (matchingLogs.any { checkNotNull(it.expiresAtMillis) >= now }) BcSelfStateUnavailableReason.PoseBlockedByOwner else null
    }

    private data class NullableStrings(val values: List<String?>? = null, val invalid: Boolean = false)

    private fun expressionValues(
        item: BcCharacterItem,
        key: String,
        assetValues: List<String?>?,
        groupValues: List<String?>?,
        unsupportedAssetKeys: Set<String>,
        unsupportedGroupKeys: Set<String>,
    ): NullableStrings {
        val property = nullableStringArray(BcItemPropertyJson.readPropertyValue(item.propertyJson, key))
        if (property.invalid || property.values != null) return property
        if (key in unsupportedAssetKeys) return NullableStrings(invalid = true)
        if (assetValues != null) return NullableStrings(assetValues)
        if (key in unsupportedGroupKeys) return NullableStrings(invalid = true)
        return NullableStrings(groupValues.orEmpty())
    }

    private fun nullableStringArray(raw: String?): NullableStrings {
        if (raw == null || raw == "null") return NullableStrings()
        val value = raw.trim()
        if (!value.startsWith("[") || !value.endsWith("]")) return NullableStrings(invalid = true)
        val body = value.substring(1, value.lastIndex).trim()
        if (body.isEmpty()) return NullableStrings(emptyList())
        val token = Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|null")
        val values = mutableListOf<String?>()
        var index = 0
        while (index < body.length) {
            val match = token.find(body, index)?.takeIf { it.range.first == index } ?: return NullableStrings(invalid = true)
            values.add(if (match.value == "null") null else BcItemPropertyJson.readStringValue(match.value))
            index = match.range.last + 1
            while (index < body.length && body[index].isWhitespace()) index++
            if (index == body.length) break
            if (body[index++] != ',') return NullableStrings(invalid = true)
            while (index < body.length && body[index].isWhitespace()) index++
            if (index == body.length) return NullableStrings(invalid = true)
        }
        return NullableStrings(values)
    }

    private fun jsonString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}
