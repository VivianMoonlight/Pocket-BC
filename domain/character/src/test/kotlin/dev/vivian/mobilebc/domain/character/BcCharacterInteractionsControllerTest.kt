package dev.vivian.mobilebc.domain.character

import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterInteractionResult
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcEchoCompatibilityCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawOrderCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawRelation
import dev.vivian.mobilebc.core.model.BcEchoLeashRole
import dev.vivian.mobilebc.core.model.BcInboundActivityCommand
import dev.vivian.mobilebc.core.model.BcInboundActivityResult
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import dev.vivian.mobilebc.domain.bcpermission.BcInteractionCandidate
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionContext
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRequest
import dev.vivian.mobilebc.domain.bcpermission.BcPermissionRoomContext
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcGroupRule
import dev.vivian.mobilebc.domain.bcrules.BcActivityRule
import dev.vivian.mobilebc.domain.bcrules.BcAssetKey
import dev.vivian.mobilebc.domain.bcrules.BcAssetRule
import dev.vivian.mobilebc.domain.bcrules.BcActivityEffect
import dev.vivian.mobilebc.domain.bcrules.BcEchoActivityCatalog
import dev.vivian.mobilebc.core.model.BcConversation
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomMapType
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.core.model.BcRoomSearchFilter
import dev.vivian.mobilebc.core.model.BcRoomSummary
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.InteractionGateway
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException

class BcCharacterInteractionsControllerTest {
    @Test fun queuedKeyholderChangesCannotOverwriteNewKeysRelationsOrKeyDeposit() = runTest {
        for (change in 0..2) {
            val actor = character(10,"Self",isSelf=true).copy(whiteList=listOf(40))
            val item = BcCharacterItem("ItemArms","HempRope",propertyJson="""{"LockedBy":"HighSecurityPadlock","LockMemberNumber":30,"MemberNumberListKeys":"10,30","Effect":["Lock"]}""")
            val target = character(1001,"Alice").copy(appearance=listOf(item))
            val wardrobe = dev.vivian.mobilebc.core.model.BcWardrobeState(10,"login",1,null,null,emptyList(),false,false)
            val snapshot = MutableStateFlow(roomSnapshot(listOf(actor,target)).copy(selfRuleLogSessionId="login",selfRuleLog=emptyList(),wardrobeState=wardrobe))
            val gateway = FakeSessionGateway(true).apply { beforeItemEmit = {
                snapshot.value = when (change) {
                    0 -> snapshot.value.copy(roomCharacters=listOf(actor,target.copy(appearance=listOf(item.copy(propertyJson="""{"LockedBy":"HighSecurityPadlock","LockMemberNumber":30,"MemberNumberListKeys":"30","Effect":["Lock"]}""")))))
                    1 -> snapshot.value.copy(roomCharacters=listOf(actor.copy(whiteList=listOf(50)),target))
                    else -> snapshot.value.copy(selfRuleLog=listOf(dev.vivian.mobilebc.core.model.BcOwnerRuleLogRecord("KeyDeposit","Cell",null)))
                }
            } }
            val base = wearRequest(actor,target)
            val assets = listOf(BcAssetRule("ItemArms","HempRope",allowLock=true), BcAssetRule("ItemMisc","HighSecurityPadlock",isLock=true,exclusiveUnlock=true))
            val request = base.copy(context=base.context.copy(rules=base.context.rules.copy(assets=base.context.rules.assets+assets.associateBy { BcAssetKey(it.group,it.name) })),
                candidate=BcInteractionCandidate.ConfigureLock(1001,item.group,item.name,"HighSecurityPadlock",
                    dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration.UpdateKeyholders("10,20",appendWhitelist=true)))
            assertTrue("Queued change $change",controller(gateway,snapshot).sendInteraction(request) is BcCharacterInteractionResult.Failed)
            assertEquals(null,gateway.updatedItemCommand)
        }
    }

