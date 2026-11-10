package dev.vivian.mobilebc.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.util.Log
import dev.vivian.mobilebc.BcAppVisibilityTracker
import dev.vivian.mobilebc.MainActivity
import dev.vivian.mobilebc.R
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.normalizedBcVersion
import dev.vivian.mobilebc.core.socket.RazorGatewayImpl
import dev.vivian.mobilebc.core.socket.PacketLogDirection
import dev.vivian.mobilebc.core.socket.PacketLogStore
import dev.vivian.mobilebc.core.protocol.DeadLetterRecord
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.ProtocolError
import dev.vivian.mobilebc.data.storage.BcPersistenceManager
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcInboundActivityCommand
import dev.vivian.mobilebc.core.model.BcInboundActivityResult
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageAddress
import dev.vivian.mobilebc.core.model.BcMessageId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcNotificationAccountContext
import dev.vivian.mobilebc.core.model.BcNotificationPolicy
import dev.vivian.mobilebc.core.model.BcNotificationSettings
import dev.vivian.mobilebc.core.model.BcNotificationDiagnostics
import dev.vivian.mobilebc.core.model.BcSender
import dev.vivian.mobilebc.domain.bctext.BcActivityDictionaryTextParser
import dev.vivian.mobilebc.domain.bctext.BcAssetStringsTextParser
import dev.vivian.mobilebc.domain.bctext.BcCachedDictionaryValueResolver
import dev.vivian.mobilebc.domain.bctext.BcInterfaceTextParser
import dev.vivian.mobilebc.MobileBcApplication
import dev.vivian.mobilebc.i18n.AppLanguageManager
import dev.vivian.mobilebc.domain.bcrules.BcCachedRuleCatalogRepository
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogLoadResult
import dev.vivian.mobilebc.domain.messaging.BcInboundMessageProcessor
import dev.vivian.mobilebc.domain.messaging.BcInboundMessageContext
import dev.vivian.mobilebc.domain.messaging.BcInboundIgnored
import dev.vivian.mobilebc.domain.messaging.PlainBcChatTextResolver
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.domain.messaging.BcInboundProcessingResult
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.messaging.ActivityBackedBcChatTextResolver
import dev.vivian.mobilebc.domain.bcrules.BcEchoActivityCatalog
import dev.vivian.mobilebc.domain.messaging.toInboundActivityCommandOrNull
import dev.vivian.mobilebc.domain.session.auth.BcAccountSwitchState
import dev.vivian.mobilebc.domain.session.auth.BcSessionPreferencesStore
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.core.label
import dev.vivian.mobilebc.domain.session.runtime.AccountSessionManager
import dev.vivian.mobilebc.domain.session.runtime.SessionAccess
import dev.vivian.mobilebc.domain.sync.OutboxDrainCoordinator
import dev.vivian.mobilebc.domain.sync.SyncScheduler
import dev.vivian.mobilebc.platform.sync.AndroidWorkManagerSyncScheduler
import dev.vivian.mobilebc.platform.sync.OutboxDrainWorkerDependencies
import dev.vivian.mobilebc.resource.BcWebResourceSyncException
import dev.vivian.mobilebc.service.notification.BcNotificationController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import org.json.JSONObject

