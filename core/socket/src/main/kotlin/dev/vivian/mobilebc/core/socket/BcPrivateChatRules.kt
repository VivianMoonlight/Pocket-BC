package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.ServerAccountData
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcPrivateChatPreferences
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateLogMutation

internal fun ServerAccountData.toBcPrivateChatState(): BcPrivateChatState {
    val difficultyLevel = difficulty?.level?.takeIf { it in 0..3 } ?: 1
    fun boolean(fields: com.google.gson.JsonElement?, name: String, fallback: Boolean): Boolean =
        fields?.takeIf { it.isJsonObject }?.asJsonObject?.get(name)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean ?: fallback
    return BcPrivateChatState(
        rawLogJson = log?.toString(),
        ruleLog = toBcSelfRuleLog(),
        preferences = BcPrivateChatPreferences(
            onlineSettingsJson = onlineSettings?.toString(),
            oocAutoClose = boolean(chatSettings, "OOCAutoClose", true),
            muStylePoses = boolean(chatSettings, "MuStylePoses", false),
            noSpeechGarble = difficultyLevel == 0 && boolean(restrictionSettings, "NoSpeechGarble", false),
            difficultyLevel = difficultyLevel,
            chatSettingsJson = chatSettings?.toString(),
            restrictionSettingsJson = restrictionSettings?.toString(),
        ),
    )
}

/** Preserves the complete source array and unknown fields when updating BC's loaded log keys. */
internal fun applyPrivateLogMutation(
    state: BcPrivateChatState,
    mutation: BcPrivateLogMutation,
): BcPrivateChatState? {
    if (state.ruleLog == null) return null
    val entries = runCatching { JsonParser.parseString(state.rawLogJson) }.getOrNull()
        ?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
    if (entries.toBcRuleLog() != state.ruleLog) return null
    fun matches(entry: JsonObject, name: String, group: String): Boolean =
        entry["Name"].asString == name && entry["Group"].asString == group
    fun setValue(entry: JsonObject, expiresAtMillis: Long?) {
        if (expiresAtMillis == null) entry.remove("Value") else entry.addProperty("Value", expiresAtMillis)
    }
    when (mutation) {
        is BcPrivateLogMutation.Add -> {
            val matching = entries.filter { matches(it.asJsonObject, mutation.name, mutation.group) }
            if (matching.isEmpty()) {
                entries.add(JsonObject().apply {
                    addProperty("Name", mutation.name)
                    addProperty("Group", mutation.group)
                    setValue(this, mutation.expiresAtMillis)
                })
            } else {
                // LogLoad deduplicates at the first position with the last value. Keep every
                // source record's extra fields while renewing the same loaded key consistently.
                matching.forEach { setValue(it.asJsonObject, mutation.expiresAtMillis) }
            }
        }
        is BcPrivateLogMutation.Delete -> {
            for (index in entries.size() - 1 downTo 0) {
                if (matches(entries[index].asJsonObject, mutation.name, mutation.group)) entries.remove(index)
            }
        }
        is BcPrivateLogMutation.DeleteGroup -> {
            for (index in entries.size() - 1 downTo 0) {
                if (entries[index].asJsonObject["Group"].asString == mutation.group) entries.remove(index)
            }
        }
        is BcPrivateLogMutation.ReplacePrefix -> {
            for (index in entries.size() - 1 downTo 0) {
                val entry = entries[index].asJsonObject
                if (entry["Group"].asString == mutation.group && entry["Name"].asString.startsWith(mutation.prefix)) {
                    entries.remove(index)
                }
            }
            entries.add(JsonObject().apply {
                addProperty("Name", mutation.name)
                addProperty("Group", mutation.group)
            })
        }
    }
    return state.copy(rawLogJson = entries.toString(), ruleLog = entries.toBcRuleLog())
}

internal sealed interface BcOwnerRuleUpdate {
    data object Ignore : BcOwnerRuleUpdate
    data object Invalidate : BcOwnerRuleUpdate
    data class Apply(val state: BcPrivateChatState, val resetWhisperTarget: Boolean = false) : BcOwnerRuleUpdate
}

