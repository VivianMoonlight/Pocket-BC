package dev.vivian.mobilebc.domain.bcspeech

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcPrivateLogMutation
import dev.vivian.mobilebc.core.model.BcRoomChatRejection
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import java.util.Locale

sealed interface BcRoomChatPreparation {
    data class Prepared(
        val text: String,
        val effects: List<String> = emptyList(),
        val original: String? = null,
        val logMutation: BcPrivateLogMutation? = null,
    ) : BcRoomChatPreparation

    data class Rejected(val rejection: BcRoomChatRejection) : BcRoomChatPreparation
}

/** Pure preparation; account-log consequences are committed only after transport emission. */
fun prepareBcRoomChat(
    text: String,
    kind: BcMessageKind,
    self: BcCharacterSnapshot?,
    roomCharacters: List<BcCharacterSnapshot>,
    privateState: BcPrivateChatState?,
    serverTimeMillis: Long?,
): BcRoomChatPreparation {
    if (kind !in setOf(BcMessageKind.Chat, BcMessageKind.Emote, BcMessageKind.Whisper)) {
        return BcRoomChatPreparation.Prepared(text)
    }
    self ?: return rejected(BcRoomChatRejectionReason.SelfStateUnavailable)
    privateState ?: return rejected(BcRoomChatRejectionReason.AccountStateUnavailable)
    val logs = privateState.ruleLog.orEmpty()
    val owner = self.ownership?.memberNumber?.takeIf { it > 0 }
    val ownerPresent = owner != null && (self.ownership?.stage ?: 0) >= 1 &&
        roomCharacters.any { it.memberNumber == owner }
    if (ownerPresent && kind != BcMessageKind.Whisper) {
        val name = if (kind == BcMessageKind.Chat) "BlockTalk" else "BlockEmote"
        when (activeRule(logs, name, serverTimeMillis)) {
            null -> return rejected(BcRoomChatRejectionReason.OwnerRulesUnavailable)
            true -> return rejected(if (kind == BcMessageKind.Chat) BcRoomChatRejectionReason.TalkBlockedByOwner else BcRoomChatRejectionReason.EmoteBlockedByOwner)
            false -> Unit
        }
    }
    var mutation: BcPrivateLogMutation? = null
    // Whisper has no forbidden-word or owner-presence send gate in R129.
    if (kind == BcMessageKind.Chat && (owner != null || self.owner?.trim()?.startsWith("NPC-") == true)) {
        when (activeRule(logs, "BlockTalkForbiddenWords", serverTimeMillis)) {
            null -> return rejected(BcRoomChatRejectionReason.OwnerRulesUnavailable)
            true -> return rejected(BcRoomChatRejectionReason.TalkMutedByForbiddenWord)
            false -> Unit
        }
        // Native selects the last prefix entry without checking its expiry here.
        val words = logs.lastOrNull { it.group == "OwnerRule" && it.name.startsWith("ForbiddenWords") }
            ?.name?.let { it.substring(14, minOf(it.length, 10_000)).split('|') }
        val consequence = words?.firstOrNull()?.trim()
        if (consequence in setOf("Block", "Mute5", "Mute15", "Mute30")) {
            val tokens = text.trim().uppercase(Locale.ROOT).replace("-", "")
                .replace(' ', '|').replace(',', '|').replace('.', '|').split('|')
            val forbidden = words!!.drop(1).toSet()
            val word = tokens.firstOrNull { it.isNotEmpty() && it in forbidden }
            if (word != null) {
                if (consequence == "Block") return rejected(BcRoomChatRejectionReason.ForbiddenWord, word)
                val now = serverTimeMillis?.takeIf { it >= 0 } ?: return rejected(BcRoomChatRejectionReason.OwnerRulesUnavailable)
                val duration = consequence!!.removePrefix("Mute").toLong() * 60_000L
                val expiry = runCatching { Math.addExact(now, duration) }.getOrNull()
                    ?: return rejected(BcRoomChatRejectionReason.OwnerRulesUnavailable)
                mutation = BcPrivateLogMutation.Add("BlockTalkForbiddenWords", "OwnerRule", expiry)
            }
        }
    }
    if (kind == BcMessageKind.Emote) return BcRoomChatPreparation.Prepared(text)
    return BcRoomChatPreparation.Prepared(
        text = if (privateState.preferences.oocAutoClose) BcSpeechTransform.closeOoc(text) else text,
        logMutation = mutation,
    )
}

private fun activeRule(logs: List<BcOwnerRuleLogRecord>, name: String, now: Long?): Boolean? {
    val matching = logs.firstOrNull { it.name == name && it.group == "OwnerRule" } ?: return false
    val expiry = matching.expiresAtMillis ?: return true
    now ?: return null
    return expiry >= now
}

/** Native name-click target selection gate, separate from the Whisper send path. */
fun bcWhisperSelectionRejection(
    self: BcCharacterSnapshot?,
    roomCharacters: List<BcCharacterSnapshot>,
    privateState: BcPrivateChatState?,
    serverTimeMillis: Long?,
    targetMemberNumber: Int,
): BcRoomChatRejection? {
    self ?: return BcRoomChatRejection(BcRoomChatRejectionReason.SelfStateUnavailable)
    val owner = self.ownership?.memberNumber?.takeIf { it > 0 } ?: return null
    if (targetMemberNumber == owner || (self.ownership?.stage ?: 0) < 1 ||
        roomCharacters.none { it.memberNumber == owner }) return null
    val logs = privateState?.ruleLog ?: return BcRoomChatRejection(BcRoomChatRejectionReason.OwnerRulesUnavailable)
    return when (activeRule(logs, "BlockWhisper", serverTimeMillis)) {
        null -> BcRoomChatRejection(BcRoomChatRejectionReason.OwnerRulesUnavailable)
        true -> BcRoomChatRejection(BcRoomChatRejectionReason.WhisperBlockedByOwner)
        false -> null
    }
}

private fun rejected(reason: BcRoomChatRejectionReason, word: String? = null) =
    BcRoomChatPreparation.Rejected(BcRoomChatRejection(reason, word))
