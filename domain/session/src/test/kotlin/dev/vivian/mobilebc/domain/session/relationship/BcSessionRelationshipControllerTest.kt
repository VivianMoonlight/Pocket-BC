package dev.vivian.mobilebc.domain.session.relationship

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcLovership
import dev.vivian.mobilebc.domain.session.core.BcOwnership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractAction
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractCommand
import dev.vivian.mobilebc.domain.session.core.BcRelationshipContractKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListKind
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.RelationshipGateway
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionRuntimeState
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.withNativeEffect
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSessionRelationshipControllerTest {
    @Test
    fun roomTransitionDiscardsLateCapabilityResults() = kotlinx.coroutines.test.runTest {
        val state = verifiedLoadedState(BcSelfRelations())
        val gateway = object : RelationshipGateway {
            override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
            override suspend fun queryOwnershipCapability(memberNumber: Int): String {
                state.updateSession { it.copy(roomPresenceEpoch = it.roomPresenceEpoch + 1) }
                return "CanOfferStartTrial"
            }
            override suspend fun queryLovershipCapability(memberNumber: Int): String = "CanOfferBeginDating"
        }
        val result = controller(state, gateway).queryRelationshipCapabilities(2002)
        assertTrue(result is BcRelationshipCapabilityQueryResult.Rejected)
        assertNull(state.current.relationships.capabilitiesByMember[2002])
    }

    @Test
    fun partialQueryKeepsTheReceivedActionUsable() = kotlinx.coroutines.test.runTest {
        val state = verifiedLoadedState(BcSelfRelations())
        val gateway = FakeRelationshipGateway(
            ownershipCapability = "CanOfferStartTrial", lovershipCapability = null,
        )
        val controller = controller(state, gateway)
        assertTrue(controller.queryRelationshipCapabilities(2002) is BcRelationshipCapabilityQueryResult.Partial)
        assertEquals(BcRelationshipContractActionResult.Submitted,
            controller.submitRelationshipContractAction(2002, BcRelationshipContractKind.Ownership,
                BcRelationshipContractAction.Propose))
        assertEquals(1, gateway.contractCommands.size)
    }

    @Test
    fun silentOwnershipStillPublishesLovershipAndClearsOldOwnershipOption() = kotlinx.coroutines.test.runTest {
        val state = verifiedLoadedState(BcSelfRelations())
        controller(state, FakeRelationshipGateway(ownershipCapability = "CanOfferStartTrial"))
            .queryRelationshipCapabilities(2002)
        val gateway = object : RelationshipGateway {
            override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
            override suspend fun queryOwnershipCapability(memberNumber: Int): String? = null
            override suspend fun queryLovershipCapability(memberNumber: Int): String = "CanBeginEngagement"
        }
        val result = controller(state, gateway).queryRelationshipCapabilities(2002)
        assertTrue(result is BcRelationshipCapabilityQueryResult.Partial)
        val capabilities = state.current.relationships.capabilitiesByMember.getValue(2002)
        assertNull(capabilities.ownershipResult)
        assertEquals("CanBeginEngagement", capabilities.lovershipResult)
    }

    @Test
    fun unansweredRefreshDoesNotRenewOldCapabilitiesOrAuthorizeActions() = kotlinx.coroutines.test.runTest {
        val state = verifiedLoadedState(BcSelfRelations())
        controller(state, FakeRelationshipGateway(ownershipCapability = "CanOfferStartTrial"))
            .queryRelationshipCapabilities(2002)
        val gateway = object : RelationshipGateway {
            override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
            override suspend fun queryOwnershipCapability(memberNumber: Int): String? = null
            override suspend fun queryLovershipCapability(memberNumber: Int): String? = null
        }
        val controller = controller(state, gateway)
        assertEquals(BcRelationshipCapabilityQueryResult.Unanswered, controller.queryRelationshipCapabilities(2002))
        val capabilities = state.current.relationships.capabilitiesByMember.getValue(2002)
        assertNull(capabilities.queriedAtMillis)
        assertNull(capabilities.ownershipResult)
        assertNull(capabilities.lovershipResult)
        assertEquals(
            BcRelationshipContractActionResult.Rejected(BcRelationshipMutationRejection.CapabilitiesMissingOrStale),
            controller.submitRelationshipContractAction(2002, BcRelationshipContractKind.Ownership,
                BcRelationshipContractAction.Propose),
        )
    }

    @Test
    fun engagementCanBeProposedAndAcceptedWithoutWeddingCharge() = kotlinx.coroutines.test.runTest {
        for ((token, action) in listOf(
            "CanOfferBeginEngagement" to BcRelationshipContractAction.Propose,
            "CanBeginEngagement" to BcRelationshipContractAction.Accept,
        )) {
            val state = verifiedLoadedState(BcSelfRelations())
            val gateway = FakeRelationshipGateway(lovershipCapability = token)
            val controller = controller(state, gateway)
            controller.queryRelationshipCapabilities(2002)
            assertEquals(BcRelationshipContractActionResult.Submitted,
                controller.submitRelationshipContractAction(2002, BcRelationshipContractKind.Lovership, action))
            assertTrue(gateway.economyEffects.isEmpty())
            assertEquals(action, gateway.contractCommands.single().action)
        }
    }

    @Test
    fun capabilityQueryPropagatesCancellation() = kotlinx.coroutines.test.runTest {
        val cancelled = CancellationException("query cancelled")
        val gateway = object : RelationshipGateway {
            override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) = Unit
            override suspend fun queryOwnershipCapability(memberNumber: Int): String = throw cancelled
            override suspend fun queryLovershipCapability(memberNumber: Int): String = "CanBeginDating"
        }
        val state = verifiedLoadedState(BcSelfRelations())

        val failure = runCatching { controller(state, gateway).queryRelationshipCapabilities(2002) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(cancelled.message, failure?.message)
        assertEquals(emptyMap<Int, dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities>(), state.current.relationships.capabilitiesByMember)
    }

    @Test
    fun cancelledListMutationRollsBackAndPropagatesCancellation() = kotlinx.coroutines.test.runTest {
        val cancelled = CancellationException("update cancelled")
        val initial = BcSelfRelations(whitelist = setOf(2002))
        val state = verifiedLoadedState(initial)
        val gateway = FakeRelationshipGateway(failure = cancelled)

        val failure = runCatching {
            controller(state, gateway).updateRelationshipList(2002, BcRelationshipListKind.Black, enabled = true)
        }.exceptionOrNull()

        assertSame(cancelled, failure)
        assertEquals(initial, state.current.relationships.self)
        assertEquals(0, state.current.relationships.pendingOperations.size)
    }

    @Test
    fun cancelledContractOrEconomyUpdateClearsPendingAndPropagatesCancellation() = kotlinx.coroutines.test.runTest {
        listOf(false, true).forEach { cancelEconomy ->
            val cancelled = CancellationException("contract cancelled")
            val state = verifiedLoadedState(BcSelfRelations(money = 500))
            val gateway = FakeRelationshipGateway(
                failure = cancelled.takeUnless { cancelEconomy },
                ownershipCapability = if (cancelEconomy) "CanOfferEndTrial" else "CanOfferStartTrial",
                economyFailure = cancelled.takeIf { cancelEconomy },
            )
            val controller = controller(state, gateway)
            controller.queryRelationshipCapabilities(2002)

            val failure = runCatching {
                controller.submitRelationshipContractAction(
                    2002,
                    BcRelationshipContractKind.Ownership,
                    BcRelationshipContractAction.Propose,
                )
            }.exceptionOrNull()

            assertSame(cancelled, failure)
            assertEquals(0, state.current.relationships.pendingOperations.size)
            assertEquals(emptyList<BcRelationshipContractCommand>(), gateway.contractCommands)
        }
    }

    @Test
    fun safeTrialActionIsSubmittedWithoutOptimisticContractState() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway(ownershipCapability = "CanOfferStartTrial")
        val initialRelations = BcSelfRelations()
        val state = verifiedLoadedState(initialRelations)
        val controller = controller(state, gateway, nowMillis = { 1_000L })
        controller.queryRelationshipCapabilities(2002)

        val result = controller.submitRelationshipContractAction(
            memberNumber = 2002,
            kind = BcRelationshipContractKind.Ownership,
            action = BcRelationshipContractAction.Propose,
        )

        assertEquals(BcRelationshipContractActionResult.Submitted, result)
        assertEquals(initialRelations, state.current.relationships.self)
        assertEquals(
            listOf(
                BcRelationshipContractCommand(
                    BcRelationshipContractKind.Ownership,
                    2002,
                    BcRelationshipContractAction.Propose,
                )
            ),
            gateway.contractCommands,
        )
        assertEquals(1, state.current.relationships.pendingOperations.size)
    }

    @Test
    fun sameContractTypeRemainsSerializedUntilServerConfirmation() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway(ownershipCapability = "CanOfferStartTrial")
        val state = verifiedLoadedState(BcSelfRelations())
        val controller = controller(state, gateway, nowMillis = { 1_000L })
        controller.queryRelationshipCapabilities(2002)
        controller.queryRelationshipCapabilities(3003)

        val first = controller.submitRelationshipContractAction(
            memberNumber = 2002,
            kind = BcRelationshipContractKind.Ownership,
            action = BcRelationshipContractAction.Propose,
        )
        val second = controller.submitRelationshipContractAction(
            memberNumber = 3003,
            kind = BcRelationshipContractKind.Ownership,
            action = BcRelationshipContractAction.Propose,
        )

        assertEquals(BcRelationshipContractActionResult.Submitted, first)
        assertEquals(
            BcRelationshipContractActionResult.Rejected(
                BcRelationshipMutationRejection.OperationPending
            ),
            second,
        )
        assertEquals(listOf(2002), gateway.contractCommands.map { it.memberNumber })
    }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun contractPendingExpiresWithoutAnotherRelationshipEvent() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway(ownershipCapability = "CanOfferStartTrial")
        val state = verifiedLoadedState(BcSelfRelations())
        val controller = BcSessionRelationshipController(
            stateHolder = state,
            gateway = gateway,
            nowMillis = { testScheduler.currentTime },
            newOperationId = { "expiring-operation" },
            contractOperationTtlMillis = 1_000L,
            contractPendingScope = backgroundScope,
        )
        controller.queryRelationshipCapabilities(2002)

        val result = controller.submitRelationshipContractAction(
            memberNumber = 2002,
            kind = BcRelationshipContractKind.Ownership,
            action = BcRelationshipContractAction.Propose,
        )
        assertEquals(BcRelationshipContractActionResult.Submitted, result)
        assertEquals(1, state.current.relationships.pendingOperations.size)
        testScheduler.runCurrent()

        testScheduler.advanceTimeBy(1_001L)
        testScheduler.runCurrent()

        assertEquals(0, state.current.relationships.pendingOperations.size)
    }

    @Test
    fun unknownCapabilityTokensRemainClosed() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway(
            ownershipCapability = "FutureOwnershipToken",
            lovershipCapability = "FutureLovershipToken",
        )
        val state = verifiedLoadedState(BcSelfRelations())
        val controller = controller(state, gateway, nowMillis = { 1_000L })
        controller.queryRelationshipCapabilities(2002)

        val ownership = controller.submitRelationshipContractAction(
            2002,
            BcRelationshipContractKind.Ownership,
            BcRelationshipContractAction.Propose,
        )
        val lovership = controller.submitRelationshipContractAction(
            2002,
            BcRelationshipContractKind.Lovership,
            BcRelationshipContractAction.Propose,
        )

        assertEquals(
            BcRelationshipContractActionResult.Rejected(BcRelationshipMutationRejection.ActionNotAllowed),
            ownership,
        )
        assertEquals(
            BcRelationshipContractActionResult.Rejected(BcRelationshipMutationRejection.ActionNotAllowed),
            lovership,
        )
        assertEquals(emptyList<BcRelationshipContractCommand>(), gateway.contractCommands)
    }

    @Test
    fun nativeEconomicCapabilityTokensApplyBoundedEffectBeforeContractCommand() =
        kotlinx.coroutines.test.runTest {
            data class Case(
                val kind: BcRelationshipContractKind,
                val action: BcRelationshipContractAction,
                val ownershipCapability: String = "FutureOwnershipToken",
                val lovershipCapability: String = "FutureLovershipToken",
                val effect: BcRelationshipEconomyEffect,
            )

            val cases = listOf(
                Case(
                    kind = BcRelationshipContractKind.Ownership,
                    action = BcRelationshipContractAction.Propose,
                    ownershipCapability = "CanOfferEndTrial",
                    effect = BcRelationshipEconomyEffect.OwnershipOfferEndTrial,
                ),
                Case(
                    kind = BcRelationshipContractKind.Ownership,
                    action = BcRelationshipContractAction.Accept,
                    ownershipCapability = "CanEndTrial",
                    effect = BcRelationshipEconomyEffect.OwnershipAcceptEndTrial,
                ),
                Case(
                    kind = BcRelationshipContractKind.Lovership,
                    action = BcRelationshipContractAction.Propose,
                    lovershipCapability = "CanOfferBeginWedding",
                    effect = BcRelationshipEconomyEffect.LovershipOfferWedding,
                ),
                Case(
                    kind = BcRelationshipContractKind.Lovership,
                    action = BcRelationshipContractAction.Accept,
                    lovershipCapability = "CanBeginWedding",
                    effect = BcRelationshipEconomyEffect.LovershipAcceptWedding,
                ),
            )

            cases.forEach { case ->
                val initialEconomy = BcRelationshipEconomyState(
                    money = 500,
                    reputation = mapOf("Dominant" to 40),
                )
                val initialRelations = BcSelfRelations(
                    money = initialEconomy.money,
                    reputation = initialEconomy.reputation,
                )
                val gateway = FakeRelationshipGateway(
                    ownershipCapability = case.ownershipCapability,
                    lovershipCapability = case.lovershipCapability,
                    initialEconomy = initialEconomy,
                )
                val state = verifiedLoadedState(initialRelations)
                val controller = controller(state, gateway, nowMillis = { 1_000L })
                controller.queryRelationshipCapabilities(2002)

                val result = controller.submitRelationshipContractAction(
                    memberNumber = 2002,
                    kind = case.kind,
                    action = case.action,
                )

                val expectedEconomy = initialEconomy.withNativeEffect(case.effect)
                assertEquals(BcRelationshipContractActionResult.Submitted, result)
                assertEquals(listOf(case.effect), gateway.economyEffects)
                assertEquals(listOf("economy", "contract"), gateway.contractCallOrder)
                assertEquals(expectedEconomy.money, state.current.relationships.self.money)
                assertEquals(expectedEconomy.reputation, state.current.relationships.self.reputation)
                assertEquals(initialRelations.ownership, state.current.relationships.self.ownership)
                assertEquals(
                    BcRelationshipContractCommand(case.kind, 2002, case.action),
                    gateway.contractCommands.single(),
                )
            }
        }

    @Test
    fun paidRelationshipActionsRejectUnavailableOrInsufficientMoney() =
        kotlinx.coroutines.test.runTest {
            suspend fun submitWithMoney(money: Int?): BcRelationshipContractActionResult {
                val gateway = FakeRelationshipGateway(
                    ownershipCapability = "CanOfferEndTrial",
                    initialEconomy = BcRelationshipEconomyState(money, emptyMap()),
                )
                val state = verifiedLoadedState(BcSelfRelations(money = money))
                val controller = controller(state, gateway, nowMillis = { 1_000L })
                controller.queryRelationshipCapabilities(2002)
                return controller.submitRelationshipContractAction(
                    2002,
                    BcRelationshipContractKind.Ownership,
                    BcRelationshipContractAction.Propose,
                ).also {
                    assertEquals(emptyList<BcRelationshipEconomyEffect>(), gateway.economyEffects)
                    assertEquals(emptyList<BcRelationshipContractCommand>(), gateway.contractCommands)
                    assertEquals(0, state.current.relationships.pendingOperations.size)
                }
            }

            assertEquals(
                BcRelationshipContractActionResult.Rejected(
                    BcRelationshipMutationRejection.EconomyUnavailable
                ),
                submitWithMoney(null),
            )
            assertEquals(
                BcRelationshipContractActionResult.Rejected(
                    BcRelationshipMutationRejection.InsufficientMoney
                ),
                submitWithMoney(99),
            )
        }

    @Test
    fun failedEconomyUpdateClearsPendingAndDoesNotSendContractCommand() =
        kotlinx.coroutines.test.runTest {
            val gateway = FakeRelationshipGateway(
                ownershipCapability = "CanOfferEndTrial",
                initialEconomy = BcRelationshipEconomyState(500, emptyMap()),
                economyFailure = IllegalStateException("economy failed"),
            )
            val state = verifiedLoadedState(BcSelfRelations(money = 500))
            val controller = controller(state, gateway, nowMillis = { 1_000L })
            controller.queryRelationshipCapabilities(2002)

            val result = controller.submitRelationshipContractAction(
                2002,
                BcRelationshipContractKind.Ownership,
                BcRelationshipContractAction.Propose,
            )

            assertEquals(BcRelationshipContractActionResult.Failed("economy failed"), result)
            assertEquals(0, state.current.relationships.pendingOperations.size)
            assertEquals(emptyList<BcRelationshipContractCommand>(), gateway.contractCommands)
        }

    @Test
    fun destructiveRelationshipActionRequiresConfirmationAndServerEventClearsPending() =
        kotlinx.coroutines.test.runTest {
            val gateway = FakeRelationshipGateway()
            val state = verifiedLoadedState(
                BcSelfRelations(ownership = BcOwnership(2002, "Owner", 0, 100L, null))
            )
            val controller = controller(state, gateway, nowMillis = { 1_000L })
            controller.queryRelationshipCapabilities(2002)

            val unconfirmed = controller.submitRelationshipContractAction(
                2002,
                BcRelationshipContractKind.Ownership,
                BcRelationshipContractAction.Break,
            )
            val submitted = controller.submitRelationshipContractAction(
                2002,
                BcRelationshipContractKind.Ownership,
                BcRelationshipContractAction.Break,
                destructiveActionConfirmed = true,
            )

            assertEquals(
                BcRelationshipContractActionResult.Rejected(
                    BcRelationshipMutationRejection.DestructiveConfirmationRequired
                ),
                unconfirmed,
            )
            assertEquals(BcRelationshipContractActionResult.Submitted, submitted)
            assertEquals(1, state.current.relationships.pendingOperations.size)

            controller.handleGatewayEvent(
                BcSessionGatewayEvent.RelationshipUpdated(
                    BcRelationshipGatewayUpdate.Ownership(
                        memberNumber = null,
                        result = null,
                        ownerName = null,
                        ownership = null,
                        clearOwnership = true,
                    )
                )
            )

            assertNull(state.current.relationships.self.ownership)
            assertEquals(0, state.current.relationships.pendingOperations.size)
        }
    @Test
    fun capabilityQueryPublishesBothNativeResultsAtomically() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway(
            ownershipCapability = "FutureOwnershipToken",
            lovershipCapability = "FutureLovershipToken",
        )
        val state = verifiedLoadedState(BcSelfRelations())
        val controller = controller(state, gateway, nowMillis = { 1234L })

        val result = controller.queryRelationshipCapabilities(2002)

        val capabilities = state.current.relationships.capabilitiesByMember.getValue(2002)
        assertEquals(BcRelationshipCapabilityQueryResult.Loaded(capabilities), result)
        assertEquals("FutureOwnershipToken", capabilities.ownershipResult)
        assertEquals("FutureLovershipToken", capabilities.lovershipResult)
        assertEquals(1234L, capabilities.queriedAtMillis)
        assertEquals(listOf(2002), gateway.ownershipQueries)
        assertEquals(listOf(2002), gateway.lovershipQueries)
    }

    @Test
    fun ownershipResponseUpdatesCapabilityAndServerConfirmedState() {
        val state = loadedState()
        val controller = controller(state = state, nowMillis = { 1234L })

        controller.handleGatewayEvent(
            BcSessionGatewayEvent.RelationshipUpdated(
                BcRelationshipGatewayUpdate.Ownership(
                    memberNumber = 2002,
                    result = "FutureServerCapability",
                    ownerName = "Owner",
                    ownership = BcOwnership(2002, "Owner", 1, 100L, "Notes"),
                    clearOwnership = false,
                ),
            ),
        )

        assertEquals("FutureServerCapability", state.current.relationships.capabilitiesByMember[2002]?.ownershipResult)
        assertEquals(1234L, state.current.relationships.capabilitiesByMember[2002]?.queriedAtMillis)
        assertEquals(2002, state.current.relationships.self.ownership?.memberNumber)
        assertEquals("Owner", state.current.relationships.self.legacyOwnerName)
    }

    @Test
    fun clearOwnershipWinsOverOtherFieldsInSameResponse() {
        val state = loadedState(
            BcSelfRelations(
                legacyOwnerName = "Old Owner",
                ownership = BcOwnership(2002, "Old Owner", 0, 100L, null),
            )
        )
        val controller = controller(state)

        controller.handleGatewayEvent(
            BcSessionGatewayEvent.RelationshipUpdated(
                BcRelationshipGatewayUpdate.Ownership(
                    memberNumber = null,
                    result = null,
                    ownerName = "Ignored",
                    ownership = BcOwnership(3003, "Ignored", 1, 200L, null),
                    clearOwnership = true,
                ),
            ),
        )

        assertNull(state.current.relationships.self.ownership)
        assertNull(state.current.relationships.self.legacyOwnerName)
    }

    @Test
    fun lovershipResponseReplacesListAndPreservesOwnershipCapability() {
        val state = loadedState()
        val controller = controller(state = state, nowMillis = { 999L })

        controller.handleGatewayEvent(
            BcSessionGatewayEvent.RelationshipUpdated(
                BcRelationshipGatewayUpdate.Ownership(2002, "CanOfferTrial", null, null, false)
            )
        )
        controller.handleGatewayEvent(
            BcSessionGatewayEvent.RelationshipUpdated(
                BcRelationshipGatewayUpdate.Lovership(
                    memberNumber = 2002,
                    result = "CanOfferBeginDating",
                    loverships = listOf(BcLovership(2002, "Lover", 2, 456L)),
                ),
            ),
        )

        val capabilities = state.current.relationships.capabilitiesByMember.getValue(2002)
        assertEquals("CanOfferTrial", capabilities.ownershipResult)
        assertEquals("CanOfferBeginDating", capabilities.lovershipResult)
        assertEquals(2002, state.current.relationships.self.loverships.single().memberNumber)
    }

    @Test
    fun relationshipResponsesAreIgnoredBeforeAccountRelationshipLoad() {
        val state = BcSessionRuntimeState()
        val controller = controller(state)

        controller.handleGatewayEvent(
            BcSessionGatewayEvent.RelationshipUpdated(
                BcRelationshipGatewayUpdate.Lovership(2002, "CanOfferBeginDating", emptyList())
            )
        )

        assertEquals(BcRelationshipSnapshot(), state.current.relationships)
    }

    @Test
    fun ignoreMutationOptimisticallyAppliesNativeCascadeAndSendsCompleteState() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway()
        val state = verifiedLoadedState(
            BcSelfRelations(
                friends = setOf(9009),
                friendNames = mapOf(9009 to "Friend"),
                whitelist = setOf(2002),
                submissives = setOf(7007),
            )
        )
        val controller = controller(state, gateway, nowMillis = { 123L })

        val result = controller.updateRelationshipList(2002, BcRelationshipListKind.Ignore, enabled = true)

        assertEquals(BcRelationshipMutationResult.Submitted, result)
        assertEquals(setOf(2002), state.current.relationships.self.ignored)
        assertEquals(setOf(2002), state.current.relationships.self.blacklist)
        assertEquals(emptySet<Int>(), state.current.relationships.self.whitelist)
        assertEquals(emptyMap<dev.vivian.mobilebc.domain.session.core.BcRelationshipOperationKey, dev.vivian.mobilebc.domain.session.core.BcPendingRelationshipOperation>(), state.current.relationships.pendingOperations)
        assertEquals(
            BcRelationshipListsUpdate(
                friends = setOf(9009),
                friendNames = mapOf(9009 to "Friend"),
                ignored = setOf(2002),
                whitelist = emptySet(),
                blacklist = setOf(2002),
                submissives = setOf(7007),
            ),
            gateway.updates.single(),
        )
    }

    @Test
    fun failedListMutationRollsBackOptimisticState() = kotlinx.coroutines.test.runTest {
        val initial = BcSelfRelations(whitelist = setOf(2002))
        val state = verifiedLoadedState(initial)
        val confirmedOwnership = BcOwnership(3003, "New Owner", 1, 999L, null)
        val gateway = FakeRelationshipGateway(
            failure = IllegalStateException("send failed"),
            onUpdate = {
                state.updateSession { session ->
                    session.copy(
                        relationships = session.relationships.copy(
                            self = session.relationships.self.copy(ownership = confirmedOwnership)
                        )
                    )
                }
            },
        )
        val controller = controller(state, gateway)

        val result = controller.updateRelationshipList(2002, BcRelationshipListKind.Black, enabled = true)

        assertEquals(BcRelationshipMutationResult.Failed("send failed"), result)
        assertEquals(initial.whitelist, state.current.relationships.self.whitelist)
        assertEquals(initial.blacklist, state.current.relationships.self.blacklist)
        assertEquals(confirmedOwnership, state.current.relationships.self.ownership)
        assertEquals(emptyMap<dev.vivian.mobilebc.domain.session.core.BcRelationshipOperationKey, dev.vivian.mobilebc.domain.session.core.BcPendingRelationshipOperation>(), state.current.relationships.pendingOperations)
    }

    @Test
    fun listMutationRejectsOfflineSelfAndIdempotentRequestsWithoutSending() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway()
        val offline = loadedState(BcSelfRelations(friends = setOf(2002)))
        val offlineController = controller(offline, gateway)
        assertEquals(
            BcRelationshipMutationResult.Rejected(BcRelationshipMutationRejection.NotVerifiedOnline),
            offlineController.updateRelationshipList(2002, BcRelationshipListKind.Friend, enabled = true),
        )

        val online = verifiedLoadedState(BcSelfRelations(friends = setOf(2002)))
        val onlineController = controller(online, gateway)
        assertEquals(
            BcRelationshipMutationResult.Rejected(BcRelationshipMutationRejection.CannotTargetSelf),
            onlineController.updateRelationshipList(1001, BcRelationshipListKind.Friend, enabled = true),
        )
        assertEquals(
            BcRelationshipMutationResult.Unchanged,
            onlineController.updateRelationshipList(2002, BcRelationshipListKind.Friend, enabled = true),
        )
        assertEquals(emptyList<BcRelationshipListsUpdate>(), gateway.updates)
    }

    @Test
    fun friendRequestsAreDeduplicatedExpiredAndBlockedForIgnoredMembers() {
        val state = loadedState(BcSelfRelations(ignored = setOf(3003)))
        val controller = BcSessionRelationshipController(
            stateHolder = state,
            gateway = FakeRelationshipGateway(),
            friendRequestTtlMillis = 1_000L,
        )

        controller.recordFriendRequest(senderMemberNumber = 2002, receivedAtMillis = 100L)
        controller.recordFriendRequest(senderMemberNumber = 2002, receivedAtMillis = 200L)
        controller.recordFriendRequest(senderMemberNumber = 3003, receivedAtMillis = 300L)
        controller.recordFriendRequest(senderMemberNumber = 4004, receivedAtMillis = 1_201L)

        assertEquals(setOf(4004), state.current.relationships.friendRequests.keys)
        assertEquals(1_201L, state.current.relationships.friendRequests.getValue(4004).receivedAtMillis)

        controller.dismissFriendRequest(4004)
        assertEquals(emptyMap<Int, dev.vivian.mobilebc.domain.session.core.BcFriendRequest>(), state.current.relationships.friendRequests)
    }

    @Test
    fun acceptingFriendRequestUpdatesAccountAndRemovesLocalRequest() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway()
        val state = verifiedLoadedState(BcSelfRelations())
        val controller = controller(state, gateway, nowMillis = { 500L })
        controller.recordFriendRequest(senderMemberNumber = 2002, receivedAtMillis = 100L)

        val result = controller.acceptFriendRequest(2002)

        assertEquals(BcRelationshipMutationResult.Submitted, result)
        assertEquals(setOf(2002), state.current.relationships.self.friends)
        assertEquals(emptyMap<Int, dev.vivian.mobilebc.domain.session.core.BcFriendRequest>(), state.current.relationships.friendRequests)
        assertEquals(setOf(2002), gateway.updates.single().friends)
    }

    @Test
    fun expiredFriendRequestCannotBeAccepted() = kotlinx.coroutines.test.runTest {
        val gateway = FakeRelationshipGateway()
        val state = verifiedLoadedState(BcSelfRelations())
        val controller = controller(state, gateway, nowMillis = { 1_100_001L })
        controller.recordFriendRequest(senderMemberNumber = 2002, receivedAtMillis = 100L)

        val result = controller.acceptFriendRequest(2002)

        assertEquals(
            BcRelationshipMutationResult.Rejected(BcRelationshipMutationRejection.FriendRequestNotFound),
            result,
        )
        assertEquals(emptyList<BcRelationshipListsUpdate>(), gateway.updates)
        assertEquals(emptyMap<Int, dev.vivian.mobilebc.domain.session.core.BcFriendRequest>(), state.current.relationships.friendRequests)
    }

    private fun loadedState(relations: BcSelfRelations = BcSelfRelations()): BcSessionRuntimeState =
        BcSessionRuntimeState().apply {
            updateSession {
                it.copy(
                    relationships = it.relationships.nextLoaded(
                        memberNumber = 1001,
                        relations = relations,
                    )
                )
            }
        }

    private fun verifiedLoadedState(relations: BcSelfRelations): BcSessionRuntimeState =
        loadedState(relations).apply {
            updateSession {
                it.copy(
                    state = BcSessionState.Ready,
                    desiredOnline = true,
                    connectionHealth = BcConnectionHealth.VerifiedOnline,
                    account = BcAccount(1001, "Alice", "alice"),
                )
            }
        }

    private fun controller(
        state: BcSessionRuntimeState,
        gateway: RelationshipGateway = FakeRelationshipGateway(),
        nowMillis: () -> Long = { 0L },
    ): BcSessionRelationshipController =
        BcSessionRelationshipController(
            stateHolder = state,
            gateway = gateway,
            nowMillis = nowMillis,
            newOperationId = { "operation-1" },
        )

    private class FakeRelationshipGateway(
        private val failure: Throwable? = null,
        private val onUpdate: (BcRelationshipListsUpdate) -> Unit = {},
        private val ownershipCapability: String? = "CanOfferTrial",
        private val lovershipCapability: String? = "CanOfferBeginDating",
        initialEconomy: BcRelationshipEconomyState = BcRelationshipEconomyState(null, emptyMap()),
        private val economyFailure: Throwable? = null,
    ) : RelationshipGateway {
        val updates = mutableListOf<BcRelationshipListsUpdate>()
        val ownershipQueries = mutableListOf<Int>()
        val lovershipQueries = mutableListOf<Int>()
        val contractCommands = mutableListOf<BcRelationshipContractCommand>()
        val economyEffects = mutableListOf<BcRelationshipEconomyEffect>()
        val contractCallOrder = mutableListOf<String>()
        private var economy = initialEconomy

        override suspend fun updateRelationshipLists(update: BcRelationshipListsUpdate) {
            onUpdate(update)
            failure?.let { throw it }
            updates += update
        }

        override suspend fun queryOwnershipCapability(memberNumber: Int): String? {
            ownershipQueries += memberNumber
            return ownershipCapability
        }

        override suspend fun queryLovershipCapability(memberNumber: Int): String? {
            lovershipQueries += memberNumber
            return lovershipCapability
        }

        override suspend fun sendRelationshipContractAction(command: BcRelationshipContractCommand) {
            failure?.let { throw it }
            contractCallOrder += "contract"
            contractCommands += command
        }

        override suspend fun applyRelationshipEconomyEffect(
            effect: BcRelationshipEconomyEffect,
        ): BcRelationshipEconomyState {
            economyFailure?.let { throw it }
            contractCallOrder += "economy"
            economyEffects += effect
            return economy.withNativeEffect(effect).also { economy = it }
        }
    }
}
