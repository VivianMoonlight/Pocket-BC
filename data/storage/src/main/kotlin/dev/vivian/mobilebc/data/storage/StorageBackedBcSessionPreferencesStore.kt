package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.session.auth.BcRecoverableSession
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginAccount
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSessionPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

internal class StorageBackedBcSessionPreferencesStore(
    private val state: DataStoreStateRepository,
) : BcSessionPreferencesStore {
    override fun observeLoginState(): Flow<BcSavedLoginState> =
        state.observeLoginState().map { it.toLoginState() }.distinctUntilChanged()

    override suspend fun readLoginState(): BcSavedLoginState = state.readLoginState().toLoginState()

    private fun PersistedLoginSnapshot.toLoginState(): BcSavedLoginState {
        val savedState = this
        val savedAccounts = savedState.profiles.map { profile ->
            BcSavedLoginAccount(
                accountName = profile.accountName,
                password = savedState.passwordsByAccountName[profile.accountName].orEmpty(),
                serverUrl = profile.serverUrl,
            )
        }
        val selectedPassword = savedState.passwordsByAccountName[savedState.selectedAccountName].orEmpty()
        val hasConfirmedSystemVersion = savedState.bcVersion
            .takeIf { savedState.bcVersionConfirmedByUser && it.isNotBlank() }
            ?.normalizedBcVersionOrNull() != null
        val savedStateWarnings = buildList {
            if (savedState.unreadableAccountNames.isNotEmpty()) {
                add(
                    savedState.unreadableAccountNames.sorted().joinToString(
                        prefix = "Saved credentials are unavailable for: ",
                        postfix = ". Re-enter the affected passwords.",
                    )
                )
            }
            if (savedAccounts.isNotEmpty() && !hasConfirmedSystemVersion) {
                add(
                    "Saved BC version is unavailable. Confirm the system version before logging in."
                )
            }
        }
        return BcSavedLoginState(
            accounts = savedAccounts,
            selectedAccountName = savedState.selectedAccountName,
            saveCredentialsByDefault = selectedPassword.isNotBlank() || savedAccounts.isEmpty(),
            bcVersion = savedState.bcVersion,
            bcVersionConfirmedByUser = savedState.bcVersionConfirmedByUser,
            credentialErrorMessage = savedStateWarnings.joinToString(" ").takeIf(String::isNotBlank),
        )
    }

    override suspend fun readAutoJoinRoomAfterReconnect(): Boolean =
        state.readSettings().autoJoinRoomAfterReconnect

    override suspend fun readRecoverableSession(): BcRecoverableSession? {
        val savedSession = state.readRecoverySession() ?: return null
        return BcRecoverableSession(
            accountName = savedSession.accountName,
            password = savedSession.password,
            serverConfig = savedSession.serverConfig,
            lastRoomName = savedSession.lastRoomName,
        )
    }

    override suspend fun saveSuccessfulLogin(
        accountName: String,
        password: String,
        serverConfig: BcServerConfig,
        saveCredentials: Boolean,
    ) {
        state.saveSuccessfulLogin(
            accountName = accountName,
            password = password,
            serverConfig = serverConfig,
            saveCredentials = saveCredentials,
        )
    }

    override suspend fun deleteSavedAccount(accountName: String) {
        state.deleteAccount(accountName)
    }

    override suspend fun markDesiredOnline(accountName: String?) {
        state.markDesiredOnline(accountName)
    }

    override suspend fun markDesiredOnline(desiredOnline: Boolean) {
        state.markDesiredOnline(desiredOnline)
    }

    override suspend fun markStoppedRestoreDisabled() {
        state.markDesiredOnline(false)
    }

    override suspend fun setAutoJoinRoomAfterReconnect(enabled: Boolean) {
        state.setAutoJoinRoomAfterReconnect(enabled)
    }

    override suspend fun saveRoomTarget(accountName: String, roomName: String?) {
        if (accountName.isBlank()) return
        if (roomName.isNullOrBlank()) {
            state.clearLastRoomTarget(accountName)
        } else {
            state.saveLastRoomTarget(accountName, roomName)
        }
    }

    override suspend fun clearRoomTarget(accountName: String) {
        state.clearLastRoomTarget(accountName)
    }

    override suspend fun clearRoomTargetForLastLogin() {
        state.clearLastRoomTargetForLastLogin()
    }
}
