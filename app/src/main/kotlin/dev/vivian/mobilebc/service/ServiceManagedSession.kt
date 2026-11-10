package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.socket.RazorGatewayImpl
import dev.vivian.mobilebc.data.storage.BcPersistenceManager
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.messaging.BcSessionMessageCoordinator
import dev.vivian.mobilebc.domain.session.core.QueuedOutboundDispatchResult
import dev.vivian.mobilebc.domain.session.runtime.ManagedSession
import dev.vivian.mobilebc.domain.session.runtime.SessionAccess
import dev.vivian.mobilebc.domain.session.runtime.SessionHostLifecycle
import dev.vivian.mobilebc.domain.session.runtime.createDefaultBcSessionRuntime
import dev.vivian.mobilebc.domain.sync.OutboxDrainCoordinator
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicBoolean

internal fun createServiceManagedSession(
    persistence: BcPersistenceManager,
    dispatchQueuedOutbound: suspend (clientMsgId: String) -> QueuedOutboundDispatchResult,
    onRoomTargetChanged: suspend (accountName: String, roomName: String?) -> Unit,
    scope: CoroutineScope,
    relationshipItemCatalog: () -> BcRuleCatalog? = { null },
    textLanguageProvider: () -> String = { "EN" },
    roomBackgroundNamesProvider: (String) -> Set<String>? = { null },
): ServiceManagedSession {
    lateinit var domainSession: ManagedSession
    val gateway = RazorGatewayImpl(
        ruleCatalogProvider = relationshipItemCatalog,
        textLanguageProvider = textLanguageProvider,
        roomBackgroundNamesProvider = {
            domainSession.access.connectionStatus.snapshot.value.bcVersion?.let(roomBackgroundNamesProvider)
        },
    )
    val accountStorage = persistence.runtime
    val messageCoordinator = BcSessionMessageCoordinator()
    val outboxDrainCoordinator = OutboxDrainCoordinator(
        store = accountStorage.outboxDrain,
        dispatcher = gateway,
        canDispatch = { command ->
            canDispatchQueuedCommand(
                command = command,
                session = domainSession.access.connectionStatus.snapshot.value,
                messageCoordinator = messageCoordinator,
            )
        },
    )
    domainSession = createDefaultBcSessionRuntime(
        gateway = gateway,
        outboundStore = accountStorage.outbound,
        dispatchQueuedOutbound = dispatchQueuedOutbound,
        timelineStore = accountStorage.timeline,
        onRoomTargetChanged = onRoomTargetChanged,
        messageCoordinator = messageCoordinator,
        relationshipItemCatalog = relationshipItemCatalog,
        scope = scope,
    )

    return ServiceManagedSession(
        domainSession = domainSession,
        gateway = gateway,
        outboxDrainCoordinator = outboxDrainCoordinator,
        messageCoordinator = messageCoordinator,
    )
}

/**
 * Service-owned session resource bundle.
 *
 * The account manager sees only [ManagedSession]. The foreground service can additionally use the
 * transport frame stream and outbox coordinator that belong to the same session lifetime.
 */
internal class ServiceManagedSession(
    private val domainSession: ManagedSession,
    val gateway: RazorGatewayImpl,
    val outboxDrainCoordinator: OutboxDrainCoordinator,
    val messageCoordinator: BcSessionMessageCoordinator,
) : ManagedSession {
    private val closed = AtomicBoolean()

    override val access: SessionAccess
        get() = domainSession.access

    override val hostLifecycle: SessionHostLifecycle
        get() = domainSession.hostLifecycle

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        outboxDrainCoordinator.close()
        domainSession.close()
        gateway.close()
    }
}
