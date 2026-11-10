package dev.vivian.mobilebc

import androidx.lifecycle.SavedStateHandle
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcError
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.model.BcInteractionCatalogLoadResult
import dev.vivian.mobilebc.core.model.BcInteractionCatalogSnapshot
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.ReconnectReason
import dev.vivian.mobilebc.resource.BcWebResourceRepository
import dev.vivian.mobilebc.resource.BcWebResourceSnapshot
import dev.vivian.mobilebc.resource.BcWebResourceSyncState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class MobileBcAppViewModelTest {
    @Test
    fun shellAlwaysFollowsBindingAndAccountState() {
        val online = MobileBcAppUiState(sessionReady = true, activeAccountName = "alice")
        assertEquals(AppShell.Main, online.shell)
        assertEquals(AppShell.Loading, online.copy(sessionReady = false).shell)
        val signedOut = online.copy(activeAccountName = null)
        assertEquals(AppShell.Login, signedOut.shell)
        val recoverable = signedOut.copy(savedAccountCount = 1)
        assertEquals(AppShell.Main, recoverable.shell)
        assertEquals(AppShell.Loading, recoverable.copy(sessionReady = false).shell)
        assertEquals(AppShell.Login, recoverable.copy(savedAccountCount = 0).shell)
    }

    @Test
    fun everyMainPageEntryReplacesTheDetailStack() {
        val saved = SavedStateHandle()
        val model = viewModel(saved)
        model.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        model.openCharacterBio(456)
        model.navigate(AppScreen.Main(MainTab.Conversations))
        model.openCharacterChat(456)
        model.onBack()
        assertEquals(AppScreen.Main(MainTab.Conversations), model.uiState.value.screen)
        model.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), model.uiState.value.screen)
        assertEquals(false, model.canNavigateBack)
        val restored = viewModel(saved)
        restored.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        assertEquals(false, restored.canNavigateBack)
    }

    @Test
    fun navigationMotionTracksForwardBackAndReplacementWithoutChangingRoutes() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        viewModel.openCharacterChat(456)
        assertEquals(NavigationMotion.Forward, viewModel.uiState.value.navigationMotion)
        viewModel.onBack()
        assertEquals(NavigationMotion.Backward, viewModel.uiState.value.navigationMotion)
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)
        viewModel.selectMainPage(2)
        assertEquals(NavigationMotion.Replace, viewModel.uiState.value.navigationMotion)
    }

    @Test
    fun swipingMainPagesDoesNotBuildABackStack() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        listOf(1, 2, 1, 0, 2).forEach(viewModel::selectMainPage)

        viewModel.onBack()

        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)
        assertEquals(false, viewModel.canNavigateBack)
    }

    @Test
    fun detailReturnsToSelectedPageAfterRestoration() {
        val saved = SavedStateHandle()
        val viewModel = viewModel(saved)
        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        viewModel.selectMainPage(1)
        viewModel.openCharacterChat(456)

        val restored = viewModel(saved)
        restored.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        restored.onBack()

        assertEquals(AppScreen.Main(MainTab.Conversations), restored.uiState.value.screen)
        restored.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), restored.uiState.value.screen)
        assertEquals(false, restored.canNavigateBack)
    }

    @Test
    fun settingsPageRestoresAndRoomPageTracksMembership() {
        val saved = SavedStateHandle()
        val viewModel = viewModel(saved)
        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        viewModel.selectMainPage(2)
        val restored = viewModel(saved)
        restored.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        assertEquals(AppScreen.Main(MainTab.Profile), restored.uiState.value.screen)

        restored.syncScreenWithSessionSnapshot(roomSession("Lobby").copy(currentRoom = null), BcAccountSwitchState())
        assertEquals(AppScreen.Main(MainTab.Profile), restored.uiState.value.screen)
        restored.selectMainPage(0)
        assertEquals(AppScreen.Main(MainTab.Rooms), restored.uiState.value.screen)
        restored.syncScreenWithSessionSnapshot(roomSession("Next"), BcAccountSwitchState())
        assertEquals(AppScreen.Chat(bcRoomConversationId("Next")), restored.uiState.value.screen)
    }

    @Test
    fun mainPagerExcludesDetailsAndDiscoveryWhileInRoom() {
        val roomId = bcRoomConversationId("Lobby")
        assertEquals(0, AppScreen.Chat(roomId).mainPage(roomId))
        assertEquals(0, AppScreen.Main(MainTab.Rooms).mainPage(null))
        assertEquals(null, AppScreen.Main(MainTab.Rooms).mainPage(roomId))
        assertEquals(null, AppScreen.Chat(BcConversationId("message:456")).mainPage(roomId))
        assertEquals(null, AppScreen.Chat(bcRoomConversationId("Old")).mainPage(roomId))
        assertEquals(null, AppScreen.CharacterBio(456).mainPage(roomId))
    }

    @Test
    fun hiddenRoomToolsCannotConsumeBackFromDirectOrHistoricalConversations() {
        val activeRoom = bcRoomConversationId("Current")
        val directChat = AppScreen.Chat(BcConversationId("message:456"))
        val historicalRoom = AppScreen.Chat(bcRoomConversationId("Previous"))

        assertEquals(false, directChat.canHandleRoomPickerBack(activeRoom))
        assertEquals(false, historicalRoom.canHandleRoomPickerBack(activeRoom))
        assertEquals(false, AppScreen.CharacterBio(456).canHandleRoomPickerBack(activeRoom))
        assertEquals(false, AppScreen.Chat(activeRoom).canHandleRoomPickerBack(null))
        assertEquals(true, AppScreen.Chat(activeRoom).canHandleRoomPickerBack(activeRoom))
        assertEquals(true, AppScreen.RoomDetails.canHandleRoomPickerBack(activeRoom))
    }

    @Test
    fun authoritativeRenameMovesActiveChatAndDetailBackDestination() {
        val viewModel = viewModel()
        val before = roomSession("Before")
        viewModel.syncScreenWithSessionSnapshot(before, BcAccountSwitchState())
        viewModel.navigate(AppScreen.Chat(bcRoomConversationId("Before")))
        viewModel.navigate(AppScreen.RoomDetails)

        viewModel.syncScreenWithSessionSnapshot(roomSession("After"), BcAccountSwitchState())

        assertEquals(AppScreen.RoomDetails, viewModel.uiState.value.screen)
        viewModel.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("After")), viewModel.uiState.value.screen)
    }

    @Test
    fun newVisitOrLoginDoesNotRedirectHistoricalRoomChat() {
        for (after in listOf(roomSession("After").copy(roomPresenceEpoch = 2), roomSession("After").copy(selfRuleLogSessionId = "next"))) {
            val viewModel = viewModel()
            viewModel.syncScreenWithSessionSnapshot(roomSession("Before"), BcAccountSwitchState())
            val historical = AppScreen.Chat(bcRoomConversationId("Before"))
            viewModel.navigate(historical)

            viewModel.syncScreenWithSessionSnapshot(after, BcAccountSwitchState())

            assertEquals(historical, viewModel.uiState.value.screen)
        }
    }

    private fun roomSession(name: String): BcSessionSnapshot = BcSessionSnapshot(
        state = BcSessionState.Ready,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        desiredOnline = true,
        account = authenticatedAccount(),
        currentRoom = BcRoom(BcRoomName(name), "", 1, 10, false, "EN"),
        roomPresenceEpoch = 1,
        selfRuleLogSessionId = "login",
    )

    @Test
    fun authenticatedEmissionKeepsRoomsTab() {
        val viewModel = viewModel()
        viewModel.navigate(AppScreen.Main(MainTab.Rooms))

        viewModel.syncScreenWithSessionState(authenticatedState())
        viewModel.syncScreenWithSessionState(authenticatedState())

        assertEquals(AppScreen.Main(MainTab.Rooms), viewModel.uiState.value.screen)
    }

    @Test
    fun authenticatedEmissionKeepsChatScreen() {
        val conversationId = BcConversationId("message:123")
        val viewModel = viewModel()
        viewModel.navigate(AppScreen.Chat(conversationId))

        viewModel.syncScreenWithSessionState(authenticatedState())
        viewModel.syncScreenWithSessionState(authenticatedState())

        assertEquals(AppScreen.Chat(conversationId), viewModel.uiState.value.screen)
    }

    @Test
    fun loginWithoutAnActiveRoomOpensRoomDiscovery() {
        val viewModel = viewModel()

        viewModel.syncScreenWithSessionState(BcSessionState.Idle)
        viewModel.syncScreenWithSessionState(authenticatedState())

        assertEquals(AppScreen.Main(MainTab.Rooms), viewModel.uiState.value.screen)
        assertEquals(true, viewModel.isAtHome)
        assertEquals(false, viewModel.canNavigateBack)
    }

    @Test
    fun verifiedSessionRestoresActiveRoomAsHomeWithoutABackLoop() {
        val viewModel = viewModel()

        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())

        val room = AppScreen.Chat(bcRoomConversationId("Lobby"))
        assertEquals(room, viewModel.uiState.value.screen)
        assertEquals(room.conversationId, viewModel.currentRoomConversationId)
        assertEquals(true, viewModel.isAtHome)
        assertEquals(false, viewModel.canNavigateBack)
        viewModel.onBack()
        assertEquals(room, viewModel.uiState.value.screen)
    }

    @Test
    fun restoredActiveRoomDropsTheLegacyConversationListBackStep() {
        val room = AppScreen.Chat(bcRoomConversationId("Lobby"))
        val viewModel = viewModel(
            SavedStateHandle(
                mapOf(
                    KEY_CURRENT_SCREEN to room.toRoute(),
                    "back_stack_routes" to arrayListOf(AppScreen.Main(MainTab.Conversations).toRoute()),
                ),
            ),
        )

        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())

        assertEquals(room, viewModel.uiState.value.screen)
        assertEquals(false, viewModel.canNavigateBack)
        viewModel.onBack()
        assertEquals(room, viewModel.uiState.value.screen)
    }

    @Test
    fun browsingDiscoveryAndProfilePreservesRoomAndBackDestination() {
        val viewModel = viewModel()
        val session = roomSession("Lobby")
        viewModel.syncScreenWithSessionSnapshot(session, BcAccountSwitchState())

        viewModel.openRooms()
        viewModel.syncScreenWithSessionSnapshot(session, BcAccountSwitchState())
        assertEquals(AppScreen.Main(MainTab.Rooms), viewModel.uiState.value.screen)
        assertEquals(bcRoomConversationId("Lobby"), viewModel.currentRoomConversationId)
        viewModel.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)

        viewModel.openProfile()
        viewModel.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)
        assertEquals(false, viewModel.canNavigateBack)
    }

    @Test
    fun directChatAndCharacterDetailsReturnToActiveRoom() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())

        viewModel.openCharacterChat(456)
        assertEquals(AppScreen.Chat(BcConversationId("message:456")), viewModel.uiState.value.screen)
        viewModel.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)

        viewModel.openCharacterBio(456)
        viewModel.onBack()
        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)
    }

    @Test
    fun openingCurrentRoomClearsAuxiliaryHistory() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())
        viewModel.openConversations()
        viewModel.openCharacterChat(456)

        viewModel.openConversation(bcRoomConversationId("Lobby"))

        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)
        assertEquals(false, viewModel.canNavigateBack)
    }

    @Test
    fun roomRejoinFromDiscoveryOpensRoomWithoutLeavingHistoryBehind() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionState(authenticatedState())

        viewModel.syncScreenWithSessionSnapshot(roomSession("Lobby"), BcAccountSwitchState())

        assertEquals(AppScreen.Chat(bcRoomConversationId("Lobby")), viewModel.uiState.value.screen)
        assertEquals(false, viewModel.canNavigateBack)
    }

    @Test
    fun roomDepartureReturnsToDiscoveryAndRemovesLiveRoomFromHistory() {
        for (browseAway in listOf(false, true)) {
            val viewModel = viewModel()
            val session = roomSession("Lobby")
            viewModel.syncScreenWithSessionSnapshot(session, BcAccountSwitchState())
            if (browseAway) viewModel.openConversations()

            viewModel.syncScreenWithSessionSnapshot(session.copy(currentRoom = null), BcAccountSwitchState())
            if (browseAway) viewModel.onBack()

            assertEquals(AppScreen.Main(MainTab.Rooms), viewModel.uiState.value.screen)
            assertEquals(null, viewModel.currentRoomConversationId)
            assertEquals(false, viewModel.canNavigateBack)
        }
    }

    @Test
    fun accountSwitchReturnsToTheNewAccountsHome() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionSnapshot(roomSession("Before"), BcAccountSwitchState())
        viewModel.handleActiveAccountChanged("tester")
        viewModel.openCharacterBio(456)

        val next = roomSession("After").copy(account = authenticatedAccount().copy(accountName = "other", memberNumber = 987))
        viewModel.syncScreenWithSessionSnapshot(next, BcAccountSwitchState())
        viewModel.handleActiveAccountChanged("other")

        assertEquals(AppScreen.Chat(bcRoomConversationId("After")), viewModel.uiState.value.screen)
        assertEquals(false, viewModel.canNavigateBack)
    }

    @Test
    fun loginOpenedFromSettingsSurvivesOnlineUpdatesAndReturnsToSettings() {
        val savedState = SavedStateHandle()
        val viewModel = viewModel(savedState)
        viewModel.navigate(AppScreen.Main(MainTab.Profile))
        viewModel.navigate(AppScreen.Login)

        viewModel.syncScreenWithSessionState(authenticatedState())
        val restored = viewModel(savedState)
        restored.syncScreenWithSessionState(authenticatedState())

        assertEquals(AppScreen.Login, restored.uiState.value.screen)
        assertEquals(true, restored.canNavigateBackFromLogin)
        restored.onBack()
        assertEquals(AppScreen.Main(MainTab.Profile), restored.uiState.value.screen)
        assertEquals(false, restored.canNavigateBackFromLogin)
    }

    @Test
    fun restoredProfileTabSurvivesAuthenticatedInitialState() {
        val viewModel = viewModel(
            SavedStateHandle(mapOf(KEY_CURRENT_SCREEN to AppScreen.Main(MainTab.Profile).toRoute())),
        )

        viewModel.syncScreenWithSessionState(authenticatedState())

        assertEquals(AppScreen.Main(MainTab.Profile), viewModel.uiState.value.screen)
    }

    @Test
    fun restoredProtectedScreenRedirectsToLoginWhenUnauthenticated() {
        val viewModel = viewModel(
            SavedStateHandle(mapOf(KEY_CURRENT_SCREEN to AppScreen.Chat(BcConversationId("room:lobby")).toRoute())),
        )

        viewModel.syncScreenWithSessionState(BcSessionState.Idle)

        assertEquals(AppScreen.Login, viewModel.uiState.value.screen)
    }

    @Test
    fun staleAuthenticatedSessionIsNotProjectedAsOnline() {
        val account = authenticatedAccount()
        val viewModel = viewModel()

        viewModel.syncScreenWithSessionSnapshot(
            session = BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.Stale,
                desiredOnline = true,
                account = account,
            ),
            switchState = BcAccountSwitchState(),
        )

        assertEquals(SessionAvailability.Reconnecting, viewModel.uiState.value.sessionAvailability)
    }

    @Test
    fun failedLoginWithRetainedAccountDoesNotLeaveLoginScreen() {
        val viewModel = viewModel()

        viewModel.syncScreenWithSessionSnapshot(
            session = BcSessionSnapshot(
                state = BcSessionState.Error(BcError.Unknown("Login failed"), recoverable = true),
                connectionHealth = BcConnectionHealth.Offline,
                desiredOnline = false,
                account = authenticatedAccount(),
            ),
            switchState = BcAccountSwitchState(),
        )

        assertEquals(AppScreen.Login, viewModel.uiState.value.screen)
        assertEquals(SessionAvailability.Failed, viewModel.uiState.value.sessionAvailability)
    }

    @Test
    fun restoredChatRemainsAvailableWhileAccountIsReconnecting() {
        val screen = AppScreen.Chat(BcConversationId("message:123"))
        val viewModel = viewModel(SavedStateHandle(mapOf(KEY_CURRENT_SCREEN to screen.toRoute())))

        viewModel.syncScreenWithSessionSnapshot(
            session = BcSessionSnapshot(
                state = BcSessionState.Reconnecting(ReconnectReason.NetworkLost, attempt = 1),
                connectionHealth = BcConnectionHealth.Stale,
                desiredOnline = true,
                account = authenticatedAccount(),
            ),
            switchState = BcAccountSwitchState(),
        )

        assertEquals(screen, viewModel.uiState.value.screen)
        assertEquals(SessionAvailability.Reconnecting, viewModel.uiState.value.sessionAvailability)
    }

    @Test
    fun terminalSessionTextIsNotOverriddenByStaleHealth() {
        val cases = listOf(
            Triple(
                BcSessionState.Error(BcError.Unknown("Disconnected"), recoverable = true),
                SessionAvailability.Failed,
                "Session error",
            ),
            Triple(BcSessionState.Stopped, SessionAvailability.Stopped, "Session stopped"),
        )
        cases.forEach { (state, availability, text) ->
            val viewModel = viewModel()
            viewModel.syncScreenWithSessionSnapshot(
                session = BcSessionSnapshot(
                    state = state,
                    connectionHealth = BcConnectionHealth.Stale,
                    account = authenticatedAccount(),
                ),
                switchState = BcAccountSwitchState(),
            )

            assertEquals(availability, viewModel.uiState.value.sessionAvailability)
            assertEquals(text, viewModel.uiState.value.sessionStatusText)
        }
    }

    @Test
    fun accountSwitchProjectsTheDestinationSeparatelyFromTheCurrentAccount() {
        val viewModel = viewModel()
        viewModel.syncScreenWithSessionSnapshot(
            session = BcSessionSnapshot(
                state = BcSessionState.Ready,
                connectionHealth = BcConnectionHealth.VerifiedOnline,
                desiredOnline = true,
                account = authenticatedAccount(),
            ),
            switchState = BcAccountSwitchState(isSwitching = true, switchingToAccountName = "other"),
        )

        assertEquals(SessionAvailability.SwitchingAccount, viewModel.uiState.value.sessionAvailability)
        assertEquals("tester", viewModel.uiState.value.activeAccountName)
        assertEquals("other", viewModel.uiState.value.switchingToAccountName)
        assertEquals("Switching to other...", viewModel.uiState.value.sessionStatusText)
    }

    private fun viewModel(
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
    ): MobileBcAppViewModel = MobileBcAppViewModel(
        savedStateHandle = savedStateHandle,
        bcWebResources = FakeBcWebResourceRepository,
        interactionCatalogRepository = FakeInteractionCatalogRepository,
        ruleCatalogRepository = FakeRuleCatalogRepository,
    )

    private fun authenticatedState(): BcSessionState =
        BcSessionState.Ready

    private fun authenticatedAccount(): BcAccount =
        BcAccount(
            memberNumber = 123,
            displayName = "Tester",
            accountName = "tester",
        )

    private object FakeBcWebResourceRepository : BcWebResourceRepository {
        override val syncState: StateFlow<BcWebResourceSyncState> =
            MutableStateFlow(BcWebResourceSyncState.Idle)

        override fun currentSnapshot(version: String): BcWebResourceSnapshot? = null

        override suspend fun sync(version: String): BcWebResourceSnapshot {
            error("Not used by app navigation tests.")
        }
    }

    private object FakeInteractionCatalogRepository : BcInteractionCatalogRepository {
        override fun currentSnapshot(version: String): BcInteractionCatalogSnapshot? = null

        override fun refresh(version: String): BcInteractionCatalogLoadResult =
            BcInteractionCatalogLoadResult.MissingResources(emptyList())
    }

    private object FakeRuleCatalogRepository : BcRuleCatalogRepository {
        override fun currentSnapshot(version: String): BcRuleCatalog? = null

        override fun refresh(version: String): BcRuleCatalogLoadResult =
            BcRuleCatalogLoadResult.MissingResources(emptyList())
    }

    private companion object {
        private const val KEY_CURRENT_SCREEN = "current_screen_route"
    }
}

private fun MobileBcAppViewModel.syncScreenWithSessionState(state: BcSessionState) {
    val authenticated = state == BcSessionState.Ready
    syncScreenWithSessionSnapshot(
        session = BcSessionSnapshot(
            state = state,
            connectionHealth = when (state) {
                BcSessionState.Ready -> BcConnectionHealth.VerifiedOnline
                else -> BcConnectionHealth.Offline
            },
            desiredOnline = authenticated,
            account = if (authenticated) {
                BcAccount(memberNumber = 123, displayName = "Tester", accountName = "tester")
            } else {
                null
            },
        ),
        switchState = BcAccountSwitchState(),
    )
}
