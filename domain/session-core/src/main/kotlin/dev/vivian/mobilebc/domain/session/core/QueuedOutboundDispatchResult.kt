package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcRoomChatRejection

sealed interface QueuedOutboundDispatchResult {
    data object Submitted : QueuedOutboundDispatchResult
    data object Confirmed : QueuedOutboundDispatchResult
    data class Failed(val message: String?, val roomChatRejection: BcRoomChatRejection? = null) : QueuedOutboundDispatchResult
    data object NotDispatched : QueuedOutboundDispatchResult
}
