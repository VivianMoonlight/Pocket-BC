package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.DefaultBcServerUrl
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull

data class PersistedAccountProfile(
    val accountName: String,
    val serverUrl: String,
    val updatedAtMillis: Long,
    val legacyVersion: String = "",
    val legacyVersionConfirmedByUser: Boolean = false,
) {
    val confirmedLegacyVersionOrNull: String?
        get() = legacyVersion
            .takeIf { legacyVersionConfirmedByUser && it.isNotBlank() }
            ?.normalizedBcVersionOrNull()
}

data class PersistedSessionState(
    val profiles: List<PersistedAccountProfile>,
    val lastAccountName: String?,
    val desiredOnlineAccountName: String?,
    val bcVersion: String,
    val bcVersionConfirmedByUser: Boolean,
) {
    val confirmedBcVersionOrNull: String?
        get() = bcVersion
            .takeIf { bcVersionConfirmedByUser && it.isNotBlank() }
            ?.normalizedBcVersionOrNull()

    fun serverConfigOrNull(profile: PersistedAccountProfile): BcServerConfig? =
        confirmedBcVersionOrNull?.let { normalizedVersion ->
            BcServerConfig(
                serverUrl = profile.serverUrl.ifBlank { DefaultBcServerUrl },
                version = normalizedVersion,
            )
        }
}

data class PersistedLoginSnapshot(
    val profiles: List<PersistedAccountProfile>,
    val passwordsByAccountName: Map<String, String>,
    val selectedAccountName: String,
    val unreadableAccountNames: Set<String>,
    val bcVersion: String,
    val bcVersionConfirmedByUser: Boolean,
)

data class PersistedRecoverySession(
    val accountName: String,
    val password: String,
    val serverConfig: BcServerConfig,
    val lastRoomName: String?,
)

data class UserSettings(
    val autoJoinRoomAfterReconnect: Boolean = false,
)
