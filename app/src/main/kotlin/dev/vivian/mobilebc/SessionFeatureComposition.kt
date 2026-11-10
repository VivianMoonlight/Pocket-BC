package dev.vivian.mobilebc

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.core.socket.PacketLogDirection
import dev.vivian.mobilebc.core.socket.PacketLogEntry
import dev.vivian.mobilebc.core.socket.PacketLogStore
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcInteractionCatalogRepository
import dev.vivian.mobilebc.domain.bctext.BcCachedRoomBackgroundCatalogRepository
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSessionPreferencesStore
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.feature.auth.LoginViewModel
import dev.vivian.mobilebc.feature.chat.ChatViewModel
import dev.vivian.mobilebc.feature.chat.ChatInputState
import dev.vivian.mobilebc.feature.characterprofile.CharacterProfileViewModel
import dev.vivian.mobilebc.feature.conversations.ConversationsViewModel
import dev.vivian.mobilebc.feature.profile.ProfilePacketLogEntry
import dev.vivian.mobilebc.feature.profile.ProfileViewModel
import dev.vivian.mobilebc.feature.roomdetails.RoomDetailsViewModel
import dev.vivian.mobilebc.feature.rooms.RoomsViewModel
import dev.vivian.mobilebc.domain.session.runtime.SessionAccess
import dev.vivian.mobilebc.resource.BcWebResourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.retryWhen

/**
 * Feature graph for one active session attachment.
 *
 * This is the sole app-layer owner of feature instances, feature-local adapters, and their child
 * coroutine scope. Closing it releases all chat instances and cancels feature collection.
 */
