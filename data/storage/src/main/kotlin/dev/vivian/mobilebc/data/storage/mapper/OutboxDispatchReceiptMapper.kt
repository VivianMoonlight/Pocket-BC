package dev.vivian.mobilebc.data.storage

import dev.vivian.mobilebc.core.model.DeliveryState
import dev.vivian.mobilebc.domain.sync.BcOutboundDispatchReceipt

internal fun MessageEntity.withDispatchReceipt(
    receipt: BcOutboundDispatchReceipt,
    delivery: DeliveryState,
): MessageEntity {
    // An inbound echo may already have replaced this row while the socket send was completing.
    if (!senderIsSelf || rawSourceEvent != "LocalOutgoing") return this
    return copy(
        text = receipt.content ?: text,
        rawDictionaryJson = receipt.dictionaryJson ?: rawDictionaryJson,
        delivery = delivery.name,
    )
}
