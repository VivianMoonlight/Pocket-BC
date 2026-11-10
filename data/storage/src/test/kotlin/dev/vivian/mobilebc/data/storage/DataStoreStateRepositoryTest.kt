package dev.vivian.mobilebc.data.storage

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import dev.vivian.mobilebc.core.model.BcServerConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreStateRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun loginObservationFollowsStorageWritesAndCredentialOnlyChanges() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("observed-state.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore(), clockMillis = { 100L })
        val reader = StorageBackedBcSessionPreferencesStore(repository)
        val writer = StorageBackedBcSessionPreferencesStore(repository)
        val observed = reader.observeLoginState().stateIn(backgroundScope)
        assertTrue(observed.value.accounts.isEmpty())

        writer.saveSuccessfulLogin("alice", "first", BcServerConfig(version = "R131"), true)
        val first = observed.first { it.accounts.singleOrNull()?.password == "first" }
        assertEquals("alice", first.selectedAccountName)
        assertEquals("R131", first.bcVersion)
        val metadata = dataStore.data.first()

        writer.saveSuccessfulLogin("alice", "replacement", BcServerConfig(version = "R131"), true)
        observed.first { it.accounts.singleOrNull()?.password == "replacement" }
        assertEquals(metadata, dataStore.data.first())

        writer.saveSuccessfulLogin("bob", "bob-password", BcServerConfig(version = "R132"), true)
        val both = observed.first { it.accounts.size == 2 }
        assertEquals("bob", both.selectedAccountName)
        assertEquals("R132", both.bcVersion)
        writer.deleteSavedAccount("bob")
        val remaining = observed.first { it.accounts.map { account -> account.accountName } == listOf("alice") }
        assertEquals("alice", remaining.selectedAccountName)
        assertEquals("replacement", remaining.accounts.single().password)

        writer.saveSuccessfulLogin("alice", "temporary", BcServerConfig(version = "R133"), false)
        val unsaved = observed.first { it.accounts.isEmpty() }
        assertEquals("R133", unsaved.bcVersion)
        assertEquals(reader.readLoginState(), unsaved)
    }

    @Test
    fun concurrentSuccessfulLoginsKeepAccountsAndOneConsistentSystemVersion() = runTest {
        val credentials = FakeCredentialStore(saveDelayMillis = 10)
        val clock = AtomicLong(100)
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("state.preferences_pb")
        }
        val repository = DataStoreStateRepository(
            dataStore = dataStore,
            credentialStore = credentials,
            clockMillis = clock::incrementAndGet,
        )

        listOf("alice" to "R130", "bob" to "R131").map { (accountName, version) ->
            async {
                repository.saveSuccessfulLogin(
                    accountName = accountName,
                    password = "$accountName-password",
                    serverConfig = BcServerConfig(version = version),
                    saveCredentials = true,
                )
            }
        }.awaitAll()

        val state = repository.readLoginState()
        val preferences = dataStore.data.first()
        assertEquals(setOf("alice", "bob"), state.profiles.map { it.accountName }.toSet())
        assertEquals("alice-password", state.passwordsByAccountName["alice"])
        assertEquals("bob-password", state.passwordsByAccountName["bob"])
        assertTrue(state.bcVersion in setOf("R130", "R131"))
        assertTrue(state.bcVersionConfirmedByUser)
        assertEquals(state.bcVersion, preferences[StatePreferenceKeys.bcVersion])
        assertTrue(preferences[StatePreferenceKeys.bcVersionConfirmedByUser] == true)
    }

    @Test
    fun unreadableCredentialDoesNotHideTheOtherSavedAccounts() = runTest {
        val credentials = FakeCredentialStore()
        val repository = DataStoreStateRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporaryFolder.root.resolve("partial-state.preferences_pb")
            },
            credentialStore = credentials,
        )
        repository.saveSuccessfulLogin(
            accountName = "alice",
            password = "alice-password",
            serverConfig = BcServerConfig(version = "R129"),
            saveCredentials = true,
        )
        repository.saveSuccessfulLogin(
            accountName = "bob",
            password = "bob-password",
            serverConfig = BcServerConfig(version = "R129"),
            saveCredentials = true,
        )
        credentials.unreadableAccounts += "bob"

        val state = repository.readLoginState()

        assertEquals(setOf("alice", "bob"), state.profiles.map { it.accountName }.toSet())
        assertEquals("alice-password", state.passwordsByAccountName["alice"])
        assertEquals("", state.passwordsByAccountName["bob"])
        assertEquals(setOf("bob"), state.unreadableAccountNames)
        assertEquals("R129", state.bcVersion)
        assertTrue(state.bcVersionConfirmedByUser)
    }

    @Test
    fun savedAccountsWithoutAnyUserConfirmedSystemVersionRequireLoginConfirmation() = runTest {
        val credentials = FakeCredentialStore()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("missing-version.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, credentials)
        repository.saveSuccessfulLogin(
            accountName = "alice",
            password = "alice-password",
            serverConfig = BcServerConfig(version = "R131"),
            saveCredentials = true,
        )
        dataStore.edit { preferences ->
            preferences.remove(StatePreferenceKeys.bcVersion)
            preferences.remove(StatePreferenceKeys.bcVersionConfirmedByUser)
            preferences[StatePreferenceKeys.profilesJson] =
                """[{"accountName":"alice","serverUrl":"https://server.example/","updatedAtMillis":123}]"""
        }

        val recovery = repository.readRecoverySession()
        val loginState = StorageBackedBcSessionPreferencesStore(repository).readLoginState()
        val migratedPreferences = dataStore.data.first()

        assertNull(recovery)
        assertEquals("", loginState.bcVersion)
        assertFalse(loginState.bcVersionConfirmedByUser)
        assertTrue(loginState.credentialErrorMessage.orEmpty().contains("system version"))
        assertEquals("", migratedPreferences[StatePreferenceKeys.bcVersion])
        assertFalse(migratedPreferences[StatePreferenceKeys.bcVersionConfirmedByUser] ?: true)
    }

    @Test
    fun markerlessLegacyAccountVersionsNeverBecomeTheSystemVersion() = runTest {
        listOf("R130", "R131").forEach { legacyVersion ->
            val credentials = FakeCredentialStore()
            val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporaryFolder.root.resolve("legacy-$legacyVersion.preferences_pb")
            }
            val repository = DataStoreStateRepository(dataStore, credentials)
            repository.saveSuccessfulLogin(
                accountName = "alice",
                password = "alice-password",
                serverConfig = BcServerConfig(version = "R132"),
                saveCredentials = true,
            )
            dataStore.edit { preferences ->
                preferences.remove(StatePreferenceKeys.bcVersion)
                preferences.remove(StatePreferenceKeys.bcVersionConfirmedByUser)
                preferences[StatePreferenceKeys.profilesJson] =
                    """[{"accountName":"alice","serverUrl":"https://server.example/","version":"$legacyVersion","updatedAtMillis":123}]"""
            }

            val loginState = StorageBackedBcSessionPreferencesStore(repository).readLoginState()

            assertNull(repository.readRecoverySession())
            assertEquals("alice-password", loginState.accounts.single().password)
            assertEquals("", loginState.bcVersion)
            assertFalse(loginState.bcVersionConfirmedByUser)
            assertFalse(dataStore.data.first()[StatePreferenceKeys.profilesJson].orEmpty().contains("\"version\""))
        }
    }

    @Test
    fun migrationPrefersTheLastSelectedConfirmedAccountAndStripsLegacyFields() = runTest {
        val credentials = FakeCredentialStore()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("selected-migration.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, credentials)
        repository.saveSuccessfulLogin("alice", "alice-password", BcServerConfig(version = "R132"), true)
        repository.saveSuccessfulLogin("bob", "bob-password", BcServerConfig(version = "R132"), true)
        dataStore.edit { preferences ->
            preferences.remove(StatePreferenceKeys.bcVersion)
            preferences.remove(StatePreferenceKeys.bcVersionConfirmedByUser)
            preferences[StatePreferenceKeys.lastAccountName] = "alice"
            preferences[StatePreferenceKeys.profilesJson] =
                """[
                    {"accountName":"alice","serverUrl":"https://alice.example/","version":"r130","updatedAtMillis":123,"versionConfirmedByUser":true,"future":{"enabled":true}},
                    {"accountName":"bob","serverUrl":"https://bob.example/","version":"R131","updatedAtMillis":456,"versionConfirmedByUser":true}
                ]""".trimIndent()
        }

        val firstRead = repository.readLoginState()
        val migratedJson = dataStore.data.first()[StatePreferenceKeys.profilesJson].orEmpty()
        val secondRead = repository.readLoginState()

        assertEquals("R130", firstRead.bcVersion)
        assertTrue(firstRead.bcVersionConfirmedByUser)
        assertEquals("R130", secondRead.bcVersion)
        assertFalse(migratedJson.contains("\"version\""))
        assertFalse(migratedJson.contains("versionConfirmedByUser"))
        assertTrue(migratedJson.contains("\"future\""))
        assertTrue(migratedJson.contains("\"enabled\":true"))
    }

    @Test
    fun migrationFallsBackToTheNewestConfirmedValidLegacyVersion() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("latest-migration.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore())
        dataStore.edit { preferences ->
            preferences[StatePreferenceKeys.lastAccountName] = "carol"
            preferences[StatePreferenceKeys.profilesJson] =
                """[
                    {"accountName":"alice","serverUrl":"https://alice.example/","version":"R130","updatedAtMillis":123,"versionConfirmedByUser":true},
                    {"accountName":"bob","serverUrl":"https://bob.example/","version":"r131beta2","updatedAtMillis":456,"versionConfirmedByUser":true},
                    {"accountName":"carol","serverUrl":"https://carol.example/","version":"latest","updatedAtMillis":789,"versionConfirmedByUser":true}
                ]""".trimIndent()
        }

        val state = repository.readLoginState()

        assertEquals("R131Beta2", state.bcVersion)
        assertTrue(state.bcVersionConfirmedByUser)
    }

    @Test
    fun eitherGlobalKeyMakesTheNewSchemaAuthoritative() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("partial-global.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore())
        dataStore.edit { preferences ->
            preferences[StatePreferenceKeys.bcVersion] = "R132"
            preferences[StatePreferenceKeys.profilesJson] =
                """[{"accountName":"alice","serverUrl":"https://alice.example/","version":"R130","updatedAtMillis":123,"versionConfirmedByUser":true}]"""
        }

        val state = repository.readLoginState()

        assertEquals("R132", state.bcVersion)
        assertFalse(state.bcVersionConfirmedByUser)
        assertNull(repository.readRecoverySession())
        repository.markDesiredOnline(false)
        val preferences = dataStore.data.first()
        assertTrue(preferences[StatePreferenceKeys.profilesJson].orEmpty().contains("\"version\""))
        assertFalse(preferences.contains(StatePreferenceKeys.bcVersionConfirmedByUser))
    }

    @Test
    fun malformedLegacyProfileJsonIsNeverOverwrittenByMigration() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("malformed-legacy.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore())
        val malformedJson = """[{"accountName":"alice","version":"R131","versionConfirmedByUser":true}"""
        dataStore.edit { preferences ->
            preferences[StatePreferenceKeys.profilesJson] = malformedJson
        }

        val state = repository.readLoginState()
        val preferences = dataStore.data.first()

        assertTrue(state.profiles.isEmpty())
        assertEquals(malformedJson, preferences[StatePreferenceKeys.profilesJson])
        assertFalse(preferences.contains(StatePreferenceKeys.bcVersion))
        assertFalse(preferences.contains(StatePreferenceKeys.bcVersionConfirmedByUser))
    }

    @Test
    fun successfulLoginPersistsTheConfirmedSystemVersionOutsideProfiles() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("confirmed-version.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore())

        repository.saveSuccessfulLogin(
            accountName = "alice",
            password = "alice-password",
            serverConfig = BcServerConfig(version = "r131beta2"),
            saveCredentials = true,
        )

        val recovery = repository.readRecoverySession()
        val preferences = dataStore.data.first()
        val rawJson = preferences[StatePreferenceKeys.profilesJson].orEmpty()
        val loginState = StorageBackedBcSessionPreferencesStore(repository).readLoginState()

        assertEquals("R131Beta2", recovery?.serverConfig?.version)
        assertEquals("R131Beta2", preferences[StatePreferenceKeys.bcVersion])
        assertTrue(preferences[StatePreferenceKeys.bcVersionConfirmedByUser] == true)
        assertFalse(rawJson.contains("\"version\""))
        assertFalse(rawJson.contains("versionConfirmedByUser"))
        assertEquals("R131Beta2", loginState.bcVersion)
        assertTrue(loginState.bcVersionConfirmedByUser)
    }

    @Test
    fun successfulLoginWithoutSavedCredentialsStillPersistsTheSystemVersion() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("unsaved-login.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore())

        repository.saveSuccessfulLogin(
            accountName = "guest",
            password = "temporary-password",
            serverConfig = BcServerConfig(version = "R131"),
            saveCredentials = false,
        )

        val state = repository.readLoginState()
        assertTrue(state.profiles.isEmpty())
        assertEquals("R131", state.bcVersion)
        assertTrue(state.bcVersionConfirmedByUser)
    }

    @Test
    fun recoveryCombinesTheTargetAccountServerWithTheCurrentSystemVersion() = runTest {
        val repository = DataStoreStateRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporaryFolder.root.resolve("shared-version-recovery.preferences_pb")
            },
            credentialStore = FakeCredentialStore(),
        )
        repository.saveSuccessfulLogin(
            "alice",
            "alice-password",
            BcServerConfig(serverUrl = "https://alice.example/", version = "R130"),
            true,
        )
        repository.saveSuccessfulLogin(
            "bob",
            "bob-password",
            BcServerConfig(serverUrl = "https://bob.example/", version = "R131"),
            true,
        )

        repository.markDesiredOnline("alice")
        val recovery = repository.readRecoverySession()

        assertEquals("alice", recovery?.accountName)
        assertEquals("https://alice.example/", recovery?.serverConfig?.serverUrl)
        assertEquals("R131", recovery?.serverConfig?.version)
    }

    @Test
    fun deletingAnAccountDoesNotDeleteTheSystemVersion() = runTest {
        val repository = DataStoreStateRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporaryFolder.root.resolve("delete-account.preferences_pb")
            },
            credentialStore = FakeCredentialStore(),
        )
        repository.saveSuccessfulLogin("alice", "password", BcServerConfig(version = "R131"), true)

        repository.deleteAccount("alice")
        val state = repository.readLoginState()

        assertTrue(state.profiles.isEmpty())
        assertEquals("R131", state.bcVersion)
        assertTrue(state.bcVersionConfirmedByUser)
    }

    @Test
    fun deletingAnAccountFromPartialGlobalStatePreservesRemainingLegacyVersionData() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("partial-delete.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, FakeCredentialStore())
        dataStore.edit { preferences ->
            preferences[StatePreferenceKeys.bcVersion] = "R132"
            preferences[StatePreferenceKeys.profilesJson] =
                """[
                    {"accountName":"alice","serverUrl":"https://alice.example/","version":"R130","updatedAtMillis":123,"versionConfirmedByUser":true},
                    {"accountName":"bob","serverUrl":"https://bob.example/","version":"R131","updatedAtMillis":456,"versionConfirmedByUser":true,"future":7}
                ]""".trimIndent()
        }

        repository.deleteAccount("alice")
        val preferences = dataStore.data.first()
        val profilesJson = preferences[StatePreferenceKeys.profilesJson].orEmpty()

        assertFalse(profilesJson.contains("alice"))
        assertTrue(profilesJson.contains("bob"))
        assertTrue(profilesJson.contains("\"version\":\"R131\""))
        assertTrue(profilesJson.contains("\"future\":7"))
        assertFalse(preferences.contains(StatePreferenceKeys.bcVersionConfirmedByUser))
    }

    @Test
    fun confirmedInvalidSystemVersionCannotBeRecovered() = runTest {
        val credentials = FakeCredentialStore()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            temporaryFolder.root.resolve("confirmed-invalid-version.preferences_pb")
        }
        val repository = DataStoreStateRepository(dataStore, credentials)
        repository.saveSuccessfulLogin(
            accountName = "alice",
            password = "alice-password",
            serverConfig = BcServerConfig(version = "R131"),
            saveCredentials = true,
        )
        dataStore.edit { preferences ->
            preferences[StatePreferenceKeys.bcVersion] = "latest"
            preferences[StatePreferenceKeys.bcVersionConfirmedByUser] = true
        }

        val loginState = StorageBackedBcSessionPreferencesStore(repository).readLoginState()

        assertNull(repository.readRecoverySession())
        assertTrue(loginState.credentialErrorMessage.orEmpty().contains("system version"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun successfulLoginCannotPersistABlankVersion() = runTest {
        val repository = DataStoreStateRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
                temporaryFolder.root.resolve("blank-version.preferences_pb")
            },
            credentialStore = FakeCredentialStore(),
        )

        repository.saveSuccessfulLogin(
            accountName = "alice",
            password = "alice-password",
            serverConfig = BcServerConfig(version = "   "),
            saveCredentials = true,
        )
    }

    private class FakeCredentialStore(
        private val saveDelayMillis: Long = 0,
    ) : CredentialStore {
        private val passwords = ConcurrentHashMap<String, String>()
        val unreadableAccounts = ConcurrentHashMap.newKeySet<String>()

        override suspend fun savePassword(accountName: String, password: CharArray) {
            delay(saveDelayMillis)
            passwords[accountName] = password.concatToString()
        }

        override suspend fun readPassword(accountName: String): CharArray? {
            check(accountName !in unreadableAccounts) { "credential unavailable" }
            return passwords[accountName]?.toCharArray()
        }

        override suspend fun clearPassword(accountName: String) {
            passwords.remove(accountName)
        }
    }
}