internal class SessionFeatureSet(
    private val session: SessionAccess,
    private val packetLog: PacketLogStore,
    private val preferences: BcSessionPreferencesStore,
    private val accountSwitchStateFlow: StateFlow<BcAccountSwitchState>,
    private val switchAccount: (String) -> Unit,
    private val bcWebResources: BcWebResourceRepository,
    private val interactionCatalogRepository: BcInteractionCatalogRepository,
    private val ruleCatalogRepository: BcRuleCatalogRepository,
    initialSavedLoginState: BcSavedLoginState,
    notificationSettings: StateFlow<BcNotificationSettings>,
    onNotificationSettingsChanged: (BcNotificationSettings) -> Unit,
    readNotificationDiagnostics: () -> BcNotificationDiagnostics,
    deobfuscationEnabled: StateFlow<Boolean>,
    onDeobfuscationEnabledChanged: (Boolean) -> Unit,
    parentScope: CoroutineScope,
    private val onOpenConversation: (BcConversationId) -> Unit,
    private val onLoggedOut: () -> Unit,
    private val onLoggedIn: () -> Unit,
    private val roomBackgroundCatalogRepository: BcCachedRoomBackgroundCatalogRepository =
        BcCachedRoomBackgroundCatalogRepository(resourceReader = { _, _ -> null }),
) {
    private val scope = CoroutineScope(
        SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.Main.immediate,
    )
    private val chatViewModels = mutableMapOf<BcConversationId, ChatViewModel>()
    private val screenModelOwners = ScreenModelOwners(scope)
    private val chatInputStates = mutableMapOf<BcConversationId, MutableStateFlow<ChatInputState>>()
    private val characterProfileViewModels = mutableMapOf<Int, CharacterProfileViewModel>()
    val savedLoginState: StateFlow<BcSavedLoginState> = preferences.observeLoginState()
        .retryWhen { failure, _ ->
            if (failure is CancellationException || failure !is Exception) throw failure
            emit(unavailableSavedLoginState(failure))
            delay(1_000)
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, initialSavedLoginState)
    private val currentBcVersion = combine(session.connectionStatus.snapshot, savedLoginState) { session, saved ->
        saved.resourceVersion(session.bcVersion)
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = initialSavedLoginState.resourceVersion(session.connectionStatus.snapshot.value.bcVersion),
    )
    private val profilePacketLogEntries = packetLog.entries
        .map { entries -> entries.map(PacketLogEntry::toProfilePacketLogEntry) }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = packetLog.entries.value.map(PacketLogEntry::toProfilePacketLogEntry),
        )

    val savedAccountCount: Int
        get() = savedLoginState.value.recoverableAccountCount()

    val connectionStatus: ConnectionStatusReader
        get() = session.connectionStatus

    val accountSwitchState: StateFlow<BcAccountSwitchState>
        get() = accountSwitchStateFlow

    val loginViewModel = LoginViewModel(
        auth = session.auth,
        connectionStatus = session.connectionStatus,
        connector = session.connector,
        scope = scope,
        savedLoginState = savedLoginState,
        prepareResources = { version ->
            bcWebResources.ensureSynced(version)
            kotlinx.coroutines.withContext(Dispatchers.IO) { roomBackgroundCatalogRepository.refresh(version) }
        },
        onLoginSucceeded = ::saveSuccessfulLogin,
        onDeleteSavedAccount = preferences::deleteSavedAccount,
    )

    val conversationsViewModel = ConversationsViewModel(
        timeline = session.timeline,
        connectionStatus = session.connectionStatus,
        relationships = session.relationships,
        scope = scope,
    )

    val roomsViewModel = RoomsViewModel(
        rooms = session.rooms,
        connectionStatus = session.connectionStatus,
        scope = scope,
        onJoinedRoom = { roomName ->
            onOpenConversation(bcRoomConversationId(roomName))
        },
        backgroundNamesProvider = { space ->
            roomBackgroundCatalogRepository.currentSnapshot(currentBcVersion.value)?.namesForSpace(space).orEmpty()
        },
    )

    val profileViewModel = ProfileViewModel(
        auth = session.auth,
        connectionStatus = session.connectionStatus,
        recoverySettings = session.recoverySettings,
        packetLogEntries = profilePacketLogEntries,
        onClearPacketLogs = packetLog::clear,
        onExportPacketLogs = packetLog::exportText,
        accountSwitchState = accountSwitchStateFlow,
        scope = scope,
        savedLoginState = savedLoginState,
        onLoggedOut = onLoggedOut,
        onAutoJoinRoomAfterReconnectChanged = preferences::setAutoJoinRoomAfterReconnect,
        onSwitchAccount = switchAccount,
        notificationSettings = notificationSettings,
        onNotificationSettingsChanged = onNotificationSettingsChanged,
        initialNotificationDiagnostics = readNotificationDiagnostics(),
        onNotificationDiagnosticsRefresh = readNotificationDiagnostics,
        deobfuscationEnabled = deobfuscationEnabled,
        onDeobfuscationEnabledChanged = onDeobfuscationEnabledChanged,
    )

    val roomDetailsViewModel = RoomDetailsViewModel(
        rooms = session.rooms,
        backgroundNamesProvider = { space ->
            roomBackgroundCatalogRepository.currentSnapshot(currentBcVersion.value)?.namesForSpace(space).orEmpty()
        },
        connectionStatus = session.connectionStatus,
        characterInteractions = session.characterInteractions,
        interactionCatalogRepository = interactionCatalogRepository,
        ruleCatalogRepository = ruleCatalogRepository,
        currentVersion = currentBcVersion,
        syncResources = { version ->
            bcWebResources.ensureSynced(version)
            kotlinx.coroutines.withContext(Dispatchers.IO) { roomBackgroundCatalogRepository.refresh(version) }
            Unit
        },
        scope = scope,
    )

    init {
        scope.launch(Dispatchers.IO) {
            currentBcVersion.collect { version ->
                if (version.isNotBlank()) roomBackgroundCatalogRepository.refresh(version)
            }
        }
    }

    fun matches(
        candidateSession: SessionAccess,
        candidatePreferences: BcSessionPreferencesStore,
    ): Boolean =
        session === candidateSession && preferences === candidatePreferences

    fun chatViewModel(conversationId: BcConversationId): ChatViewModel =
        chatViewModels.getOrPut(conversationId) {
            ChatViewModel(
                conversationId = conversationId,
                chat = session.chat,
                timeline = session.timeline,
                connectionStatus = session.connectionStatus,
                scope = scope,
                inputState = chatInputStates.getOrPut(conversationId) { MutableStateFlow(ChatInputState()) },
            )
        }

    fun characterProfileViewModel(memberNumber: Int): CharacterProfileViewModel =
        characterProfileViewModels.getOrPut(memberNumber) {
            CharacterProfileViewModel(
                memberNumber = memberNumber,
                timeline = session.timeline,
                connectionStatus = session.connectionStatus,
                relationships = session.relationships,
                scope = scope,
            )
        }

    fun retainChatViewModel(
        conversationId: BcConversationId,
        instance: ChatViewModel,
    ): () -> Unit =
        screenModelOwners.retain(instance) {
            if (chatViewModels[conversationId] === instance) {
                chatViewModels.remove(conversationId)
                instance.close()
            }
        }

    fun retainCharacterProfileViewModel(
        memberNumber: Int,
        instance: CharacterProfileViewModel,
    ): () -> Unit =
        screenModelOwners.retain(instance) {
            if (characterProfileViewModels[memberNumber] === instance) {
                characterProfileViewModels.remove(memberNumber)
                instance.close()
            }
        }

    fun clearChatViewModels(clearInput: Boolean = true) {
        chatViewModels.values.forEach { it.close() }
        chatViewModels.clear()
        if (clearInput) chatInputStates.clear()
    }

    fun renameRoomConversation(oldId: BcConversationId, newId: BcConversationId) {
        chatInputStates.moveRoomInputState(oldId, newId)
    }

    fun refreshLocalizedResources() {
        scope.launch(Dispatchers.IO) {
            val version = currentBcVersion.value.takeIf(String::isNotBlank) ?: return@launch
            try {
                bcWebResources.sync(version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Refresh the catalogs from the last complete snapshot when downloading fails.
            }
            currentCoroutineContext().ensureActive()
            interactionCatalogRepository.refresh(version)
            ruleCatalogRepository.refresh(version)
            roomBackgroundCatalogRepository.refresh(version)
        }
    }

    fun close() {
        clearChatViewModels()
        characterProfileViewModels.values.forEach { it.close() }
        characterProfileViewModels.clear()
        scope.cancel()
    }

    private suspend fun saveSuccessfulLogin(
        accountName: String,
        password: String,
        serverConfig: BcServerConfig,
        saveCredentials: Boolean,
    ) {
        preferences.saveSuccessfulLogin(
            accountName = accountName,
            password = password,
            serverConfig = serverConfig,
            saveCredentials = saveCredentials,
        )
        onLoggedIn()
    }


}

