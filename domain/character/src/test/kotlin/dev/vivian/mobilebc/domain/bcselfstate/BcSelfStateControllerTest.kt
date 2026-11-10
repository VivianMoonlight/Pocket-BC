package dev.vivian.mobilebc.domain.bcselfstate

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterOwnership
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcSelfStateChange
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcSelfStateResult
import dev.vivian.mobilebc.core.model.BcSelfStateUnavailableReason
import dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcPoseRule
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.character.BcCharacterInteractionsController
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BcSelfStateControllerTest {
    @Test
    fun poseUpdateRequiresAnAuthenticatedSession() = runTest {
        val started = snapshot()
        val snapshots = MutableStateFlow(started.copy(state = BcSessionState.LoggingIn))
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, snapshots).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.Offline), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun selfMarkerAloneCannotAuthorizeAnotherAccountsCharacter() = runTest {
        val started = snapshot()
        val snapshots = MutableStateFlow(started.copy(roomCharacters = listOf(selfCharacter(memberNumber = 20))))
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, snapshots).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.SelfUnavailable), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun selfStateRequestCannotTargetAnotherRoomCharacter() = runTest {
        val started = snapshot()
        val other = selfCharacter(memberNumber = 20).copy(isSelf = false)
        val snapshots = MutableStateFlow(started.copy(roomCharacters = started.roomCharacters + other))
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, snapshots).updateSelfState(
            request(started).copy(targetMemberNumber = other.memberNumber),
        )

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.AccountChanged), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun catalogFromAnotherBcVersionCannotSendPose() = runTest {
        val started = snapshot()
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(
            request(started, rules = ruleCatalog().copy(version = "R128")),
        )

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.CatalogPreparing), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun reloggingIntoTheSameAccountInvalidatesTheVisibleRequest() = runTest {
        val started = snapshot()
        val snapshots = MutableStateFlow(started.copy(
            relationships = started.relationships.copy(accountGeneration = started.relationships.accountGeneration + 1),
        ))
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, snapshots).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.AccountChanged), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun leavingAndRejoiningTheSameRoomInvalidatesTheVisibleRequest() = runTest {
        val started = snapshot()
        val snapshots = MutableStateFlow(started.copy(roomPresenceEpoch = started.roomPresenceEpoch + 1))
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, snapshots).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.RoomChanged), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun changingLowerBodyPoseRetainsTheCurrentUpperBodyPose() = runTest {
        val started = snapshot(selfCharacter().copy(activePose = listOf("BaseLower", "Yoked")))
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Sent, result)
        assertEquals(listOf(BcPoseUpdateCommand(listOf("Yoked", "Kneel"))), gateway.poseCommands)
        assertEquals(
            listOf(BcSelfStateEmitExpectation(started.roomCharacters.single(), ownerPresent = false)),
            gateway.poseExpectations,
        )
        assertTrue(gateway.expressionCommands.isEmpty())
    }

    @Test
    fun poseEmissionExpectationKeepsTheSourceLoginSessionId() = runTest {
        val started = snapshot().copy(selfRuleLogSessionId = "login-7")
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Sent, result)
        assertEquals(
            listOf(BcSelfStateEmitExpectation(
                character = started.roomCharacters.single(),
                ownerPresent = false,
                selfRuleLogSessionId = "login-7",
            )),
            gateway.poseExpectations,
        )
    }

    @Test
    fun bothEyeEmissionExpectationsKeepTheSourceLoginSessionId() = runTest {
        val started = snapshot().copy(selfRuleLogSessionId = "login-7")
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(
            request(started, change = BcSelfStateChange.Expression("Eyes", "Closed")),
        )

        assertEquals(BcSelfStateResult.Sent, result)
        val expected = BcSelfStateEmitExpectation(
            character = started.roomCharacters.single(),
            ownerPresent = false,
            editedExpressionGroups = setOf("Eyes", "Eyes2"),
            selfRuleLogSessionId = "login-7",
        )
        assertEquals(listOf(expected, expected), gateway.expressionExpectations)
        assertEquals(listOf("Eyes", "Eyes2"), gateway.expressionCommands.map { it.group })
    }

    @Test
    fun combinedEyesSendsBothGroupsWithTheCompleteFinalAppearance() = runTest {
        val started = snapshot()
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(
            request(started, change = BcSelfStateChange.Expression("Eyes", "Closed")),
        )

        assertEquals(BcSelfStateResult.Sent, result)
        assertEquals(listOf("Eyes", "Eyes2"), gateway.expressionCommands.map { it.group })
        assertEquals(listOf("Closed", "Closed"), gateway.expressionCommands.map { it.name })
        assertEquals(
            List(2) {
                BcSelfStateEmitExpectation(
                    started.roomCharacters.single(),
                    ownerPresent = false,
                    editedExpressionGroups = setOf("Eyes", "Eyes2"),
                )
            },
            gateway.expressionExpectations,
        )
        gateway.expressionCommands.forEach { command ->
            val appearance = checkNotNull(command.appearance)
            assertEquals(started.roomCharacters.single().appearance.size, appearance.size)
            listOf("Eyes", "Eyes2").forEach { group ->
                val original = started.roomCharacters.single().appearance.single { it.group == group }
                val updated = appearance.single { it.group == group }
                assertEquals("\"Closed\"", BcItemPropertyJson.readPropertyValue(updated.propertyJson, "Expression"))
                assertEquals(original, updated.copy(propertyJson = original.propertyJson))
                assertEquals(
                    BcItemPropertyJson.readPropertyValue(original.propertyJson, "Custom"),
                    BcItemPropertyJson.readPropertyValue(updated.propertyJson, "Custom"),
                )
            }
            assertEquals(
                started.roomCharacters.single().appearance.filter { it.group !in setOf("Eyes", "Eyes2") },
                appearance.filter { it.group !in setOf("Eyes", "Eyes2") },
            )
        }
    }

    @Test
    fun secondEyePacketUsesAppearanceReceivedWhileTheFirstPacketWasPending() = runTest {
        val snapshots = MutableStateFlow(snapshot())
        val incomingItem = BcCharacterItem(
            group = "ItemArms",
            name = "HempRope",
            colorJson = "\"#FFAA00\"",
            propertyJson = "{\"Effect\":[\"Block\"],\"Custom\":true}",
            craftJson = "{\"Name\":\"New rope\"}",
        )

        val (result, gateway) = changeSnapshotDuringFirstEyeSend(snapshots) { current ->
            val self = current.roomCharacters.single()
            current.copy(roomCharacters = listOf(self.copy(appearance = self.appearance + incomingItem)))
        }

        assertEquals(BcSelfStateResult.Sent, result)
        assertEquals(2, gateway.expressionCommands.size)
        val finalAppearance = checkNotNull(gateway.expressionCommands.last().appearance)
        assertEquals(incomingItem, finalAppearance.single { it.group == "ItemArms" })
        listOf("Eyes", "Eyes2").forEach { group ->
            assertEquals("\"Closed\"", BcItemPropertyJson.readPropertyValue(
                finalAppearance.single { it.group == group }.propertyJson,
                "Expression",
            ))
        }
    }

    @Test
    fun latestExpressionRestrictionOverridesAnEnabledVisibleOption() = runTest {
        val started = snapshot()
        val rules = ruleCatalog()
        val closed = BcSelfStateRules().projection(started, rules).expressionGroups
            .single { it.group == "Eyes" }.options.single { it.name == "Closed" }
        assertEquals(null, closed.unavailableReason)
        val snapshots = MutableStateFlow(started.restrictSecondEye())
        val gateway = RecordingGateway()

        val result = BcCharacterInteractionsController(gateway, snapshots).updateSelfState(
            request(started, change = BcSelfStateChange.Expression("Eyes", "Closed"), rules = rules),
        )

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.ExpressionRestrictedByItem), result)
        assertTrue(gateway.expressionCommands.isEmpty())
    }

    @Test
    fun restrictionReceivedDuringFirstEyePacketPreventsTheSecondPacket() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotDuringFirstEyeSend(snapshots) { it.restrictSecondEye() }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.ExpressionRestrictedByItem), result)
        assertEquals(listOf("Eyes"), gateway.expressionCommands.map { it.group })
    }

    @Test
    fun roomChangeDuringFirstEyePacketPreventsTheSecondPacket() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotDuringFirstEyeSend(snapshots) { current ->
            current.copy(currentRoom = room("Another room"), roomPresenceEpoch = current.roomPresenceEpoch + 1)
        }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.RoomChanged), result)
        assertEquals(listOf("Eyes"), gateway.expressionCommands.map { it.group })
    }

    @Test
    fun accountChangeDuringFirstEyePacketPreventsTheSecondPacket() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotDuringFirstEyeSend(snapshots) { current ->
            current.copy(
                account = BcAccount(memberNumber = 20, displayName = "Other", accountName = "other"),
                roomCharacters = listOf(selfCharacter(memberNumber = 20)),
                relationships = current.relationships.copy(
                    accountMemberNumber = 20,
                    accountGeneration = current.relationships.accountGeneration + 1,
                ),
            )
        }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.AccountChanged), result)
        assertEquals(listOf("Eyes"), gateway.expressionCommands.map { it.group })
    }

    @Test
    fun expressionGatewayCancellationPropagatesAndStopsTheCombinedEyeUpdate() = runTest {
        val started = snapshot()
        val cancellation = CancellationException("Account disconnected")
        val gateway = RecordingGateway().apply { onExpression = { throw cancellation } }
        var caught: CancellationException? = null

        try {
            BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(
                request(started, change = BcSelfStateChange.Expression("Eyes", "Closed")),
            )
        } catch (failure: CancellationException) {
            caught = failure
        }

        assertSame(cancellation, caught)
        assertEquals(listOf("Eyes"), gateway.expressionCommands.map { it.group })
    }

    @Test
    fun poseGatewayErrorReturnsItsFailureMessage() = runTest {
        val started = snapshot()
        val gateway = RecordingGateway().apply { onPose = { throw IllegalStateException("Socket write failed") } }

        val result = BcCharacterInteractionsController(gateway, MutableStateFlow(started)).updateSelfState(request(started))

        assertEquals(BcSelfStateResult.Failed("Socket write failed"), result)
        assertEquals(1, gateway.poseCommands.size)
    }

    @Test
    fun concurrentCombinedEyeRequestsFinishOnePairBeforeStartingTheNext() = runTest {
        val started = snapshot()
        val firstStarted = CompletableDeferred<Unit>()
        val resumeFirst = CompletableDeferred<Unit>()
        val gateway = RecordingGateway().apply {
            onExpression = {
                if (expressionCommands.size == 1) {
                    firstStarted.complete(Unit)
                    resumeFirst.await()
                }
            }
        }
        val controller = BcCharacterInteractionsController(gateway, MutableStateFlow(started))
        val first = async { controller.updateSelfState(request(started, change = BcSelfStateChange.Expression("Eyes", "Closed"))) }
        firstStarted.await()
        val secondStarted = CompletableDeferred<Unit>()
        val second = async {
            secondStarted.complete(Unit)
            controller.updateSelfState(request(started, change = BcSelfStateChange.Expression("Eyes", "Dazed")))
        }
        secondStarted.await()
        assertEquals(1, gateway.expressionCommands.size)

        resumeFirst.complete(Unit)

        assertEquals(BcSelfStateResult.Sent, first.await())
        assertEquals(BcSelfStateResult.Sent, second.await())
        assertEquals(
            listOf("Eyes" to "Closed", "Eyes2" to "Closed", "Eyes" to "Dazed", "Eyes2" to "Dazed"),
            gateway.expressionCommands.map { it.group to it.name },
        )
    }

    @Test
    fun roomRejoinWhilePosePacketIsQueuedRejectsBeforeEmission() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotBeforeEmission(
            snapshots,
            requestChange = BcSelfStateChange.Pose("Kneel"),
        ) { current, _ -> current.copy(roomPresenceEpoch = current.roomPresenceEpoch + 1) }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.RoomChanged), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun accountChangeWhileEyePacketIsQueuedRejectsBeforeEmission() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotBeforeEmission(snapshots) { current, _ ->
            current.copy(
                account = BcAccount(memberNumber = 20, displayName = "Other", accountName = "other"),
                roomCharacters = listOf(selfCharacter(memberNumber = 20)),
                relationships = current.relationships.copy(
                    accountMemberNumber = 20,
                    accountGeneration = current.relationships.accountGeneration + 1,
                ),
            )
        }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.AccountChanged), result)
        assertTrue(gateway.expressionCommands.isEmpty())
    }

    @Test
    fun latestItemRestrictionAtFinalEmissionReturnsTypedRejection() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotBeforeEmission(snapshots) { current, _ -> current.restrictSecondEye() }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.ExpressionRestrictedByItem), result)
        assertTrue(gateway.expressionCommands.isEmpty())
    }

    @Test
    fun queuedEyeAppearanceCannotOverwriteNewLegalCharacterChanges() = runTest {
        val changes: Map<String, (BcCharacterSnapshot) -> BcCharacterSnapshot> = linkedMapOf(
            "new unrelated equipment" to { self ->
                self.copy(appearance = self.appearance + BcCharacterItem("ItemArms", "HempRope"))
            },
            "edited eye color" to { self ->
                self.copy(appearance = self.appearance.map { item ->
                    if (item.group == "Eyes") item.copy(colorJson = "\"#0000FF\"") else item
                })
            },
            "edited eye crafting" to { self ->
                self.copy(appearance = self.appearance.map { item ->
                    if (item.group == "Eyes") item.copy(craftJson = "{\"Name\":\"Updated eye\"}") else item
                })
            },
            "edited eye custom property" to { self ->
                self.copy(appearance = self.appearance.map { item ->
                    if (item.group == "Eyes") item.copy(propertyJson = "{\"Expression\":\"Open\",\"Custom\":{\"Value\":9}}") else item
                })
            },
            "unrelated mouth expression" to { self ->
                self.copy(appearance = self.appearance.map { item ->
                    if (item.group == "Mouth") item.copy(propertyJson = "{\"Expression\":\"Open\"}") else item
                })
            },
        )
        changes.forEach { (description, change) ->
            val snapshots = MutableStateFlow(snapshot())

            val (result, gateway) = changeSnapshotBeforeEmission(snapshots) { current, _ ->
                val changed = current.copy(roomCharacters = listOf(change(current.roomCharacters.single())))
                assertEquals(description, null, BcSelfStateRules().rejection(
                    request(current, change = BcSelfStateChange.Expression("Eyes", "Closed")),
                    changed,
                ))
                changed
            }

            assertEquals(description, BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.CharacterStateChanged), result)
            assertTrue(description, gateway.expressionCommands.isEmpty())
        }
    }

    @Test
    fun firstCombinedEyeAppearanceEchoDoesNotRejectTheQueuedSecondEye() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotBeforeEmission(snapshots, pauseBeforeEmitNumber = 2) { current, pendingGateway ->
            val firstPacket = pendingGateway.expressionCommands.single()
            assertEquals("Eyes", firstPacket.group)
            current.copy(roomCharacters = listOf(current.roomCharacters.single().copy(
                appearance = checkNotNull(firstPacket.appearance),
            )))
        }

        assertEquals(BcSelfStateResult.Sent, result)
        assertEquals(listOf("Eyes", "Eyes2"), gateway.expressionCommands.map { it.group })
        assertEquals(listOf("Closed", "Closed"), gateway.expressionCommands.map { it.name })
        assertEquals(gateway.expressionCommands.first().appearance, gateway.expressionCommands.last().appearance)
    }

    @Test
    fun resetExpressionAppearanceEchoDoesNotRejectTheRemainingQueuedGroups() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotBeforeEmission(
            snapshots,
            requestChange = BcSelfStateChange.ResetExpressions,
            pauseBeforeEmitNumber = 2,
        ) { current, pendingGateway ->
            val firstPacket = pendingGateway.expressionCommands.single()
            current.copy(roomCharacters = listOf(current.roomCharacters.single().copy(
                appearance = checkNotNull(firstPacket.appearance),
            )))
        }

        assertEquals(BcSelfStateResult.Sent, result)
        assertEquals(listOf("Eyes", "Eyes2", "Mouth"), gateway.expressionCommands.map { it.group })
        assertTrue(gateway.expressionCommands.all { it.name == null })
        gateway.expressionCommands.forEach { command ->
            val appearance = checkNotNull(command.appearance)
            listOf("Eyes", "Eyes2", "Mouth").forEach { group ->
                assertEquals("null", BcItemPropertyJson.readPropertyValue(
                    appearance.single { it.group == group }.propertyJson,
                    "Expression",
                ))
            }
        }
    }

    @Test
    fun activePoseChangeWhilePosePacketIsQueuedPreventsOverwritingTheNewPose() = runTest {
        val snapshots = MutableStateFlow(snapshot())

        val (result, gateway) = changeSnapshotBeforeEmission(
            snapshots,
            requestChange = BcSelfStateChange.Pose("Kneel"),
        ) { current, _ ->
            current.copy(roomCharacters = listOf(current.roomCharacters.single().copy(
                activePose = listOf("BaseLower", "Yoked"),
            )))
        }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.CharacterStateChanged), result)
        assertTrue(gateway.poseCommands.isEmpty())
    }

    @Test
    fun ownerPoseRuleReceivedWhilePosePacketIsQueuedRejectsBeforeEmission() = runTest {
        val self = selfCharacter().copy(ownership = BcCharacterOwnership(name = "Owner", memberNumber = 20, stage = 1))
        val owner = selfCharacter(memberNumber = 20).copy(name = "Owner", displayName = "Owner", isSelf = false)
        val snapshots = MutableStateFlow(snapshot(self).copy(roomCharacters = listOf(self, owner), selfRuleLog = emptyList()))

        val (result, gateway) = changeSnapshotBeforeEmission(
            snapshots,
            requestChange = BcSelfStateChange.Pose("Kneel"),
        ) { current, _ -> current.copy(selfRuleLog = listOf(BcOwnerRuleLogRecord("BlockChangePose", "OwnerRule"))) }

        assertEquals(BcSelfStateResult.Rejected(BcSelfStateUnavailableReason.PoseBlockedByOwner), result)
        assertTrue(gateway.poseCommands.isEmpty())
        assertEquals(listOf(BcSelfStateEmitExpectation(self, ownerPresent = true)), gateway.poseExpectations)
    }

    private suspend fun TestScope.changeSnapshotBeforeEmission(
        snapshots: MutableStateFlow<BcSessionSnapshot>,
        requestChange: BcSelfStateChange = BcSelfStateChange.Expression("Eyes", "Closed"),
        pauseBeforeEmitNumber: Int = 1,
        change: (BcSessionSnapshot, RecordingGateway) -> BcSessionSnapshot,
    ): Pair<BcSelfStateResult, RecordingGateway> {
        val queued = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var validationNumber = 0
        val gateway = RecordingGateway().apply {
            beforeValidate = {
                validationNumber += 1
                if (validationNumber == pauseBeforeEmitNumber) {
                    queued.complete(Unit)
                    resume.await()
                }
            }
        }
        val request = request(snapshots.value, change = requestChange)
        val controller = BcCharacterInteractionsController(gateway, snapshots)
        val result = async { controller.updateSelfState(request) }
        queued.await()
        snapshots.value = change(snapshots.value, gateway)
        resume.complete(Unit)
        return result.await() to gateway
    }

    private suspend fun TestScope.changeSnapshotDuringFirstEyeSend(
        snapshots: MutableStateFlow<BcSessionSnapshot>,
        change: (BcSessionSnapshot) -> BcSessionSnapshot,
    ): Pair<BcSelfStateResult, RecordingGateway> {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = RecordingGateway().apply {
            onExpression = {
                if (expressionCommands.size == 1) {
                    started.complete(Unit)
                    resume.await()
                }
            }
        }
        val request = request(snapshots.value, change = BcSelfStateChange.Expression("Eyes", "Closed"))
        val controller = BcCharacterInteractionsController(gateway, snapshots)
        val result = async { controller.updateSelfState(request) }
        started.await()
        snapshots.value = change(snapshots.value)
        resume.complete(Unit)
        return result.await() to gateway
    }

    private fun BcSessionSnapshot.restrictSecondEye(): BcSessionSnapshot = copy(
        roomCharacters = roomCharacters.map { character ->
            character.copy(appearance = character.appearance.map { item ->
                if (item.group == "Eyes2") item.copy(propertyJson = "{\"AllowExpression\":[\"Open\"]}") else item
            })
        },
    )

    private fun request(
        snapshot: BcSessionSnapshot,
        change: BcSelfStateChange = BcSelfStateChange.Pose("Kneel"),
        rules: BcRuleCatalog = ruleCatalog(),
    ): BcSelfStateRequest = BcSelfStateRequest(
        targetMemberNumber = checkNotNull(snapshot.account).memberNumber,
        roomName = checkNotNull(snapshot.currentRoom).name,
        roomPresenceEpoch = snapshot.roomPresenceEpoch,
        accountGeneration = snapshot.relationships.accountGeneration,
        change = change,
        rules = rules,
    )

    private fun snapshot(self: BcCharacterSnapshot = selfCharacter()): BcSessionSnapshot = BcSessionSnapshot(
        state = BcSessionState.Ready,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        desiredOnline = true,
        bcVersion = "R129",
        account = BcAccount(self.memberNumber, self.name, "self"),
        currentRoom = room("Test room"),
        roomPresenceEpoch = 7,
        roomCharacters = listOf(self),
        relationships = BcRelationshipSnapshot(accountMemberNumber = self.memberNumber, accountGeneration = 3),
    )

    private fun room(name: String): BcRoom = BcRoom(
        name = BcRoomName(name),
        description = "",
        memberCount = 1,
        memberLimit = 10,
        isLocked = false,
        language = null,
        mapType = BcRoomMapType.ChatRoom,
    )

    private fun selfCharacter(memberNumber: Int = 10): BcCharacterSnapshot = BcCharacterSnapshot(
        id = memberNumber.toString(),
        memberNumber = memberNumber,
        name = "Self",
        displayName = "Self",
        isSelf = true,
        appearance = listOf(
            BcCharacterItem(
                group = "Eyes",
                name = "Eyes1",
                colorJson = "\"#55CCFF\"",
                difficulty = 2,
                propertyJson = "{\"Expression\":\"Open\",\"Custom\":{\"Value\":1}}",
                craftJson = "{\"Name\":\"Left eye\"}",
            ),
            BcCharacterItem(
                group = "Eyes2",
                name = "Eyes2",
                colorJson = "\"#BB55FF\"",
                difficulty = 4,
                propertyJson = "{\"Expression\":\"Dazed\",\"Custom\":[1,2]}",
                craftJson = "{\"Name\":\"Right eye\"}",
            ),
            BcCharacterItem("Mouth", "Mouth1", propertyJson = "{\"Expression\":\"Smile\"}"),
            BcCharacterItem("BodyUpper", "Normal", colorJson = "\"#EDD7BE\""),
        ),
    )

    private fun ruleCatalog(): BcRuleCatalog = BcRuleCatalog(
        version = "R129",
        groups = listOf(
            BcGroupRule("Eyes", allowExpression = listOf("Open", "Closed", "Dazed")),
            BcGroupRule("Eyes2", allowExpression = listOf("Open", "Closed", "Dazed")),
            BcGroupRule("Mouth", allowExpression = listOf("Smile", "Open")),
        ).associateBy { it.name },
        activities = emptyMap(),
        assets = emptyMap(),
        poses = listOf(
            BcPoseRule("BaseLower", category = "BodyLower", allowMenu = true),
            BcPoseRule("Kneel", category = "BodyLower", allowMenu = true),
            BcPoseRule("BaseUpper", category = "BodyUpper", allowMenu = true),
            BcPoseRule("Yoked", category = "BodyUpper", allowMenu = true),
        ).associateBy { it.name },
    )

    private class RecordingGateway : InteractionGateway {
        val poseCommands = mutableListOf<BcPoseUpdateCommand>()
        val expressionCommands = mutableListOf<BcExpressionUpdateCommand>()
        val poseExpectations = mutableListOf<BcSelfStateEmitExpectation>()
        val expressionExpectations = mutableListOf<BcSelfStateEmitExpectation>()
        var beforeValidate: suspend () -> Unit = {}
        var onPose: suspend (BcPoseUpdateCommand) -> Unit = {}
        var onExpression: suspend (BcExpressionUpdateCommand) -> Unit = {}

        override suspend fun queryAllowItem(memberNumber: Int): Boolean = error("Unexpected item permission query")

        override suspend fun sendActivity(command: BcActivityCommand) = error("Unexpected activity send")

        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) = error("Unexpected item update")

        override suspend fun updatePose(command: BcPoseUpdateCommand) {
            poseCommands += command
            onPose(command)
        }

        override suspend fun updatePose(
            command: BcPoseUpdateCommand,
            expectedSelfState: BcSelfStateEmitExpectation,
            validateBeforeEmit: () -> Unit,
        ) {
            poseExpectations += expectedSelfState
            beforeValidate()
            validateBeforeEmit()
            updatePose(command)
        }

        override suspend fun updateExpression(command: BcExpressionUpdateCommand) {
            expressionCommands += command
            onExpression(command)
        }

        override suspend fun updateExpression(
            command: BcExpressionUpdateCommand,
            expectedSelfState: BcSelfStateEmitExpectation,
            validateBeforeEmit: () -> Unit,
        ) {
            expressionExpectations += expectedSelfState
            beforeValidate()
            validateBeforeEmit()
            updateExpression(command)
        }
    }
}