    @Test fun queuedTimerEditRechecksExpiryAndTheTargetsMaximumSetting() = runTest {
        for (advanceClock in listOf(true, false)) {
            val actor = character(10, "Self", isSelf = true)
            val item = BcCharacterItem("ItemArms", "LeatherCuffs", propertyJson = """{"LockedBy":"TimerPasswordPadlock","LockMemberNumber":10,"RemoveTimer":10000}""")
            val target = character(1001, "Alice").copy(appearance = listOf(item))
            val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)).copy(serverTimeAnchor =
                dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(1000, System.nanoTime())))
            val gateway = FakeSessionGateway(true).apply { beforeItemEmit = {
                snapshot.value = if (advanceClock) snapshot.value.copy(serverTimeAnchor = dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(20000, System.nanoTime()))
                else snapshot.value.copy(roomCharacters = listOf(actor, target.copy(onlineSharedSettingsJson = """{"LockTimerLimit":"LockTimerLimitDay"}""")))
            } }
            val base = wearRequest(actor, target)
            val assets = listOf(BcAssetRule("ItemArms", "LeatherCuffs", allowLock = true), BcAssetRule("ItemMisc", "TimerPasswordPadlock", isLock = true, removeTimerSeconds = 300, maxTimerSeconds = 3600))
            val request = base.copy(context = base.context.copy(rules = base.context.rules.copy(assets = base.context.rules.assets + assets.associateBy { BcAssetKey(it.group, it.name) })),
                candidate = BcInteractionCandidate.ChangeLockTimer(1001, item.group, item.name, "TimerPasswordPadlock", dev.vivian.mobilebc.domain.bcitemproperty.BcLockTimerChange.Adjust(300)))
            assertTrue(controller(gateway, snapshot).sendInteraction(request) is BcCharacterInteractionResult.Failed)
            assertEquals(null, gateway.updatedItemCommand)
        }
    }

    @Test fun queuedPasswordInitializationCannotOverwriteAnAlreadyConfirmedLock() = runTest {
        val actor = character(10, "Self", isSelf = true)
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"PasswordPadlock","Password":"PASSWORD","LockSet":false,"LockMemberNumber":10,"Effect":["Lock"]}""")
        val target = character(1001, "Alice").copy(appearance = listOf(item))
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val gateway = FakeSessionGateway(true).apply { beforeItemEmit = {
            snapshot.value = snapshot.value.copy(roomCharacters = listOf(actor, target.copy(appearance = listOf(item.copy(
                propertyJson = """{"LockedBy":"PasswordPadlock","Password":"CHANGED","LockSet":true,"LockMemberNumber":10,"Effect":["Lock"]}""")))))
        } }
        val base = wearRequest(actor, target)
        val lock = BcAssetRule("ItemMisc", "PasswordPadlock", isLock = true)
        val request = base.copy(context = base.context.copy(rules = base.context.rules.copy(assets = base.context.rules.assets + mapOf(
            BcAssetKey(lock.group, lock.name) to lock,
            BcAssetKey("ItemArms", "HempRope") to base.context.rules.asset("ItemArms", "HempRope")!!.copy(allowLock = true)))),
            candidate = BcInteractionCandidate.ConfigureLock(1001, "ItemArms", "HempRope", lock.name,
                dev.vivian.mobilebc.domain.bcitemproperty.BcLockConfiguration.InitializePassword(dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential("SECRET"), "提示")))
        assertTrue(controller(gateway, snapshot).sendInteraction(request) is BcCharacterInteractionResult.Failed)
        assertEquals(null, gateway.updatedItemCommand)
    }

    @Test fun queuedCredentialUnlockCannotUnlockAChangedPassword() = runTest {
        val actor = character(10, "Self", isSelf = true)
        val item = BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"PasswordPadlock","Password":"SECRET","Effect":["Lock"]}""")
        val target = character(1001, "Alice").copy(appearance = listOf(item))
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val gateway = FakeSessionGateway(true).apply {
            beforeItemEmit = {
                snapshot.value = snapshot.value.copy(roomCharacters = listOf(actor, target.copy(appearance = listOf(
                    item.copy(propertyJson = """{"LockedBy":"PasswordPadlock","Password":"CHANGED","Effect":["Lock"]}""")))))
            }
        }
        val base = wearRequest(actor, target)
        val lock = BcAssetRule("ItemMisc", "PasswordPadlock", isLock = true)
        val request = base.copy(context = base.context.copy(rules = base.context.rules.copy(assets = base.context.rules.assets +
            (BcAssetKey(lock.group, lock.name) to lock))), candidate = BcInteractionCandidate.UnlockItem(1001, "ItemArms", "HempRope", lock.name,
            dev.vivian.mobilebc.domain.bcitemproperty.BcLockCredential("SECRET")))
        val result = controller(gateway, snapshot).sendInteraction(request)
        assertTrue(result is BcCharacterInteractionResult.Failed)
        assertEquals(null, gateway.updatedItemCommand)
    }

    @Test
    fun queuedItemUpdateRechecksLockAndActorPermissionsBeforeEmission() = runTest {
        for (changeTarget in listOf(true, false)) {
            val actor = character(10, "Self", isSelf = true)
            val target = character(1001, "Alice")
            val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
            val gateway = FakeSessionGateway(true).apply {
                beforeItemEmit = {
                    snapshot.value = snapshot.value.copy(roomCharacters = if (changeTarget) {
                        listOf(actor, target.copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope",
                            propertyJson = """{"LockedBy":"PasswordPadlock"}"""))))
                    } else {
                        listOf(actor.copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope",
                            propertyJson = """{"Effect":["Block"]}"""))), target)
                    })
                }
            }
            val result = controller(gateway, snapshot).sendInteraction(wearRequest(actor, target))
            assertTrue(result is BcCharacterInteractionResult.Failed)
            assertEquals(null, gateway.updatedItemCommand)
        }
    }

    @Test
    fun staleRemovalCannotRemoveAReplacementItem() = runTest {
        val actor = character(10, "Self", isSelf = true)
        val selectedTarget = character(1001, "Alice").copy(appearance = listOf(BcCharacterItem("ItemArms", "OldRope")))
        val target = selectedTarget.copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope")))
        val gateway = FakeSessionGateway(true)
        val request = wearRequest(actor, selectedTarget).copy(candidate = BcInteractionCandidate.RemoveItem(1001, "ItemArms"))
        val result = controller(gateway, listOf(actor, target)).sendInteraction(request)
        assertTrue(result is BcCharacterInteractionResult.Rejected)
        assertEquals(null, gateway.updatedItemCommand)
        assertEquals(null, gateway.allowItemMemberNumber)
    }

    @Test
    fun sendInteractionRejectsWhenTargetIsNotInCurrentRoster() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 2002, name = "Missing")
        val controller = controller(
            gateway = FakeSessionGateway(allowItem = true),
            characters = listOf(actor),
        )

        val result = controller.sendInteraction(
            activityRequest(actor = actor, target = target),
        )

        assertEquals(
            BcCharacterInteractionResult.Rejected("Target character is not in the current room."),
            result,
        )
    }

    @Test
    fun sendInteractionDelegatesPermissionApprovedActivityToGateway() = runTest {
        val gateway = FakeSessionGateway(allowItem = true)
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val controller = controller(gateway, listOf(actor, target))

        val result = controller.sendInteraction(
            activityRequest(actor = actor, target = target),
        )

        assertEquals(BcCharacterInteractionResult.Sent, result)
        val command = checkNotNull(gateway.sentActivityCommand)
        assertEquals(1001, command.targetMemberNumber)
        assertEquals("ItemArms", command.targetGroup)
        assertEquals("Inspect", command.activityName)
        assertTrue(command.clientMsgId?.isNotBlank() == true)
    }

    @Test
    fun sendInteractionPassesTheRequestedClientMsgIdToGateway() = runTest {
        val gateway = FakeSessionGateway(allowItem = true)
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val controller = controller(
            gateway = gateway,
            characters = listOf(actor, target),
        )

        val result = controller.sendInteraction(
            request = activityRequest(actor = actor, target = target),
            activityClientMsgId = "activity-client-1",
        )

        assertEquals(BcCharacterInteractionResult.Sent, result)
        assertEquals("activity-client-1", gateway.sentActivityCommand?.clientMsgId)
    }

    @Test
    fun selfActivityExecutesDeclaredEffectsAfterActivityPacket() = runTest {
        val gateway = FakeSessionGateway(allowItem = true)
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val rules = BcRuleCatalog(
            version = "test",
            groups = mapOf("ItemMouth" to BcGroupRule("ItemMouth")),
            activities = mapOf(
                "Open" to BcActivityRule(
                    name = "Open",
                    selfTargetGroups = setOf("ItemMouth"),
                    effects = listOf(
                        BcActivityEffect.Expression("Mouth", "Open"),
                        BcActivityEffect.Pose(listOf("Kneel")),
                    ),
                ),
            ),
            assets = emptyMap(),
        )
        val controller = controller(gateway, listOf(actor))

        val result = controller.sendInteraction(
            BcPermissionRequest(
                context = BcPermissionContext(
                    actor = actor,
                    target = actor,
                    room = BcPermissionRoomContext(memberNumbers = setOf(actor.memberNumber)),
                    rules = rules,
                ),
                candidate = BcInteractionCandidate.Activity(
                    targetMemberNumber = actor.memberNumber,
                    group = "ItemMouth",
                    activityName = "Open",
                ),
            ),
        )

        assertEquals(BcCharacterInteractionResult.Sent, result)
        assertEquals(BcExpressionUpdateCommand("Mouth", "Open"), gateway.updatedExpressionCommand)
        assertEquals(BcPoseUpdateCommand(listOf("Kneel")), gateway.updatedPoseCommand)
    }

    @Test
    fun echoCuddlePublishesCompatibilityThenActivityItemAndDrawOrder() = runTest {
        data class Case(
            val activityName: String,
            val actorItemName: String,
            val targetItemName: String?,
            val relation: BcEchoDrawRelation,
            val leash: BcEchoLeashRole,
        )

        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val baseTarget = character(memberNumber = 1001, name = "Alice")
        val rules = echoPairRuleCatalog()
        val cases = listOf(
            Case("钻进怀里", "贴贴", null, BcEchoDrawRelation.PreviousCharacter, BcEchoLeashRole.Lead),
            Case("抱入怀中", "贴贴", null, BcEchoDrawRelation.NextCharacter, BcEchoLeashRole.Follow),
            Case("扛起", "扛起来的麻袋", "BurlapSack", BcEchoDrawRelation.PreviousCharacter, BcEchoLeashRole.Lead),
            Case("手推车", "抓住推车", "Trolley", BcEchoDrawRelation.NextCharacter, BcEchoLeashRole.Lead),
        )

        cases.forEach { case ->
            val target = baseTarget.copy(
                appearance = case.targetItemName?.let { listOf(BcCharacterItem("ItemDevices", it)) }.orEmpty(),
            )
            val gateway = FakeSessionGateway(allowItem = true)
            val controller = controller(gateway, listOf(actor, target))
            val result = controller.sendInteraction(
                BcPermissionRequest(
                    context = BcPermissionContext(
                        actor = actor,
                        target = target,
                        room = BcPermissionRoomContext(
                            roomName = "Test Room",
                            memberNumbers = setOf(actor.memberNumber, target.memberNumber),
                        ),
                        rules = rules,
                    ),
                    candidate = BcInteractionCandidate.Activity(
                        targetMemberNumber = target.memberNumber,
                        group = "ItemTorso",
                        activityName = case.activityName,
                    ),
                ),
            )

            assertEquals(BcCharacterInteractionResult.Sent, result)
            assertEquals(listOf("compatibility", "activity", "item", "drawOrder"), gateway.calls)
            assertEquals(
                BcEchoCompatibilityCommand(
                    modName = "服装拓展",
                    version = "1.129.2-mobile-bc.1",
                ),
                gateway.echoCompatibilityCommand,
            )
            assertEquals(case.activityName, gateway.sentActivityCommand?.activityName)
            assertEquals(
                BcCharacterItemUpdateCommand(
                    targetMemberNumber = actor.memberNumber,
                    group = "ItemMisc",
                    name = case.actorItemName,
                ),
                gateway.updatedItemCommand,
            )
            assertEquals(
                BcEchoDrawOrderCommand(
                    otherMemberNumber = target.memberNumber,
                    relation = case.relation,
                    associatedAssetGroup = "ItemMisc",
                    associatedAssetName = case.actorItemName,
                    leash = case.leash,
                ),
                gateway.echoDrawOrderCommand,
            )
        }
    }

    @Test
    fun inboundEchoCuddleEquipsLocalTargetAndPublishesInversePairing() = runTest {
        data class Case(
            val activityName: String,
            val relation: BcEchoDrawRelation,
            val leash: BcEchoLeashRole,
        )

        val actor = character(memberNumber = 1001, name = "Alice")
        val target = character(memberNumber = 10, name = "Self", isSelf = true)
        val cases = listOf(
            Case("钻进怀里", BcEchoDrawRelation.NextCharacter, BcEchoLeashRole.Follow),
            Case("抱入怀中", BcEchoDrawRelation.PreviousCharacter, BcEchoLeashRole.Lead),
        )

        cases.forEachIndexed { index, case ->
            val gateway = FakeSessionGateway(allowItem = true)
            val controller = controller(gateway, listOf(actor, target))

            val result = controller.handleInboundActivity(
                BcInboundActivityCommand(
                    sourceMemberNumber = actor.memberNumber,
                    targetMemberNumber = target.memberNumber,
                    targetGroup = "ItemTorso",
                    activityName = case.activityName,
                    clientMsgId = "inbound-cuddle-$index",
                ),
            )

            assertEquals(BcInboundActivityResult.Applied, result)
            assertEquals(listOf("compatibility", "item", "drawOrder"), gateway.calls)
            assertEquals(
                BcCharacterItemUpdateCommand(
                    targetMemberNumber = target.memberNumber,
                    group = "ItemMisc",
                    name = "贴贴",
                ),
                gateway.updatedItemCommand,
            )
            assertEquals(
                BcEchoDrawOrderCommand(
                    otherMemberNumber = actor.memberNumber,
                    relation = case.relation,
                    associatedAssetGroup = "ItemMisc",
                    associatedAssetName = "贴贴",
                    leash = case.leash,
                ),
                gateway.echoDrawOrderCommand,
            )
        }
    }

    @Test
    fun inboundEchoCuddleHonorsWireTargetAndLocalAllowItem() = runTest {
        val actor = character(memberNumber = 1001, name = "Alice")
        val restrictedTarget = character(memberNumber = 10, name = "Self", isSelf = true).copy(
            allowedInteractions = 5,
        )
        val command = BcInboundActivityCommand(
            sourceMemberNumber = actor.memberNumber,
            targetMemberNumber = restrictedTarget.memberNumber,
            targetGroup = "ItemTorso",
            activityName = "钻进怀里",
        )

        val restrictedGateway = FakeSessionGateway(allowItem = true)
        val restrictedResult = controller(restrictedGateway, listOf(actor, restrictedTarget))
            .handleInboundActivity(command)
        val invalidGroupGateway = FakeSessionGateway(allowItem = true)
        val invalidGroupResult = controller(
            invalidGroupGateway,
            listOf(actor, restrictedTarget.copy(allowedInteractions = null)),
        ).handleInboundActivity(command.copy(targetGroup = "ItemHead"))

        assertTrue(restrictedResult is BcInboundActivityResult.Rejected)
        assertTrue(invalidGroupResult is BcInboundActivityResult.Rejected)
        assertTrue(restrictedGateway.calls.isEmpty())
        assertTrue(invalidGroupGateway.calls.isEmpty())
    }

    @Test
    fun inboundEchoActivityIgnoresOwnServerEcho() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val gateway = FakeSessionGateway(allowItem = true)
        val controller = controller(gateway, listOf(actor, target))

        val result = controller.handleInboundActivity(
            BcInboundActivityCommand(
                sourceMemberNumber = actor.memberNumber,
                targetMemberNumber = target.memberNumber,
                targetGroup = "ItemTorso",
                activityName = "钻进怀里",
                clientMsgId = "own-echo",
            ),
        )

        assertEquals(BcInboundActivityResult.Ignored, result)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun echoPairingReusesAlreadyWornActorItem() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true).copy(
            appearance = listOf(BcCharacterItem("ItemMisc", "扛起来的麻袋")),
        )
        val target = character(memberNumber = 1001, name = "Alice").copy(
            appearance = listOf(BcCharacterItem("ItemDevices", "BurlapSack")),
        )
        val gateway = FakeSessionGateway(allowItem = true)
        val controller = controller(gateway, listOf(actor, target))

        val result = controller.sendInteraction(
            BcPermissionRequest(
                context = BcPermissionContext(
                    actor = actor,
                    target = target,
                    room = BcPermissionRoomContext(memberNumbers = setOf(actor.memberNumber, target.memberNumber)),
                    rules = echoPairRuleCatalog(),
                ),
                candidate = BcInteractionCandidate.Activity(
                    targetMemberNumber = target.memberNumber,
                    group = "ItemTorso",
                    activityName = "扛起",
                ),
            ),
        )

        assertEquals(BcCharacterInteractionResult.Sent, result)
        assertEquals(listOf("compatibility", "activity", "drawOrder"), gateway.calls)
        assertEquals(null, gateway.updatedItemCommand)
    }

    @Test
    fun sendInteractionPropagatesGatewayCancellation() = runTest {
        val gateway = FakeSessionGateway(allowItem = true).apply { cancelActivitySend = true }
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val controller = controller(gateway, listOf(actor, target))

        var cancellation: CancellationException? = null
        try {
            controller.sendInteraction(activityRequest(actor = actor, target = target))
        } catch (caught: CancellationException) {
            cancellation = caught
        }

        assertNotNull(cancellation)
    }

    @Test
    fun sendInteractionReturnsTheGatewayFailure() = runTest {
        val gateway = FakeSessionGateway(allowItem = true).apply {
            afterActivity = { throw IllegalStateException("Activity transport failed") }
        }
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")

        val result = controller(gateway, listOf(actor, target)).sendInteraction(activityRequest(actor, target))

        assertEquals(BcCharacterInteractionResult.Failed("Activity transport failed"), result)
        assertEquals(listOf("activity"), gateway.calls)
    }

    @Test
    fun sendInteractionPerformsServerCheckAndSendsItemUpdate() = runTest {
        val gateway = FakeSessionGateway(allowItem = true)
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val controller = controller(gateway, listOf(actor, target))

        val result = controller.sendInteraction(
            BcPermissionRequest(
                context = BcPermissionContext(
                    actor = actor,
                    target = target,
                    room = BcPermissionRoomContext(
                        roomName = "Test Room",
                        memberNumbers = setOf(actor.memberNumber, target.memberNumber),
                    ),
                    rules = ruleCatalog(),
                    serverAllowItem = null,
                ),
                candidate = BcInteractionCandidate.WearItem(
                    targetMemberNumber = target.memberNumber,
                    group = "ItemArms",
                    itemName = "HempRope",
                ),
            ),
        )

        assertEquals(BcCharacterInteractionResult.Sent, result)
        assertEquals(target.memberNumber, gateway.allowItemMemberNumber)
        assertEquals(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = target.memberNumber,
                group = "ItemArms",
                name = "HempRope",
                publishAction = BcItemActionMessage(
                    content = "ActionUse",
                    nextAssetName = "HempRope",
                ),
            ),
            gateway.updatedItemCommand,
        )
    }

    @Test
    fun sendInteractionRejectsWhenServerAllowItemDeniesCandidate() = runTest {
        val gateway = FakeSessionGateway(allowItem = false)
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val controller = controller(gateway, listOf(actor, target))

        val result = controller.sendInteraction(
            BcPermissionRequest(
                context = BcPermissionContext(
                    actor = actor,
                    target = target,
                    room = BcPermissionRoomContext(
                        roomName = "Test Room",
                        memberNumbers = setOf(actor.memberNumber, target.memberNumber),
                    ),
                    rules = ruleCatalog(),
                    serverAllowItem = null,
                ),
                candidate = BcInteractionCandidate.WearItem(
                    targetMemberNumber = target.memberNumber,
                    group = "ItemArms",
                    itemName = "HempRope",
                ),
            ),
        )

        assertEquals(
            BcCharacterInteractionResult.Rejected("The server AllowItem check denied this item update."),
            result,
        )
        assertEquals(target.memberNumber, gateway.allowItemMemberNumber)
        assertEquals(null, gateway.updatedItemCommand)
    }

    @Test
    fun pendingAllowItemRejectsRoomAccountAndConnectionChanges() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val initial = roomSnapshot(listOf(actor, target))
        val changedSessions = listOf(
            initial.copy(currentRoom = null, roomCharacters = emptyList()),
            initial.copy(roomPresenceEpoch = initial.roomPresenceEpoch + 1),
            initial.copy(currentRoom = room("Other Room"), roomPresenceEpoch = initial.roomPresenceEpoch + 1),
            initial.copy(
                account = checkNotNull(initial.account).copy(memberNumber = 20),
                roomCharacters = listOf(character(20, "New Self", isSelf = true), target),
            ),
            initial.copy(relationships = initial.relationships.copy(accountGeneration = 1)),
            initial.copy(connectionHealth = BcConnectionHealth.Stale),
        )

        changedSessions.forEachIndexed { index, changed ->
            val snapshot = MutableStateFlow(initial)
            val (result, gateway) = interactionWhileAllowItemIsPending(
                snapshot = snapshot,
                request = wearRequest(actor, target),
            ) { snapshot.value = changed }

            assertTrue("Session transition $index must reject the pending action", result is BcCharacterInteractionResult.Rejected)
            assertEquals(target.memberNumber, gateway.allowItemMemberNumber)
            assertTrue(gateway.calls.isEmpty())
        }
    }

    @Test
    fun pendingAllowItemReevaluatesLatestRestraintsLocksRelationshipsAndItemPermissions() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice").copy(
            allowedInteractions = 5,
            ownerMemberNumber = actor.memberNumber,
        )
        val changedCharacters = listOf(
            listOf(
                actor.copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"Effect":["Block","BlockHands","BlockMouth","BlockFeet"]}"""))),
                target,
            ),
            listOf(
                actor,
                target.copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"LockedBy":"PasswordPadlock"}"""))),
            ),
            listOf(actor, target.copy(ownerMemberNumber = null)),
            listOf(actor, target.copy(blockItemsJson = """{"ItemArms":{"HempRope":[""]}}""")),
        )

        changedCharacters.forEachIndexed { index, characters ->
            val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
            val (result, gateway) = interactionWhileAllowItemIsPending(snapshot, wearRequest(actor, target)) {
                snapshot.value = snapshot.value.copy(roomCharacters = characters)
            }

            assertTrue("Permission transition $index must override the old server result", result is BcCharacterInteractionResult.Rejected)
            assertEquals(null, gateway.updatedItemCommand)
        }
    }

    @Test
    fun sendInteractionDiscardsUiPermissionResultAndUsesLatestActorState() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val blockedActor = actor.copy(
            appearance = listOf(BcCharacterItem("ItemArms", "HempRope", propertyJson = """{"Effect":["Block","BlockHands","BlockMouth","BlockFeet"]}""")),
        )
        val gateway = FakeSessionGateway(allowItem = true)
        val request = wearRequest(actor, target).let { it.copy(context = it.context.copy(serverAllowItem = true)) }

        val result = controller(gateway, listOf(blockedActor, target)).sendInteraction(request)

        assertTrue(result is BcCharacterInteractionResult.Rejected)
        assertEquals(null, gateway.allowItemMemberNumber)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun pendingAllowItemRejectsAnItemWornAfterTheSelection() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val request = wearRequest(actor, target).let { it.copy(context = it.context.copy(serverAllowItem = true)) }

        val (result, gateway) = interactionWhileAllowItemIsPending(snapshot, request) {
            snapshot.value = snapshot.value.copy(
                currentRoom = checkNotNull(snapshot.value.currentRoom).copy(description = "Updated description"),
                roomCharacters = listOf(actor, target.copy(appearance = listOf(BcCharacterItem("ItemArms", "HempRope")))),
            )
        }

        assertTrue(result is BcCharacterInteractionResult.Rejected)
        assertEquals(target.memberNumber, gateway.allowItemMemberNumber)
        assertEquals(null, gateway.updatedItemCommand)
    }

    @Test
    fun pendingAllowItemStillAllowsUnrelatedRoomDescriptionChanges() = runTest {
        val actor = character(10, "Self", isSelf = true)
        val target = character(1001, "Alice")
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val (result, gateway) = interactionWhileAllowItemIsPending(snapshot, wearRequest(actor, target)) {
            snapshot.value = snapshot.value.copy(currentRoom = snapshot.value.currentRoom!!.copy(description = "Updated"))
        }
        assertEquals(BcCharacterInteractionResult.Sent, result)
        assertEquals("HempRope", gateway.updatedItemCommand?.name)
    }

    @Test
    fun outboundEchoStopsWhenRoomChangesDuringCompatibilityPublish() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = FakeSessionGateway(allowItem = true).apply {
            afterCompatibility = { started.complete(Unit); resume.await() }
        }
        val request = activityRequest(actor, target).let {
            it.copy(
                context = it.context.copy(rules = echoPairRuleCatalog()),
                candidate = BcInteractionCandidate.Activity(target.memberNumber, "ItemTorso", "钻进怀里"),
            )
        }
        val controller = controller(gateway, snapshot)
        val result = async { controller.sendInteraction(request) }
        started.await()
        snapshot.value = snapshot.value.copy(roomPresenceEpoch = snapshot.value.roomPresenceEpoch + 1)
        resume.complete(Unit)

        assertTrue(result.await() is BcCharacterInteractionResult.Rejected)
        assertEquals(listOf("compatibility"), gateway.calls)
    }

    @Test
    fun outboundEchoStopsEffectsWhenAccountChangesAfterActivitySend() = runTest {
        val actor = character(memberNumber = 10, name = "Self", isSelf = true)
        val target = character(memberNumber = 1001, name = "Alice")
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = FakeSessionGateway(allowItem = true).apply {
            afterActivity = { started.complete(Unit); resume.await() }
        }
        val request = activityRequest(actor, target).let {
            it.copy(
                context = it.context.copy(rules = echoPairRuleCatalog()),
                candidate = BcInteractionCandidate.Activity(target.memberNumber, "ItemTorso", "钻进怀里"),
            )
        }
        val controller = controller(gateway, snapshot)
        val result = async { controller.sendInteraction(request) }
        started.await()
        snapshot.value = roomSnapshot(listOf(character(20, "New Self", isSelf = true), target))
        resume.complete(Unit)

        assertTrue(result.await() is BcCharacterInteractionResult.Failed)
        assertEquals(listOf("compatibility", "activity"), gateway.calls)
        assertEquals(null, gateway.updatedItemCommand)
        assertEquals(null, gateway.echoDrawOrderCommand)
    }

    @Test
    fun inboundEchoStopsDrawOrderWhenRoomChangesDuringItemUpdate() = runTest {
        val actor = character(memberNumber = 1001, name = "Alice")
        val target = character(memberNumber = 10, name = "Self", isSelf = true)
        val snapshot = MutableStateFlow(roomSnapshot(listOf(actor, target)))
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = FakeSessionGateway(allowItem = true).apply {
            afterItemUpdate = { started.complete(Unit); resume.await() }
        }
        val controller = controller(gateway, snapshot)
        val result = async {
            controller.handleInboundActivity(BcInboundActivityCommand(actor.memberNumber, target.memberNumber, "ItemTorso", "钻进怀里"))
        }
        started.await()
        snapshot.value = snapshot.value.copy(roomPresenceEpoch = snapshot.value.roomPresenceEpoch + 1)
        resume.complete(Unit)

        assertTrue(result.await() is BcInboundActivityResult.Failed)
        assertEquals(listOf("compatibility", "item"), gateway.calls)
        assertEquals(null, gateway.echoDrawOrderCommand)
    }

    @Test
    fun sessionSnapshotUpdatesAndClearsCharacterRoster() = runTest {
        val first = character(memberNumber = 1001, name = "Alice")
        val updated = character(memberNumber = 1001, name = "Bee")
        val snapshot = MutableStateFlow(roomSnapshot(listOf(first)))
        assertEquals(
            BcCharacterRosterState.InRoom(BcRoomName("Test Room"), listOf(first)),
            snapshot.value.toCharacterRosterState(),
        )

        snapshot.value = roomSnapshot(listOf(updated))

        assertEquals(
            BcCharacterRosterState.InRoom(BcRoomName("Test Room"), listOf(updated)),
            snapshot.value.toCharacterRosterState(),
        )

        snapshot.value = BcSessionSnapshot()

        assertEquals(BcCharacterRosterState.NotInRoom, snapshot.value.toCharacterRosterState())
    }

    private fun TestScope.controller(
        gateway: InteractionGateway,
        characters: List<BcCharacterSnapshot>,
    ): BcCharacterInteractionsController = controller(
        gateway = gateway,
        snapshot = MutableStateFlow(roomSnapshot(characters)),
    )

    private fun TestScope.controller(
        gateway: InteractionGateway,
        snapshot: MutableStateFlow<BcSessionSnapshot>,
    ): BcCharacterInteractionsController = BcCharacterInteractionsController(
        gateway = gateway,
        sessionSnapshot = snapshot,
    )

    private fun roomSnapshot(characters: List<BcCharacterSnapshot>): BcSessionSnapshot =
        BcSessionSnapshot(
            state = BcSessionState.Ready,
            connectionHealth = BcConnectionHealth.VerifiedOnline,
            desiredOnline = true,
            account = characters.firstOrNull(BcCharacterSnapshot::isSelf)?.let {
                BcAccount(it.memberNumber, it.displayName, it.accountName ?: it.name)
            },
            currentRoom = room("Test Room"),
            roomCharacters = characters,
        )

    private fun room(name: String): BcRoom =
        BcRoom(
            name = BcRoomName(name),
            description = "",
            memberCount = 1,
            memberLimit = 10,
            isLocked = false,
            language = null,
            mapType = BcRoomMapType.ChatRoom,
        )

    private fun character(
        memberNumber: Int,
        name: String,
        isSelf: Boolean = false,
    ): BcCharacterSnapshot =
        BcCharacterSnapshot(
            id = memberNumber.toString(),
            memberNumber = memberNumber,
            name = name,
            displayName = name,
            isSelf = isSelf,
        )

    private fun ruleCatalog(): BcRuleCatalog = BcRuleCatalog(
        version = "test",
        groups = mapOf("ItemArms" to BcGroupRule(name = "ItemArms")),
        activities = mapOf(
            "Inspect" to BcActivityRule(
                name = "Inspect",
                targetGroups = setOf("ItemArms"),
            ),
        ),
        assets = mapOf(
            BcAssetKey("ItemArms", "HempRope") to BcAssetRule(
                group = "ItemArms",
                name = "HempRope",
            ),
        ),
    )

    private fun echoPairRuleCatalog(): BcRuleCatalog = BcEchoActivityCatalog.mergeInto(
        BcRuleCatalog(
            version = "test",
            groups = mapOf("ItemDevices" to BcGroupRule("ItemDevices")),
            activities = emptyMap(),
            assets = listOf(
                BcAssetRule(group = "ItemDevices", name = "BurlapSack"),
                BcAssetRule(group = "ItemDevices", name = "Trolley", effects = setOf("Leash")),
            ).associateBy { BcAssetKey(it.group, it.name) },
        ),
    )

    private fun activityRequest(
        actor: BcCharacterSnapshot,
        target: BcCharacterSnapshot,
    ): BcPermissionRequest = BcPermissionRequest(
        context = BcPermissionContext(
            actor = actor,
            target = target,
            room = BcPermissionRoomContext(
                roomName = "Test Room",
                memberNumbers = setOf(actor.memberNumber, target.memberNumber),
            ),
            rules = ruleCatalog(),
        ),
        candidate = BcInteractionCandidate.Activity(
            targetMemberNumber = target.memberNumber,
            group = "ItemArms",
            activityName = "Inspect",
        ),
    )

    private fun wearRequest(actor: BcCharacterSnapshot, target: BcCharacterSnapshot): BcPermissionRequest =
        activityRequest(actor, target).copy(
            candidate = BcInteractionCandidate.WearItem(target.memberNumber, "ItemArms", "HempRope"),
        )

    private suspend fun TestScope.interactionWhileAllowItemIsPending(
        snapshot: MutableStateFlow<BcSessionSnapshot>,
        request: BcPermissionRequest,
        update: () -> Unit,
    ): Pair<BcCharacterInteractionResult, FakeSessionGateway> {
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val gateway = FakeSessionGateway(allowItem = true).apply {
            beforeAllowItemResult = { started.complete(Unit); resume.await() }
        }
        val controller = controller(gateway, snapshot)
        val result = async { controller.sendInteraction(request) }
        started.await()
        update()
        resume.complete(Unit)
        return result.await() to gateway
    }

    private class FakeSessionGateway(
        private val allowItem: Boolean,
    ) : InteractionGateway {
        val calls = mutableListOf<String>()
        var allowItemMemberNumber: Int? = null
            private set
        var sentActivityCommand: BcActivityCommand? = null
            private set
        var updatedItemCommand: BcCharacterItemUpdateCommand? = null
            private set
        var updatedPoseCommand: BcPoseUpdateCommand? = null
            private set
        var updatedExpressionCommand: BcExpressionUpdateCommand? = null
            private set
        var echoCompatibilityCommand: BcEchoCompatibilityCommand? = null
            private set
        var echoDrawOrderCommand: BcEchoDrawOrderCommand? = null
            private set
        var cancelActivitySend: Boolean = false
        var beforeAllowItemResult: suspend () -> Unit = {}
        var afterCompatibility: suspend () -> Unit = {}
        var afterActivity: suspend () -> Unit = {}
        var afterItemUpdate: suspend () -> Unit = {}
        var beforeItemEmit: suspend () -> Unit = {}

        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand, expectedCharacters: List<BcCharacterSnapshot>?, validateBeforeEmit: () -> Unit) {
            beforeItemEmit()
            validateBeforeEmit()
            updateCharacterItem(command)
        }

        override suspend fun queryAllowItem(memberNumber: Int): Boolean {
            allowItemMemberNumber = memberNumber
            beforeAllowItemResult()
            return allowItem
        }

        override suspend fun sendActivity(command: BcActivityCommand) {
            if (cancelActivitySend) throw CancellationException("cancelled")
            calls += "activity"
            sentActivityCommand = command
            afterActivity()
        }

        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) {
            calls += "item"
            updatedItemCommand = command
            afterItemUpdate()
        }

        override suspend fun updatePose(command: BcPoseUpdateCommand) {
            calls += "pose"
            updatedPoseCommand = command
        }

        override suspend fun updateExpression(command: BcExpressionUpdateCommand) {
            calls += "expression"
            updatedExpressionCommand = command
        }

        override suspend fun publishEchoCompatibility(command: BcEchoCompatibilityCommand) {
            calls += "compatibility"
            echoCompatibilityCommand = command
            afterCompatibility()
        }

        override suspend fun publishEchoDrawOrder(command: BcEchoDrawOrderCommand) {
            calls += "drawOrder"
            echoDrawOrderCommand = command
        }
    }
}