/** Caller validates the Hidden frame's recipient, sender room presence and current receive context. */
internal fun planOwnerRule(
    state: BcPrivateChatState,
    content: String,
    sender: Int,
    ownerMemberNumber: Int?,
    ownershipStage: Int?,
    serverTimeMillis: Long?,
): BcOwnerRuleUpdate {
    if (!content.startsWith("OwnerRule") || sender < 0) return BcOwnerRuleUpdate.Ignore
    if (ownerMemberNumber != null && ownerMemberNumber != sender) return BcOwnerRuleUpdate.Ignore
    if (ownershipStage != null && ownershipStage < 1) return BcOwnerRuleUpdate.Ignore
    if (ownerMemberNumber == null || ownershipStage == null) return BcOwnerRuleUpdate.Invalidate
    val group = "OwnerRule"
    val toggles = mapOf(
        "Talk" to "BlockTalk", "Emote" to "BlockEmote", "Whisper" to "BlockWhisper",
        "ChangePose" to "BlockChangePose", "AccessSelf" to "BlockAccessSelf", "AccessOther" to "BlockAccessOther",
        "Key" to "BlockKey", "SelfOwnerLock" to "BlockOwnerLockSelf", "Remote" to "BlockRemote",
        "Nickname" to "BlockNickname",
    )
    var mutation: BcPrivateLogMutation? = null
    for ((command, logName) in toggles) {
        if (content == "OwnerRule${command}Allow") mutation = BcPrivateLogMutation.Delete(logName, group)
        if (content == "OwnerRule${command}Block") mutation = BcPrivateLogMutation.Add(logName, group)
    }
    when (content) {
        "OwnerRuleChangeAllow" -> mutation = BcPrivateLogMutation.Delete("BlockChange", group)
        "OwnerRuleKeyAllowFamily" -> mutation = BcPrivateLogMutation.Delete("BlockFamilyKey", group)
        "OwnerRuleKeyBlockFamily" -> mutation = BcPrivateLogMutation.Add("BlockFamilyKey", group)
        "OwnerRuleRemoteAllowSelf" -> mutation = BcPrivateLogMutation.Delete("BlockRemoteSelf", group)
        "OwnerRuleRemoteBlockSelf" -> mutation = BcPrivateLogMutation.Add("BlockRemoteSelf", group)
        "OwnerRuleCollarRelease", "OwnerRuleCollarWear" -> return BcOwnerRuleUpdate.Invalidate
    }
    val duration = when (content) {
        "OwnerRuleChangeBlock1Hour" -> 3_600_000L
        "OwnerRuleChangeBlock1Day" -> 86_400_000L
        "OwnerRuleChangeBlock1Week" -> 604_800_000L
        "OwnerRuleChangeBlock" -> 1_000_000_000_000L
        else -> null
    }
    if (duration != null) {
        val expiry = serverTimeMillis?.takeIf { it >= 0L }
            ?.let { runCatching { Math.addExact(it, duration) }.getOrNull() }
            ?: return BcOwnerRuleUpdate.Invalidate
        mutation = BcPrivateLogMutation.Add("BlockChange", group, expiry)
    }
    for ((prefix, limit) in listOf(
            "BlockScreen" to 100, "BlockAppearance" to 100, "BlockItemGroup" to 100, "ForbiddenWords" to 10_000,
        )) {
        val command = "OwnerRule$prefix"
        if (content.startsWith(command)) {
            mutation = BcPrivateLogMutation.ReplacePrefix(
                prefix, group, prefix + content.substring(command.length, minOf(content.length, limit)),
            )
        }
    }
    // Other OwnerRule commands change inventory, screens or nickname, and unknown commands
    // do not edit the native log. This reducer only executes account-log operations.
    val logMutation = mutation ?: return BcOwnerRuleUpdate.Ignore
    val updated = applyPrivateLogMutation(state, logMutation) ?: return BcOwnerRuleUpdate.Invalidate
    return BcOwnerRuleUpdate.Apply(updated, resetWhisperTarget = content == "OwnerRuleWhisperBlock")
}
