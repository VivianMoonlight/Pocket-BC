package dev.vivian.mobilebc.core.socket

import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractCommand
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RazorRelationshipContractPayloadTest {
    @Test
    fun ownershipActionUsesNativeEventAndWireAction() {
        val payload = BcRelationshipContractCommand(
            kind = BcRelationshipContractKind.Ownership,
            memberNumber = 2002,
            action = BcRelationshipContractAction.Release,
        ).toRazorPayload()

        assertEquals("AccountOwnership", payload.eventName)
        assertEquals(2002, payload.value["MemberNumber"])
        assertEquals("Release", payload.value["Action"])
    }

    @Test
    fun lovershipActionUsesNativeEventAndWireAction() {
        val payload = BcRelationshipContractCommand(
            kind = BcRelationshipContractKind.Lovership,
            memberNumber = 3003,
            action = BcRelationshipContractAction.Propose,
        ).toRazorPayload()

        assertEquals("AccountLovership", payload.eventName)
        assertEquals(3003, payload.value["MemberNumber"])
        assertEquals("Propose", payload.value["Action"])
    }
}
