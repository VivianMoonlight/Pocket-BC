package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.domain.messaging.BcInboundPersistenceStore
import dev.vivian.mobilebc.domain.messaging.BcMessageTimelineStore
import dev.vivian.mobilebc.domain.messaging.BcOutboundMessageStore
import dev.vivian.mobilebc.domain.sync.OutboxDrainStore

/** Domain storage ports used by the session runtime. */
class RuntimePersistenceStores internal constructor(
    val timeline: BcMessageTimelineStore,
    val outbound: BcOutboundMessageStore,
    val inbound: BcInboundPersistenceStore,
    val outboxDrain: OutboxDrainStore,
)
