package dev.vivian.mobilebc.feature.profile

import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginAccount
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuth
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.connection.SessionRecoverySettings
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcLovership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileViewModelTest {
    @Test
    fun cancelledQueuedLogoutReleasesBusyStateAndAllowsRetry() {
        val tasks = java.util.ArrayDeque<Runnable>()
        var lastJob: kotlinx.coroutines.Job? = null
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                lastJob = context[kotlinx.coroutines.Job]
                tasks.add(block)
            }
        }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        var logoutCalls = 0
        var navigations = 0
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            auth = object : BcSessionAuth {
                override suspend fun login(accountName: String, password: String) = Unit
                override suspend fun logout() { logoutCalls++ }
            }, onLoggedOut = { navigations++ }, onSwitchAccount = {})
        try {
            drain()
            model.logout()
            assertTrue(model.uiState.value.isLoggingOut)
            requireNotNull(lastJob).cancel()
            drain()
            assertFalse(model.uiState.value.isLoggingOut)
            assertEquals(0, logoutCalls)
            assertEquals(0, navigations)
            assertNull(model.uiState.value.accountSwitchMessage)

            model.logout()
            drain()
            assertFalse(model.uiState.value.isLoggingOut)
            assertEquals(1, logoutCalls)
            assertEquals(1, navigations)

            scope.cancel()
            model.logout()
            drain()
            assertFalse(model.uiState.value.isLoggingOut)
            assertEquals(1, logoutCalls)
        } finally {
            scope.cancel()
            drain()
        }
    }

    @Test
    fun localAccountFeedbackCannotFollowAnotherAccountOrLogin() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = MutableStateFlow(BcSessionSnapshot(
            account = dev.vivian.mobilebc.core.model.BcAccount(10, "Alice", "alice"),
        ))
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            connectionStatus = object : ConnectionStatusReader { override val snapshot = snapshot },
            onSwitchAccount = {})
        try {
            model.switchAccount("missing")
            assertTrue(model.uiState.value.accountSwitchMessageIsError)
            snapshot.value = snapshot.value.copy(relationships = snapshot.value.relationships.nextUnloaded())
            assertNull(model.uiState.value.accountSwitchMessage)
            assertFalse(model.uiState.value.accountSwitchMessageIsError)
            model.switchAccount("missing")
            assertTrue(model.uiState.value.accountSwitchMessageIsError)
            snapshot.value = snapshot.value.copy(account = dev.vivian.mobilebc.core.model.BcAccount(20, "Bob", "bob"))
            assertNull(model.uiState.value.accountSwitchMessage)
            assertFalse(model.uiState.value.accountSwitchMessageIsError)
        } finally { scope.cancel() }
    }

    @Test
    fun packetRowsAndCountComeFromOneSnapshot() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val first = ProfilePacketLogEntry(1, "RECV", "ChatRoomChat", "{}", Instant.EPOCH)
        val source = MutableStateFlow(listOf(first))
        val changingSource = object : StateFlow<List<ProfilePacketLogEntry>> by source {
            override val value: List<ProfilePacketLogEntry>
                get() {
                    val snapshot = source.value
                    source.value = snapshot + first.copy(id = snapshot.size.toLong() + 1)
                    return snapshot
                }
        }
        try {
            val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
                packetLogs = changingSource, onSwitchAccount = {})
            assertEquals(listOf(1L), model.uiState.value.packetLogs.map { it.id })
            assertEquals(1, model.uiState.value.packetLogCount)
        } finally {
            scope.cancel()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun aSlowerProjectionCannotOverwriteNewerSharedSettings() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blockNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val settings = MutableStateFlow(BcNotificationSettings())
        val source = MutableStateFlow(false)
        val gatedTextSetting = object : StateFlow<Boolean> by source {
            override val value: Boolean
                get() {
                    val snapshot = source.value
                    if (blockNext.compareAndSet(true, false)) {
                        entered.countDown()
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    return snapshot
                }
        }
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            notificationSettings = settings, deobfuscationEnabled = gatedTextSetting, onSwitchAccount = {})
        val worker = Thread { model.refreshNotificationDiagnostics() }
        try {
            blockNext.set(true)
            worker.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            source.value = true
            assertTrue(model.uiState.value.deobfuscationEnabled)
            release.countDown()
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertTrue(model.uiState.value.deobfuscationEnabled)
        } finally {
            release.countDown()
            worker.join(5_000)
            scope.cancel()
        }
    }

    @Test
    fun savedAccountDisplayAndSwitchingFollowSharedSnapshot() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val saved = MutableStateFlow(BcSavedLoginState(
            listOf(BcSavedLoginAccount("alice", "secret", "https://example.com")),
            "alice", true, "R131", true,
        ))
        val switched = mutableListOf<String>()
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            savedLoginState = saved, onSwitchAccount = { switched += it })
        try {
            saved.value = saved.value.copy(
                accounts = saved.value.accounts + BcSavedLoginAccount("bob", "secret", "https://example.com"),
                bcVersion = "R132",
            )
            assertEquals(listOf("alice", "bob"), model.uiState.value.savedAccounts.map { it.accountName })
            assertEquals("R132", model.uiState.value.systemBcVersion)
            model.switchAccount("bob")
            assertEquals(listOf("bob"), switched)
            saved.value = saved.value.copy(bcVersionConfirmedByUser = false)
            assertEquals("", model.uiState.value.systemBcVersion)
            assertFalse(model.uiState.value.savedAccounts.last().canSwitch)
            model.switchAccount("bob")
            assertEquals(listOf("bob"), switched)
            saved.value = saved.value.copy(accounts = emptyList(), selectedAccountName = "")
            assertTrue(model.uiState.value.savedAccounts.isEmpty())
            assertNull(model.uiState.value.activeAccountName)
        } finally { scope.cancel() }
    }

    @Test
    fun switchReadsCurrentCredentialsBeforeDisplayCollectorRuns() {
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val saved = MutableStateFlow(BcSavedLoginState(
            listOf(BcSavedLoginAccount("bob", "secret", "https://example.com")),
            "alice", true, "R131", true,
        ))
        val switched = mutableListOf<String>()
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            savedLoginState = saved, onSwitchAccount = { switched += it })
        try {
            assertTrue(model.uiState.value.savedAccounts.single().canSwitch)
            saved.value = saved.value.copy(accounts = listOf(saved.value.accounts.single().copy(password = "")))
            model.switchAccount("bob")
            assertTrue(switched.isEmpty())
            assertEquals(ProfileSavedAccountStatus.CredentialsRequired, model.uiState.value.savedAccounts.single().status)
            saved.value = saved.value.copy(accounts = emptyList())
            model.switchAccount("bob")
            assertTrue(switched.isEmpty())
            assertTrue(model.uiState.value.savedAccounts.isEmpty())
        } finally { scope.cancel() }
    }

    @Test
    fun settingsDisplayFollowsSharedSourceInsteadOfUnconfirmedRequests() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = MutableStateFlow(BcNotificationSettings())
        val deobfuscation = MutableStateFlow(false)
        val requestedSettings = mutableListOf<BcNotificationSettings>()
        val requestedText = mutableListOf<Boolean>()
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            notificationSettings = settings, deobfuscationEnabled = deobfuscation,
            onNotificationSettingsChanged = { requestedSettings += it },
            onDeobfuscationEnabledChanged = { requestedText += it }, onSwitchAccount = {})
        try {
            model.onSuppressDirectMessagesWhenForegroundChange(true)
            model.onDeobfuscationEnabledChange(true)
            assertFalse(model.uiState.value.notificationSettings.suppressDirectMessagesWhenForeground)
            assertFalse(model.uiState.value.deobfuscationEnabled)
            assertEquals(listOf(true), requestedText)
            settings.value = requestedSettings.single()
            deobfuscation.value = true
            assertTrue(model.uiState.value.notificationSettings.suppressDirectMessagesWhenForeground)
            assertTrue(model.uiState.value.deobfuscationEnabled)
            settings.value = BcNotificationSettings(suppressRoomMessagesWhenForeground = false)
            deobfuscation.value = false
            assertFalse(model.uiState.value.notificationSettings.suppressRoomMessagesWhenForeground)
            assertFalse(model.uiState.value.deobfuscationEnabled)
        } finally { scope.cancel() }
    }

    @Test
    fun routingEditorFollowsPersistedRulesAndForegroundTogglesPreserveThem() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = MutableStateFlow(BcNotificationSettings())
        val requested = mutableListOf<BcNotificationSettings>()
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            notificationSettings = settings,
            onNotificationSettingsChanged = { requested += it }, onSwitchAccount = {})
        try {
            val edited = BcNotificationSettings(
                matrix = mapOf("Submissive:Beep" to dev.vivian.mobilebc.core.model.BcNotificationLevel.Critical),
                rules = listOf(dev.vivian.mobilebc.core.model.BcNotificationRule("id", "Rule", memberNumber = 42)),
            )
            model.updateNotificationSettings(edited)
            assertEquals(BcNotificationSettings(), model.uiState.value.notificationSettings.routing)
            settings.value = requested.single()
            assertEquals(edited, model.uiState.value.notificationSettings.routing)
            model.onSuppressDirectMessagesWhenForegroundChange(true)
            assertEquals(edited.matrix, requested.last().matrix)
            assertEquals(edited.rules, requested.last().rules)
            settings.value = BcNotificationSettings()
            assertEquals(BcNotificationSettings(), model.uiState.value.notificationSettings.routing)
        } finally { scope.cancel() }
    }

    @Test
    fun consecutiveTogglesUseLatestSharedSettings() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val settings = MutableStateFlow(BcNotificationSettings())
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            notificationSettings = settings,
            onNotificationSettingsChanged = { settings.value = it }, onSwitchAccount = {})
        try {
            model.onSuppressDirectMessagesWhenForegroundChange(true)
            model.onSuppressSystemAlertsWhenForegroundChange(true)
            assertTrue(settings.value.suppressDirectMessagesWhenForeground)
            assertTrue(settings.value.suppressSystemAlertsWhenForeground)
            assertTrue(model.uiState.value.notificationSettings.suppressDirectMessagesWhenForeground)
            assertTrue(model.uiState.value.notificationSettings.suppressSystemAlertsWhenForeground)
        } finally { scope.cancel() }
    }

    @Test
    fun validSwitchDelegatesAndDisplaysOnlyTheSharedSwitchState() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val switchState = MutableStateFlow(BcAccountSwitchState())
        val requestedAccounts = mutableListOf<String>()
        val viewModel = createViewModel(scope, switchState) { requestedAccounts += it }

        try {
            viewModel.switchAccount("bob")

            assertEquals(listOf("bob"), requestedAccounts)
            assertNull(viewModel.uiState.value.accountSwitchMessage)
            assertFalse(viewModel.uiState.value.isSwitchingAccount)

            switchState.value = BcAccountSwitchState(
                isSwitching = true,
                switchingToAccountName = "bob",
            )

            assertTrue(viewModel.uiState.value.isSwitchingAccount)
            assertEquals("Switching to bob...", viewModel.uiState.value.accountSwitchMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun invalidLocalSwitchDoesNotDispatchACommand() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requestedAccounts = mutableListOf<String>()
        val viewModel = createViewModel(scope, MutableStateFlow(BcAccountSwitchState())) {
            requestedAccounts += it
        }

        try {
            viewModel.switchAccount("missing")

            assertTrue(requestedAccounts.isEmpty())
            assertEquals(
                "Saved credentials were not found for missing.",
                viewModel.uiState.value.accountSwitchMessage,
            )
            assertTrue(viewModel.uiState.value.accountSwitchMessageIsError)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun accountWithoutUsableCredentialsCannotBeSwitchedFromProfile() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requestedAccounts = mutableListOf<String>()
        val viewModel = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            savedAccounts = listOf(
                BcSavedLoginAccount("alice", "password", "https://example.com"),
                BcSavedLoginAccount("bob", "", "https://example.com"),
            ),
            onSwitchAccount = { requestedAccounts += it },
        )

        try {
            val bob = viewModel.uiState.value.savedAccounts.single { it.accountName == "bob" }
            assertFalse(bob.canSwitch)
            assertEquals(ProfileSavedAccountStatus.CredentialsRequired, bob.status)

            viewModel.switchAccount("bob")

            assertTrue(requestedAccounts.isEmpty())
            assertEquals(
                "Saved credentials are unavailable for bob. Log in again to save them.",
                viewModel.uiState.value.accountSwitchMessage,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun missingSystemVersionDisablesSwitchingForEverySavedAccount() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requestedAccounts = mutableListOf<String>()
        val viewModel = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            initialSystemBcVersion = "",
            onSwitchAccount = { requestedAccounts += it },
        )

        try {
            val bob = viewModel.uiState.value.savedAccounts.single { it.accountName == "bob" }
            assertEquals("", viewModel.uiState.value.systemBcVersion)
            assertFalse(bob.canSwitch)
            assertEquals(ProfileSavedAccountStatus.VersionRequired, bob.status)

            viewModel.switchAccount("bob")

            assertTrue(requestedAccounts.isEmpty())
            assertEquals(
                "Confirm the system BC version before switching accounts.",
                viewModel.uiState.value.accountSwitchMessage,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun packetLogsAreCollectedOnlyWhileProfileIsVisible() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val packetLogs = MutableStateFlow<List<ProfilePacketLogEntry>>(emptyList())
        val viewModel = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            packetLogs = packetLogs,
            onSwitchAccount = {},
        )
        val first = ProfilePacketLogEntry(1, "RECV", "ChatRoomChat", "{}", Instant.EPOCH)
        val second = ProfilePacketLogEntry(2, "RECV", "AccountBeep", "{}", Instant.EPOCH)

        try {
            packetLogs.value = listOf(first)
            assertTrue(viewModel.uiState.value.packetLogs.isEmpty())

            viewModel.setPacketLogCollectionEnabled(true)
            assertEquals(1, viewModel.uiState.value.packetLogCount)

            viewModel.setPacketLogCollectionEnabled(false)
            packetLogs.value = listOf(first, second)
            assertEquals(1, viewModel.uiState.value.packetLogCount)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun queuedLogoutCannotActOnANewerLogin() {
        val tasks = java.util.ArrayDeque<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
        }
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val snapshot = MutableStateFlow(BcSessionSnapshot())
        var logoutCalls = 0
        var navigations = 0
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            connectionStatus = object : ConnectionStatusReader { override val snapshot = snapshot },
            auth = object : BcSessionAuth {
                override suspend fun login(accountName: String, password: String) = Unit
                override suspend fun logout() { logoutCalls++ }
            }, onLoggedOut = { navigations++ }, onSwitchAccount = {})
        try {
            model.logout()
            snapshot.value = snapshot.value.copy(relationships = snapshot.value.relationships.nextUnloaded())
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
            assertEquals(0, logoutCalls)
            assertEquals(0, navigations)
            assertFalse(model.uiState.value.isLoggingOut)
            assertNull(model.uiState.value.accountSwitchMessage)
        } finally {
            scope.cancel()
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test
    fun cancelledLogoutCannotNavigateAfterNonCooperativeCompletion() = kotlinx.coroutines.runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val result = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var navigations = 0
        val model = createViewModel(scope, MutableStateFlow(BcAccountSwitchState()),
            auth = object : BcSessionAuth {
                override suspend fun login(accountName: String, password: String) = Unit
                override suspend fun logout() {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        entered.complete(Unit)
                        result.await()
                    }
                }
            }, onLoggedOut = { navigations++ }, onSwitchAccount = {})
        model.logout()
        entered.await()
        scope.cancel()
        result.complete(Unit)
        kotlinx.coroutines.yield()
        assertEquals(0, navigations)
        assertFalse(model.uiState.value.isLoggingOut)
    }

    @Test
    fun logoutBlocksOverlappingAccountActionsAndCanBeRetriedAfterFailure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val finishLogout = CompletableDeferred<Unit>()
        var logoutCalls = 0
        var navigationCalls = 0
        var failLogout = true
        val switchCalls = mutableListOf<String>()
        val viewModel = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            auth = object : BcSessionAuth {
                override suspend fun login(accountName: String, password: String) = Unit
                override suspend fun logout() {
                    logoutCalls += 1
                    finishLogout.await()
                    if (failLogout) error("logout unavailable")
                }
            },
            onLoggedOut = { navigationCalls += 1 },
            onSwitchAccount = { switchCalls += it },
        )
        try {
            viewModel.logout()
            viewModel.logout()
            viewModel.switchAccount("bob")
            assertTrue(viewModel.uiState.value.isLoggingOut)
            assertFalse(viewModel.uiState.value.savedAccounts.single { it.accountName == "bob" }.canSwitch)
            assertEquals(1, logoutCalls)
            assertTrue(switchCalls.isEmpty())

            finishLogout.complete(Unit)
            assertFalse(viewModel.uiState.value.isLoggingOut)
            assertTrue(viewModel.uiState.value.accountSwitchMessageIsError)
            assertEquals("logout unavailable", viewModel.uiState.value.accountSwitchMessage)
            assertEquals(0, navigationCalls)

            failLogout = false
            viewModel.logout()
            assertEquals(2, logoutCalls)
            assertEquals(1, navigationCalls)
            assertFalse(viewModel.uiState.value.isLoggingOut)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun autoRejoinDisplayFollowsRuntimeAuthorityInsteadOfRequestsOrSaveCompletion() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val snapshot = MutableStateFlow(BcSessionSnapshot(autoJoinRoomAfterReconnect = false))
        val requested = mutableListOf<Boolean>()
        val saved = mutableListOf<Boolean>()
        val model = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            connectionStatus = object : ConnectionStatusReader { override val snapshot = snapshot },
            recoverySettings = object : SessionRecoverySettings {
                override fun setAutoJoinRoomAfterReconnect(enabled: Boolean) { requested += enabled }
            },
            onAutoJoinRoomAfterReconnectChanged = { saved += it },
            onSwitchAccount = {},
        )
        try {
            model.onAutoJoinRoomAfterReconnectChange(true)
            assertEquals(listOf(true), requested)
            assertEquals(listOf(true), saved)
            assertFalse(model.uiState.value.autoJoinRoomAfterReconnect)
            snapshot.value = snapshot.value.copy(autoJoinRoomAfterReconnect = true)
            assertTrue(model.uiState.value.autoJoinRoomAfterReconnect)
            snapshot.value = snapshot.value.copy(autoJoinRoomAfterReconnect = false)
            assertFalse(model.uiState.value.autoJoinRoomAfterReconnect)
        } finally { scope.cancel() }
    }

    @Test
    fun unsavedAutoRejoinSettingRemainsEffectiveAndRetryClearsTheWarning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val runtimeChanges = mutableListOf<Boolean>()
        var failSave = true
        val viewModel = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            recoverySettings = object : SessionRecoverySettings {
                override fun setAutoJoinRoomAfterReconnect(enabled: Boolean) {
                    runtimeChanges += enabled
                }
            },
            onAutoJoinRoomAfterReconnectChanged = { if (failSave) error("storage unavailable") },
            onSwitchAccount = {},
        )
        try {
            viewModel.onAutoJoinRoomAfterReconnectChange(true)
            assertEquals(listOf(true), runtimeChanges)
            assertTrue(viewModel.uiState.value.autoJoinSaveFailed)

            failSave = false
            viewModel.onAutoJoinRoomAfterReconnectChange(true)
            assertEquals(listOf(true, true), runtimeChanges)
            assertFalse(viewModel.uiState.value.autoJoinSaveFailed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedLoversProduceOneStableMemberRowWithoutDiscardingOtherMembers() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val viewModel = createViewModel(
            scope = scope,
            switchState = MutableStateFlow(BcAccountSwitchState()),
            connectionStatus = FakeConnectionStatusReader(
                BcSessionSnapshot(
                    relationships = BcRelationshipSnapshot(
                        loadState = BcRelationshipLoadState.Loaded,
                        self = BcSelfRelations(loverships = listOf(
                            BcLovership(2, "Bob", 0, null),
                            BcLovership(2, "Duplicate Bob", 1, null),
                            BcLovership(3, "Charlie", 2, null),
                        )),
                    ),
                ),
            ),
            onSwitchAccount = {},
        )
        try {
            val rows = viewModel.uiState.value.relationships.lovers
            assertEquals(listOf(2, 3), rows.map { it.memberNumber })
            assertEquals("Bob", rows.first().displayName)
        } finally {
            scope.cancel()
        }
    }

    private fun createViewModel(
        scope: CoroutineScope,
        switchState: StateFlow<BcAccountSwitchState>,
        connectionStatus: ConnectionStatusReader = FakeConnectionStatusReader(),
        packetLogs: StateFlow<List<ProfilePacketLogEntry>> = MutableStateFlow(emptyList()),
        savedAccounts: List<BcSavedLoginAccount> = listOf(
            BcSavedLoginAccount("alice", "password", "https://example.com"),
            BcSavedLoginAccount("bob", "password", "https://example.com"),
        ),
        initialSystemBcVersion: String = "R131",
        savedLoginState: StateFlow<BcSavedLoginState> = MutableStateFlow(
            BcSavedLoginState(savedAccounts, "alice", true, initialSystemBcVersion, true),
        ),
        auth: BcSessionAuth = NoOpAuth,
        onLoggedOut: suspend () -> Unit = {},
        recoverySettings: SessionRecoverySettings = NoOpRecoverySettings,
        onAutoJoinRoomAfterReconnectChanged: suspend (Boolean) -> Unit = {},
        notificationSettings: StateFlow<BcNotificationSettings> = MutableStateFlow(BcNotificationSettings()),
        deobfuscationEnabled: StateFlow<Boolean> = MutableStateFlow(false),
        onNotificationSettingsChanged: (BcNotificationSettings) -> Unit = {},
        onDeobfuscationEnabledChanged: (Boolean) -> Unit = {},
        onSwitchAccount: (String) -> Unit,
    ): ProfileViewModel = ProfileViewModel(
        auth = auth,
        connectionStatus = connectionStatus,
        recoverySettings = recoverySettings,
        packetLogEntries = packetLogs,
        onClearPacketLogs = {},
        onExportPacketLogs = { "" },
        accountSwitchState = switchState,
        scope = scope,
        savedLoginState = savedLoginState,
        onLoggedOut = onLoggedOut,
        onAutoJoinRoomAfterReconnectChanged = onAutoJoinRoomAfterReconnectChanged,
        onSwitchAccount = onSwitchAccount,
        notificationSettings = notificationSettings,
        deobfuscationEnabled = deobfuscationEnabled,
        onNotificationSettingsChanged = onNotificationSettingsChanged,
        onDeobfuscationEnabledChanged = onDeobfuscationEnabledChanged,
    )

    private class FakeConnectionStatusReader(
        initialSnapshot: BcSessionSnapshot = BcSessionSnapshot(),
    ) : ConnectionStatusReader {
        override val snapshot: StateFlow<BcSessionSnapshot> = MutableStateFlow(initialSnapshot)
    }

    private object NoOpRecoverySettings : SessionRecoverySettings {
        override fun setAutoJoinRoomAfterReconnect(enabled: Boolean) = Unit
    }

    private object NoOpAuth : BcSessionAuth {
        override suspend fun login(accountName: String, password: String) = Unit
        override suspend fun logout() = Unit
    }
}
