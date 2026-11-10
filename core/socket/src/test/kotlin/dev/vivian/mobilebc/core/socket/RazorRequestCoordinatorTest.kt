package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.account.AccountQueryResult
import bot.inker.bc.razor.protocol.account.AccountOwnershipResponse
import bot.inker.bc.razor.protocol.account.AccountLovershipResponse
import bot.inker.bc.razor.protocol.account.Lovership
import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.Reputation
import bot.inker.bc.razor.protocol.auth.LoginResult
import bot.inker.bc.razor.protocol.room.RoomJoinResult
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPrivateLogMutation
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.BcRoomJoinRejectedException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RazorRequestCoordinatorTest {
    @Test
    fun silentCapabilityDoesNotDiscardTheOtherResponse() = runBlocking {
        for (ownershipResponds in listOf(true, false)) {
            val requests = RazorRequestCoordinator(requestTimeoutMs = 100)
            val ownership = async(start = CoroutineStart.UNDISPATCHED) {
                requests.awaitOwnershipCapability(2002) {}
            }
            val lovership = async(start = CoroutineStart.UNDISPATCHED) {
                requests.awaitLovershipCapability(2002) {}
            }
            if (ownershipResponds) {
                requests.handle(RazorWireEvent.AccountOwnership(
                    AccountOwnershipResponse(memberNumber = 2002, result = "CanStartTrial"),
                ))
            } else {
                requests.handle(RazorWireEvent.AccountLovership(
                    AccountLovershipResponse(memberNumber = 2002, result = "CanBeginDating"),
                ))
            }
            assertEquals(if (ownershipResponds) "CanStartTrial" else null, ownership.await())
            assertEquals(if (ownershipResponds) null else "CanBeginDating", lovership.await())
            assertNull(requests.awaitOwnershipCapability(2002) {})
            assertNull(requests.awaitLovershipCapability(2002) {})
        }
    }

    @Test
    fun silentCapabilitiesStillFailOnDisconnect() = runBlocking {
        val requests = RazorRequestCoordinator()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitOwnershipCapability(2002) {} }
        }
        requests.disconnect("Disconnected")
        assertTrue(result.await().exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun beginningAnotherLoginClearsPrivateStateBeforeTheLoginIsSent(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
            ServerAccountData("account-1001", 1001, "Alice", log = JsonParser.parseString("[]")),
        )))
        first.await()
        val previous = requireNotNull(requests.authenticatedPrivateChatState)
        var sendSawPrivateState = true
        val next = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitLogin { sendSawPrivateState = requests.authenticatedPrivateChatState != null }
        }
        assertFalse(sendSawPrivateState)
        assertNull(requests.authenticatedPrivateChatState)
        assertNull(requests.authenticatedSelfRuleLogSessionId)
        assertNull(requests.withAuthenticatedPrivateChatState(previous.selfRuleLogSessionId) { error("Must not run") })
        requests.disconnect("Disconnected")
        assertEquals(BcSessionLoginResult.Failure("Disconnected"), next.await())
    }

    @Test
    fun privateChatStateIsInstalledBeforeAuthenticationAndRejectsStaleEdits(): Unit = runBlocking {
        lateinit var requests: RazorRequestCoordinator
        val observed = mutableListOf<String?>()
        requests = RazorRequestCoordinator(onAuthenticated = { observed += requests.authenticatedPrivateChatState?.selfRuleLogSessionId })
        suspend fun login(): BcSessionLoginResult.Success {
            val reply = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
            requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
                ServerAccountData("account-1001", 1001, "Alice", log = JsonParser.parseString("[]")),
            )))
            return reply.await() as BcSessionLoginResult.Success
        }
        val firstLogin = login()
        val original = requireNotNull(requests.authenticatedPrivateChatState)
        assertEquals(firstLogin.privateChatState, original.state)
        assertEquals(listOf(firstLogin.selfRuleLogSessionId), observed)
        assertEquals(original, requests.withAuthenticatedPrivateChatState(original.selfRuleLogSessionId) { it })
        assertNull(requests.withAuthenticatedPrivateChatState("stale-token") { error("Must not run") })
        val staged = requireNotNull(applyPrivateLogMutation(original.state, BcPrivateLogMutation.Add("BlockTalk", "OwnerRule")))
        assertTrue(original.state.ruleLog!!.isEmpty())
        val accepted = requireNotNull(requests.compareAndSetPrivateChatState(original, staged.copy(revision = 900L)))
        assertEquals(1L, accepted.state.revision)
        assertEquals(listOf(BcOwnerRuleLogRecord("BlockTalk", "OwnerRule")), accepted.state.ruleLog)
        assertNull(requests.compareAndSetPrivateChatState(original, original.state))

        val secondLogin = login()
        assertTrue(firstLogin.selfRuleLogSessionId != secondLogin.selfRuleLogSessionId)
        assertNull(requests.compareAndSetPrivateChatState(accepted, staged))
        assertEquals(secondLogin.privateChatState, requests.authenticatedPrivateChatState?.state)
        assertNull(requests.withAuthenticatedPrivateChatState(original.selfRuleLogSessionId) { error("Must not run") })
        requests.disconnect("Disconnected")
        assertNull(requests.authenticatedPrivateChatState)
        assertNull(requests.withAuthenticatedPrivateChatState(requireNotNull(secondLogin.selfRuleLogSessionId)) { error("Must not run") })
        assertNull(requests.compareAndSetPrivateChatState(requireNotNull(original), staged))
    }

    @Test
    fun loginResponseAfterDisconnectCannotRestorePrivateRuleState(): Unit = runBlocking {
        val authenticated = mutableListOf<Int>()
        val requests = RazorRequestCoordinator(onAuthenticated = authenticated::add)
        val reply = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.disconnect("Disconnected")
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
            ServerAccountData("account-1001", 1001, "Alice", log = JsonParser.parseString("[]")),
        )))

        assertEquals(BcSessionLoginResult.Failure("Disconnected"), reply.await())
        assertTrue(authenticated.isEmpty())
        assertNull(requests.authenticatedSelfRuleLogSessionId)
    }

    @Test
    fun unsolicitedLoginResponsesCannotReplaceTheAuthenticatedRuleGeneration(): Unit = runBlocking {
        val authenticated = mutableListOf<Int>()
        val requests = RazorRequestCoordinator(onAuthenticated = authenticated::add)
        val reply = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
            ServerAccountData("account-1001", 1001, "Alice"),
        )))
        val first = reply.await() as BcSessionLoginResult.Success

        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
            ServerAccountData("account-2002", 2002, "Bob"),
        )))

        assertEquals(listOf(1001), authenticated)
        assertEquals(first.selfRuleLogSessionId, requests.authenticatedSelfRuleLogSessionId)
    }

    @Test
    fun loginMapsThePrivateLogAndRotatesItsGenerationForTheSameAccount(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        suspend fun login(): BcSessionLoginResult.Success {
            val response = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
            requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
                ServerAccountData(
                    id = "account-1001", memberNumber = 1001, name = "Alice",
                    log = JsonParser.parseString("""[{"Name":"BlockChangePose","Group":"OwnerRule","Value":9007199254740993}]"""),
                )
            )))
            return response.await() as BcSessionLoginResult.Success
        }

        val first = login()
        assertEquals(listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule", 9007199254740993L)), first.selfRuleLog)
        assertTrue(first.selfRuleLogSessionId?.isNotBlank() == true)
        assertEquals(first.selfRuleLogSessionId, requests.authenticatedSelfRuleLogSessionId)

        val second = login()
        assertEquals(first.selfRuleLog, second.selfRuleLog)
        assertTrue(first.selfRuleLogSessionId != second.selfRuleLogSessionId)
        assertEquals(second.selfRuleLogSessionId, requests.authenticatedSelfRuleLogSessionId)
        requests.disconnect("Disconnected")
        assertNull(requests.authenticatedSelfRuleLogSessionId)
    }

    @Test
    fun authenticatedProbeAndFriendPollSerializeTheirRequestsAndAwaitSeparateResponses(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        var sends = 0
        val poll = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitOnlineFriends { sends += 1 }
        }
        val probe = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitOnlineFriends { sends += 1 }
        }
        assertEquals(1, sends)
        assertFalse(probe.isCompleted)

        requests.handle(RazorWireEvent.AccountQueryResult(AccountQueryResult("OnlineFriends")))
        poll.await()
        yield()

        assertEquals(2, sends)
        assertFalse(probe.isCompleted)
        requests.handle(RazorWireEvent.AccountQueryResult(AccountQueryResult("OnlineFriends")))
        assertTrue(probe.await().isEmpty())
    }

    @Test
    fun loginResponseMapsAccountAndPublishesAuthenticatedMember(): Unit = runBlocking {
        val authenticatedMembers = mutableListOf<Int>()
        val economies = mutableListOf<BcRelationshipEconomyState>()
        val requests = RazorRequestCoordinator(
            onAuthenticated = authenticatedMembers::add,
            onRelationshipEconomyLoaded = economies::add,
        )
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitLogin(send = {})
        }

        val handled = requests.handle(
            RazorWireEvent.LoginResponse(
                LoginResult.Success(
                    ServerAccountData(
                        id = "account-1001",
                        memberNumber = 1001,
                        name = "Alice",
                        nickname = "Bee",
                        friendList = listOf(2002, 3003),
                        whiteList = listOf(4004),
                        blackList = listOf(5005),
                        ghostList = listOf(5005),
                        friendNames = codeUnitString(
                            7032, 4896, 13256, 1696, 17448, 4141, 16566, 824, 2699,
                            405, 22049, 19633, 29616, 15406, 20607, 7126, 11424, 32,
                        ),
                        submissivesList = codeUnitString(7008, 22563, 3168, 13354, 24760, 5992, 32, 32),
                        owner = "Owner",
                        ownership = Ownership(
                            memberNumber = 6006,
                            name = "Owner",
                            stage = 0,
                            start = 123L,
                            notes = codeUnitString(7779, 23613, 16748, 1280, 592, 15776, 11824, 6632, 32, 32),
                        ),
                        lovership = listOf(
                            Lovership(memberNumber = 7007, name = "Lover", stage = 2, start = 456L)
                        ),
                        money = 987,
                        reputation = listOf(Reputation(type = "Dominant", value = 42)),
                    )
                )
            )
        )

        val success = result.await() as BcSessionLoginResult.Success
        assertTrue(handled)
        assertEquals(listOf(1001), authenticatedMembers)
        assertEquals("Bee", success.name)
        assertEquals(setOf(2002, 3003), success.relationships.friends)
        assertEquals(mapOf(2002 to "Alice", 3003 to "小白"), success.relationships.friendNames)
        assertEquals(setOf(4004), success.relationships.whitelist)
        assertEquals(setOf(5005), success.relationships.blacklist)
        assertEquals(setOf(5005), success.relationships.ignored)
        assertEquals(setOf(4004, 5005), success.relationships.submissives)
        assertEquals(6006, success.relationships.ownership?.memberNumber)
        assertEquals(0, success.relationships.ownership?.stage)
        assertEquals("Owner notes", success.relationships.ownership?.notes)
        assertEquals(7007, success.relationships.loverships.single().memberNumber)
        assertEquals(2, success.relationships.loverships.single().stage)
        assertEquals(987, success.relationships.money)
        assertEquals(mapOf("Dominant" to 42), success.relationships.reputation)
        assertEquals(
            listOf(BcRelationshipEconomyState(987, mapOf("Dominant" to 42))),
            economies,
        )
    }

    @Test
    fun roomJoinResponsePreservesServerFailure(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitRoomJoin(send = {}) }
        }

        requests.handle(
            RazorWireEvent.ChatRoomSearchResponse(
                RoomJoinResult.Error("Room is locked.")
            )
        )

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is BcRoomJoinRejectedException)
        assertEquals("Room is locked.", failure?.message)
    }

    @Test
    fun roomJoinTimeoutIsATransportFailureAndReleasesTheRequest(): Unit = runBlocking {
        val requests = RazorRequestCoordinator(requestTimeoutMs = 0L)
        var sends = 0

        repeat(2) {
            val failure = runCatching { requests.awaitRoomJoin { sends++ } }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(failure is BcRoomJoinRejectedException)
            assertEquals("Room join timed out.", failure?.message)
        }
        assertEquals(2, sends)
    }

    @Test
    fun disconnectDuringRoomJoinIsATransportFailure(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitRoomJoin {} }
        }

        requests.disconnect("Connection lost.")

        val failure = result.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertFalse(failure is BcRoomJoinRejectedException)
        assertEquals("Connection lost.", failure?.message)
    }

    @Test
    fun malformedCompressedRelationshipMetadataDoesNotBreakAccountMapping() {
        val relations = ServerAccountData(
            id = "account-1001",
            memberNumber = 1001,
            name = "Alice",
            friendList = listOf(1001, 2002),
            friendNames = "not-lzstring",
            submissivesList = "also-not-lzstring",
        ).toBcSelfRelations()

        assertEquals(setOf(2002), relations.friends)
        assertEquals(emptyMap<Int, String>(), relations.friendNames)
        assertEquals(emptySet<Int>(), relations.submissives)
    }

    @Test
    fun ownershipEventMappingDecodesNativeCompressedNotes() {
        val update = AccountOwnershipResponse(
            owner = "Owner",
            ownership = Ownership(
                memberNumber = 2002,
                name = "Owner",
                stage = 1,
                notes = codeUnitString(7779, 23613, 16748, 1280, 592, 15776, 11824, 6632, 32, 32),
            ),
        ).toGatewayUpdate()

        assertEquals("Owner notes", update.ownership?.notes)
    }

    @Test
    fun relationshipCapabilityResponsesCompleteQueriesWithoutConsumingStateEvents(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val ownership = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitOwnershipCapability(2002, send = {})
        }
        val lovership = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitLovershipCapability(2002, send = {})
        }

        val ownershipConsumed = requests.handle(
            RazorWireEvent.AccountOwnership(
                AccountOwnershipResponse(memberNumber = 2002, result = "FutureOwnershipToken")
            )
        )
        val lovershipConsumed = requests.handle(
            RazorWireEvent.AccountLovership(
                AccountLovershipResponse(memberNumber = 2002, result = "FutureLovershipToken")
            )
        )

        assertFalse(ownershipConsumed)
        assertFalse(lovershipConsumed)
        assertEquals("FutureOwnershipToken", ownership.await())
        assertEquals("FutureLovershipToken", lovership.await())
    }

    @Test
    fun disconnectCompletesEveryPendingRequestFamily(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val login = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitLogin(send = {})
        }
        val onlineFriends = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitOnlineFriends(send = {}) }
        }
        val allowItem = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitAllowItem(memberNumber = 1001, send = {})
        }

        requests.disconnect("Connection lost.")

        assertEquals(
            BcSessionLoginResult.Failure("Connection lost."),
            login.await(),
        )
        assertFalse(allowItem.await())
        val onlineFailure = onlineFriends.await().exceptionOrNull()
        assertTrue(onlineFailure is IllegalStateException)
        assertEquals("Connection lost.", onlineFailure?.message)
    }

    @Test
    fun timeoutUsesRequestPolicyAndReleasesOwnership(): Unit = runBlocking {
        val requests = RazorRequestCoordinator(requestTimeoutMs = 0L)
        var sends = 0

        val first = requests.awaitRoomSearch { sends++ }
        val second = requests.awaitRoomSearch { sends++ }

        assertTrue(first.isEmpty())
        assertTrue(second.isEmpty())
        assertEquals(2, sends)
    }

    private fun codeUnitString(vararg codeUnits: Int): String =
        codeUnits.map(Int::toChar).joinToString(separator = "")
}
