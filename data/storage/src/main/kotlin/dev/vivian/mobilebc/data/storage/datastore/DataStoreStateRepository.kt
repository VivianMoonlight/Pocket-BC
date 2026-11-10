package dev.vivian.mobilebc.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class DataStoreStateRepository(
    private val dataStore: DataStore<Preferences>,
    private val credentialStore: CredentialStore,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        dataStore = context.applicationContext.mobileBcStateDataStore,
        credentialStore = DataStoreCredentialStore(
            context = context.applicationContext,
            dataStore = context.applicationContext.mobileBcStateDataStore,
        ),
    )

    private val accountStateMutex = Mutex()

    // Credentials live outside DataStore; even a metadata no-op can change the login snapshot.
    private val accountChanges = MutableStateFlow(0L)

    fun observeLoginState(): Flow<PersistedLoginSnapshot> =
        combine(dataStore.data, accountChanges) { _, _ -> readLoginState() }.distinctUntilChanged()

    private suspend fun <T> mutateAccounts(block: suspend () -> T): T = accountStateMutex.withLock {
        try {
            block()
        } finally {
            accountChanges.update { it + 1 }
        }
    }

    suspend fun readLoginState(): PersistedLoginSnapshot = accountStateMutex.withLock {
        val state = readState()
        val unreadableAccountNames = mutableSetOf<String>()
        val passwords = state.profiles.associate { profile ->
            val password = try {
                readPassword(profile.accountName)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                unreadableAccountNames += profile.accountName
                ""
            }
            profile.accountName to password
        }
        PersistedLoginSnapshot(
            profiles = state.profiles,
            passwordsByAccountName = passwords,
            selectedAccountName = selectedProfile(state)?.accountName ?: state.lastAccountName.orEmpty(),
            unreadableAccountNames = unreadableAccountNames,
            bcVersion = state.bcVersion,
            bcVersionConfirmedByUser = state.bcVersionConfirmedByUser,
        )
    }

    suspend fun readRecoverySession(): PersistedRecoverySession? = accountStateMutex.withLock {
        val state = readState()
        val accountName = state.desiredOnlineAccountName ?: return@withLock null
        val profile = state.profiles.firstOrNull { it.accountName == accountName } ?: return@withLock null
        val password = readPassword(accountName)
        if (password.isBlank()) return@withLock null
        val serverConfig = state.serverConfigOrNull(profile) ?: return@withLock null
        PersistedRecoverySession(
            accountName = accountName,
            password = password,
            serverConfig = serverConfig,
            lastRoomName = readLastRoomTarget(accountName),
        )
    }

    suspend fun saveSuccessfulLogin(
        accountName: String,
        password: String,
        serverConfig: BcServerConfig,
        saveCredentials: Boolean,
    ) {
        val normalizedAccountName = accountName.trim()
        if (normalizedAccountName.isBlank()) return
        val normalizedVersion = requireNotNull(
            serverConfig.version
                .takeIf(String::isNotBlank)
                ?.normalizedBcVersionOrNull(),
        ) { "BC version is required when saving a login." }
        mutateAccounts {
            val state = readState()
            val updatedProfiles = if (saveCredentials) {
                val profile = PersistedAccountProfile(
                    accountName = normalizedAccountName,
                    serverUrl = serverConfig.serverUrl,
                    updatedAtMillis = clockMillis(),
                )
                savePassword(normalizedAccountName, password)
                listOf(profile) + state.profiles.filterNot { it.accountName == normalizedAccountName }
            } else {
                clearPassword(normalizedAccountName)
                state.profiles.filterNot { it.accountName == normalizedAccountName }
            }
            writeState(
                PersistedSessionState(
                    profiles = updatedProfiles,
                    lastAccountName = normalizedAccountName,
                    desiredOnlineAccountName = if (saveCredentials) normalizedAccountName else null,
                    bcVersion = normalizedVersion,
                    bcVersionConfirmedByUser = true,
                )
            )
        }
    }

    suspend fun deleteAccount(accountName: String) = mutateAccounts {
        val state = readState()
        clearPassword(accountName)
        val updatedProfiles = state.profiles.filterNot { it.accountName == accountName }
        val nextLastAccount = when {
            state.lastAccountName != accountName -> state.lastAccountName
            else -> updatedProfiles.maxByOrNull { it.updatedAtMillis }?.accountName
        }
        dataStore.edit { prefs ->
            val rawProfilesJson = prefs[StatePreferenceKeys.profilesJson]
            val profilesWithoutAccount = StateJsonCodecs.removeProfile(rawProfilesJson, accountName)
            when {
                profilesWithoutAccount != null ->
                    prefs[StatePreferenceKeys.profilesJson] = profilesWithoutAccount
                rawProfilesJson == null ->
                    prefs[StatePreferenceKeys.profilesJson] =
                        StateJsonCodecs.profilesToJson(updatedProfiles)
                else -> Unit
            }
            nextLastAccount.putOrRemove(prefs, StatePreferenceKeys.lastAccountName)
            state.desiredOnlineAccountName
                ?.takeUnless { it == accountName }
                .putOrRemove(prefs, StatePreferenceKeys.desiredOnlineAccountName)
        }
        clearLastRoomTarget(accountName)
    }

    suspend fun markDesiredOnline(accountName: String?) = mutateAccounts {
        val state = readState()
        writeDesiredOnlineState(state, accountName)
    }

    suspend fun markDesiredOnline(desiredOnline: Boolean) = mutateAccounts {
        val state = readState()
        val accountName = selectedProfile(state)?.accountName.takeIf { desiredOnline }
        writeDesiredOnlineState(state, accountName)
    }

    suspend fun readSettings(): UserSettings {
        val prefs = dataStore.data.first()
        return UserSettings(
            autoJoinRoomAfterReconnect =
                prefs[StatePreferenceKeys.autoJoinRoomAfterReconnect] ?: false,
        )
    }

    suspend fun setAutoJoinRoomAfterReconnect(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[StatePreferenceKeys.autoJoinRoomAfterReconnect] = enabled
        }
    }

    suspend fun readLastRoomTarget(accountName: String): String? {
        if (accountName.isBlank()) return null
        return dataStore.data.first()[StatePreferenceKeys.roomTargetKey(accountName)]
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun saveLastRoomTarget(accountName: String, roomName: String) {
        if (accountName.isBlank() || roomName.isBlank()) return
        dataStore.edit { prefs ->
            prefs[StatePreferenceKeys.roomTargetKey(accountName)] = roomName
        }
    }

    suspend fun clearLastRoomTarget(accountName: String) {
        if (accountName.isBlank()) return
        dataStore.edit { prefs ->
            prefs.remove(StatePreferenceKeys.roomTargetKey(accountName))
        }
    }

    suspend fun clearLastRoomTargetForLastLogin() = accountStateMutex.withLock {
        val state = readState()
        val accountName = selectedProfile(state)?.accountName ?: state.lastAccountName
        if (!accountName.isNullOrBlank()) clearLastRoomTarget(accountName)
    }

    private suspend fun readState(): PersistedSessionState {
        val prefs = dataStore.data.first()
        val rawProfilesJson = prefs[StatePreferenceKeys.profilesJson]
        val profiles = StateJsonCodecs.parseProfiles(rawProfilesJson)
            .sortedByDescending { it.updatedAtMillis }
        val lastAccountName = prefs[StatePreferenceKeys.lastAccountName]
        val hasStoredBcVersion = prefs.contains(StatePreferenceKeys.bcVersion)
        val hasStoredBcVersionConfirmation =
            prefs.contains(StatePreferenceKeys.bcVersionConfirmedByUser)
        val hasStoredBcVersionState = hasStoredBcVersion || hasStoredBcVersionConfirmation
        val hasCompleteStoredBcVersionState =
            hasStoredBcVersion && hasStoredBcVersionConfirmation
        val strippedProfilesJson = StateJsonCodecs.removeLegacyVersionFields(rawProfilesJson)
        val profilesJsonIsReadable = rawProfilesJson.isNullOrBlank() || strippedProfilesJson != null
        val profilesContainLegacyVersion =
            strippedProfilesJson != null && strippedProfilesJson != rawProfilesJson
        val resolvedPrefs = if (
            (!hasStoredBcVersionState && profilesJsonIsReadable) ||
            (hasCompleteStoredBcVersionState && profilesContainLegacyVersion)
        ) {
            dataStore.edit { mutablePreferences ->
                val mutableHasVersion = mutablePreferences.contains(StatePreferenceKeys.bcVersion)
                val mutableHasConfirmation =
                    mutablePreferences.contains(StatePreferenceKeys.bcVersionConfirmedByUser)
                val mutableHasVersionState = mutableHasVersion || mutableHasConfirmation
                val mutableHasCompleteVersionState = mutableHasVersion && mutableHasConfirmation
                val currentRawProfilesJson = mutablePreferences[StatePreferenceKeys.profilesJson]
                val currentStrippedProfilesJson =
                    StateJsonCodecs.removeLegacyVersionFields(currentRawProfilesJson)
                val currentProfilesJsonIsReadable =
                    currentRawProfilesJson.isNullOrBlank() || currentStrippedProfilesJson != null
                if (!mutableHasVersion && !mutableHasConfirmation) {
                    if (currentProfilesJsonIsReadable) {
                        val currentProfiles = StateJsonCodecs.parseProfiles(currentRawProfilesJson)
                            .sortedByDescending { it.updatedAtMillis }
                        val currentMigratedBcVersion = legacyMigrationVersion(
                            profiles = currentProfiles,
                            lastAccountName = mutablePreferences[StatePreferenceKeys.lastAccountName],
                        )
                        mutablePreferences[StatePreferenceKeys.bcVersion] =
                            currentMigratedBcVersion.orEmpty()
                        mutablePreferences[StatePreferenceKeys.bcVersionConfirmedByUser] =
                            currentMigratedBcVersion != null
                    }
                }
                if (
                    currentStrippedProfilesJson != null &&
                    currentStrippedProfilesJson != currentRawProfilesJson &&
                    (!mutableHasVersionState || mutableHasCompleteVersionState)
                ) {
                    mutablePreferences[StatePreferenceKeys.profilesJson] = currentStrippedProfilesJson
                }
            }
        } else {
            prefs
        }
        return PersistedSessionState(
            profiles = profiles,
            lastAccountName = lastAccountName,
            desiredOnlineAccountName = resolvedPrefs[StatePreferenceKeys.desiredOnlineAccountName],
            bcVersion = resolvedPrefs[StatePreferenceKeys.bcVersion].orEmpty(),
            bcVersionConfirmedByUser =
                resolvedPrefs[StatePreferenceKeys.bcVersionConfirmedByUser] ?: false,
        )
    }

    private suspend fun writeState(state: PersistedSessionState) {
        dataStore.edit { prefs ->
            prefs[StatePreferenceKeys.profilesJson] = StateJsonCodecs.profilesToJson(state.profiles)
            state.lastAccountName.putOrRemove(prefs, StatePreferenceKeys.lastAccountName)
            state.desiredOnlineAccountName.putOrRemove(prefs, StatePreferenceKeys.desiredOnlineAccountName)
            prefs[StatePreferenceKeys.bcVersion] = state.bcVersion
            prefs[StatePreferenceKeys.bcVersionConfirmedByUser] = state.bcVersionConfirmedByUser
        }
    }

    private suspend fun writeDesiredOnlineState(
        state: PersistedSessionState,
        accountName: String?,
    ) {
        val normalizedAccountName = accountName?.trim()?.takeIf { it.isNotBlank() }
        val desiredAccountName = normalizedAccountName
            ?.takeIf { state.confirmedBcVersionOrNull != null }
            ?.takeIf { candidate ->
                state.profiles.any { profile ->
                    profile.accountName == candidate &&
                        readPassword(candidate).isNotBlank()
                }
            }
        dataStore.edit { prefs ->
            (desiredAccountName ?: state.lastAccountName)
                .putOrRemove(prefs, StatePreferenceKeys.lastAccountName)
            desiredAccountName.putOrRemove(prefs, StatePreferenceKeys.desiredOnlineAccountName)
        }
    }

    private suspend fun readPassword(accountName: String): String {
        val password = credentialStore.readPassword(accountName) ?: return ""
        return try {
            password.concatToString()
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun savePassword(accountName: String, password: String) {
        val passwordChars = password.toCharArray()
        try {
            credentialStore.savePassword(accountName, passwordChars)
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    private suspend fun clearPassword(accountName: String) =
        credentialStore.clearPassword(accountName)

    private fun selectedProfile(state: PersistedSessionState): PersistedAccountProfile? =
        state.lastAccountName?.let { accountName ->
            state.profiles.firstOrNull { it.accountName == accountName }
        } ?: state.profiles.maxByOrNull { it.updatedAtMillis }

    private fun legacyMigrationVersion(
        profiles: List<PersistedAccountProfile>,
        lastAccountName: String?,
    ): String? =
        lastAccountName
            ?.let { selectedName -> profiles.firstOrNull { it.accountName == selectedName } }
            ?.confirmedLegacyVersionOrNull
            ?: profiles.firstNotNullOfOrNull(PersistedAccountProfile::confirmedLegacyVersionOrNull)

    private fun String?.putOrRemove(prefs: MutablePreferences, key: Preferences.Key<String>) {
        val value = this
        if (value == null) {
            prefs.remove(key)
        } else {
            prefs[key] = value
        }
    }
}
