package dev.vivian.mobilebc.feature.auth

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.DefaultBcServerUrl
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginAccount
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuth
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.connection.SessionConnector
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginViewModelTest {
    @Test
    fun cancellationBeforeExecutionReleasesLoginAndDeleteWithoutSideEffects() {
        LoginOperation.entries.forEach { operation ->
            listOf(true, false).forEach { cancelBeforeSubmit ->
                val tasks = java.util.ArrayDeque<Runnable>()
                val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
                }
                val scope = CoroutineScope(SupervisorJob() + dispatcher)
                val calls = mutableListOf<String>()
                val model = LoginViewModel(
                    NoOpAuth, FakeConnectionStatusReader(), SessionConnector { calls += "connect" }, scope,
                    savedState(listOf(SavedAlice), "alice", "R129", true),
                    prepareResources = { calls += "prepare" },
                    onLoginSucceeded = { _, _, _, _ -> calls += "persist" },
                    onDeleteSavedAccount = { calls += "delete" },
                )
                try {
                    if (cancelBeforeSubmit) scope.cancel()
                    when (operation) {
                        LoginOperation.LoggingIn -> model.login()
                        LoginOperation.DeletingSavedAccount -> model.deleteCurrentSavedAccount()
                    }
                    scope.cancel()
                    while (tasks.isNotEmpty()) tasks.removeFirst().run()
                    assertFalse(model.uiState.value.isBusy)
                    assertNull(model.uiState.value.errorMessage)
                    assertTrue(calls.isEmpty())
                    model.onPasswordChange("editable")
                    assertEquals("editable", model.uiState.value.password)
                } finally {
                    scope.cancel()
                    while (tasks.isNotEmpty()) tasks.removeFirst().run()
                }
            }
        }
    }

    @Test
    fun cancelledResourcePreparationCannotContinueConnecting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var connected = false
        val model = LoginViewModel(
            NoOpAuth, FakeConnectionStatusReader(), SessionConnector { connected = true }, scope,
            savedState(listOf(SavedAlice), "alice", "R129", true),
            prepareResources = { scope.cancel() },
            onLoginSucceeded = { _, _, _, _ -> error("Cancelled login must not persist") },
            onDeleteSavedAccount = {},
        )
        try {
            model.login()
            assertFalse(connected)
            assertFalse(model.uiState.value.isBusy)
            assertNull(model.uiState.value.errorMessage)
        } finally { scope.cancel() }
    }

    @Test
    fun recoverableLoginFailureWithARetainedAccountDoesNotPersistCredentials() {
        assertLoginDoesNotPersist(
            BcSessionSnapshot(
                state = BcSessionState.Error(BcError.Network("Login unavailable"), recoverable = true),
                account = BcAccount(1, "Alice", "alice"),
            ),
        )
    }

    @Test
    fun authenticatedDifferentAccountDoesNotPersistTheRequestedCredentials() {
        assertLoginDoesNotPersist(
            BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(2, "Bob", "bob"),
            ),
        )
    }

    private fun assertLoginDoesNotPersist(session: BcSessionSnapshot) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var persisted = false
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(session),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice), SavedAlice.accountName, "R129", true),
            onLoginSucceeded = { _, _, _, _ -> persisted = true },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.login()

            assertFalse(persisted)
            assertFalse(viewModel.uiState.value.isBusy)
            assertNotNull(viewModel.uiState.value.errorMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistenceFailureAfterSuccessfulLoginReturnsToIdleWithAnError() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val connectionStatus = FakeConnectionStatusReader(
            BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(1, "Alice", "alice"),
            ),
        )
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = connectionStatus,
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice), SavedAlice.accountName, "R129", true),
            onLoginSucceeded = { _, _, _, _ -> error("credential store unavailable") },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.login()

            assertFalse(viewModel.uiState.value.isBusy)
            assertNotNull(viewModel.uiState.value.errorMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedSavedAccountDeletionKeepsTheSelectedAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice), SavedAlice.accountName, "R129", true),
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = { error("delete failed") },
        )

        try {
            viewModel.deleteCurrentSavedAccount()

            assertFalse(viewModel.uiState.value.isBusy)
            assertEquals(SavedAlice, viewModel.uiState.value.currentSavedAccount)
            assertEquals(listOf(SavedAlice), viewModel.uiState.value.savedAccounts)
            assertNotNull(viewModel.uiState.value.errorMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun selectingSavedAndNewAccountsKeepsTheAppWideVersion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice, SavedBob), SavedAlice.accountName, "r131beta2", true),
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            assertEquals("R131Beta2", viewModel.uiState.value.bcVersion)

            viewModel.selectSavedAccount(SavedBob)
            assertEquals("R131Beta2", viewModel.uiState.value.bcVersion)

            viewModel.onAccountNameChange(SavedAlice.accountName)
            assertEquals("R131Beta2", viewModel.uiState.value.bcVersion)

            viewModel.selectNewAccount()
            assertEquals("R131Beta2", viewModel.uiState.value.bcVersion)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun deletingASavedAccountKeepsTheAppWideVersion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var deletedAccountName: String? = null
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice), SavedAlice.accountName, "R131", true),
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = { deletedAccountName = it },
        )

        try {
            viewModel.deleteCurrentSavedAccount()

            assertEquals(SavedAlice.accountName, deletedAccountName)
            assertEquals("R131", viewModel.uiState.value.bcVersion)
            assertNull(viewModel.uiState.value.currentSavedAccount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loginConnectsWithTheVersionEnteredByTheUser() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var connectedServer: BcServerConfig? = null
        var persistedLogin: PersistedLoginCall? = null
        val calls = mutableListOf<String>()
        val connectionStatus = FakeConnectionStatusReader(
            BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = BcAccount(1, "Alice", "alice"),
            ),
        )
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = connectionStatus,
            connector = SessionConnector { server ->
                calls += "connect:${server.version}"
                connectedServer = server
            },
            scope = scope,
            savedLoginState = savedState(emptyList(), null, "", false),
            prepareResources = { version -> calls += "resources:$version" },
            onLoginSucceeded = { accountName, password, server, saveCredentials ->
                persistedLogin = PersistedLoginCall(
                    accountName = accountName,
                    password = password,
                    server = server,
                    saveCredentials = saveCredentials,
                )
            },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.onAccountNameChange("alice")
            viewModel.onPasswordChange("secret")
            viewModel.onBcVersionChange("r131beta2")
            viewModel.onServerUrlChange("  https://bc.example.test/  ")
            viewModel.login()

            assertEquals("R131Beta2", connectedServer?.version)
            assertEquals("https://bc.example.test/", connectedServer?.serverUrl)
            assertEquals(listOf("resources:R131Beta2", "connect:R131Beta2"), calls)
            assertEquals(
                PersistedLoginCall(
                    accountName = "alice",
                    password = "secret",
                    server = BcServerConfig(serverUrl = "https://bc.example.test/", version = "R131Beta2"),
                    saveCredentials = false,
                ),
                persistedLogin,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun resourcePreparationFailurePreventsSocketConnection() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var connectedServer: BcServerConfig? = null
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector { server -> connectedServer = server },
            scope = scope,
            savedLoginState = savedState(emptyList(), null, "", false),
            prepareResources = { version -> error("BC $version resources unavailable") },
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.onAccountNameChange("alice")
            viewModel.onPasswordChange("secret")
            viewModel.onBcVersionChange("R131")
            viewModel.login()

            assertNull(connectedServer)
            assertFalse(viewModel.uiState.value.isBusy)
            assertNotNull(viewModel.uiState.value.errorMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loginWaitsForResourcePreparationBeforeConnecting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val resourcesReady = CompletableDeferred<Unit>()
        var connected = false
        var preparationCount = 0
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector { connected = true },
            scope = scope,
            savedLoginState = savedState(emptyList(), null, "", false),
            prepareResources = { preparationCount += 1; resourcesReady.await() },
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.onAccountNameChange("alice")
            viewModel.onPasswordChange("secret")
            viewModel.onBcVersionChange("R131")
            viewModel.login()

            assertFalse(connected)
            assertTrue(viewModel.uiState.value.isBusy)
            val pendingLogin = viewModel.uiState.value
            viewModel.onAccountNameChange("bob")
            viewModel.onPasswordChange("other-secret")
            viewModel.onBcVersionChange("R132")
            viewModel.onServerUrlChange("https://other.example.test/")
            viewModel.onSaveCredentialsChange(true)
            viewModel.onAccountMenuExpandedChange(true)
            viewModel.selectSavedAccount(SavedBob)
            viewModel.selectNewAccount()
            viewModel.login()
            assertEquals(pendingLogin, viewModel.uiState.value)
            assertEquals(1, preparationCount)

            resourcesReady.complete(Unit)

            assertTrue(connected)
            assertFalse(viewModel.uiState.value.isBusy)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun editingASavedAccountCannotSubmitItsPasswordForAnotherAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var connected = false
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector { connected = true },
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice.copy(serverUrl = "https://saved.example.test/")), "alice", "R131", true),
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )
        try {
            viewModel.onAccountNameChange("another-account")
            viewModel.login()
            assertEquals("", viewModel.uiState.value.password)
            assertEquals(DefaultBcServerUrl, viewModel.uiState.value.serverUrl)
            assertNull(viewModel.uiState.value.currentSavedAccount)
            assertEquals(LoginValidationError.PasswordRequired, viewModel.uiState.value.validationError)
            assertFalse(connected)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidCustomServerIsRejectedBeforePreparingResourcesOrConnecting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector { calls += "connect" },
            scope = scope,
            savedLoginState = savedState(listOf(SavedAlice), "alice", "R131", true),
            prepareResources = { calls += "resources" },
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )
        try {
            listOf("bc.example.test", "file:///tmp/server", "https://").forEach { server ->
                viewModel.onServerUrlChange(server)
                viewModel.login()
                assertEquals(LoginValidationError.ServerUrlInvalid, viewModel.uiState.value.validationError)
                assertTrue(calls.isEmpty())
                assertFalse(viewModel.uiState.value.isBusy)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun activeUnsavedLoginKeepsItsUserSelectedVersionWhenSavedAccountsRefresh() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = savedState(emptyList(), null, "", false)
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = saved,
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.onAccountNameChange("alice")
            viewModel.onBcVersionChange("R131")
            saved.value = savedState(
                savedAccounts = emptyList(),
                selectedAccountName = "alice",
                version = "R129",
                saveCredentials = false,
            ).value

            assertEquals("R131", viewModel.uiState.value.bcVersion)
            assertNull(viewModel.uiState.value.currentSavedAccount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun savedAccountsRefreshDoesNotReplaceAnEditedAppWideVersion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = savedState(listOf(SavedAlice), SavedAlice.accountName, "R130", true)
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = saved,
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            viewModel.onBcVersionChange("R131")
            saved.value = savedState(
                savedAccounts = listOf(SavedAlice, SavedBob),
                selectedAccountName = SavedBob.accountName,
                version = "R130",
                saveCredentials = true,
            ).value

            assertEquals("R131", viewModel.uiState.value.bcVersion)
            assertEquals(SavedBob, viewModel.uiState.value.currentSavedAccount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun uneditedAppWideVersionFollowsAConfirmedPersistenceRefresh() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = savedState(listOf(SavedAlice), SavedAlice.accountName, "R130", true)
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector {},
            scope = scope,
            savedLoginState = saved,
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            saved.value = savedState(
                savedAccounts = listOf(SavedAlice),
                selectedAccountName = SavedAlice.accountName,
                version = "r131beta2",
                saveCredentials = true,
            ).value

            assertEquals("R131Beta2", viewModel.uiState.value.bcVersion)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun blankVersionIsRejectedInsteadOfFallingBackToTheDefault() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var connectedServer: BcServerConfig? = null
        val viewModel = LoginViewModel(
            auth = NoOpAuth,
            connectionStatus = FakeConnectionStatusReader(),
            connector = SessionConnector { server -> connectedServer = server },
            scope = scope,
            savedLoginState = savedState(emptyList(), null, "", false),
            onLoginSucceeded = { _, _, _, _ -> },
            onDeleteSavedAccount = {},
        )

        try {
            assertEquals("", viewModel.uiState.value.bcVersion)
            viewModel.onAccountNameChange("alice")
            viewModel.onPasswordChange("secret")
            viewModel.onBcVersionChange("   ")
            viewModel.login()

            assertNull(connectedServer)
            assertEquals(LoginValidationError.VersionRequired, viewModel.uiState.value.validationError)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sharedAccountUpdatesPreserveDraftsAndNeverMoveEditedCredentialsToAnotherAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = savedState(listOf(SavedAlice, SavedBob), "alice", "R131", true)
        val model = LoginViewModel(NoOpAuth, FakeConnectionStatusReader(), SessionConnector {}, scope, saved,
            onLoginSucceeded = { _, _, _, _ -> }, onDeleteSavedAccount = {})
        try {
            saved.value = saved.value.copy(accounts = saved.value.accounts.map { it.copy(password = "updated") })
            assertEquals("updated", model.uiState.value.password)
            model.onPasswordChange("draft password")
            model.onServerUrlChange("https://draft.example.test")
            model.onSaveCredentialsChange(false)
            saved.value = saved.value.copy(selectedAccountName = "bob", bcVersion = "R132")
            assertEquals("alice", model.uiState.value.accountName)
            assertEquals("draft password", model.uiState.value.password)
            assertEquals("https://draft.example.test", model.uiState.value.serverUrl)
            assertFalse(model.uiState.value.saveCredentials)
            assertEquals("R132", model.uiState.value.bcVersion)
            model.selectSavedAccount(SavedBob)
            assertEquals("updated", model.uiState.value.password)
            assertEquals(SavedBob.serverUrl, model.uiState.value.serverUrl)
            saved.value = saved.value.copy(accounts = emptyList())
            assertEquals("", model.uiState.value.password)
            assertNull(model.uiState.value.currentSavedAccount)
            model.selectSavedAccount(SavedAlice)
            assertEquals("bob", model.uiState.value.accountName)
            assertEquals("", model.uiState.value.password)
        } finally { scope.cancel() }
    }

    @Test
    fun loginAndDeleteReadLatestSourceBeforeTheUiCollectorRuns() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val saved = savedState(listOf(SavedAlice), "alice", "R131", true)
        var deleted = false
        val model = LoginViewModel(NoOpAuth, FakeConnectionStatusReader(), SessionConnector {}, scope, saved,
            onLoginSucceeded = { _, _, _, _ -> }, onDeleteSavedAccount = { deleted = true })
        try {
            assertEquals(SavedAlice.password, model.uiState.value.password)
            saved.value = saved.value.copy(accounts = emptyList())
            model.deleteCurrentSavedAccount()
            model.login()
            assertEquals(LoginValidationError.PasswordRequired, model.uiState.value.validationError)
            assertFalse(model.uiState.value.isBusy)
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            assertFalse(deleted)
        } finally {
            scope.cancel()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    private fun savedState(
        savedAccounts: List<LoginAccountOption>,
        selectedAccountName: String?,
        version: String,
        saveCredentials: Boolean,
    ) = MutableStateFlow(BcSavedLoginState(
        accounts = savedAccounts.map { BcSavedLoginAccount(it.accountName, it.password, it.serverUrl) },
        selectedAccountName = selectedAccountName.orEmpty(),
        saveCredentialsByDefault = saveCredentials,
        bcVersion = version,
        bcVersionConfirmedByUser = true,
    ))

    private class FakeConnectionStatusReader(
        initial: BcSessionSnapshot = BcSessionSnapshot(),
    ) : ConnectionStatusReader {
        override val snapshot: StateFlow<BcSessionSnapshot> = MutableStateFlow(initial)
    }

    private object NoOpAuth : BcSessionAuth {
        override suspend fun login(accountName: String, password: String) = Unit
        override suspend fun logout() = Unit
    }

    private data class PersistedLoginCall(
        val accountName: String,
        val password: String,
        val server: BcServerConfig,
        val saveCredentials: Boolean,
    )

    private companion object {
        val SavedAlice = LoginAccountOption(
            accountName = "alice",
            password = "secret",
            serverUrl = DefaultBcServerUrl,
        )
        val SavedBob = LoginAccountOption(
            accountName = "bob",
            password = "bob-secret",
            serverUrl = "https://bob.example/",
        )
    }
}
