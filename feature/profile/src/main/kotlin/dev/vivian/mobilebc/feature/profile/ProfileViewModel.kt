package dev.vivian.mobilebc.feature.profile

import androidx.annotation.StringRes
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.normalizedBcVersionOrNull
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginState
import dev.vivian.mobilebc.domain.session.auth.BcSavedLoginAccount
import dev.vivian.mobilebc.domain.session.auth.BcSessionAuth
import dev.vivian.mobilebc.domain.session.connection.ConnectionStatusReader
import dev.vivian.mobilebc.domain.session.connection.SessionRecoverySettings
import dev.vivian.mobilebc.domain.session.core.BcLovershipStage
import dev.vivian.mobilebc.domain.session.core.BcOwnershipStage
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ProfileSavedAccountStatus {
    Current,
    Switching,
    CredentialsRequired,
    VersionRequired,
    Saved,
}

data class ProfileSavedAccountUiItem(
    val accountName: String,
    val isCurrent: Boolean,
    val isSwitchTarget: Boolean,
    val canSwitch: Boolean,
    val status: ProfileSavedAccountStatus,
)

data class ProfileNotificationSettingsUiState(
    val routing: BcNotificationSettings = BcNotificationSettings(),
    val suppressRoomMessagesWhenForeground: Boolean = BcNotificationSettings().suppressRoomMessagesWhenForeground,
    val suppressDirectMessagesWhenForeground: Boolean = BcNotificationSettings().suppressDirectMessagesWhenForeground,
    val suppressSystemAlertsWhenForeground: Boolean = BcNotificationSettings().suppressSystemAlertsWhenForeground,
)

data class ProfileRelationshipMemberUiItem(
    val memberNumber: Int,
    val displayName: String,
    val detail: String,
)

data class ProfileRelationshipsUiState(
    val isLoaded: Boolean = false,
    val money: Int? = null,
    val dominantReputation: Int? = null,
    val pendingFriendRequestCount: Int = 0,
    val owner: ProfileRelationshipMemberUiItem? = null,
    val lovers: List<ProfileRelationshipMemberUiItem> = emptyList(),
    val submissives: List<ProfileRelationshipMemberUiItem> = emptyList(),
    val friends: List<ProfileRelationshipMemberUiItem> = emptyList(),
    val ignored: List<ProfileRelationshipMemberUiItem> = emptyList(),
    val whitelist: List<ProfileRelationshipMemberUiItem> = emptyList(),
    val blacklist: List<ProfileRelationshipMemberUiItem> = emptyList(),
)

data class ProfileUiState(
    val memberNumber: Int? = null,
    val accountTitle: String = "No account",
    val accountSubtitle: String = "Login to start a lightweight BC session",
    @param:StringRes val statusRes: Int = R.string.profile_session_offline,
    val autoJoinRoomAfterReconnect: Boolean = false,
    val autoJoinSaveFailed: Boolean = false,
    val systemBcVersion: String = "",
    val activeAccountName: String? = null,
    val isSwitchingAccount: Boolean = false,
    val isLoggingOut: Boolean = false,
    val switchingToAccountName: String? = null,
    val savedAccounts: List<ProfileSavedAccountUiItem> = emptyList(),
    val accountSwitchMessage: String? = null,
    val accountSwitchMessageIsError: Boolean = false,
    val notificationSettings: ProfileNotificationSettingsUiState = ProfileNotificationSettingsUiState(),
    val notificationDiagnostics: BcNotificationDiagnostics = BcNotificationDiagnostics(),
    val relationships: ProfileRelationshipsUiState = ProfileRelationshipsUiState(),
    val deobfuscationEnabled: Boolean = false,
    val packetLogs: List<PacketLogUiItem> = emptyList(),
    val packetLogCount: Int = 0,
)

data class PacketLogUiItem(
    val id: Long,
    val direction: String,
    val eventName: String,
    val time: String,
    val payloadPreview: String,
)

data class ProfilePacketLogEntry(
    val id: Long,
    val direction: String,
    val eventName: String,
    val payloadJson: String,
    val timestamp: Instant,
)

