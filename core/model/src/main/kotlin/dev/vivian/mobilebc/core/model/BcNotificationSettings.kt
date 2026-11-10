package dev.vivian.mobilebc.core.model

/** Account-owned routing and suppression. Android channels own interruption behavior. */
data class BcNotificationSettings(
    val suppressRoomMessagesWhenForeground: Boolean = true,
    val suppressDirectMessagesWhenForeground: Boolean = false,
    val suppressSystemAlertsWhenForeground: Boolean = false,
    val matrix: Map<String, BcNotificationLevel> = emptyMap(),
    val rules: List<BcNotificationRule> = emptyList(),
    val systemLevel: BcNotificationLevel = BcNotificationLevel.Important,
) {
    fun level(identity: BcNotificationIdentity, type: BcMessageKind): BcNotificationLevel =
        matrix["${identity.name}:${type.name}"] ?: when (type) {
            BcMessageKind.Whisper, BcMessageKind.Beep -> BcNotificationLevel.Important
            else -> BcNotificationLevel.Normal
        }

    fun decide(input: BcNotificationInput): BcNotificationDecision {
        rules.firstOrNull { it.matches(input) }?.let {
            return BcNotificationDecision(it.level, it.id)
        }
        return BcNotificationDecision(
            if (input.system) systemLevel else
                input.identities.maxOfOrNull { level(it, input.type) } ?: BcNotificationLevel.Normal,
        )
    }

    fun shouldSuppressWhenForeground(policy: BcNotificationPolicy): Boolean = when (policy) {
        BcNotificationPolicy.RoomMessage -> suppressRoomMessagesWhenForeground
        BcNotificationPolicy.DirectMessage -> suppressDirectMessagesWhenForeground
        BcNotificationPolicy.SystemAlert -> suppressSystemAlertsWhenForeground
        BcNotificationPolicy.None -> true
    }
}

enum class BcNotificationLevel { Off, Normal, Important, Critical }
enum class BcNotificationIdentity { Stranger, Friend, Lover, Owner, Submissive, Unknown }

data class BcNotificationInput(
    val memberNumber: Int?,
    val identities: Set<BcNotificationIdentity>,
    val type: BcMessageKind,
    val text: String,
    val involvesSelf: Boolean = false,
    val system: Boolean = false,
)

data class BcNotificationDecision(val level: BcNotificationLevel, val ruleId: String? = null)

data class BcNotificationRule(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val matchAll: Boolean = true,
    val memberNumber: Int? = null,
    val identity: BcNotificationIdentity? = null,
    val type: BcMessageKind? = null,
    val keyword: String = "",
    val involvesSelf: Boolean? = null,
    val level: BcNotificationLevel = BcNotificationLevel.Important,
) {
    fun matches(input: BcNotificationInput): Boolean {
        if (!enabled) return false
        val conditions = buildList {
            memberNumber?.let { add(it == input.memberNumber) }
            identity?.let { add(it in input.identities) }
            type?.let { add(it == input.type) }
            if (keyword.isNotBlank()) add(input.text.contains(keyword, ignoreCase = true))
            involvesSelf?.let { add(it == input.involvesSelf) }
        }
        return conditions.isNotEmpty() && if (matchAll) conditions.all { it } else conditions.any { it }
    }
}
