package dev.vivian.mobilebc.core.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorWireParserTest {
    @Test
    fun parsesAllowItemResponse() {
        val event = RazorWireParser.parse(
            "ChatRoomAllowItem",
            """{"MemberNumber":235841,"AllowItem":true}""",
        )

        assertTrue(event is RazorWireEvent.AllowItem)
        event as RazorWireEvent.AllowItem
        assertEquals(235841, event.memberNumber)
        assertEquals(true, event.allowItem)
    }

    @Test
    fun parsesOwnershipCapabilityAndStateResponses() {
        val capability = RazorWireParser.parse(
            "AccountOwnership",
            """{"MemberNumber":235841,"Result":"CanOfferBeginTrial"}""",
        ) as RazorWireEvent.AccountOwnership
        val state = RazorWireParser.parse(
            "AccountOwnership",
            """{"Owner":"Mistress","Ownership":{"MemberNumber":42,"Name":"Mistress","Stage":1,"Start":123}}""",
        ) as RazorWireEvent.AccountOwnership
        val clear = RazorWireParser.parse(
            "AccountOwnership",
            """{"ClearOwnership":true}""",
        ) as RazorWireEvent.AccountOwnership

        assertEquals(235841, capability.value.memberNumber)
        assertEquals("CanOfferBeginTrial", capability.value.result)
        assertEquals("Mistress", state.value.owner)
        assertEquals(42, state.value.ownership?.memberNumber)
        assertEquals(1, state.value.ownership?.stage)
        assertEquals(true, clear.value.clearOwnership)
    }

    @Test
    fun parsesLovershipCapabilityAndStateResponses() {
        val capability = RazorWireParser.parse(
            "AccountLovership",
            """{"MemberNumber":235841,"Result":"CanOfferBeginDating"}""",
        ) as RazorWireEvent.AccountLovership
        val state = RazorWireParser.parse(
            "AccountLovership",
            """{"Lovership":[{"MemberNumber":42,"Name":"Lover","Stage":2,"Start":456}]}""",
        ) as RazorWireEvent.AccountLovership

        assertEquals(235841, capability.value.memberNumber)
        assertEquals("CanOfferBeginDating", capability.value.result)
        assertEquals(42, state.value.lovership?.single()?.memberNumber)
        assertEquals(2, state.value.lovership?.single()?.stage)
    }
}