class ProfileViewModel(
    private val auth: BcSessionAuth,
    private val connectionStatus: ConnectionStatusReader,
    private val recoverySettings: SessionRecoverySettings,
    private val packetLogEntries: StateFlow<List<ProfilePacketLogEntry>>,
    private val onClearPacketLogs: () -> Unit,
    private val onExportPacketLogs: () -> String,
    private val accountSwitchState: StateFlow<BcAccountSwitchState>,
    private val scope: CoroutineScope,
    private val savedLoginState: StateFlow<BcSavedLoginState>,
    private val onLoggedOut: suspend () -> Unit,
    private val onAutoJoinRoomAfterReconnectChanged: suspend (Boolean) -> Unit,
    private val onSwitchAccount: (String) -> Unit,
    private val notificationSettings: StateFlow<BcNotificationSettings> = MutableStateFlow(BcNotificationSettings()),
    private val onNotificationSettingsChanged: (BcNotificationSettings) -> Unit = {},
    initialNotificationDiagnostics: BcNotificationDiagnostics = BcNotificationDiagnostics(),
    private val onNotificationDiagnosticsRefresh: () -> BcNotificationDiagnostics = { initialNotificationDiagnostics },
    private val deobfuscationEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    private val onDeobfuscationEnabledChanged: (Boolean) -> Unit = {},
) {
    private data class AccountFeedback(val owner: Int?, val generation: Long, val message: String) {
        fun belongsTo(session: BcSessionSnapshot): Boolean =
            owner == session.account?.memberNumber && generation == session.relationships.accountGeneration
    }

    private var localAccountFeedback: AccountFeedback? = null
    private var notificationDiagnostics: BcNotificationDiagnostics = initialNotificationDiagnostics
    private var packetLogCollectionJob: Job? = null
    private var logoutJob: Job? = null
    private val isLoggingOut: Boolean get() = logoutJob != null
    private var autoJoinPersistenceJob: Job? = null
    private var autoJoinSaveFailed = false

    private val _uiState = MutableStateFlow(buildUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        scope.launch { savedLoginState.collect { refresh() } }
        scope.launch { notificationSettings.collect { refresh() } }
        scope.launch { deobfuscationEnabled.collect { refresh() } }
        scope.launch { connectionStatus.snapshot.collect { refresh() } }
        scope.launch {
            accountSwitchState.collect { state ->
                if (state.isSwitching || !state.lastErrorMessage.isNullOrBlank()) {
                    localAccountFeedback = null
                }
                refresh()
            }
        }
    }

    fun setPacketLogCollectionEnabled(enabled: Boolean) {
        if (!enabled) {
            packetLogCollectionJob?.cancel()
            packetLogCollectionJob = null
            return
        }
        if (packetLogCollectionJob?.isActive == true) return
        packetLogCollectionJob = scope.launch {
            packetLogEntries.collect { refresh() }
        }
    }

    fun onAutoJoinRoomAfterReconnectChange(enabled: Boolean) {
        autoJoinPersistenceJob?.cancel()
        autoJoinSaveFailed = false
        recoverySettings.setAutoJoinRoomAfterReconnect(enabled)
        autoJoinPersistenceJob = scope.launch {
            try {
                onAutoJoinRoomAfterReconnectChanged(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                currentCoroutineContext().ensureActive()
                autoJoinSaveFailed = true
                refresh()
            }
        }
        refresh()
    }

    fun onSuppressRoomMessagesWhenForegroundChange(enabled: Boolean) {
        updateNotificationSettings(notificationSettings.value.copy(suppressRoomMessagesWhenForeground = enabled))
    }

    fun onSuppressDirectMessagesWhenForegroundChange(enabled: Boolean) {
        updateNotificationSettings(notificationSettings.value.copy(suppressDirectMessagesWhenForeground = enabled))
    }

    fun onSuppressSystemAlertsWhenForegroundChange(enabled: Boolean) {
        updateNotificationSettings(notificationSettings.value.copy(suppressSystemAlertsWhenForeground = enabled))
    }

    fun onDeobfuscationEnabledChange(enabled: Boolean) {
        onDeobfuscationEnabledChanged(enabled)
        refresh()
    }

    fun switchAccount(accountName: String) {
        if (isLoggingOut || accountSwitchState.value.isSwitching) return
        val normalizedAccountName = accountName.trim()
        if (normalizedAccountName.isBlank()) return
        val saved = savedLoginState.value
        val session = connectionStatus.snapshot.value
        val currentAccountName = session.account?.accountName
            ?: saved.selectedAccountName.nonBlankOrNull()
        if (normalizedAccountName == currentAccountName) {
            setAccountFeedback(session, "$normalizedAccountName is already the active account.")
            refresh()
            return
        }
        val target = saved.accounts.normalized().firstOrNull { it.accountName == normalizedAccountName }
        if (target == null) {
            setAccountFeedback(session, "Saved credentials were not found for $normalizedAccountName.")
            refresh()
            return
        }
        if (target.password.isBlank()) {
            setAccountFeedback(session,
                "Saved credentials are unavailable for $normalizedAccountName. Log in again to save them.")
            refresh()
            return
        }
        if (saved.confirmedVersion().normalizedBcVersionOrNull() == null) {
            setAccountFeedback(session, "Confirm the system BC version before switching accounts.")
            refresh()
            return
        }
        localAccountFeedback = null
        refresh()
        onSwitchAccount(target.accountName)
    }

    fun logout() {
        if (isLoggingOut || accountSwitchState.value.isSwitching) return
        val started = connectionStatus.snapshot.value
        localAccountFeedback = null
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                currentCoroutineContext().ensureActive()
                if (!connectionStatus.snapshot.value.sameAccountAs(started)) return@launch
                auth.logout()
                currentCoroutineContext().ensureActive()
                val current = connectionStatus.snapshot.value
                if (current.account != null && !current.sameAccountAs(started)) return@launch
                onLoggedOut()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                currentCoroutineContext().ensureActive()
                if (connectionStatus.snapshot.value.sameAccountAs(started)) {
                    setAccountFeedback(started, throwable.message?.takeIf(String::isNotBlank)
                        ?: "Could not log out. Try again.")
                }
            }
        }
        logoutJob = job
        job.invokeOnCompletion {
            if (logoutJob === job) {
                logoutJob = null
                refresh()
            }
        }
        refresh()
        job.start()
    }

    private fun setAccountFeedback(session: BcSessionSnapshot, message: String) {
        localAccountFeedback = AccountFeedback(session.account?.memberNumber, session.relationships.accountGeneration, message)
    }

    private fun BcSessionSnapshot.sameAccountAs(other: BcSessionSnapshot): Boolean =
        account?.memberNumber == other.account?.memberNumber &&
            relationships.accountGeneration == other.relationships.accountGeneration

    fun clearPacketLogs() {
        onClearPacketLogs()
    }

    fun exportPacketLogs(): String =
        onExportPacketLogs()

    fun refreshNotificationDiagnostics() {
        notificationDiagnostics = onNotificationDiagnosticsRefresh()
        refresh()
    }

    fun updateNotificationSettings(settings: BcNotificationSettings) {
        onNotificationSettingsChanged(settings)
        refresh()
    }

    private fun refresh() {
        _uiState.update { buildUiState() }
    }

    private fun buildUiState(): ProfileUiState {
        val session = connectionStatus.snapshot.value
        val logs = packetLogEntries.value
        val saved = savedLoginState.value
        val systemBcVersion = saved.confirmedVersion()
        val account = session.account
        val switchState = accountSwitchState.value
        val activeAccountName = account?.accountName ?: saved.selectedAccountName.nonBlankOrNull()
        val activeDisplayName = account?.displayName
        val activeMemberNumber = account?.memberNumber
        val localAccountErrorMessage = localAccountFeedback?.takeIf { it.belongsTo(session) }?.message
        val switchMessage = switchState.messageOrNull() ?: localAccountErrorMessage
        val switchMessageIsError = !switchState.lastErrorMessage.isNullOrBlank() || localAccountErrorMessage != null
        val hasConfirmedSystemVersion = systemBcVersion
            .takeIf(String::isNotBlank)
            ?.normalizedBcVersionOrNull() != null
        return ProfileUiState(
            memberNumber = account?.memberNumber,
            accountTitle = activeDisplayName?.takeIf(String::isNotBlank) ?: activeAccountName ?: "No account",
            accountSubtitle = activeAccountName?.let { accountName ->
                activeMemberNumber?.let { "$accountName · #$it" } ?: accountName
            } ?: "Login to start a lightweight BC session",
            statusRes = when (session.state) {
                is BcSessionState.Error -> R.string.profile_session_error
                is BcSessionState.Blocked -> R.string.profile_session_blocked
                BcSessionState.Stopped -> R.string.profile_session_stopped
                else -> when (session.connectionHealth) {
                    BcConnectionHealth.Offline -> R.string.profile_session_offline
                    BcConnectionHealth.ConnectedUnverified -> R.string.profile_session_verifying
                    BcConnectionHealth.VerifiedOnline -> R.string.profile_session_online
                    BcConnectionHealth.Stale -> R.string.profile_session_stale
                }
            },
            autoJoinRoomAfterReconnect = session.autoJoinRoomAfterReconnect,
            autoJoinSaveFailed = autoJoinSaveFailed,
            systemBcVersion = systemBcVersion.normalizedBcVersionOrNull().orEmpty(),
            deobfuscationEnabled = deobfuscationEnabled.value,
            activeAccountName = activeAccountName,
            isSwitchingAccount = switchState.isSwitching,
            isLoggingOut = isLoggingOut,
            switchingToAccountName = switchState.switchingToAccountName,
            savedAccounts = saved.accounts.normalized().map { accountOption ->
                val hasUsableCredentials = accountOption.password.isNotBlank()
                val isCurrent = accountOption.accountName == activeAccountName
                val isSwitchTarget = switchState.isSwitching && accountOption.accountName == switchState.switchingToAccountName
                ProfileSavedAccountUiItem(
                    accountName = accountOption.accountName,
                    isCurrent = isCurrent,
                    isSwitchTarget = isSwitchTarget,
                    canSwitch = hasConfirmedSystemVersion && hasUsableCredentials &&
                        !isCurrent && !switchState.isSwitching && !isLoggingOut,
                    status = when {
                        isCurrent -> ProfileSavedAccountStatus.Current
                        isSwitchTarget -> ProfileSavedAccountStatus.Switching
                        !hasUsableCredentials -> ProfileSavedAccountStatus.CredentialsRequired
                        !hasConfirmedSystemVersion -> ProfileSavedAccountStatus.VersionRequired
                        else -> ProfileSavedAccountStatus.Saved
                    },
                )
            },
            accountSwitchMessage = switchMessage,
            accountSwitchMessageIsError = switchMessageIsError,
            notificationSettings = notificationSettings.value.toUiState(),
            notificationDiagnostics = notificationDiagnostics,
            relationships = session.toProfileRelationshipsUiState(),
            packetLogs = logs.takeLast(MAX_VISIBLE_PACKET_LOGS).asReversed().map { it.toUiItem() },
            packetLogCount = logs.size,
        )
    }

    private fun BcAccountSwitchState.messageOrNull(): String? = when {
        isSwitching && !switchingToAccountName.isNullOrBlank() -> "Switching to $switchingToAccountName..."
        isSwitching -> "Switching account..."
        !lastErrorMessage.isNullOrBlank() -> lastErrorMessage
        else -> null
    }

    private fun BcNotificationSettings.toUiState(): ProfileNotificationSettingsUiState =
        ProfileNotificationSettingsUiState(
            routing = this,
            suppressRoomMessagesWhenForeground = suppressRoomMessagesWhenForeground,
            suppressDirectMessagesWhenForeground = suppressDirectMessagesWhenForeground,
            suppressSystemAlertsWhenForeground = suppressSystemAlertsWhenForeground,
        )

    private fun BcSessionSnapshot.toProfileRelationshipsUiState(): ProfileRelationshipsUiState {
        val relationshipSnapshot = relationships
        if (relationshipSnapshot.loadState != BcRelationshipLoadState.Loaded) {
            return ProfileRelationshipsUiState()
        }

        val relations = relationshipSnapshot.self
        val loverships = relations.loverships.distinctBy { it.memberNumber }
        val roomNames = buildMap {
            roomMembers.forEach { member -> put(member.memberNumber, member.name) }
            roomCharacters.forEach { character ->
                put(
                    character.memberNumber,
                    character.displayName.takeIf(String::isNotBlank) ?: character.name,
                )
            }
        }
        val contractNames = buildMap {
            relations.ownership?.let { put(it.memberNumber, it.name) }
            loverships.forEach { put(it.memberNumber, it.name) }
        }

        fun displayName(memberNumber: Int): String =
            contractNames[memberNumber].nonBlankOrNull()
                ?: relations.friendNames[memberNumber].nonBlankOrNull()
                ?: roomNames[memberNumber].nonBlankOrNull()
                ?: "#$memberNumber"

        fun members(memberNumbers: Set<Int>, detail: String): List<ProfileRelationshipMemberUiItem> =
            memberNumbers
                .map { memberNumber ->
                    ProfileRelationshipMemberUiItem(
                        memberNumber = memberNumber,
                        displayName = displayName(memberNumber),
                        detail = detail,
                    )
                }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

        return ProfileRelationshipsUiState(
            isLoaded = true,
            money = relations.money,
            dominantReputation = relations.reputation.entries
                .firstOrNull { it.key.equals(DOMINANT_REPUTATION, ignoreCase = true) }
                ?.value,
            pendingFriendRequestCount = relationshipSnapshot.friendRequests.values
                .count { it.expiresAtMillis > System.currentTimeMillis() },
            owner = relations.ownership?.let { ownership ->
                ProfileRelationshipMemberUiItem(
                    memberNumber = ownership.memberNumber,
                    displayName = displayName(ownership.memberNumber),
                    detail = ownership.stageKind.profileLabel(),
                )
            },
            lovers = loverships.map { lovership ->
                ProfileRelationshipMemberUiItem(
                    memberNumber = lovership.memberNumber,
                    displayName = displayName(lovership.memberNumber),
                    detail = lovership.stageKind.profileLabel(),
                )
            },
            submissives = members(relations.submissives, "Submissive"),
            friends = members(relations.friends, "Friend"),
            ignored = members(relations.ignored, "Ignored"),
            whitelist = members(relations.whitelist, "Whitelisted"),
            blacklist = members(relations.blacklist, "Blacklisted"),
        )
    }

    private fun ProfilePacketLogEntry.toUiItem(): PacketLogUiItem =
        PacketLogUiItem(
            id = id,
            direction = direction,
            eventName = eventName,
            time = TimeFormatter.format(timestamp.atZone(ZoneId.systemDefault())),
            payloadPreview = payloadJson.singleLinePreview(),
        )

    private fun String.singleLinePreview(): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= PAYLOAD_PREVIEW_LIMIT) compact else compact.take(PAYLOAD_PREVIEW_LIMIT) + "..."
    }

    private companion object {
        private const val MAX_VISIBLE_PACKET_LOGS = 80
        private const val PAYLOAD_PREVIEW_LIMIT = 220
        private const val DOMINANT_REPUTATION = "Dominant"
        private val TimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        fun BcSavedLoginState.confirmedVersion(): String = bcVersion.takeIf { bcVersionConfirmedByUser }.orEmpty()

        fun List<BcSavedLoginAccount>.normalized(): List<BcSavedLoginAccount> =
            distinctBy { it.accountName.trim() }
                .map { account ->
                    account.copy(accountName = account.accountName.trim())
                }
                .filter { it.accountName.isNotBlank() }
    }
}

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf(String::isNotBlank)

private fun BcOwnershipStage.profileLabel(): String = when (this) {
    BcOwnershipStage.Trial -> "Trial ownership"
    BcOwnershipStage.FullyOwned -> "Full ownership"
    BcOwnershipStage.Unknown -> "Ownership"
}

private fun BcLovershipStage.profileLabel(): String = when (this) {
    BcLovershipStage.Dating -> "Dating"
    BcLovershipStage.Engaged -> "Engaged"
    BcLovershipStage.Married -> "Married"
    BcLovershipStage.Unknown -> "Lovership"
}
