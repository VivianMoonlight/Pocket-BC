package dev.vivian.mobilebc

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewModelScope
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.socket.PacketLogStore
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bcrules.BcAppearanceGroupKind
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcCachedRoomBackgroundCatalogRepository
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSessionPreferencesStore
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.label
import dev.vivian.mobilebc.domain.session.core.statusDescription
import dev.vivian.mobilebc.feature.auth.LoginViewModel
import dev.vivian.mobilebc.feature.chat.ChatViewModel
import dev.vivian.mobilebc.feature.characterprofile.CharacterProfileViewModel
import dev.vivian.mobilebc.feature.characterprofile.CharacterPortraitState
import dev.vivian.mobilebc.feature.conversations.ConversationsViewModel
import dev.vivian.mobilebc.feature.profile.ProfileViewModel
import dev.vivian.mobilebc.feature.roomdetails.RoomDetailsViewModel
import dev.vivian.mobilebc.feature.rooms.RoomsViewModel
import dev.vivian.mobilebc.domain.session.runtime.SessionAccess
import dev.vivian.mobilebc.resource.BcWebResourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class AppShell {
    Loading,
    Login,
    Main,
}

enum class SessionAvailability {
    NoAccount,
    Starting,
    Online,
    Reconnecting,
    SwitchingAccount,
    Stopped,
    Failed,
}

data class MobileBcAppUiState(
    val navigation: AppNavigationSnapshot = AppNavigationSnapshot(),
    val sessionReady: Boolean = false,
    val sessionAvailability: SessionAvailability = SessionAvailability.Starting,
    val sessionStatusText: String = "Starting BC session service",
    val activeAccountName: String? = null,
    val switchingToAccountName: String? = null,
    val switchErrorMessage: String? = null,
    val savedAccountCount: Int = 0,
    val currentRoomConversationId: BcConversationId? = null,
    val accountGeneration: Long = 0L,
    val roomPresenceEpoch: Long = 0L,
) {
    val screen: AppScreen get() = navigation.screen
    val navigationMotion: NavigationMotion get() = navigation.motion

    val shell: AppShell
        get() = when {
            !sessionReady -> AppShell.Loading
            activeAccountName == null && savedAccountCount <= 0 -> AppShell.Login
            else -> AppShell.Main
        }
}

class MobileBcAppViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val bcWebResources: BcWebResourceRepository,
    private val interactionCatalogRepository: BcInteractionCatalogRepository,
    private val ruleCatalogRepository: BcRuleCatalogRepository,
    private val roomBackgroundCatalogRepository: BcCachedRoomBackgroundCatalogRepository =
        BcCachedRoomBackgroundCatalogRepository(resourceReader = { _, _ -> null }),
) : ViewModel() {
    private val _uiState = MutableStateFlow(MobileBcAppUiState())
    val uiState: StateFlow<MobileBcAppUiState> = _uiState.asStateFlow()
    private val _portraitCandidates = MutableStateFlow<List<CharacterPortraitState>>(emptyList())
    internal val portraitCandidates: StateFlow<List<CharacterPortraitState>> = _portraitCandidates.asStateFlow()
    private val navigation = AppNavigationState(savedStateHandle) { snapshot ->
        _uiState.value = _uiState.value.copy(navigation = snapshot)
    }

    val canNavigateBackFromLogin: Boolean
        get() = uiState.value.screen == AppScreen.Login && navigation.hasBackStack

    val currentRoomConversationId: BcConversationId?
        get() = uiState.value.currentRoomConversationId

    val isAtHome: Boolean
        get() = uiState.value.screen == homeScreen()

    val canNavigateBack: Boolean
        get() = navigation.canGoBack(homeScreen())

    private var sessionFeatureSet: SessionFeatureSet? = null
    private var sessionUiJob: Job? = null
    private var lastObservedActiveAccountName: String? = null
    private var pendingNotificationHomeOpen: Boolean = false
    private var lastObservedSessionSnapshot: BcSessionSnapshot? = null

    val loginViewModel: LoginViewModel
        get() = requireSessionFeatureSet().loginViewModel
    val conversationsViewModel: ConversationsViewModel
        get() = requireSessionFeatureSet().conversationsViewModel
    val roomsViewModel: RoomsViewModel
        get() = requireSessionFeatureSet().roomsViewModel
    val profileViewModel: ProfileViewModel
        get() = requireSessionFeatureSet().profileViewModel
    val roomDetailsViewModel: RoomDetailsViewModel
        get() = requireSessionFeatureSet().roomDetailsViewModel

    fun characterProfileViewModel(memberNumber: Int) =
        requireSessionFeatureSet().characterProfileViewModel(memberNumber)

    suspend fun attachSession(
        session: SessionAccess,
        packetLog: PacketLogStore,
        preferences: BcSessionPreferencesStore,
        accountSwitchState: StateFlow<BcAccountSwitchState>,
        switchAccount: (String) -> Unit,
        notificationSettings: StateFlow<BcNotificationSettings> = MutableStateFlow(BcNotificationSettings()),
        onNotificationSettingsChanged: (BcNotificationSettings) -> Unit = {},
        readNotificationDiagnostics: () -> BcNotificationDiagnostics = { BcNotificationDiagnostics() },
        deobfuscationEnabled: StateFlow<Boolean> = MutableStateFlow(false),
        onDeobfuscationEnabledChanged: (Boolean) -> Unit = {},
    ) {
        val existing = sessionFeatureSet
        if (existing?.matches(session, preferences) == true) {
            if (!_uiState.value.sessionReady) {
                _uiState.value = _uiState.value.copy(sessionReady = true)
            }
            syncScreenWithSessionSnapshot(
                session = existing.connectionStatus.snapshot.value,
                switchState = existing.accountSwitchState.value,
            )
            return
        }

        val savedLoginState = readLoginStateOrFallback(preferences)
        clearAttachedSession()
        val backgroundVersion = session.connectionStatus.snapshot.value.bcVersion
            ?: savedLoginState.bcVersion.takeIf { savedLoginState.bcVersionConfirmedByUser }
        if (!backgroundVersion.isNullOrBlank()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                roomBackgroundCatalogRepository.refresh(backgroundVersion)
            }
        }
        val featureSet = SessionFeatureSet(
            session = session,
            packetLog = packetLog,
            preferences = preferences,
            accountSwitchStateFlow = accountSwitchState,
            switchAccount = switchAccount,
            bcWebResources = bcWebResources,
            interactionCatalogRepository = interactionCatalogRepository,
            ruleCatalogRepository = ruleCatalogRepository,
            roomBackgroundCatalogRepository = roomBackgroundCatalogRepository,
            initialSavedLoginState = savedLoginState,
            notificationSettings = notificationSettings,
            onNotificationSettingsChanged = onNotificationSettingsChanged,
            readNotificationDiagnostics = readNotificationDiagnostics,
            deobfuscationEnabled = deobfuscationEnabled,
            onDeobfuscationEnabledChanged = onDeobfuscationEnabledChanged,
            parentScope = viewModelScope,
            onOpenConversation = ::openConversation,
            onLoggedOut = ::forceLoginAfterExplicitLogout,
            onLoggedIn = ::openHome,
        )
        sessionFeatureSet = featureSet

        _uiState.value = _uiState.value.copy(
            sessionReady = true,
            savedAccountCount = featureSet.savedAccountCount,
        )
        syncScreenWithSessionSnapshot(
            session = featureSet.connectionStatus.snapshot.value,
            switchState = featureSet.accountSwitchState.value,
        )
        if (pendingNotificationHomeOpen) {
            openNotificationHome()
        }
        sessionUiJob = viewModelScope.launch {
            combine(
                featureSet.connectionStatus.snapshot,
                featureSet.accountSwitchState,
                featureSet.savedLoginState,
            ) { session, switchState, saved -> Triple(session, switchState, saved.recoverableAccountCount()) }
                .collect { (session, switchState, accountCount) ->
                    val owner = session.account
                    val version = session.bcVersion
                    _portraitCandidates.value = if (session.isVerifiedOnline && session.currentRoom != null &&
                        owner != null && version != null && !switchState.isSwitching) {
                        session.roomCharacters.filter { it.appearance.isNotEmpty() }.map {
                            CharacterPortraitState(version, owner.memberNumber, session.relationships.accountGeneration,
                                session.roomPresenceEpoch, it)
                        }
                    } else emptyList()
                    _uiState.value = _uiState.value.copy(savedAccountCount = accountCount)
                    syncScreenWithSessionSnapshot(session, switchState)
                    handleActiveAccountChanged(session.account?.accountName)
                }
        }
    }

    fun detachSession() {
        if (sessionFeatureSet == null && !_uiState.value.sessionReady) return
        clearAttachedSession()
        _uiState.value = _uiState.value.copy(sessionReady = false)
    }

    fun awaitSessionBinding() {
        _uiState.value = _uiState.value.copy(sessionReady = false)
    }

    fun selectTab(tab: MainTab) {
        if (tab == MainTab.Rooms && currentRoomConversationId != null) {
            navigate(AppScreen.Main(tab))
        } else {
            selectMainPage(when (tab) {
                MainTab.Rooms -> 0
                MainTab.Conversations -> 1
                MainTab.Profile -> 2
            })
        }
    }

    /** Browsing changes the screen only; room membership is owned by the session. */
    fun openRooms() = selectTab(MainTab.Rooms)

    fun openConversations() = selectTab(MainTab.Conversations)

    fun openProfile() = selectTab(MainTab.Profile)

    fun openContacts() = navigate(AppScreen.Contacts)

    fun selectMainPage(page: Int) {
        val next = mainPageScreen(page, currentRoomConversationId)
        if (_uiState.value.screen != next) {
            replace(next)
        }
    }

    fun onMainPageSettled(page: Int) {
        if (page != 0 && uiState.value.screen == mainPageScreen(page, currentRoomConversationId)) {
            sessionFeatureSet?.roomDetailsViewModel?.closeInteract()
        }
    }

    /** Return to the current room, or discover rooms when there is no active room. */
    fun openHome() {
        replace(homeScreen())
    }

    fun openConversation(conversationId: BcConversationId) {
        if (conversationId == currentRoomConversationId) {
            openHome()
        } else {
            navigate(AppScreen.Chat(conversationId))
        }
    }

    fun openNotificationHome() {
        if (!uiState.value.sessionReady) {
            pendingNotificationHomeOpen = true
            return
        }
        pendingNotificationHomeOpen = false
        if (uiState.value.screen == AppScreen.Login) return
        // A summary notification opens the conversation switcher, preserving a way
        // back to the active room and any composer draft already in memory.
        openConversations()
    }

    fun openRoomDetails() {
        sessionFeatureSet?.roomDetailsViewModel?.closeInteract()
        navigate(AppScreen.RoomDetails)
    }

    fun openCharacterBio(memberNumber: Int) {
        navigate(AppScreen.CharacterBio(memberNumber))
    }

    fun openCharacterChat(memberNumber: Int) {
        navigate(AppScreen.Chat(BcConversationId("message:$memberNumber")))
    }

    fun openLogin(accountName: String?) {
        val features = sessionFeatureSet ?: return
        if (features.accountSwitchState.value.isSwitching || features.loginViewModel.uiState.value.isBusy) return
        val account = features.loginViewModel.uiState.value.savedAccounts
            .firstOrNull { it.accountName == accountName }
        if (account != null) features.loginViewModel.selectSavedAccount(account)
        else features.loginViewModel.selectNewAccount()
        navigate(AppScreen.Login)
    }

    fun openCharacterItemPicker(memberNumber: Int, group: String, kind: BcAppearanceGroupKind) {
        val features = sessionFeatureSet ?: return
        val snapshot = features.connectionStatus.snapshot.value
        val room = snapshot.currentRoom ?: return
        if (!snapshot.isVerifiedOnline || features.accountSwitchState.value.isSwitching) return
        when (kind) {
            BcAppearanceGroupKind.Item -> features.roomDetailsViewModel.openItemPicker(memberNumber)
            BcAppearanceGroupKind.Clothing -> features.roomDetailsViewModel.openClothingPicker(memberNumber)
            BcAppearanceGroupKind.Appearance -> return
        }
        features.roomDetailsViewModel.selectInteractionGroup(group)
        features.roomDetailsViewModel.selectItemPickerOperation("Modify")
        openConversation(bcRoomConversationId(room.name.value))
    }

    fun interactWithCharacter(memberNumber: Int) {
        val snapshot = sessionFeatureSet?.connectionStatus?.snapshot?.value ?: return
        if (!snapshot.isVerifiedOnline || snapshot.currentRoom == null ||
            snapshot.roomCharacters.none { it.memberNumber == memberNumber }
        ) return
        roomDetailsViewModel.openActionPicker(memberNumber)
        openHome()
    }

    fun refreshLocalizedResources() {
        sessionFeatureSet?.refreshLocalizedResources()
    }

    fun chatViewModel(conversationId: BcConversationId): ChatViewModel =
        requireSessionFeatureSet().chatViewModel(conversationId)

    fun retainChatViewModel(
        conversationId: BcConversationId,
        instance: ChatViewModel,
    ): () -> Unit = requireSessionFeatureSet().retainChatViewModel(conversationId, instance)

    fun retainCharacterProfileViewModel(
        memberNumber: Int,
        instance: CharacterProfileViewModel,
    ): () -> Unit = requireSessionFeatureSet().retainCharacterProfileViewModel(memberNumber, instance)

    fun onBack() {
        val current = uiState.value.screen
        if (current == AppScreen.Login && sessionFeatureSet?.loginViewModel?.uiState?.value?.isBusy == true) return
        if (current.canHandleRoomPickerBack(currentRoomConversationId) &&
            sessionFeatureSet?.roomDetailsViewModel?.handlePickerBack() == true
        ) return
        if (current == AppScreen.Main(MainTab.Conversations) || current == AppScreen.Main(MainTab.Profile)) {
            openHome()
            return
        }
        navigation.back(homeScreen())
    }

    internal fun navigate(next: AppScreen) {
        if (next.mainPage(currentRoomConversationId) != null) {
            replace(next)
            return
        }
        if (uiState.value.screen == currentRoomConversationId?.let(AppScreen::Chat) && next != uiState.value.screen) {
            sessionFeatureSet?.roomDetailsViewModel?.closeInteract()
        }
        navigation.navigate(next)
    }

    internal fun syncScreenWithSessionSnapshot(
        session: BcSessionSnapshot,
        switchState: BcAccountSwitchState,
    ) {
        val previous = lastObservedSessionSnapshot
        lastObservedSessionSnapshot = session
        val oldRoom = previous?.currentRoom
        val newRoom = session.currentRoom
        if (previous != null && oldRoom != null && newRoom != null && oldRoom.name != newRoom.name &&
            previous.account?.memberNumber == session.account?.memberNumber &&
            previous.relationships.accountGeneration == session.relationships.accountGeneration &&
            previous.selfRuleLogSessionId == session.selfRuleLogSessionId &&
            previous.roomPresenceEpoch == session.roomPresenceEpoch
        ) {
            val oldId = bcRoomConversationId(oldRoom.name.value)
            val newId = bcRoomConversationId(newRoom.name.value)
            sessionFeatureSet?.renameRoomConversation(oldId, newId)
            navigation.renameRoomConversation(oldId, newId)
        }
        val current = uiState.value.screen
        val currentUiState = uiState.value
        val shouldForceLogin = session.shouldForceLogin(currentUiState.savedAccountCount)
        val availability = session.toAvailability(switchState, currentUiState.savedAccountCount)
        val nextUiState = currentUiState.copy(
            sessionAvailability = availability,
            sessionStatusText = session.uiStatusText(switchState, availability),
            activeAccountName = session.account?.accountName,
            switchingToAccountName = switchState.switchingToAccountName?.takeIf { switchState.isSwitching },
            switchErrorMessage = switchState.lastErrorMessage,
            currentRoomConversationId = newRoom?.name?.value?.let(::bcRoomConversationId),
            accountGeneration = session.relationships.accountGeneration,
            roomPresenceEpoch = session.roomPresenceEpoch,
        )
        if (nextUiState != currentUiState) {
            _uiState.value = nextUiState
        }
        if (oldRoom != null && newRoom == null && session.isVerifiedOnline) {
            navigation.removeDepartedRoomFromHistory(bcRoomConversationId(oldRoom.name.value))
        }
        when {
            shouldForceLogin &&
                current.requiresAuthenticatedSession() -> {
                replace(AppScreen.Login)
            }
            current is AppScreen.Login &&
                !navigation.hasBackStack &&
                sessionFeatureSet?.loginViewModel?.uiState?.value?.isBusy != true &&
                session.isVerifiedOnline -> {
                openHome()
            }
            current == AppScreen.Main(MainTab.Rooms) &&
                oldRoom == null && newRoom != null && session.isVerifiedOnline -> {
                openHome()
            }
            previous == null && newRoom != null && session.isVerifiedOnline &&
                current == AppScreen.Chat(bcRoomConversationId(newRoom.name.value)) -> {
                // A room restored from an older tab-based stack becomes the root.
                openHome()
            }
            oldRoom != null && newRoom == null && session.isVerifiedOnline &&
                (current == AppScreen.Chat(bcRoomConversationId(oldRoom.name.value)) ||
                    current == AppScreen.RoomDetails) -> {
                openHome()
            }
        }
    }

    internal fun handleActiveAccountChanged(accountName: String?) {
        val activeAccountName = accountName
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return
        val previousActiveAccountName = lastObservedActiveAccountName
        if (previousActiveAccountName == null) {
            lastObservedActiveAccountName = activeAccountName
            return
        }
        if (previousActiveAccountName == activeAccountName) return

        lastObservedActiveAccountName = activeAccountName
        resetUiAfterActiveAccountSwitch()
    }

    private fun resetUiAfterActiveAccountSwitch() {
        sessionFeatureSet?.clearChatViewModels()
        if (uiState.value.screen.requiresAuthenticatedSession()) {
            openHome()
        }
    }

    private fun forceLoginAfterExplicitLogout() {
        sessionFeatureSet?.clearChatViewModels()
        replace(AppScreen.Login)
    }

    override fun onCleared() {
        clearAttachedSession()
        super.onCleared()
    }

    private fun replace(next: AppScreen) {
        navigation.replace(next)
    }

    private fun homeScreen(): AppScreen =
        currentRoomConversationId?.let { AppScreen.Chat(it) } ?: AppScreen.Main(MainTab.Rooms)

    private fun clearAttachedSession() {
        _portraitCandidates.value = emptyList()
        sessionUiJob?.cancel()
        sessionUiJob = null
        sessionFeatureSet?.close()
        sessionFeatureSet = null
        lastObservedSessionSnapshot = null
    }

    private fun requireSessionFeatureSet(): SessionFeatureSet =
        requireNotNull(sessionFeatureSet) {
            "Session features are unavailable until the session service is attached."
        }

    private suspend fun readLoginStateOrFallback(
        preferences: BcSessionPreferencesStore,
    ): BcSavedLoginState = try {
        preferences.readLoginState()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        unavailableSavedLoginState(throwable)
    }

    class Factory(
        private val appComposition: AppComposition,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            if (modelClass.isAssignableFrom(MobileBcAppViewModel::class.java)) {
                return appComposition.createMobileBcAppViewModel(
                    savedStateHandle = extras.createSavedStateHandle(),
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
        }
    }

}

