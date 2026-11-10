package dev.vivian.mobilebc.core.model

/**
 * Stable account identity used when posting message notifications.
 *
 * This is intentionally smaller than a full account/session model so platform notification code
 * can scope IDs, groups, titles, and pending-intent request codes without depending on service or
 * runtime modules.
 */
data class BcNotificationAccountContext(
    val accountName: String?,
    val displayName: String?,
    val ownerMemberNumber: Int?,
) {
    val stableKey: String
        get() = ownerMemberNumber?.toString()
            ?: accountName?.takeIf { it.isNotBlank() }
            ?: UNKNOWN_ACCOUNT_KEY

    val displayLabel: String?
        get() = when {
            !displayName.isNullOrBlank() && ownerMemberNumber != null -> "$displayName (#$ownerMemberNumber)"
            !displayName.isNullOrBlank() -> displayName
            !accountName.isNullOrBlank() && ownerMemberNumber != null -> "$accountName (#$ownerMemberNumber)"
            !accountName.isNullOrBlank() -> accountName
            ownerMemberNumber != null -> "#$ownerMemberNumber"
            else -> null
        }

    companion object {
        const val UNKNOWN_ACCOUNT_KEY = "unknown"
    }
}
