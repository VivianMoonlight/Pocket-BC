package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.statusDescription

/**
 * Stable foreground-notification model derived from runtime and account-switch state.
 *
 * The service may see short-lived runtime states such as Stopped during account switching,
 * reconnect, or service rebinding. This model keeps notification wording aligned with
 * app-level availability instead of exposing every transient runtime state directly.
 */
data class BcServiceNotificationState(
    val availability: BcServiceAvailability,
    val title: String,
    val text: String,
    val activeAccountName: String? = null,
    val activeDisplayName: String? = null,
    val activeMemberNumber: Int? = null,
    val switchingToAccountName: String? = null,
    val errorMessage: String? = null,
)

enum class BcServiceAvailability {
    Starting,
    Online,
    Reconnecting,
    SwitchingAccount,
    Stopped,
    Failed,
    Offline,
}

fun buildBcServiceNotificationState(
    session: BcSessionSnapshot,
    switchState: BcAccountSwitchState,
): BcServiceNotificationState {
    val sessionState = session.state
    val health = session.connectionHealth
    val activeName = session.account?.accountName
    val activeDisplayName = session.account?.displayName
    val activeMemberNumber = session.account?.memberNumber
    val accountLabel = activeDisplayName ?: activeName

    if (switchState.isSwitching) {
        val target = switchState.switchingToAccountName?.takeIf { it.isNotBlank() }
        return BcServiceNotificationState(
            availability = BcServiceAvailability.SwitchingAccount,
            title = "Switching account",
            text = target?.let { "Switching to $it" } ?: "Switching BC account",
            activeAccountName = activeName,
            activeDisplayName = activeDisplayName,
            activeMemberNumber = activeMemberNumber,
            switchingToAccountName = target,
        )
    }

    val switchErrorMessage = switchState.lastErrorMessage?.takeIf { it.isNotBlank() }
    if (switchErrorMessage != null) {
        return BcServiceNotificationState(
            availability = BcServiceAvailability.Failed,
            title = "Attention needed",
            text = switchErrorMessage,
            activeAccountName = activeName,
            activeDisplayName = activeDisplayName,
            activeMemberNumber = activeMemberNumber,
            errorMessage = switchErrorMessage,
        )
    }

    return when {
        sessionState is BcSessionState.Blocked ->
            BcServiceNotificationState(
                availability = BcServiceAvailability.Failed,
                title = "Session blocked",
                text = "Session blocked: ${sessionState.reason}",
                activeAccountName = activeName,
                activeDisplayName = activeDisplayName,
                activeMemberNumber = activeMemberNumber,
                errorMessage = "Session blocked: ${sessionState.reason}",
            )

        sessionState is BcSessionState.Error ->
            BcServiceNotificationState(
                availability = BcServiceAvailability.Failed,
                title = if (sessionState.recoverable) "Session issue" else "Attention needed",
                text = sessionState.error.notificationMessage(),
                activeAccountName = activeName,
                activeDisplayName = activeDisplayName,
                activeMemberNumber = activeMemberNumber,
                errorMessage = sessionState.error.notificationMessage(),
            )

        sessionState is BcSessionState.Stopped ->
            BcServiceNotificationState(
                availability = BcServiceAvailability.Stopped,
                title = "Stopped",
                text = accountLabel?.let { "$it stopped" } ?: "BC session stopped",
                activeAccountName = activeName,
                activeDisplayName = activeDisplayName,
                activeMemberNumber = activeMemberNumber,
            )

        sessionState is BcSessionState.Reconnecting || health == BcConnectionHealth.Stale ->
            BcServiceNotificationState(
                availability = BcServiceAvailability.Reconnecting,
                title = "Reconnecting",
                text = accountLabel?.let { "Reconnecting $it" } ?: "Reconnecting BC session",
                activeAccountName = activeName,
                activeDisplayName = activeDisplayName,
                activeMemberNumber = activeMemberNumber,
            )

        session.isVerifiedOnline ->
            BcServiceNotificationState(
                availability = BcServiceAvailability.Online,
                title = "Online",
                text = accountLabel?.let { "$it online" } ?: session.statusDescription(includeMemberNumber = true),
                activeAccountName = activeName,
                activeDisplayName = activeDisplayName,
                activeMemberNumber = activeMemberNumber,
            )

        health == BcConnectionHealth.Offline ->
            BcServiceNotificationState(
                availability = BcServiceAvailability.Offline,
                title = "Offline",
                text = accountLabel?.let { "$it offline" } ?: "BC session offline",
                activeAccountName = activeName,
                activeDisplayName = activeDisplayName,
                activeMemberNumber = activeMemberNumber,
            )

        else -> BcServiceNotificationState(
            availability = BcServiceAvailability.Starting,
            title = "Starting",
            text = session.statusDescription(includeMemberNumber = true),
            activeAccountName = activeName,
            activeDisplayName = activeDisplayName,
            activeMemberNumber = activeMemberNumber,
        )
    }
}

private fun BcError.notificationMessage(): String = when (this) {
    is BcError.Network -> message.ifBlank { "Network error" }
    is BcError.Auth -> "Authentication error: $reason"
    is BcError.Server -> message.ifBlank { "Server error: $code" }
    is BcError.Room -> "Room error: $reason"
    is BcError.RateLimited -> retryAfter?.let { "Rate limited. Retry after $it" } ?: "Rate limited"
    is BcError.DuplicatedLogin -> "Duplicated login detected ($count active sessions)"
    is BcError.Unknown -> message.ifBlank { "Unknown error" }
}
