package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractCommand
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind

internal data class RazorRelationshipContractPayload(
    val eventName: String,
    val value: Map<String, Any>,
)

internal fun BcRelationshipContractCommand.toRazorPayload(): RazorRelationshipContractPayload =
    RazorRelationshipContractPayload(
        eventName = when (kind) {
            BcRelationshipContractKind.Ownership -> "AccountOwnership"
            BcRelationshipContractKind.Lovership -> "AccountLovership"
        },
        value = mapOf(
            "MemberNumber" to memberNumber,
            "Action" to action.wireValue,
        ),
    )
