package dev.vivian.mobilebc.domain.session.auth

import dev.vivian.mobilebc.core.model.BcServerConfig
import kotlinx.coroutines.flow.Flow

data class BcSavedLoginAccount(
    val accountName: String,
    val password: String,
    val serverUrl: String,
)

data class BcSavedLoginState(
    val accounts: List<BcSavedLoginAccount>,
    val selectedAccountName: String,
    val saveCredentialsByDefault: Boolean,
    val bcVersion: String,
    val bcVersionConfirmedByUser: Boolean,
    val credentialErrorMessage: String? = null,
)

data class BcRecoverableSession(
    val accountName: String,
    val password: String,
    val serverConfig: BcServerConfig,
    val lastRoomName: String?,
)

data class BcAccountSwitchState(
    val isSwitching: Boolean = false,
    val switchingToAccountName: String? = null,
    val lastErrorMessage: String? = null,
)

interface BcSessionPreferencesStore {
    fun observeLoginState(): Flow<BcSavedLoginState>
    suspend fun readLoginState(): BcSavedLoginState
    suspend fun readAutoJoinRoomAfterReconnect(): Boolean
    suspend fun readRecoverableSession(): BcRecoverableSession?
    suspend fun saveSuccessfulLogin(
        accountName: String,
        password: String,
        serverConfig: BcServerConfig,
        saveCredentials: Boolean,
    )
    suspend fun deleteSavedAccount(accountName: String)
    suspend fun markDesiredOnline(accountName: String?)
    suspend fun markDesiredOnline(desiredOnline: Boolean)
    suspend fun markStoppedRestoreDisabled()
    suspend fun setAutoJoinRoomAfterReconnect(enabled: Boolean)
    suspend fun saveRoomTarget(accountName: String, roomName: String?)
    suspend fun clearRoomTarget(accountName: String)
    suspend fun clearRoomTargetForLastLogin()
}
