package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorOwnerRuleInvalidationTest {
    @Test
    fun ownerRuleUpdatesBypassTheCoalescedHealthSignalAndKeepEachPacket(): Unit = runBlocking {
        val gateway = authenticatedGateway()
        try {
            val token = requireNotNull(requests(gateway).authenticatedSelfRuleLogSessionId)
            receive(gateway, "ChatRoomMessage", ownerRule())
            receive(gateway, "ChatRoomChat", ownerRule(content = "OwnerRuleChangePoseAllow"))

            val events = drainEvents(gateway)
            val updates = events.filterIsInstance<BcSessionGatewayEvent.SelfPrivateChatStateUpdated>()
            assertEquals(listOf(10, 10), updates.map { it.ownerMemberNumber })
            assertEquals(listOf(token, token), updates.map { it.selfRuleLogSessionId })
            assertEquals(listOf(1L, 2L), updates.map { it.state.revision })
            assertEquals(listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule")), updates[0].state.ruleLog)
            assertEquals(emptyList<BcOwnerRuleLogRecord>(), updates[1].state.ruleLog)
            assertEquals(JsonParser.parseString("""[{"Name":"BlockChangePose","Group":"OwnerRule"}]"""),
                JsonParser.parseString(updates[0].state.rawLogJson))
            assertEquals(JsonParser.parseString("[]"), JsonParser.parseString(updates[1].state.rawLogJson))
            assertTrue(updates.none { it.resetWhisperTarget })
            assertEquals(updates[1].state, requests(gateway).authenticatedPrivateChatState?.state)
            assertTrue(events.none { it is BcSessionGatewayEvent.SelfRuleLogInvalidated })
            assertEquals(1, events.count { it == BcSessionGatewayEvent.InboundMessage })
            assertEquals(2, gateway.drainPendingInboundFrames().size)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun updatesCaptureTheOriginalLoginGenerationEvenBeforeTheQueueIsRead(): Unit = runBlocking {
        val gateway = authenticatedGateway()
        try {
            val firstToken = requireNotNull(requests(gateway).authenticatedSelfRuleLogSessionId)
            receive(gateway, "ChatRoomMessage", ownerRule())
            val firstState = requireNotNull(requests(gateway).authenticatedPrivateChatState).state
            val secondToken = login(gateway)
            syncRoom(gateway)
            receive(gateway, "ChatRoomMessage", ownerRule())

            val updates = drainEvents(gateway).filterIsInstance<BcSessionGatewayEvent.SelfPrivateChatStateUpdated>()
            assertNotEquals(firstToken, secondToken)
            assertEquals(listOf(firstToken, secondToken), updates.map { it.selfRuleLogSessionId })
            assertEquals(listOf(1L, 1L), updates.map { it.state.revision })
            assertEquals(listOf(10, 10), updates.map { it.ownerMemberNumber })
            assertEquals(firstState, updates[0].state)
            assertEquals(updates[1].state, requests(gateway).authenticatedPrivateChatState?.state)
            val frames = gateway.drainPendingInboundFrames()
            assertEquals(2, frames.size)
            assertFalse(gateway.isCurrentRoomContext(requireNotNull(frames[0].receiveContext)))
            assertTrue(gateway.isCurrentRoomContext(requireNotNull(frames[1].receiveContext)))
        } finally {
            gateway.close()
        }
    }

    @Test
    fun unrelatedTrafficWrongRecipientsAndKnownNonOwnersDoNotChangeRules(): Unit = runBlocking {
        val gateway = authenticatedGateway()
        try {
            val original = requireNotNull(requests(gateway).authenticatedPrivateChatState)
            val payloads = listOf(
                ownerRule(type = "Chat"),
                ownerRule(content = "LoverRuleChangePoseBlock"),
                ownerRule(content = "Owner rule"),
                ownerRule(sender = 30),
                ownerRule(target = 30),
                """{"Type":"Hidden","Content":"OwnerRuleChangePoseBlock","Sender":"20","Target":10}""",
                """{"Type":"Hidden","Content":"OwnerRuleChangePoseBlock","Sender":20.5,"Target":10}""",
                """{"Type":"Hidden","Content":"OwnerRuleChangePoseBlock","Sender":-1,"Target":10}""",
            )
            payloads.forEach { receive(gateway, "ChatRoomMessage", it) }

            assertTrue(drainEvents(gateway).none {
                it is BcSessionGatewayEvent.SelfRuleLogInvalidated || it is BcSessionGatewayEvent.SelfPrivateChatStateUpdated
            })
            assertSame(original, requests(gateway).authenticatedPrivateChatState)
            assertEquals(0L, original.state.revision)
            assertNull(outbound(gateway).drainAccountUpdatePacket())
            assertEquals(payloads.size, gateway.drainPendingInboundFrames().size)
        } finally {
            gateway.close()
        }
    }

    @Test
    fun missingAuthenticationGenerationCannotChangeAnotherLogin(): Unit = runBlocking {
        val gateway = authenticatedGateway()
        try {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { requests(gateway).awaitLogin {} }
            assertNull(requests(gateway).authenticatedSelfRuleLogSessionId)
            assertNull(requests(gateway).authenticatedPrivateChatState)
            receive(gateway, "ChatRoomMessage", ownerRule())

            assertTrue(drainEvents(gateway).none {
                it is BcSessionGatewayEvent.SelfRuleLogInvalidated || it is BcSessionGatewayEvent.SelfPrivateChatStateUpdated
            })
            assertNull(requests(gateway).authenticatedPrivateChatState)
            assertNull(outbound(gateway).drainAccountUpdatePacket())
            assertEquals(1, gateway.drainPendingInboundFrames().size)
            requests(gateway).disconnect("Login interrupted")
            assertEquals(BcSessionLoginResult.Failure("Login interrupted"), pending.await())
        } finally {
            gateway.close()
        }
    }

    @Test
    fun unknownRoomOwnershipConservativelyInvalidatesWithoutExecutingTheRule(): Unit = runBlocking {
        val gateway = authenticatedGateway(includeOwnership = false,
            log = """[{"Name":"Existing","Group":"Other","Extra":{"Keep":true}}]""")
        try {
            val original = requireNotNull(requests(gateway).authenticatedPrivateChatState)
            val raw = ownerRule()
            receive(gateway, "ChatRoomMessage", raw)

            val events = drainEvents(gateway)
            val update = events.filterIsInstance<BcSessionGatewayEvent.SelfPrivateChatStateUpdated>().single()
            assertEquals(
                original.state.copy(ruleLog = null, revision = 1L), update.state,
            )
            assertEquals(10, update.ownerMemberNumber)
            assertEquals(original.selfRuleLogSessionId, update.selfRuleLogSessionId)
            assertFalse(update.resetWhisperTarget)
            assertNull(update.state.ruleLog)
            assertEquals(original.state.rawLogJson, update.state.rawLogJson)
            assertEquals(original.state.preferences, update.state.preferences)
            assertEquals(update.state, requests(gateway).authenticatedPrivateChatState?.state)
            assertTrue(events.none { it is BcSessionGatewayEvent.SelfRuleLogInvalidated })
            assertNull(outbound(gateway).drainAccountUpdatePacket())
            assertEquals(raw, gateway.drainPendingInboundFrames().single().raw)
        } finally {
            gateway.close()
        }
    }

    private suspend fun CoroutineScope.authenticatedGateway(
        includeOwnership: Boolean = true,
        log: String = "[]",
    ): RazorGatewayImpl {
        val gateway = RazorGatewayImpl()
        login(gateway, includeOwnership, log)
        syncRoom(gateway, includeOwnership)
        drainEvents(gateway)
        return gateway
    }

    private suspend fun CoroutineScope.login(
        gateway: RazorGatewayImpl,
        includeOwnership: Boolean = true,
        log: String = "[]",
    ): String {
        val coordinator = requests(gateway)
        val reply = async(start = CoroutineStart.UNDISPATCHED) { coordinator.awaitLogin {} }
        coordinator.handle(RazorWireEvent.LoginResponse(LoginResult.Success(ServerAccountData(
            id = "10", memberNumber = 10, name = "Self", log = JsonParser.parseString(log),
            owner = if (includeOwnership) "Owner" else null,
            ownership = if (includeOwnership) Ownership(memberNumber = 20, name = "Owner", stage = 3) else null,
        ))))
        return requireNotNull((reply.await() as BcSessionLoginResult.Success).selfRuleLogSessionId)
    }

    private fun syncRoom(gateway: RazorGatewayImpl, includeOwnership: Boolean = true) {
        val ownership = if (includeOwnership) """, "Ownership":{"MemberNumber":20,"Name":"Owner","Stage":3}""" else ""
        receive(gateway, "ChatRoomSync",
            """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self"$ownership},{"ID":"20","MemberNumber":20,"Name":"Owner"},{"ID":"30","MemberNumber":30,"Name":"Visitor"}]}""",
        )
    }

    private fun requests(gateway: RazorGatewayImpl): RazorRequestCoordinator =
        gateway.javaClass.getDeclaredField("requests").apply { isAccessible = true }.get(gateway) as RazorRequestCoordinator

    private fun outbound(gateway: RazorGatewayImpl): RazorOutboundPipeline =
        gateway.javaClass.getDeclaredField("outbound").apply { isAccessible = true }.get(gateway) as RazorOutboundPipeline

    @Suppress("UNCHECKED_CAST")
    private fun drainEvents(gateway: RazorGatewayImpl): List<BcSessionGatewayEvent> {
        val queueField = gateway.javaClass.getDeclaredField("eventQueue").apply { isAccessible = true }
        val queue = queueField.get(gateway) as Channel<BcSessionGatewayEvent>
        return generateSequence { queue.tryReceive().getOrNull() }.toList()
    }

    private fun receive(gateway: RazorGatewayImpl, eventName: String, payload: String) {
        val method = gateway.javaClass.getDeclaredMethod("handleIncomingEvent", String::class.java, String::class.java)
            .apply { isAccessible = true }
        method.invoke(gateway, eventName, payload)
    }

    private fun ownerRule(
        type: String = "Hidden",
        content: String = "OwnerRuleChangePoseBlock",
        sender: Int = 20,
        target: Int = 10,
    ) = """{"Type":"$type","Content":"$content","Sender":$sender,"Target":$target}"""
}
