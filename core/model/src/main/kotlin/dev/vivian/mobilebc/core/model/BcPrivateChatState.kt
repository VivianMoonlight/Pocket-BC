package dev.vivian.mobilebc.core.model

/** Account-only preferences used by BC's outgoing chat path. */
data class BcPrivateChatPreferences(
    val oocAutoClose: Boolean = true,
    val muStylePoses: Boolean = false,
    val noSpeechGarble: Boolean = false,
    val difficultyLevel: Int = 1,
    val chatSettingsJson: String? = null,
    val restrictionSettingsJson: String? = null,
    val onlineSettingsJson: String? = null,
)

/** Private account state; an unavailable rule projection never becomes a trusted empty log. */
data class BcPrivateChatState(
    val rawLogJson: String?,
    val ruleLog: List<BcOwnerRuleLogRecord>?,
    val preferences: BcPrivateChatPreferences,
    val revision: Long = 0L,
)

data class BcAuthenticatedPrivateChatState(
    val memberNumber: Int,
    val selfRuleLogSessionId: String,
    val state: BcPrivateChatState,
)

/** A staged account-log edit. Preparing a chat message must not apply it. */
sealed interface BcPrivateLogMutation {
    data class Add(val name: String, val group: String, val expiresAtMillis: Long? = null) : BcPrivateLogMutation
    data class Delete(val name: String, val group: String) : BcPrivateLogMutation
    data class DeleteGroup(val group: String) : BcPrivateLogMutation
    data class ReplacePrefix(val prefix: String, val group: String, val name: String) : BcPrivateLogMutation
}