internal fun unavailableSavedLoginState(throwable: Throwable): BcSavedLoginState =
    BcSavedLoginState(
        accounts = emptyList(),
        selectedAccountName = "",
        saveCredentialsByDefault = true,
        bcVersion = "",
        bcVersionConfirmedByUser = false,
        credentialErrorMessage = throwable.persistenceMessage("load saved accounts"),
    )


private fun Throwable.persistenceMessage(action: String): String =
    message?.takeIf { it.isNotBlank() } ?: "Could not $action (${this::class.java.simpleName})."

private fun BcSessionSnapshot.shouldForceLogin(savedAccountCount: Int): Boolean =
    savedAccountCount <= 0 && account == null

private fun BcSessionSnapshot.toAvailability(
    switchState: BcAccountSwitchState,
    savedAccountCount: Int,
): SessionAvailability = when {
    switchState.isSwitching -> SessionAvailability.SwitchingAccount
    savedAccountCount <= 0 && account == null -> SessionAvailability.NoAccount
    state is BcSessionState.Reconnecting ->
        SessionAvailability.Reconnecting
    state is BcSessionState.Error || state is BcSessionState.Blocked -> SessionAvailability.Failed
    state is BcSessionState.Stopped -> SessionAvailability.Stopped
    connectionHealth == BcConnectionHealth.Stale -> SessionAvailability.Reconnecting
    isVerifiedOnline -> SessionAvailability.Online
    else -> SessionAvailability.Starting
}

private fun BcSessionSnapshot.uiStatusText(
    switchState: BcAccountSwitchState,
    availability: SessionAvailability,
): String = when {
    switchState.isSwitching && !switchState.switchingToAccountName.isNullOrBlank() ->
        "Switching to ${switchState.switchingToAccountName}..."
    switchState.isSwitching -> "Switching account..."
    !switchState.lastErrorMessage.isNullOrBlank() -> switchState.lastErrorMessage.orEmpty()
    availability == SessionAvailability.Reconnecting && connectionHealth == BcConnectionHealth.Stale ->
        connectionHealth.label()
    else -> statusDescription()
}

private fun AppScreen.requiresAuthenticatedSession(): Boolean = when (this) {
    AppScreen.Login -> false

    is AppScreen.Main,
    is AppScreen.Chat,
    AppScreen.RoomDetails,
    AppScreen.Contacts,
    is AppScreen.CharacterBio,
    -> true
}

internal fun AppScreen.canHandleRoomPickerBack(currentRoomConversationId: BcConversationId?): Boolean =
    this == AppScreen.RoomDetails ||
        (currentRoomConversationId != null && this == AppScreen.Chat(currentRoomConversationId))
