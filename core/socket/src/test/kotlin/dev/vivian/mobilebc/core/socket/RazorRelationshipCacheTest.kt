package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.Lovership
import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.Reputation
import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.session.core.BcLovership
import dev.vivian.mobilebc.domain.session.core.BcOwnership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorRelationshipCacheTest {
    @Test
    fun loginInstallsCacheBeforeAuthenticationAndReturnsCallbackUpdates(): Unit = runBlocking {
        lateinit var requests: RazorRequestCoordinator
        var beforeCallbackUpdates: BcRelationshipSnapshot? = null
        requests = RazorRequestCoordinator(onAuthenticated = { memberNumber ->
            val initial = requireNotNull(requests.authenticatedRelationships)
            beforeCallbackUpdates = initial
            assertEquals(memberNumber, initial.accountMemberNumber)
            assertEquals(BcRelationshipLoadState.Loaded, initial.loadState)
            assertEquals(requests.authenticatedSelfRuleLogSessionId, initial.loginSessionId)
            assertEquals(0L, initial.revision)
            val token = requireNotNull(initial.loginSessionId)
            requests.applyRelationshipUpdate(
                token,
                ownershipUpdate(memberNumber = 9009, ownerName = "New owner", ownerMemberNumber = 3003),
                receivedAtMillis = 100L,
            )
            requests.applyRelationshipUpdate(
                token,
                BcRelationshipGatewayUpdate.Lovership(
                    memberNumber = 9009,
                    result = "CanPropose",
                    loverships = listOf(lover(4004)),
                ),
                receivedAtMillis = 101L,
            )
        })

        val result = login(requests, accountWithRelationships())
        val current = requireNotNull(requests.authenticatedRelationships)

        assertEquals("Original owner", beforeCallbackUpdates?.self?.legacyOwnerName)
        assertEquals(listOf(2002), beforeCallbackUpdates?.self?.loverships?.map { it.memberNumber })
        assertEquals("New owner", current.self.legacyOwnerName)
        assertEquals(3003, current.self.ownership?.memberNumber)
        assertEquals(listOf(4004), current.self.loverships.map { it.memberNumber })
        assertEquals(2L, current.revision)
        assertEquals("CanOfferTrial", current.capabilitiesByMember[9009]?.ownershipResult)
        assertEquals("CanPropose", current.capabilitiesByMember[9009]?.lovershipResult)
        assertEquals(101L, current.capabilitiesByMember[9009]?.queriedAtMillis)
        assertEquals(current.self, result.relationships)
        assertEquals(current.revision, result.relationshipRevision)
        assertEquals(current.capabilitiesByMember, result.relationshipCapabilities)
        assertEquals(current.loginSessionId, result.selfRuleLogSessionId)
    }

    @Test
    fun updatesAfterLoginResponseButBeforeAwaitResumesAppearInLoginResult(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(accountWithRelationships())))
        assertFalse(pending.isCompleted)
        val initial = requireNotNull(requests.authenticatedRelationships)
        val token = requireNotNull(initial.loginSessionId)

        requests.applyRelationshipUpdate(token, clearOwnership(), receivedAtMillis = 200L)
        requests.applyRelationshipUpdate(
            token,
            BcRelationshipGatewayUpdate.Lovership(null, null, listOf(lover(3003))),
            receivedAtMillis = 201L,
        )
        val result = pending.await() as BcSessionLoginResult.Success
        val current = requireNotNull(requests.authenticatedRelationships)

        assertEquals("Original owner", initial.self.legacyOwnerName)
        assertEquals(2002, initial.self.ownership?.memberNumber)
        assertNull(result.relationships.legacyOwnerName)
        assertNull(result.relationships.ownership)
        assertEquals(listOf(3003), result.relationships.loverships.map { it.memberNumber })
        assertEquals(current.self, result.relationships)
        assertEquals(2L, result.relationshipRevision)
        assertEquals(1L, current.ownershipClearRevision)
        assertEquals(1L, result.relationshipOwnershipClearRevision)
        assertEquals(current.capabilitiesByMember, result.relationshipCapabilities)
    }

    @Test
    fun firstLoginCannotMergeTheCacheOfASecondLoginBeforeItsAwaitResumes(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(accountWithRelationships())))
        assertFalse(first.isCompleted)
        val firstSnapshot = requireNotNull(requests.authenticatedRelationships)

        val second = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
            accountWithRelationships().copy(
                owner = "Second owner",
                ownership = Ownership(memberNumber = 3003, name = "Second owner", stage = 1),
            ),
        )))
        assertFalse(second.isCompleted)
        val secondToken = requireNotNull(requests.authenticatedRelationships?.loginSessionId)
        requests.applyRelationshipUpdate(
            secondToken,
            BcRelationshipGatewayUpdate.Ownership(9009, "SecondLoginCapability", null, null, false),
            receivedAtMillis = 250L,
        )

        val firstResult = first.await() as BcSessionLoginResult.Success
        val secondResult = second.await() as BcSessionLoginResult.Success
        val latest = requireNotNull(requests.authenticatedRelationships)
        assertNotEquals(firstResult.selfRuleLogSessionId, secondResult.selfRuleLogSessionId)
        assertEquals(firstSnapshot.loginSessionId, firstResult.selfRuleLogSessionId)
        assertEquals(firstSnapshot.self, firstResult.relationships)
        assertTrue(firstResult.relationshipCapabilities.isEmpty())
        assertEquals(0L, firstResult.relationshipRevision)
        assertNull(firstResult.relationshipOwnershipClearRevision)
        assertEquals("Second owner", secondResult.relationships.legacyOwnerName)
        assertEquals(3003, secondResult.relationships.ownership?.memberNumber)
        assertEquals(latest.loginSessionId, secondResult.selfRuleLogSessionId)
        assertEquals(latest.self, secondResult.relationships)
        assertEquals(latest.capabilitiesByMember, secondResult.relationshipCapabilities)
        assertEquals("SecondLoginCapability", secondResult.relationshipCapabilities[9009]?.ownershipResult)
        assertEquals(1L, secondResult.relationshipRevision)
        assertNull(secondResult.relationshipOwnershipClearRevision)
        assertNull(latest.ownershipClearRevision)
    }

    @Test
    fun latestListsReplaceBlacklistAndGhostListWhilePreservingContractsAndLoginFence(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        val account = accountWithRelationships().copy(reputation = listOf(Reputation("Dominant", 42)))
        login(requests, account)
        val token = requireNotNull(requests.authenticatedRelationships?.loginSessionId)
        requests.applyRelationshipUpdate(token, clearOwnership(), receivedAtMillis = 260L)
        val beforeLists = requireNotNull(requests.applyRelationshipUpdate(
            token,
            ownershipUpdate(memberNumber = 9009, ownerName = "Restored owner", ownerMemberNumber = 2002),
            receivedAtMillis = 261L,
        ))
        val firstLists = BcRelationshipListsUpdate(
            friends = setOf(6006),
            friendNames = mapOf(6006 to "New friend"),
            ignored = setOf(7007),
            whitelist = setOf(8008),
            blacklist = setOf(7007),
            submissives = setOf(9009),
        )
        val first = requireNotNull(requests.updateRelationshipLists(token, firstLists))
        val latestLists = firstLists.copy(ignored = emptySet(), blacklist = setOf(5005))
        val latest = requireNotNull(requests.updateRelationshipLists(token, latestLists))

        assertEquals(3L, first.revision)
        assertEquals(4L, latest.revision)
        assertEquals(setOf(7007), first.self.ignored)
        assertEquals(setOf(7007), first.self.blacklist)
        assertEquals(beforeLists.self.copy(
            friends = latestLists.friends,
            friendNames = latestLists.friendNames,
            ignored = latestLists.ignored,
            whitelist = latestLists.whitelist,
            blacklist = latestLists.blacklist,
            submissives = latestLists.submissives,
        ), latest.self)
        assertTrue(latest.self.ignored.isEmpty())
        assertEquals(setOf(5005), latest.self.blacklist)
        assertEquals("Restored owner", latest.self.legacyOwnerName)
        assertEquals(beforeLists.self.ownership, latest.self.ownership)
        assertEquals(beforeLists.self.loverships, latest.self.loverships)
        assertEquals(100, latest.self.money)
        assertEquals(mapOf("Dominant" to 42), latest.self.reputation)
        assertEquals(beforeLists.capabilitiesByMember, latest.capabilitiesByMember)
        assertEquals(1L, latest.ownershipClearRevision)

        login(requests, account)
        val relogged = requireNotNull(requests.authenticatedRelationships)
        assertEquals(latest.accountMemberNumber, relogged.accountMemberNumber)
        assertNotEquals(token, relogged.loginSessionId)
        assertNull(requests.updateRelationshipLists(token, firstLists))
        assertSame(relogged, requests.authenticatedRelationships)
        assertEquals(setOf(5005), relogged.self.friends)
        assertEquals(0L, relogged.revision)
        assertNull(relogged.ownershipClearRevision)
    }

    @Test
    fun capabilityResponsesForAnotherMemberMergeWithoutReplacingSelfRelations(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        login(requests, accountWithRelationships())
        val initial = requireNotNull(requests.authenticatedRelationships)
        val token = requireNotNull(initial.loginSessionId)

        val ownership = requireNotNull(requests.applyRelationshipUpdate(
            token,
            BcRelationshipGatewayUpdate.Ownership(8008, "FutureOwnershipToken", null, null, false),
            receivedAtMillis = 300L,
        ))
        val lovership = requireNotNull(requests.applyRelationshipUpdate(
            token,
            BcRelationshipGatewayUpdate.Lovership(8008, "FutureLovershipToken", null),
            receivedAtMillis = 301L,
        ))

        assertEquals(initial.self, ownership.self)
        assertEquals(initial.self, lovership.self)
        assertEquals(1L, ownership.revision)
        assertEquals(2L, lovership.revision)
        assertEquals(setOf(8008), lovership.capabilitiesByMember.keys)
        assertEquals("FutureOwnershipToken", lovership.capabilitiesByMember[8008]?.ownershipResult)
        assertEquals("FutureLovershipToken", lovership.capabilitiesByMember[8008]?.lovershipResult)
        assertEquals(301L, lovership.capabilitiesByMember[8008]?.queriedAtMillis)
        assertTrue(initial.capabilitiesByMember.isEmpty())
    }

    @Test
    fun aRecognizedNoOpPacketStillAdvancesRevisionExactlyOnce(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        login(requests, accountWithRelationships())
        val initial = requireNotNull(requests.authenticatedRelationships)

        val current = requireNotNull(requests.applyRelationshipUpdate(
            requireNotNull(initial.loginSessionId),
            BcRelationshipGatewayUpdate.Ownership(null, null, null, null, false),
            receivedAtMillis = 400L,
        ))

        assertEquals(initial.self, current.self)
        assertEquals(initial.capabilitiesByMember, current.capabilitiesByMember)
        assertEquals(1L, current.revision)
        assertSame(current, requests.authenticatedRelationships)
    }

    @Test
    fun staleTokensCannotEditCurrentOrSameMemberReloginCache(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        login(requests, accountWithRelationships())
        val first = requireNotNull(requests.authenticatedRelationships)
        val oldToken = requireNotNull(first.loginSessionId)
        assertNull(requests.applyRelationshipUpdate("stale-token", clearOwnership(), 500L))
        assertSame(first, requests.authenticatedRelationships)

        val secondLogin = login(requests, accountWithRelationships())
        val second = requireNotNull(requests.authenticatedRelationships)
        assertEquals(first.accountMemberNumber, second.accountMemberNumber)
        assertNotEquals(oldToken, second.loginSessionId)
        assertEquals(secondLogin.selfRuleLogSessionId, second.loginSessionId)
        assertEquals(0L, second.revision)
        assertNull(requests.applyRelationshipUpdate(oldToken, clearOwnership(), 501L))
        assertSame(second, requests.authenticatedRelationships)

        val updated = requireNotNull(requests.applyRelationshipUpdate(
            requireNotNull(second.loginSessionId), clearOwnership(), 502L,
        ))
        assertEquals(1L, updated.revision)
        assertNull(updated.self.ownership)
        assertEquals(2002, first.self.ownership?.memberNumber)
        assertEquals(2002, second.self.ownership?.memberNumber)
    }

    @Test
    fun beginningLoginClearsRelationshipCacheBeforeSend(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        login(requests, accountWithRelationships())
        val token = requireNotNull(requests.authenticatedRelationships?.loginSessionId)
        var sendObservedClear = false
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            requests.awaitLogin {
                sendObservedClear = requests.authenticatedRelationships == null &&
                    requests.authenticatedPrivateChatState == null &&
                    requests.authenticatedSelfRuleLogSessionId == null
            }
        }

        assertTrue(sendObservedClear)
        assertNull(requests.authenticatedRelationships)
        assertNull(requests.applyRelationshipUpdate(token, clearOwnership(), 600L))
        requests.disconnect("Login interrupted")
        assertEquals(BcSessionLoginResult.Failure("Login interrupted"), pending.await())
        assertNull(requests.authenticatedRelationships)
    }

    @Test
    fun disconnectClearsCacheAndLateLoginResponseCannotRestoreIt(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        login(requests, accountWithRelationships())
        val token = requireNotNull(requests.authenticatedRelationships?.loginSessionId)

        requests.disconnect("Connection lost")
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(accountWithRelationships())))

        assertNull(requests.authenticatedRelationships)
        assertNull(requests.applyRelationshipUpdate(token, clearOwnership(), 700L))
        assertNull(requests.authenticatedSelfRuleLogSessionId)
    }

    @Test
    fun duplicatePendingLoginDoesNotSendOrReplaceTheAcceptedResponse(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        var sends = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin { sends++ } }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.awaitLogin { sends++ } }
        }

        val failure = second.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("A login request is already pending.", failure?.message)
        assertEquals(1, sends)
        assertNull(requests.authenticatedRelationships)
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(accountWithRelationships())))
        val result = first.await() as BcSessionLoginResult.Success

        assertEquals(result.relationships, requests.authenticatedRelationships?.self)
        assertEquals(result.selfRuleLogSessionId, requests.authenticatedRelationships?.loginSessionId)
        assertEquals(0L, requests.authenticatedRelationships?.revision)
    }

    @Test
    fun unsolicitedLoginResponseCannotReplaceAnAuthenticatedRelationshipCache(): Unit = runBlocking {
        val authenticated = mutableListOf<Int>()
        val requests = RazorRequestCoordinator(onAuthenticated = authenticated::add)
        login(requests, accountWithRelationships())
        val original = requireNotNull(requests.authenticatedRelationships)

        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
            ServerAccountData("account-9009", 9009, "Other account"),
        )))

        assertSame(original, requests.authenticatedRelationships)
        assertEquals(listOf(1001), authenticated)
        assertEquals(original.loginSessionId, requests.authenticatedSelfRuleLogSessionId)
    }

    @Test
    fun failedLoginLeavesPreviousRelationshipsUnavailable(): Unit = runBlocking {
        val requests = RazorRequestCoordinator()
        login(requests, accountWithRelationships())
        val previousToken = requireNotNull(requests.authenticatedRelationships?.loginSessionId)
        val pending = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Error("Wrong password")))

        assertEquals(BcSessionLoginResult.Failure("Wrong password"), pending.await())
        assertNull(requests.authenticatedRelationships)
        assertNull(requests.applyRelationshipUpdate(previousToken, clearOwnership(), 800L))
    }

    private suspend fun CoroutineScope.login(
        requests: RazorRequestCoordinator,
        account: ServerAccountData,
    ): BcSessionLoginResult.Success {
        val pending = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
        requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(account)))
        return pending.await() as BcSessionLoginResult.Success
    }

    private fun accountWithRelationships() = ServerAccountData(
        id = "account-1001",
        memberNumber = 1001,
        name = "Alice",
        friendList = listOf(5005),
        whiteList = listOf(6006),
        money = 100,
        owner = "Original owner",
        ownership = Ownership(memberNumber = 2002, name = "Original owner", stage = 1),
        lovership = listOf(Lovership(memberNumber = 2002, name = "Original lover", stage = 0)),
    )

    private fun ownershipUpdate(
        memberNumber: Int?,
        ownerName: String,
        ownerMemberNumber: Int,
    ) = BcRelationshipGatewayUpdate.Ownership(
        memberNumber = memberNumber,
        result = "CanOfferTrial",
        ownerName = ownerName,
        ownership = BcOwnership(ownerMemberNumber, ownerName, 1, null, null),
        clearOwnership = false,
    )

    private fun clearOwnership() = BcRelationshipGatewayUpdate.Ownership(
        memberNumber = null,
        result = null,
        ownerName = null,
        ownership = null,
        clearOwnership = true,
    )

    private fun lover(memberNumber: Int) = BcLovership(memberNumber, "Lover $memberNumber", 2, null)
}