internal fun MutableMap<BcConversationId, MutableStateFlow<ChatInputState>>.moveRoomInputState(
    oldId: BcConversationId,
    newId: BcConversationId,
) {
    if (oldId == newId) return
    val previousInput = this[oldId] ?: return
    val destination = this[newId]?.value
    if (destination == null || destination.text.isEmpty() && destination.pendingSend == null && destination.reply == null &&
        destination.roomWhisperTarget == null && destination.roomDrafts.values.none { it.text.isNotEmpty() || it.reply != null }) {
        previousInput.update { it.copy(reply = null) }
        this[newId] = previousInput
        remove(oldId)
    }
}

internal fun BcSavedLoginState.resourceVersion(sessionVersion: String?): String =
    if (sessionVersion.isNullOrBlank()) {
        confirmedBcVersionOrEmpty().normalizedBcVersionOrNull().orEmpty()
    } else {
        sessionVersion.normalizedBcVersionOrNull().orEmpty()
    }

internal fun BcSavedLoginState.recoverableAccountCount(): Int =
    if (confirmedBcVersionOrEmpty().normalizedBcVersionOrNull() == null) {
        0
    } else {
        accounts.count { account ->
            account.accountName.isNotBlank() &&
                account.password.isNotBlank()
        }
    }

private fun BcSavedLoginState.confirmedBcVersionOrEmpty(): String =
    bcVersion.takeIf { bcVersionConfirmedByUser }.orEmpty()

private fun PacketLogEntry.toProfilePacketLogEntry(): ProfilePacketLogEntry =
    ProfilePacketLogEntry(
        id = id,
        direction = when (direction) {
            PacketLogDirection.Outbound -> "SEND"
            PacketLogDirection.Inbound -> "RECV"
        },
        eventName = eventName,
        payloadJson = payloadJson,
        timestamp = timestamp,
    )