class BcSessionForegroundService : Service(), BcSessionServiceBinding {
    class LocalBinder internal constructor(
        val binding: BcSessionServiceBinding,
    ) : Binder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val inboundProcessingDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val binder by lazy { LocalBinder(this) }
    private val persistence: BcPersistenceManager by lazy { BcPersistenceManager.get(this) }
    private val inboundPersistenceRetrier
        get() = (application as MobileBcApplication).appComposition.inboundPersistenceRetrier
    private val relationshipRuleCatalogRepository by lazy {
        BcCachedRuleCatalogRepository(
            resourceReader = ::readBcTextResource,
            languageProvider = ::currentBcTextLanguage,
        )
    }
    private val dictionaryValueResolver by lazy {
        BcCachedDictionaryValueResolver(
            resourceReader = ::readBcTextResource,
            languageProvider = ::currentBcTextLanguage,
        )
    }
    private val syncScheduler: SyncScheduler by lazy {
        AndroidWorkManagerSyncScheduler(this)
    }
    private fun createInboundMessageProcessor(access: SessionAccess): BcInboundMessageProcessor =
        BcInboundMessageProcessor(
            persistenceStore = persistence.runtime.inbound,
            ownerMemberNumber = { access.connectionStatus.snapshot.value.account?.memberNumber },
            ownerDisplayName = { access.connectionStatus.snapshot.value.account?.displayName },
            roomMemberLookup = { access.connectionStatus.snapshot.value.roomMembers.associate { it.memberNumber to it.name } },
            currentRoomConversationId = {
                access.connectionStatus.snapshot.value.currentRoom
                    ?.let(messageCoordinator::roomConversationId)
            },
            isConversationVisible = access.timeline::isConversationVisible,
            ignoredMemberNumbers = {
                access.connectionStatus.snapshot.value.relationships.self.ignored
            },
            onFriendRequest = { request ->
                withContext(Dispatchers.Main.immediate) {
                    if (accountSessionManager.activeSession.value?.access !== access) return@withContext
                    val previous = access.connectionStatus.snapshot.value
                        .relationships.friendRequests[request.senderMemberNumber]
                    access.relationships.recordFriendRequest(
                        senderMemberNumber = request.senderMemberNumber,
                        receivedAtMillis = request.receivedAtMillis,
                    )
                    val recorded = access.connectionStatus.snapshot.value
                        .relationships.friendRequests[request.senderMemberNumber]
                    if (recorded != previous && recorded?.receivedAtMillis == request.receivedAtMillis) {
                        notifyFriendRequest(request.senderMemberNumber, request.receivedAtMillis)
                    }
                }
            },
            showDeobfuscatedOriginal = { textDisplaySettingsStore.deobfuscationEnabled.value },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityDictionaryTextParser(
                    resourceReader = ::readBcTextResource,
                    languageProvider = ::currentBcTextLanguage,
                    dictionaryValueResolver = dictionaryValueResolver,
                ),
                interfaceTextParser = BcInterfaceTextParser(
                    resourceReader = ::readBcTextResource,
                    languageProvider = ::currentBcTextLanguage,
                    dictionaryValueResolver = dictionaryValueResolver,
                ),
                assetStringsTextParser = BcAssetStringsTextParser(
                    resourceReader = ::readBcTextResource,
                    languageProvider = ::currentBcTextLanguage,
                    dictionaryValueResolver = dictionaryValueResolver,
                ),
                currentVersion = { access.connectionStatus.snapshot.value.bcVersion ?: error("BC version is unavailable.") },
                dictionaryValueResolver = dictionaryValueResolver,
                activityTemplateProvider = { activityName, targetGroup, isSelfTarget ->
                    BcEchoActivityCatalog.localizedChatTemplate(
                        activityName = activityName,
                        language = currentBcTextLanguage(),
                        targetGroup = targetGroup,
                        isSelfTarget = isSelfTarget,
                    )
                },
            ),
            deadLetterSink = persistence.shared.deadLetterSink,
        )
    private fun currentBcTextLanguage(): String =
        (application as MobileBcApplication).appLanguageManager.currentBcLanguageCode()

    private fun readBcTextResource(version: String, relativePath: String): String? =
        (application as MobileBcApplication).appComposition.readBcTextResource(version, relativePath)
    private val accountSessionManager: AccountSessionManager<ServiceManagedSession> by lazy {
        AccountSessionManager(
            createSession = {
                createServiceManagedSession(
                    persistence = persistence,
                    dispatchQueuedOutbound = ::dispatchQueuedOutbound,
                    onRoomTargetChanged = ::persistRoomTarget,
                    scope = scope,
                    relationshipItemCatalog = ::currentRelationshipItemCatalog,
                    textLanguageProvider = ::currentBcTextLanguage,
                    roomBackgroundNamesProvider = { version ->
                        (application as MobileBcApplication).appComposition.currentRoomBackgroundNames(version)
                    },
                )
            },
            prepareAccountSwitch = { accountName ->
                sessionPreferences.markDesiredOnline(accountName)
            },
        )
    }
    private val managedSession: ServiceManagedSession
        get() = accountSessionManager.requireActiveSession()
    private val activeAccess: SessionAccess
        get() = managedSession.access
    private val gateway: RazorGatewayImpl
        get() = managedSession.gateway
    private val outboxDrainCoordinator: OutboxDrainCoordinator
        get() = managedSession.outboxDrainCoordinator
    private val messageCoordinator: BcSessionMessageCoordinator
        get() = managedSession.messageCoordinator
    override val activeSession: StateFlow<SessionAccess?> by lazy {
        accountSessionManager.activeSession
            .map { session -> session?.access }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = accountSessionManager.activeSession.value?.access,
            )
    }
    private val _accountSwitchState = MutableStateFlow(BcAccountSwitchState())
    private var accountSwitchJob: Job? = null
    override val accountSwitchState: StateFlow<BcAccountSwitchState> = _accountSwitchState.asStateFlow()

    override fun switchAccount(accountName: String) {
        BcSessionForegroundService.switchAccount(this, accountName)
    }

    override fun currentPacketLog(): PacketLogStore = gateway.packetLog
    override val sessionPreferences: BcSessionPreferencesStore by lazy {
        persistence.shared.sessionPreferences
    }

    override val notificationSettings: StateFlow<BcNotificationSettings>
        get() = notificationSettingsStore.settings

    override fun saveNotificationSettings(settings: BcNotificationSettings) {
        notificationSettingsStore.selectAccount(currentNotificationAccountContext()?.ownerMemberNumber)
        notificationSettingsStore.save(settings)
    }

    override val deobfuscationEnabled: StateFlow<Boolean>
        get() = textDisplaySettingsStore.deobfuscationEnabled

    override fun saveDeobfuscationEnabled(enabled: Boolean) {
        textDisplaySettingsStore.saveDeobfuscationEnabled(enabled)
    }

    override fun readNotificationDiagnostics(): BcNotificationDiagnostics =
        notificationController.readDiagnostics()

    private lateinit var notificationController: BcNotificationController
    private lateinit var notificationSettingsStore: BcNotificationSettingsStore
    private lateinit var textDisplaySettingsStore: BcTextDisplaySettingsStore
    private lateinit var notificationCoordinator: BcSessionNotificationCoordinator
    private lateinit var connectivityManager: ConnectivityManager
    private var stateJob: Job? = null
    private var statePersistenceJob: Job? = null
    private var inboundFramesJob: Job? = null
    private var inboundSessionSequence: Long = 0L
    private var resourceSyncJob: Job? = null
    private var resourceSyncRequests: Channel<BcInboundResourceKey>? = null
    private val pendingResourceSyncKeys = mutableSetOf<BcInboundResourceKey>()
    private val resourceSyncRequestLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recoveryJob: Job? = null
    private var activeAccountJob: Job? = null
    private var lastOutboxDrainHealth: BcConnectionHealth? = null
    private var registeredOutboxDrainCoordinator: OutboxDrainCoordinator? = null
    private var relationshipCatalogFailedVersion: String? = null
    private var relationshipCatalogFailedAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        serviceRunning = true
        notificationSettingsStore = BcNotificationSettingsStore(this)
        textDisplaySettingsStore = BcTextDisplaySettingsStore(this)
        configureNotificationPresentation()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
    }

    private fun configureNotificationPresentation() {
        notificationController = BcNotificationController(AppLanguageManager.wrap(this))
        notificationCoordinator = BcSessionNotificationCoordinator(
            notificationController = notificationController,
            openIntentForMessage = ::createMessageOpenIntent,
            smallIconRes = R.drawable.ic_notification_rope,
            accountContextProvider = ::currentNotificationAccountContext,
            settingsProvider = {
                notificationSettingsStore.selectAccount(currentNotificationAccountContext()?.ownerMemberNumber)
                notificationSettingsStore.settings.value
            },
            isAppInForeground = { BcAppVisibilityTracker.isForeground },
            inputProvider = { message ->
                notificationInput(message, accountSessionManager.activeSession.value?.access?.connectionStatus?.snapshot?.value)
            },
        )
        notificationController.ensureChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REFRESH_LANGUAGE -> {
                configureNotificationPresentation()
                val notificationContext = AppLanguageManager.wrap(this)
                val currentAccess = accountSessionManager.activeSession.value?.access
                val localizedNotification = currentAccess?.let { access ->
                    buildBcServiceNotificationState(
                        session = access.connectionStatus.snapshot.value,
                        switchState = _accountSwitchState.value,
                    ).localized(notificationContext)
                } ?: (
                    notificationContext.getString(R.string.service_starting) to
                        notificationContext.getString(R.string.service_starting_bc)
                    )
                startForeground(
                    BcNotificationController.SESSION_NOTIFICATION_ID,
                    buildSessionNotification(localizedNotification.first, localizedNotification.second),
                )
                return START_STICKY
            }
            ACTION_STOP -> {
                val notificationContext = AppLanguageManager.wrap(this)
                startForeground(
                    BcNotificationController.SESSION_NOTIFICATION_ID,
                    buildSessionNotification(
                        notificationContext.getString(R.string.service_stopping),
                        notificationContext.getString(R.string.service_stopping_bc),
                    ),
                )
                scope.launch {
                    lifecycleMutex.withLock {
                        persistPreference("disable session restore") {
                            sessionPreferences.markStoppedRestoreDisabled()
                        }
                        clearRoomTargetForCurrentOrSavedAccount()
                        unregisterOutboxDrainWorkerDependencies()
                        accountSessionManager.stopActiveSession()
                        stopActiveSessionCollectors()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_SWITCH_ACCOUNT -> {
                val accountName = intent.getStringExtra(EXTRA_ACCOUNT_NAME).orEmpty().trim()
                val session = accountSessionManager.activeSession.value?.access?.connectionStatus?.snapshot?.value
                if (accountSwitchJob?.isActive == true || _accountSwitchState.value.isSwitching ||
                    (session?.isVerifiedOnline == true && session.account?.accountName.sameAccountName(accountName))
                ) return START_STICKY
                if (accountName.isBlank()) {
                    failAccountSwitch("No account was selected.")
                    return START_STICKY
                }
                publishAccountSwitchState(
                    isSwitching = true,
                    switchingToAccountName = accountName,
                    lastErrorMessage = null,
                )
                val notificationContext = AppLanguageManager.wrap(this)
                startForeground(
                    BcNotificationController.SESSION_NOTIFICATION_ID,
                    buildSessionNotification(
                        notificationContext.getString(R.string.service_switching_account),
                        notificationContext.getString(R.string.service_preparing_account_switch),
                    ),
                )
                accountSwitchJob = scope.launch { lifecycleMutex.withLock { switchActiveAccount(accountName) } }
                return START_STICKY
            }

            else -> {
                val notificationContext = AppLanguageManager.wrap(this)
                startForeground(
                    BcNotificationController.SESSION_NOTIFICATION_ID,
                    buildSessionNotification(
                        notificationContext.getString(R.string.service_starting),
                        notificationContext.getString(R.string.service_preparing_bc_session),
                    ),
                )
            }
        }

        scope.launch { lifecycleMutex.withLock { bindActiveSession(restoreSavedSession = true) } }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let(connectivityManager::unregisterNetworkCallback)
        networkCallback = null
        serviceRunning = false
        unregisterOutboxDrainWorkerDependencies()
        accountSessionManager.close()
        notificationSettingsStore.close()
        textDisplaySettingsStore.close()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private suspend fun bindActiveSession(
        restoreSavedSession: Boolean,
    ) {
        accountSessionManager.ensureActiveSession()
        registerOutboxDrainWorkerDependencies()

        val autoJoinRoomAfterReconnect = preferenceResult {
            sessionPreferences.readAutoJoinRoomAfterReconnect()
        }.getOrElse { throwable ->
            reportPreferenceFailure("read reconnect settings", throwable)
            false
        }
        activeAccess.recoverySettings.setAutoJoinRoomAfterReconnect(autoJoinRoomAfterReconnect)

        startActiveSessionCollectors()
        registerNetworkCallback()
        if (restoreSavedSession) {
            maybeRestoreSession()
        }
    }

    private suspend fun startActiveSessionCollectors() {
        stopActiveSessionCollectors()
        val access = activeAccess
        val frameGateway = gateway
        val inboundProcessor = createInboundMessageProcessor(access)
        val persistenceRetrier = inboundPersistenceRetrier
        fun retainUnprocessedFrame(frame: CanonicalFrame) {
            persistenceRetrier.retain(frame) {
                processInboundFrame(frame, access, frameGateway, inboundProcessor, resolveText = false, applyEffects = false)
            }
        }

        activeAccountJob = scope.launch {
            access.connectionStatus.snapshot
                .map { it.account }
                .distinctUntilChanged()
                .collect { account ->
                notificationSettingsStore.selectAccount(account?.memberNumber)
                val switchState = _accountSwitchState.value
                val switchTarget = switchState.switchingToAccountName
                if (account != null && access.connectionStatus.snapshot.value.isVerifiedOnline &&
                    switchState.isSwitching && account.accountName.sameAccountName(switchTarget)
                ) {
                    publishAccountSwitchState(
                        isSwitching = false,
                        switchingToAccountName = null,
                        lastErrorMessage = null,
                    )
                }
            }
        }

        stateJob = scope.launch {
            combine(
                access.connectionStatus.snapshot,
                _accountSwitchState,
                notificationTickFlow(),
            ) { session, switchState, _ ->
                session.copy(
                    connectionHealth = displayHealth(
                        session.connectionHealth,
                        session.lastVerifiedAtMillis,
                    ),
                ) to switchState
            }.collectLatest { (session, switchState) ->
                if (switchState.isSwitching && accountSwitchJob?.isActive != true &&
                    session.state.switchFailureMessage() != null
                ) {
                    publishAccountSwitchState(
                        isSwitching = false,
                        switchingToAccountName = null,
                        lastErrorMessage = session.state.switchFailureMessage(),
                    )
                }
                val serviceNotificationState = buildBcServiceNotificationState(
                    session = session,
                    switchState = switchState,
                )
                val localizedNotification = serviceNotificationState.localized(
                    AppLanguageManager.wrap(this@BcSessionForegroundService),
                )
                notificationController.notifySessionStatus(
                    buildSessionNotification(
                        title = localizedNotification.first,
                        text = localizedNotification.second,
                    ),
                )
                maybeDrainOutbox(session.connectionHealth)
            }
        }
        statePersistenceJob = scope.launch {
            access.connectionStatus.snapshot
                .map { it.state }
                .distinctUntilChanged()
                .collect(::persistStoppedSessionState)
        }
        val sessionId = ++inboundSessionSequence
        val work = createInboundWorkChannel(::retainUnprocessedFrame)
        val syncRequests = Channel<BcInboundResourceKey>(Channel.BUFFERED)
        resourceSyncRequests = syncRequests
        resourceSyncJob = scope.launch(Dispatchers.IO) {
            val connectionVersionRequests = launch {
                access.connectionStatus.snapshot
                    .map { snapshot -> snapshot.bcVersion?.takeIf(String::isNotBlank) }
                    .filterNotNull()
                    .map(String::normalizedBcVersion)
                    .distinctUntilChanged()
                    .collect { version ->
                        enqueueBcWebResourceSync(
                            key = BcInboundResourceKey.normalized(
                                sessionId = sessionId,
                                version = version,
                                language = currentBcTextLanguage(),
                            ),
                            requests = syncRequests,
                        )
                    }
            }
            try {
                for (request in syncRequests) {
                    launch {
                        val result = try {
                            synchronizeInboundBcWebResources(request, work)
                        } finally {
                            synchronized(resourceSyncRequestLock) {
                                pendingResourceSyncKeys.remove(request)
                            }
                        }
                        work.trySend(BcInboundWork.ResourceSyncCompleted(request, result))
                    }
                }
            } finally {
                connectionVersionRequests.cancel()
            }
        }
        // Keep frame classification and ordered parsing off the main thread. State mutations are
        // dispatched back to Main by processInboundFrame after blocking work has completed.
        inboundFramesJob = scope.launch(inboundProcessingDispatcher) {
            val resourceGate = BcInboundResourceGate(
                sessionId = sessionId,
                versionProvider = { access.connectionStatus.snapshot.value.bcVersion ?: error("BC version is unavailable.") },
                languageProvider = ::currentBcTextLanguage,
                resourcesReady = { version, language ->
                    (application as MobileBcApplication).appComposition
                        .hasReadyBcWebResources(version, language)
                },
            )
            val coordinator = BcInboundWorkCoordinator(
                sessionId = sessionId,
                resourceGate = resourceGate,
                processFrame = { frame ->
                    persistenceRetrier.retryUntilSaved {
                        processInboundFrame(frame, access, frameGateway, inboundProcessor)
                    }
                },
                processUnresolvedFrame = { frame ->
                    persistenceRetrier.retryUntilSaved {
                        processInboundFrame(frame, access, frameGateway, inboundProcessor, resolveText = false)
                    }
                },
                requestResourceSync = { key -> enqueueBcWebResourceSync(key, syncRequests) },
                onRejected = { frame, decision ->
                    recordInboundResourceFailure(
                        eventName = frame.transportEventName,
                        version = decision.version,
                        language = decision.language,
                        error = decision.error,
                        packetLog = frameGateway.packetLog,
                    )
                },
                onOverflow = { frame, key -> recordInboundDeferredOverflow(frame, key, frameGateway.packetLog) },
                onPermanentFailure = { key, frames, error ->
                    recordInboundPermanentResourceFailure(key, frames, error, frameGateway.packetLog)
                },
                onStaleCompletion = { key -> recordInboundStaleResourceCompletion(key, frameGateway.packetLog) },
            )
            val gatewayFrames = launch {
                frameGateway.inboundFrames.collect { frame ->
                    work.trySend(BcInboundWork.Frame(frame)).getOrThrow()
                }
            }
            var processing: BcInboundWork? = null
            try {
                for (item in work) {
                    processing = item
                    coordinator.handle(item)
                    processing = null
                }
            } finally {
                withContext(NonCancellable) {
                    gatewayFrames.cancelAndJoin()
                    work.close()
                    // Transfer ownership before this session's collector terminates. Database
                    // failure must not block an account switch or abandon accepted frames.
                    val pending = mutableListOf<CanonicalFrame>()
                    (processing as? BcInboundWork.Frame)?.takeUnless { coordinator.ownsDeferred(it.frame) }?.let {
                        pending += it.frame
                    }
                    pending += coordinator.takeDeferredFrames()
                    while (true) {
                        val queued = work.tryReceive().getOrNull() ?: break
                        if (queued is BcInboundWork.Frame) {
                            pending += queued.frame
                        }
                    }
                    pending += frameGateway.drainPendingInboundFrames()
                    pending.forEach(::retainUnprocessedFrame)
                }
            }
        }
    }

    private suspend fun synchronizeInboundBcWebResources(
        key: BcInboundResourceKey,
        work: Channel<BcInboundWork>,
    ): BcInboundResourceSyncResult {
        var retryDelayMillis = RESOURCE_SYNC_INITIAL_RETRY_MILLIS
        while (true) {
            try {
                (application as MobileBcApplication).appComposition.ensureBcWebResources(key.version)
                return BcInboundResourceSyncResult.Success
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Could not synchronize BC ${key.version} text resources", error)
                if ((error as? BcWebResourceSyncException)?.retryable != true) {
                    return BcInboundResourceSyncResult.PermanentFailure(error)
                }
                work.trySend(
                    BcInboundWork.ResourceSyncCompleted(
                        key = key,
                        result = BcInboundResourceSyncResult.RetryableFailure(error),
                    ),
                )
            }
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(RESOURCE_SYNC_MAX_RETRY_MILLIS)
        }
    }

    private suspend fun processInboundFrame(
        frame: CanonicalFrame,
        access: SessionAccess,
        frameGateway: RazorGatewayImpl,
        processor: BcInboundMessageProcessor,
        resolveText: Boolean = true,
        applyEffects: Boolean = true,
    ) {
        try {
            val session = access.connectionStatus.snapshot.value
            val received = frame.receiveContext
            val context = BcInboundMessageContext(
                ownerMemberNumber = if (received != null) received.ownerMemberNumber else session.account?.memberNumber,
                ownerDisplayName = if (received != null) received.ownerDisplayName else session.account?.displayName,
                roomMembers = received?.roomMembers ?: session.roomMembers.associate { it.memberNumber to it.name },
                roomConversationId = if (received != null) {
                    received.roomName?.let(::bcRoomConversationId)
                } else {
                    session.currentRoom?.let { bcRoomConversationId(it.name.value) }
                },
                ignoredMemberNumbers = session.relationships.self.ignored,
            )
            val result = withContext(inboundProcessingDispatcher) {
                if (resolveText) processor.handle(frame, context)
                else processor.handle(frame, context, PlainBcChatTextResolver)
            }
            withContext(Dispatchers.Main.immediate) {
                if (!applyEffects || accountSessionManager.activeSession.value?.access !== access) return@withContext
                when (result) {
                    is BcInboundProcessingResult.Recorded -> {
                        val command = result.packet.toInboundActivityCommandOrNull()
                        if (command != null && received != null &&
                            frameGateway.isCurrentRoomContext(received) &&
                            access.connectionStatus.snapshot.value.currentRoom?.name?.value == received.roomName
                        ) {
                            when (val activityResult = access.characterInteractions.handleInboundActivity(command)) {
                                BcInboundActivityResult.Applied,
                                BcInboundActivityResult.Ignored,
                                -> Unit
                                is BcInboundActivityResult.Rejected -> recordInboundActivityDiagnostic(
                                    packetId = result.packet.packetId,
                                    command = command,
                                    outcome = "rejected",
                                    reason = activityResult.reason,
                                    packetLog = frameGateway.packetLog,
                                )
                                is BcInboundActivityResult.Failed -> recordInboundActivityDiagnostic(
                                    packetId = result.packet.packetId,
                                    command = command,
                                    outcome = "failed",
                                    reason = activityResult.reason,
                                    packetLog = frameGateway.packetLog,
                                )
                            }
                        }
                        result.message?.let(notificationCoordinator::onInboundMessage)
                    }
                    is BcInboundIgnored,
                    is BcInboundProcessingResult.Duplicate,
                    -> Unit
                    is BcInboundProcessingResult.DeadLetter -> recordInboundDeadLetter(
                        eventName = frame.transportEventName,
                        reason = result.reason.toString(),
                        packetLog = frameGateway.packetLog,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            recordInboundProcessingFailure(frame.transportEventName, throwable, frameGateway.packetLog)
            // Returning acknowledges this frame to the ordered queue. If even the raw packet
            // cannot be saved, propagate so the owner retries instead of acknowledging it.
            persistence.shared.deadLetterSink.record(
                DeadLetterRecord(
                    frame = frame,
                    reason = ProtocolError.DecodeFailed(
                        "Inbound processing failed for owner=${frame.receiveContext?.ownerMemberNumber}, " +
                            "room=${frame.receiveContext?.roomName}: ${throwable.message}",
                    ),
                    recordedAtMillis = System.currentTimeMillis(),
                    codecName = "BcInboundMessageProcessor",
                ),
            )
        }
    }

    private fun recordInboundResourceFailure(
        eventName: String,
        version: String?,
        language: String?,
        error: Throwable,
        packetLog: PacketLogStore,
    ) {
        val reason = error.message ?: error::class.java.simpleName
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundResourceUnavailable",
            payloadJson = JSONObject()
                .put("TransportEventName", eventName)
                .put("BcVersion", version ?: "unknown")
                .put("BcLanguage", language ?: "unknown")
                .put("Reason", reason)
                .toString(),
        )
        Log.e(
            LOG_TAG,
            "Saving untranslated inbound $eventName because BC ${version ?: "unknown"} resources are unavailable",
            error,
        )
    }

    private fun recordInboundDeferredOverflow(
        frame: CanonicalFrame,
        key: BcInboundResourceKey,
        packetLog: PacketLogStore,
    ) {
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundDeferredOverflow",
            payloadJson = JSONObject()
                .put("TransportEventName", frame.transportEventName)
                .put("SessionId", key.sessionId)
                .put("BcVersion", key.version)
                .put("BcLanguage", key.language)
                .put("Capacity", BcInboundWorkCoordinator.DEFAULT_DEFERRED_CAPACITY)
                .toString(),
        )
        Log.w(LOG_TAG, "Saving the oldest deferred inbound frame without translation after reaching capacity.")
    }

    private fun recordInboundPermanentResourceFailure(
        key: BcInboundResourceKey,
        frames: List<CanonicalFrame>,
        error: Throwable,
        packetLog: PacketLogStore,
    ) {
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundDeferredResourceFailure",
            payloadJson = JSONObject()
                .put("SessionId", key.sessionId)
                .put("BcVersion", key.version)
                .put("BcLanguage", key.language)
                .put("UntranslatedFrameCount", frames.size)
                .put("Reason", error.message ?: error::class.java.simpleName)
                .toString(),
        )
        Log.e(LOG_TAG, "Saving ${frames.size} inbound frames without translation after permanent resource failure.", error)
    }

    private fun recordInboundStaleResourceCompletion(key: BcInboundResourceKey, packetLog: PacketLogStore) {
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundStaleResourceCompletion",
            payloadJson = JSONObject()
                .put("SessionId", key.sessionId)
                .put("BcVersion", key.version)
                .put("BcLanguage", key.language)
                .toString(),
        )
    }

    private fun enqueueBcWebResourceSync(
        key: BcInboundResourceKey,
        requests: Channel<BcInboundResourceKey>,
    ): Boolean {
        synchronized(resourceSyncRequestLock) {
            if (!pendingResourceSyncKeys.add(key)) return true
            if (requests.trySend(key).isFailure) {
                pendingResourceSyncKeys.remove(key)
                return false
            }
        }
        return true
    }

    private fun recordInboundActivityDiagnostic(
        packetId: String,
        command: BcInboundActivityCommand,
        outcome: String,
        reason: String,
        packetLog: PacketLogStore,
    ) {
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundActivityDiagnostic",
            payloadJson = JSONObject()
                .put("PacketId", packetId)
                .put("ActivityName", command.activityName)
                .put("SourceMemberNumber", command.sourceMemberNumber)
                .put("TargetMemberNumber", command.targetMemberNumber)
                .put("Outcome", outcome)
                .put("Reason", reason)
                .toString(),
        )
        Log.w(
            LOG_TAG,
            "Inbound activity ${command.activityName} $outcome for " +
                "${command.sourceMemberNumber}->${command.targetMemberNumber}: $reason",
        )
    }

    private suspend fun ensureBcWebResourcesWithRetry(version: String): Boolean {
        var retryDelayMillis = RESOURCE_SYNC_INITIAL_RETRY_MILLIS
        while (true) {
            try {
                (application as MobileBcApplication).appComposition.ensureBcWebResources(version)
                return true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(LOG_TAG, "Could not synchronize BC $version text resources", error)
                if ((error as? BcWebResourceSyncException)?.retryable != true) return false
            }
            delay(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(RESOURCE_SYNC_MAX_RETRY_MILLIS)
        }
    }

    private fun recordInboundProcessingFailure(eventName: String, throwable: Throwable, packetLog: PacketLogStore) {
        val reason = throwable.message ?: throwable::class.java.simpleName
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundProcessingFailure",
            payloadJson = JSONObject()
                .put("TransportEventName", eventName)
                .put("Reason", reason)
                .toString(),
        )
        Log.e(LOG_TAG, "Inbound processing failed for $eventName", throwable)
    }

    private fun recordInboundDeadLetter(eventName: String, reason: String, packetLog: PacketLogStore) {
        packetLog.record(
            direction = PacketLogDirection.Inbound,
            eventName = "MobileBcInboundDeadLetter",
            payloadJson = JSONObject()
                .put("TransportEventName", eventName)
                .put("Reason", reason)
                .toString(),
        )
        Log.w(LOG_TAG, "Inbound frame became a dead letter for $eventName: $reason")
    }

    private suspend fun stopActiveSessionCollectors() {
        stateJob?.cancel()
        stateJob = null
        statePersistenceJob?.cancel()
        statePersistenceJob = null
        val stoppingResourceSyncJob = resourceSyncJob
        resourceSyncJob = null
        stoppingResourceSyncJob?.cancel()
        resourceSyncRequests?.close()
        resourceSyncRequests = null
        val stoppingInboundFramesJob = inboundFramesJob
        inboundFramesJob = null
        stoppingInboundFramesJob?.cancel()
        synchronized(resourceSyncRequestLock) {
            pendingResourceSyncKeys.clear()
        }
        stoppingResourceSyncJob?.join()
        stoppingInboundFramesJob?.join()
        recoveryJob?.cancel()
        recoveryJob = null
        activeAccountJob?.cancel()
        activeAccountJob = null
        lastOutboxDrainHealth = null
    }

    private fun registerOutboxDrainWorkerDependencies() {
        val access = activeAccess
        val coordinator = outboxDrainCoordinator
        if (registeredOutboxDrainCoordinator === coordinator) return
        unregisterOutboxDrainWorkerDependencies()
        OutboxDrainWorkerDependencies.register(
            coordinator = coordinator,
            currentOwnerMemberNumber = {
                access.connectionStatus.snapshot.value
                    .takeIf { it.isVerifiedOnline }
                    ?.account?.memberNumber
            },
        )
        registeredOutboxDrainCoordinator = coordinator
    }

    private fun unregisterOutboxDrainWorkerDependencies() {
        registeredOutboxDrainCoordinator?.let(OutboxDrainWorkerDependencies::unregister)
        registeredOutboxDrainCoordinator = null
    }

    private fun buildSessionNotification(title: String, text: String) =
        notificationController.buildSessionStatus(
            title = title,
            text = text,
            openIntent = createOpenIntent(requestCode = 0),
            stopIntent = PendingIntent.getService(
                this,
                1,
                Intent(this, BcSessionForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            smallIconRes = R.drawable.ic_notification_rope,
        )

    private fun createOpenIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            requestCode,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createMessageOpenIntent(
        message: BcMessage,
        accountContext: BcNotificationAccountContext?,
    ): PendingIntent =
        PendingIntent.getActivity(
            this,
            messageNotificationRequestCode(message, accountContext),
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_FROM_MESSAGE_NOTIFICATION)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun messageNotificationRequestCode(
        message: BcMessage,
        accountContext: BcNotificationAccountContext?,
    ): Int =
        MESSAGE_OPEN_REQUEST_BASE +
            (31 * (accountContext?.stableKey ?: BcNotificationAccountContext.UNKNOWN_ACCOUNT_KEY).hashCode() +
                message.conversationId.value.hashCode()).and(MESSAGE_OPEN_REQUEST_MASK)

    private fun currentNotificationAccountContext(): BcNotificationAccountContext? {
        val account = accountSessionManager.activeSession.value
            ?.access
            ?.connectionStatus
            ?.snapshot
            ?.value
            ?.account
        val accountName = account?.accountName
        val displayName = account?.displayName
        val memberNumber = account?.memberNumber
        if (accountName.isNullOrBlank() && displayName.isNullOrBlank() && memberNumber == null) return null
        return BcNotificationAccountContext(
            accountName = accountName,
            displayName = displayName,
            ownerMemberNumber = memberNumber,
        )
    }

    private suspend fun switchActiveAccount(accountName: String) {
        val normalizedAccountName = accountName.trim()
        if (normalizedAccountName.isBlank()) {
            failAccountSwitch("No account was selected.")
            return
        }

        val savedLoginState = preferenceResult {
            sessionPreferences.readLoginState()
        }.getOrElse { throwable ->
            failAccountSwitch(throwable.preferenceFailureMessage("read saved accounts"))
            return
        }
        val savedAccount = savedLoginState.accounts
            .firstOrNull { it.accountName == normalizedAccountName }
        if (savedAccount == null || savedAccount.password.isBlank()) {
            val message = savedLoginState.credentialErrorMessage
                ?: "Saved credentials were not found for $normalizedAccountName."
            failAccountSwitch(message)
            return
        }

        publishAccountSwitchState(
            isSwitching = true,
            switchingToAccountName = normalizedAccountName,
            lastErrorMessage = null,
        )
        notificationController.notifySessionStatus(
            buildSessionNotification("Switching account", "Switching to $normalizedAccountName"),
        )
        val targetVersion = savedLoginState.confirmedNormalizedBcVersionOrNull()
        if (targetVersion == null) {
            failAccountSwitch("Confirm the system BC version before switching accounts.")
            return
        }
        val resourceFailure = try {
            (application as MobileBcApplication).appComposition.ensureBcWebResources(targetVersion)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Could not prepare BC $targetVersion resources for account switch", error)
            error
        }
        if (resourceFailure != null) {
            val detail = resourceFailure.message?.takeIf(String::isNotBlank)
                ?: resourceFailure::class.java.simpleName
            failAccountSwitch(
                "BC $targetVersion resources are unavailable ($detail). The current account remains active.",
            )
            return
        }
        val switchResult = runCatching {
            accountSessionManager.switchActiveAccount(normalizedAccountName)
            unregisterOutboxDrainWorkerDependencies()
            stopActiveSessionCollectors()
            bindActiveSession(restoreSavedSession = false)
        }
        switchResult.exceptionOrNull()?.let { throwable ->
            if (throwable is CancellationException) throw throwable
            failAccountSwitch(throwable.accountSwitchFailureMessage(normalizedAccountName))
            return
        }

        val recoverableSession = preferenceResult {
            sessionPreferences.readRecoverableSession()
        }.getOrElse { throwable ->
            failAccountSwitch(throwable.preferenceFailureMessage("read the recovery session"))
            return
        }
        if (recoverableSession == null || recoverableSession.accountName != normalizedAccountName) {
            failAccountSwitch("Could not restore saved session for $normalizedAccountName.")
            return
        }

        val restoreResult = runCatching {
            managedSession.hostLifecycle.restoreSavedSession(
                server = recoverableSession.serverConfig.copy(version = targetVersion),
                accountName = recoverableSession.accountName,
                password = recoverableSession.password,
                lastRoomName = recoverableSession.lastRoomName,
            )
        }
        restoreResult.exceptionOrNull()?.let { throwable ->
            if (throwable is CancellationException) throw throwable
            failAccountSwitch(throwable.accountSwitchFailureMessage(normalizedAccountName))
        }
    }

    private fun maybeRestoreSession() {
        if (recoveryJob?.isActive == true) return
        if (activeAccess.connectionStatus.snapshot.value.desiredOnline) return
        recoveryJob = scope.launch {
            val savedSession = preferenceResult {
                sessionPreferences.readRecoverableSession()
            }.getOrElse { throwable ->
                reportPreferenceFailure("read the recovery session", throwable)
                return@launch
            } ?: return@launch
            notificationController.notifySessionStatus(
                buildSessionNotification("Reconnecting", "Restoring saved BC session"),
            )
            val version = savedSession.serverConfig.version.normalizedBcVersion()
            if (!ensureBcWebResourcesWithRetry(version)) {
                notificationController.notifySessionStatus(
                    buildSessionNotification(
                        "Saved session unavailable",
                        "BC $version resources are unavailable. Confirm the version and log in again.",
                    ),
                )
                persistPreference("disable session restore") {
                    sessionPreferences.markStoppedRestoreDisabled()
                }
                return@launch
            }
            managedSession.hostLifecycle.restoreSavedSession(
                server = savedSession.serverConfig,
                accountName = savedSession.accountName,
                password = savedSession.password,
                lastRoomName = savedSession.lastRoomName,
            )
        }
    }

    private fun showMessageNotification(message: BcMessage) {
        notificationCoordinator.onInboundMessage(message)
    }

    private fun notifyFriendRequest(senderMemberNumber: Int, receivedAtMillis: Long) {
        val senderName = currentRoomMemberLookup()[senderMemberNumber] ?: "#$senderMemberNumber"
        val text = AppLanguageManager.wrap(this).getString(R.string.friend_request_received, senderName)
        notificationCoordinator.onInboundMessage(
            BcMessage(
                id = BcMessageId("friend-request:$senderMemberNumber:$receivedAtMillis"),
                conversationId = BcConversationId("message:$senderMemberNumber"),
                address = BcMessageAddress.Direct(senderMemberNumber),
                sender = BcSender(senderMemberNumber, senderName),
                kind = BcMessageKind.System,
                transport = BcMessageTransport.LocalSystem,
                text = text,
                timestamp = Instant.ofEpochMilli(receivedAtMillis),
                notificationPolicy = BcNotificationPolicy.SystemAlert,
            )
        )
    }

    private fun currentRoomMemberLookup(): Map<Int, String> {
        val session = activeAccess.connectionStatus.snapshot.value
        val members = session.roomMembers.associate { member ->
            member.memberNumber to member.name
        }
        val account = session.account ?: return members
        return if (members.containsKey(account.memberNumber)) {
            members
        } else {
            members + (account.memberNumber to account.displayName)
        }
    }

    private fun currentBcVersion(): String =
        requireNotNull(
            activeAccess.connectionStatus.snapshot.value.bcVersion
                ?: gateway.currentBcVersion,
        ) { "BC version is unavailable for inbound message parsing." }
            .also { version -> require(version.isNotBlank()) { "BC version is blank for inbound message parsing." } }
            .normalizedBcVersion()

    private fun currentRelationshipItemCatalog(): BcRuleCatalog? {
        val version = currentBcVersion()
        relationshipRuleCatalogRepository.currentSnapshot(version)?.let { return it }
        val now = System.currentTimeMillis()
        if (
            relationshipCatalogFailedVersion == version &&
            now - relationshipCatalogFailedAtMillis < RELATIONSHIP_CATALOG_RETRY_MILLIS
        ) {
            return null
        }
        return when (val result = relationshipRuleCatalogRepository.refresh(version)) {
            is BcRuleCatalogLoadResult.Ready -> result.catalog.also {
                relationshipCatalogFailedVersion = null
                relationshipCatalogFailedAtMillis = 0L
            }
            is BcRuleCatalogLoadResult.MissingResources,
            is BcRuleCatalogLoadResult.Failed,
            -> null.also {
                relationshipCatalogFailedVersion = version
                relationshipCatalogFailedAtMillis = now
            }
        }
    }

    private fun maybeDrainOutbox(health: BcConnectionHealth) {
        val previous = lastOutboxDrainHealth
        lastOutboxDrainHealth = health
        if (health == BcConnectionHealth.VerifiedOnline && previous != BcConnectionHealth.VerifiedOnline) {
            syncScheduler.scheduleOutboxDrain()
        }
    }

    private suspend fun dispatchQueuedOutbound(clientMsgId: String): QueuedOutboundDispatchResult {
        val session = activeAccess.connectionStatus.snapshot.value
        if (!session.isVerifiedOnline) {
            return QueuedOutboundDispatchResult.NotDispatched
        }
        val ownerMemberNumber = session.account?.memberNumber
            ?: return QueuedOutboundDispatchResult.NotDispatched
        val result = outboxDrainCoordinator.drain(
            ownerMemberNumber = ownerMemberNumber,
            clientMsgId = clientMsgId,
            limit = 1,
        )
        if (result.shouldRetry) syncScheduler.scheduleOutboxDrain()
        return when (clientMsgId) {
            in result.confirmedClientMsgIds -> QueuedOutboundDispatchResult.Confirmed
            in result.submittedClientMsgIds -> QueuedOutboundDispatchResult.Submitted
            in result.retryableFailedClientMsgIds,
            in result.terminalFailedClientMsgIds,
            -> QueuedOutboundDispatchResult.Failed(result.failedReasons[clientMsgId], result.failedRoomChatRejections[clientMsgId])
            else -> QueuedOutboundDispatchResult.NotDispatched
        }
    }

    private suspend fun persistStoppedSessionState(state: BcSessionState) {
        val shouldStopRestore = state is BcSessionState.Stopped ||
            state is BcSessionState.Blocked ||
            (state is BcSessionState.Error && !state.recoverable)
        if (shouldStopRestore) {
            persistPreference("disable session restore") {
                sessionPreferences.markStoppedRestoreDisabled()
            }
        }
    }

    private suspend fun persistRoomTarget(accountName: String, roomName: String?) {
        persistPreference("save the room target") {
            sessionPreferences.saveRoomTarget(accountName, roomName)
        }
    }

    private suspend fun clearRoomTargetForCurrentOrSavedAccount() {
        val accountName = accountSessionManager.activeSession.value?.access
            ?.connectionStatus?.snapshot?.value?.account?.accountName
        persistPreference("clear the room target") {
            if (accountName.isNullOrBlank()) {
                sessionPreferences.clearRoomTargetForLastLogin()
            } else {
                sessionPreferences.clearRoomTarget(accountName)
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val session = accountSessionManager.activeSession.value ?: return
                session.hostLifecycle.onNetworkAvailable()
                syncScheduler.scheduleOutboxDrain()
            }

            override fun onLost(network: Network) {
                accountSessionManager.activeSession.value?.hostLifecycle?.onNetworkLost()
            }
        }.also { callback ->
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    private fun String?.sameAccountName(other: String?): Boolean {
        val left = this?.trim()?.takeIf { it.isNotBlank() }
        val right = other?.trim()?.takeIf { it.isNotBlank() }
        return left != null && right != null && left.equals(right, ignoreCase = true)
    }

    private fun Throwable.accountSwitchFailureMessage(accountName: String): String {
        val detail = message?.takeIf { it.isNotBlank() }
            ?: this::class.simpleName
            ?: "unknown error"
        return "Account switch to $accountName failed: $detail"
    }

    private fun BcSessionState.switchFailureMessage(): String? = when (this) {
        is BcSessionState.Error -> "Account switch failed."
        is BcSessionState.Blocked -> "Account switch blocked: $reason"
        BcSessionState.Stopped -> "Account switch stopped."
        else -> null
    }

    private fun displayHealth(health: BcConnectionHealth, lastVerifiedAtMillis: Long?): BcConnectionHealth {
        if (health != BcConnectionHealth.VerifiedOnline) return health
        val verifiedAt = lastVerifiedAtMillis ?: return BcConnectionHealth.Stale
        return if (System.currentTimeMillis() - verifiedAt <= ONLINE_DISPLAY_FRESH_MS) {
            BcConnectionHealth.VerifiedOnline
        } else {
            BcConnectionHealth.Stale
        }
    }

    private fun notificationTickFlow() = flow {
        while (true) {
            emit(Unit)
            delay(NOTIFICATION_TICK_MS)
        }
    }

    private suspend fun <T> preferenceResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        Result.failure(throwable)
    }

    private suspend fun persistPreference(
        action: String,
        block: suspend () -> Unit,
    ) {
        preferenceResult(block).exceptionOrNull()?.let { throwable ->
            reportPreferenceFailure(action, throwable)
        }
    }

    private fun reportPreferenceFailure(action: String, throwable: Throwable) {
        notificationController.notifySessionStatus(
            buildSessionNotification(
                title = "Saved session unavailable",
                text = throwable.preferenceFailureMessage(action),
            ),
        )
    }

    private fun failAccountSwitch(message: String) {
        publishAccountSwitchState(
            isSwitching = false,
            switchingToAccountName = null,
            lastErrorMessage = message,
        )
        notificationController.notifySessionStatus(
            buildSessionNotification("Account switch failed", message),
        )
    }

    private fun Throwable.preferenceFailureMessage(action: String): String =
        message?.takeIf { it.isNotBlank() }
            ?.let { "Could not $action: $it" }
            ?: "Could not $action (${this::class.java.simpleName})."


    private fun publishAccountSwitchState(
        isSwitching: Boolean = _accountSwitchState.value.isSwitching,
        switchingToAccountName: String? = _accountSwitchState.value.switchingToAccountName,
        lastErrorMessage: String? = _accountSwitchState.value.lastErrorMessage,
    ) {
        _accountSwitchState.value = _accountSwitchState.value.copy(
            isSwitching = isSwitching,
            switchingToAccountName = switchingToAccountName,
            lastErrorMessage = lastErrorMessage,
        )
    }

    companion object {
        private const val LOG_TAG = "BcSessionService"
        private const val RELATIONSHIP_CATALOG_RETRY_MILLIS = 60_000L
        private const val RESOURCE_SYNC_INITIAL_RETRY_MILLIS = 5_000L
        private const val RESOURCE_SYNC_MAX_RETRY_MILLIS = 60_000L
        private const val ACTION_STOP = "dev.vivian.mobilebc.action.STOP_BC_SESSION"
        private const val ACTION_SWITCH_ACCOUNT = "dev.vivian.mobilebc.action.SWITCH_BC_ACCOUNT"
        private const val ACTION_REFRESH_LANGUAGE = "dev.vivian.mobilebc.action.REFRESH_LANGUAGE"
        private const val EXTRA_ACCOUNT_NAME = "dev.vivian.mobilebc.extra.ACCOUNT_NAME"
        private const val ONLINE_DISPLAY_FRESH_MS = 60_000L
        private const val NOTIFICATION_TICK_MS = 15_000L
        private const val MESSAGE_OPEN_REQUEST_BASE = 10_000
        private const val MESSAGE_OPEN_REQUEST_MASK = 0x00ffffff

        @Volatile
        private var serviceRunning: Boolean = false

        fun start(context: Context) {
            if (serviceRunning) return
            val intent = Intent(context, BcSessionForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BcSessionForegroundService::class.java).setAction(ACTION_STOP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun switchAccount(context: Context, accountName: String) {
            val intent = Intent(context, BcSessionForegroundService::class.java)
                .setAction(ACTION_SWITCH_ACCOUNT)
                .putExtra(EXTRA_ACCOUNT_NAME, accountName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun refreshLanguage(context: Context) {
            context.startService(
                Intent(context, BcSessionForegroundService::class.java)
                    .setAction(ACTION_REFRESH_LANGUAGE),
            )
        }
    }
}

private fun BcServiceNotificationState.localized(context: Context): Pair<String, String> {
    val account = activeDisplayName ?: activeAccountName
    return when (availability) {
        BcServiceAvailability.SwitchingAccount -> context.getString(R.string.service_switching_account) to
            (switchingToAccountName?.let { context.getString(R.string.service_switching_to, it) }
                ?: context.getString(R.string.service_switching_bc_account))
        BcServiceAvailability.Online -> context.getString(R.string.service_online) to
            (account?.let { context.getString(R.string.service_account_online, it) }
                ?: context.getString(R.string.service_bc_online))
        BcServiceAvailability.Reconnecting -> context.getString(R.string.service_reconnecting) to
            (account?.let { context.getString(R.string.service_reconnecting_account, it) }
                ?: context.getString(R.string.service_reconnecting_bc))
        BcServiceAvailability.Stopped -> context.getString(R.string.service_stopped) to
            (account?.let { context.getString(R.string.service_account_stopped, it) }
                ?: context.getString(R.string.service_bc_stopped))
        BcServiceAvailability.Failed -> context.getString(R.string.service_attention_needed) to
            (errorMessage ?: text)
        BcServiceAvailability.Offline -> context.getString(R.string.service_offline) to
            (account?.let { context.getString(R.string.service_account_offline, it) }
                ?: context.getString(R.string.service_bc_offline))
        BcServiceAvailability.Starting -> context.getString(R.string.service_starting) to
            context.getString(R.string.service_starting_bc)
    }
}
