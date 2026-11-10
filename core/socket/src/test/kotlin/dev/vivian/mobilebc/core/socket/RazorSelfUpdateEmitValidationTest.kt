package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.auth.LoginResult
import bot.inker.bc.razor.state.RoomState
import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URI
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorSelfUpdateEmitValidationTest {
    @Test fun timerAdjustmentsUseTheEmissionClockAndPublishTheClampedChange() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.blockOutbound()
            val epoch = 2_000_000_000_000L
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms", "LeatherCuffs",
                    propertyJson = """{"LockedBy":"TimerPasswordPadlock","RemoveTimer":${epoch + 300000},"ShowTimer":true,"Text":"保留"}""",
                    adjustLockTimer = dev.vivian.mobilebc.core.model.BcItemTimerAdjustment(300, 100))) {}
            }
            fixture.gateway.javaClass.getDeclaredField("serverTimeAnchor").apply { isAccessible = true }.set(
                fixture.gateway, dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(epoch, System.nanoTime()))
            fixture.releaseOutbound(); sending.join()
            assertEquals(listOf("ChatRoomCharacterItemUpdate", "ChatRoomChat"), fixture.socket.events)
            val payload = JsonParser.parseString(fixture.socket.payloads[0].second).asJsonObject
            val property = payload.getAsJsonObject("Property")
            assertTrue(property["RemoveTimer"].asLong in (epoch + 100000)..(epoch + 105000))
            assertEquals("保留", property["Text"].asString)
            assertTrue(!payload.has("adjustLockTimer"))
            val action = JsonParser.parseString(fixture.socket.payloads[1].second).asJsonObject
            assertEquals("TimerRemoveTime", action["Content"].asString)
            val local = (field(fixture.gateway, "roomStateStore") as RazorRoomStateStore).captureCharacterSnapshots().single().appearance.single()
            assertEquals(property["RemoveTimer"], JsonParser.parseString(local.propertyJson).asJsonObject["RemoveTimer"])
        } finally { fixture.close() }
    }

    @Test fun timerExpiryUpdatesCanonicalSelfAndSavedAppearanceBeforeItsActionMessage() = runBlocking {
        val fixture = Fixture()
        try {
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            store.handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Appearance":[{"Group":"ItemArms","Name":"LeatherCuffs","Property":{"LockedBy":"OwnerTimerPadlock","RemoveTimer":1000,"Effect":["Lock","Block"],"Text":"定制文字😀"}}]}]}"""))
            val self = store.captureCharacterSnapshots().single()
            val item = self.appearance.single()
            fixture.gateway.javaClass.getDeclaredField("serverTimeAnchor").apply { isAccessible = true }.set(
                fixture.gateway, dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(2000, System.nanoTime()))
            fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, item.group, item.name,
                propertyJson = dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson.withoutLock(item.propertyJson),
                publishAction = BcItemActionMessage("TimerRelease", lockName = "OwnerTimerPadlock", destinationCharacterName = "Self"), expireTimerSource = item), listOf(self)) {}
            assertEquals(listOf("ChatRoomCharacterItemUpdate", "ChatRoomChat"), fixture.socket.events)
            val local = JsonParser.parseString(store.captureCharacterSnapshots().single().appearance.single().propertyJson).asJsonObject
            assertTrue(!local.has("LockedBy") && !local.has("RemoveTimer"))
            assertEquals("定制文字😀", local.get("Text").asString)
            val outbound = field(fixture.gateway, "outbound") as RazorOutboundPipeline
            val saved = JsonParser.parseString(checkNotNull(outbound.drainAccountUpdatePacket()).payloadJson).asJsonObject
            val property = saved.getAsJsonArray("Appearance").single().asJsonObject.getAsJsonObject("Property")
            assertTrue(!property.has("LockedBy") && !property.has("RemoveTimer"))
        } finally { fixture.close() }
    }

    @Test fun twoExpiredItemsCanSettleFromTheSameSnapshotButARenewedTimerCannot() = runBlocking {
        val fixture = Fixture()
        try {
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            store.handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Appearance":[{"Group":"ItemArms","Name":"LeatherCuffs","Property":{"LockedBy":"TimerPadlock","RemoveTimer":1000,"Effect":["Lock"]}},{"Group":"ItemLegs","Name":"LeatherLegCuffs","Property":{"LockedBy":"TimerPadlock","RemoveTimer":1000,"Effect":["Lock"]}}]}]}"""))
            val self = store.captureCharacterSnapshots().single()
            fixture.gateway.javaClass.getDeclaredField("serverTimeAnchor").apply { isAccessible = true }.set(
                fixture.gateway, dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(2000, System.nanoTime()))
            for (item in self.appearance) {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, item.group, item.name,
                    propertyJson = dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson.withoutLock(item.propertyJson), expireTimerSource = item), listOf(self)) {}
            }
            assertEquals(2, fixture.socket.events.size)
            assertTrue(store.captureCharacterSnapshots().single().appearance.all { !it.propertyJson.orEmpty().contains("LockedBy") })
            assertTrue(fixture.socket.payloads.none { it.second.contains("expireTimerSource") })
            val stale = self.appearance.first()
            store.handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Appearance":[{"Group":"ItemArms","Name":"LeatherCuffs","Property":{"LockedBy":"TimerPadlock","RemoveTimer":5000,"Effect":["Lock"]}}]}]}"""))
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, stale.group, stale.name, expireTimerSource = stale), listOf(self)) {}
            }.isFailure)
            val renewed = store.captureCharacterSnapshots().single().appearance.single()
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, renewed.group, renewed.name, expireTimerSource = renewed)) {}
            }.isFailure)
            assertEquals(5000L, JsonParser.parseString(store.captureCharacterSnapshots().single().appearance.single().propertyJson).asJsonObject.get("RemoveTimer").asLong)
            assertEquals(2, fixture.socket.events.size)
            store.handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Appearance":[{"Group":"ItemArms","Name":"LeatherCuffs","Property":{"LockedBy":"TimerPadlock","RemoveTimer":1000,"Effect":["Lock"]}}]}]}"""))
            val expired = store.captureCharacterSnapshots().single().appearance.single()
            fixture.gateway.javaClass.getDeclaredField("serverTimeAnchor").apply { isAccessible = true }.set(fixture.gateway, null)
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, expired.group, expired.name,
                    propertyJson = dev.vivian.mobilebc.domain.bcitemproperty.BcItemPropertyJson.withoutLock(expired.propertyJson), expireTimerSource = expired)) {}
            }.isFailure)
            assertEquals(2, fixture.socket.events.size)
        } finally { fixture.close() }
    }

    @Test
    fun ownItemUpdatesRefreshAppearanceAndPersistWithoutAnyServerEcho() = runBlocking {
        val fixture = Fixture()
        try {
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            fun current() = store.captureCharacterSnapshots().single().appearance
            fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms", "HempRope",
                propertyJson = """{"TypeRecord":{"typed":0},"Text":"自定义😀"}""", craftJson = """{"Name":"定制绳"}""")) {}
            assertEquals("HempRope", current().single().name)
            assertTrue(current().single().propertyJson!!.contains("自定义😀"))
            fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms", "HempRope",
                propertyJson = """{"TypeRecord":{"typed":1},"Text":"自定义😀"}""")) {}
            assertTrue(current().single().propertyJson!!.contains("\"typed\":1"))
            fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms")) {}
            assertTrue(current().isEmpty())
            val outbound = field(fixture.gateway, "outbound") as RazorOutboundPipeline
            val saved = JsonParser.parseString(checkNotNull(outbound.drainAccountUpdatePacket()).payloadJson).asJsonObject
            assertEquals(0, saved.getAsJsonArray("Appearance").size())
        } finally { fixture.close() }
    }

    @Test
    fun afcCompanionAndItemShareFinalValidationAndPreserveEmissionOrder() = runBlocking {
        val fixture = Fixture()
        try {
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            store.handle(RazorWireParser.parse("ChatRoomSync", """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self"},{"ID":"20","MemberNumber":20,"Name":"Wearer"}]}"""))
            fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(20, "ItemArms", "Cuffs",
                propertyJson = """{"LockedBy":"HighSecurityPadlock","Name":"Heart Padlock","LockMemberNumber":10}""",
                afcHeartLockChange = dev.vivian.mobilebc.core.model.BcAfcHeartLockChange.Apply("Self", "Female3DCG"))) {}
            assertEquals(listOf("ChatRoomChat", "ChatRoomCharacterItemUpdate"), fixture.socket.events)
            val companion = JsonParser.parseString(fixture.socket.payloads[0].second).asJsonObject
            val item = JsonParser.parseString(fixture.socket.payloads[1].second).asJsonObject
            assertEquals(companion.getAsJsonArray("Dictionary")[0].asJsonObject.get("LockId"), item.getAsJsonObject("Property").get("HeartLockId"))
            val localWearer = store.captureCharacterSnapshots().single { it.memberNumber == 20 }
            assertEquals(item.getAsJsonObject("Property").get("HeartLockId").asString,
                JsonParser.parseString(localWearer.appearance.single().propertyJson).asJsonObject.get("HeartLockId").asString)
            fixture.socket.events.clear()
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(20, "ItemArms", "Cuffs",
                    afcHeartLockChange = dev.vivian.mobilebc.core.model.BcAfcHeartLockChange.Remove("id"))) { error("permission changed") }
            }.isFailure)
            assertTrue(fixture.socket.events.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun timerLockStartsFromTheClockAtActualEmission() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms", "Cuffs",
                    propertyJson = """{"LockedBy":"TimerPadlock","RemoveTimer":0,"Text":"keep"}""",
                    initializeLockTimerSeconds = 300)) {}
            }
            val epoch = 2_000_000_000_000L
            fixture.gateway.javaClass.getDeclaredField("serverTimeAnchor").apply { isAccessible = true }.set(
                fixture.gateway, dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor(epoch, System.nanoTime()))
            fixture.releaseOutbound()
            sending.join()
            val payload = JsonParser.parseString(fixture.socket.payloads.single { it.first == "ChatRoomCharacterItemUpdate" }.second).asJsonObject
            val property = payload.getAsJsonObject("Property")
            assertTrue(property.get("RemoveTimer").asLong in (epoch + 300_000)..(epoch + 305_000))
            assertEquals("keep", property.get("Text").asString)
            assertTrue(!payload.has("initializeLockTimerSeconds") && !payload.has("permissionPrivateState"))
        } finally { fixture.close() }
    }

    @Test
    fun timerLockWithoutClockCannotEmitAnItemOrItsAction() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms", "Cuffs",
                    initializeLockTimerSeconds = 300, publishAction = BcItemActionMessage("ActionAddLock", "Cuffs", "TimerPadlock", nextAssetGroup = "ItemMisc"))) {}
            }.isFailure)
            assertTrue(fixture.socket.events.isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun queuedUnlockRejectsChangedPrivateInventoryWithoutPublishingAnAction() = runBlocking {
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            val requests = field(fixture.gateway, "requests") as RazorRequestCoordinator
            val wardrobe = requireNotNull(requests.authenticatedWardrobeState)
            val privateState = dev.vivian.mobilebc.core.model.BcItemPermissionPrivateState(wardrobe, emptyList())
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching {
                    fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(
                        10, "ItemArms", "HempRope", publishAction = BcItemActionMessage("ActionUnlock", previousAssetName = "HempRope"),
                        permissionPrivateState = privateState,
                    )) {}
                }.exceptionOrNull()
            }
            requests.compareAndSetWardrobeState(wardrobe, wardrobe.copy(privateInputs = wardrobe.privateInputs.copy(inventoryAvailable = false)))
            fixture.releaseOutbound()
            sending.join()
            assertTrue(failure is IllegalStateException)
            assertTrue(fixture.socket.events.none { it == "ChatRoomCharacterItemUpdate" || it == "ChatRoomChat" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun itemSelectionMustMatchCanonicalStateBeforeQueueing() = runBlocking {
        val fixture = Fixture()
        try {
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            val selected = store.captureCharacterSnapshots()
            store.handle(RazorWireParser.parse("ChatRoomSyncItem",
                """{"Source":20,"Item":{"Target":10,"Group":"ItemArms","Name":"HempRope","Property":{"LockedBy":"PasswordPadlock"}}}""",
            ))
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(10, "ItemArms"), selected) {}
            }.isFailure)
            assertTrue(fixture.socket.events.none { it == "ChatRoomCharacterItemUpdate" || it == "ChatRoomChat" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun canonicalItemSyncRejectsQueuedUpdateBeforeDomainProjectionCatchesUp() = runBlocking {
        val fixture = Fixture()
        var failure: Throwable? = null
        var validationCalls = 0
        try {
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching {
                    fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(
                        10, "ItemArms", "HempRope", publishAction = BcItemActionMessage("ActionUse", nextAssetName = "HempRope"),
                    )) { validationCalls++ }
                }.exceptionOrNull()
            }
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            store.handle(RazorWireParser.parse("ChatRoomSyncItem",
                """{"Source":20,"Item":{"Target":10,"Group":"ItemArms","Name":"HempRope","Property":{"LockedBy":"PasswordPadlock","Effect":["Lock"]}}}""",
            ))
            fixture.releaseOutbound()
            sending.join()
            assertTrue(failure is IllegalStateException)
            assertEquals(0, validationCalls)
            assertTrue(fixture.socket.events.none { it == "ChatRoomCharacterItemUpdate" || it == "ChatRoomChat" })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun rejectedItemValidationDoesNotPublishAnActionMessage() = runBlocking {
        val fixture = Fixture()
        try {
            assertTrue(runCatching {
                fixture.gateway.updateCharacterItem(BcCharacterItemUpdateCommand(
                    10, "ItemArms", "HempRope", publishAction = BcItemActionMessage("ActionUse", nextAssetName = "HempRope"),
                )) { error("Permission changed") }
            }.isFailure)
            assertTrue(fixture.socket.events.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun queuedPoseAndExpressionValidationRunsAtEmissionAndCanRejectThePacket() = runBlocking {
        SelfUpdate.entries.forEach { update ->
            val fixture = Fixture()
            val rejected = IllegalStateException("The permission changed while queued.")
            var allowed = true
            var validationCalls = 0
            var failure: Throwable? = null
            try {
                fixture.blockOutbound()
                val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                    failure = runCatching {
                        fixture.send(update) {
                            validationCalls++
                            if (!allowed) throw rejected
                        }
                    }.exceptionOrNull()
                }
                assertEquals(0, validationCalls)
                assertTrue(fixture.socket.events.isEmpty())
                allowed = false
                fixture.releaseOutbound()
                sending.join()

                assertEquals(1, validationCalls)
                assertEquals(rejected::class.java, failure?.javaClass)
                assertEquals(rejected.message, failure?.message)
                assertTrue(failure === rejected || failure?.cause === rejected)
                assertTrue(fixture.socket.events.isEmpty())
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun queuedPoseAndExpressionEmitOnceAfterSuccessfulFinalValidation() = runBlocking {
        SelfUpdate.entries.forEach { update ->
            val fixture = Fixture()
            var validationCalls = 0
            try {
                fixture.blockOutbound()
                val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.send(update) { validationCalls++ }
                }
                assertEquals(0, validationCalls)
                fixture.releaseOutbound()
                sending.join()

                assertEquals(1, validationCalls)
                assertEquals(listOf(update.eventName), fixture.socket.events)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun activitySideEffectCallersKeepTheExistingCommandOnlyUpdateMethods() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.gateway.updatePose(BcPoseUpdateCommand(listOf("Kneel")))
            fixture.gateway.updateExpression(BcExpressionUpdateCommand("Eyes", "Closed"))

            assertEquals(
                listOf("ChatRoomCharacterPoseUpdate", "ChatRoomCharacterExpressionUpdate"),
                fixture.socket.events,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun emittedPoseAndExpressionUpdateTheSenderWithoutAnEcho() = runBlocking {
        val fixture = Fixture()
        try {
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            store.handle(RazorWireParser.parse("ChatRoomSync",
                """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Appearance":[{"Group":"Eyes","Name":"Eyes"}]}]}""",
            ))
            fixture.gateway.updatePose(BcPoseUpdateCommand(listOf("Kneel", "OverTheHead")))
            assertEquals(listOf("Kneel", "OverTheHead"), store.captureCharacterSnapshots().first { it.isSelf }.activePose)

            fixture.gateway.updatePose(BcPoseUpdateCommand(listOf("Kneel", "BaseUpper")))
            assertEquals(listOf("Kneel", "BaseUpper"), store.captureCharacterSnapshots().first { it.isSelf }.activePose)

            fixture.gateway.updateExpression(BcExpressionUpdateCommand("Eyes", "Closed"))
            val eyes = store.captureCharacterSnapshots().first { it.isSelf }.appearance.first { it.group == "Eyes" }
            assertEquals("Closed", com.google.gson.JsonParser.parseString(eyes.propertyJson).asJsonObject.get("Expression").asString)
            fixture.gateway.updateExpression(BcExpressionUpdateCommand("Eyes", null))
            val reset = store.captureCharacterSnapshots().first { it.isSelf }.appearance.first { it.group == "Eyes" }
            val expression = com.google.gson.JsonParser.parseString(reset.propertyJson).asJsonObject.get("Expression")
            assertTrue(expression == null || expression.isJsonNull)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successfulSameSocketLoginRenewsPrivateRulesAndRejectsThePreviousGeneration() = runBlocking {
        val fixture = Fixture()
        try {
            val requests = field(fixture.gateway, "requests") as RazorRequestCoordinator
            val store = field(fixture.gateway, "roomStateStore") as RazorRoomStateStore
            suspend fun login(): String {
                val response = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
                requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(ServerAccountData("10", 10, "Self", log = JsonParser.parseString("[]")))))
                return requireNotNull((response.await() as BcSessionLoginResult.Success).selfRuleLogSessionId)
            }
            val firstToken = login()
            store.handle(RazorWireParser.parse("ChatRoomSync",
                """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Ownership":{"MemberNumber":20,"Name":"Owner","Stage":1}},{"ID":"20","MemberNumber":20,"Name":"Owner"}]}""",
            ))
            val self = (field(store, "current") as RoomState).characters.single { it.memberNumber == 10 }
                .toBcCharacterSnapshot(isSelf = true, gson = Gson())
            val oldExpectation = BcSelfStateEmitExpectation(self, ownerPresent = true, selfRuleLogSessionId = firstToken)
            val oldRoomContext = store.captureReceiveContext()
            val oldGuard = store.captureProtectedSelfUpdateGuard(oldExpectation, requireOwnerPoseRules = false)
            assertTrue(store.invalidateSelfRuleLog(store.captureReceiveContext()))
            assertThrows(IllegalStateException::class.java) {
                store.captureProtectedSelfUpdateGuard(oldExpectation, requireOwnerPoseRules = true)
            }

            val secondToken = login()
            assertTrue(firstToken != secondToken)
            assertEquals(null, store.captureReceiveContext().roomName)
            assertTrue(store.captureCharacterSnapshots().isEmpty())
            assertTrue(store.captureReceiveContext().roomPresenceEpoch > oldRoomContext.roomPresenceEpoch)
            assertThrows(IllegalStateException::class.java) { oldGuard { error("Must not emit") } }
            val staleFailure = runCatching {
                fixture.gateway.updatePose(BcPoseUpdateCommand(listOf("Kneel")), oldExpectation) {}
            }.exceptionOrNull()
            assertTrue(staleFailure is IllegalStateException)
            assertTrue(fixture.socket.events.isEmpty())
            assertTrue(runCatching {
                fixture.gateway.updatePose(
                    BcPoseUpdateCommand(listOf("Kneel")),
                    oldExpectation.copy(selfRuleLogSessionId = secondToken),
                ) {}
            }.isFailure)
            store.handle(RazorWireParser.parse("ChatRoomSync",
                """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self","Ownership":{"MemberNumber":20,"Name":"Owner","Stage":1}},{"ID":"20","MemberNumber":20,"Name":"Owner"}]}""",
            ))
            fixture.gateway.updatePose(
                BcPoseUpdateCommand(listOf("Kneel")),
                oldExpectation.copy(selfRuleLogSessionId = secondToken),
            ) {}

            assertEquals(listOf("ChatRoomCharacterPoseUpdate"), fixture.socket.events)
        } finally {
            fixture.close()
        }
    }

    private enum class SelfUpdate(val eventName: String) {
        Pose("ChatRoomCharacterPoseUpdate"),
        Expression("ChatRoomCharacterExpressionUpdate"),
        Item("ChatRoomCharacterItemUpdate"),
    }

    private class Fixture {
        val gateway = RazorGatewayImpl()
        val socket = RecordingSocket()
        private val outbound = field(gateway, "outbound") as RazorOutboundPipeline
        private val executor = field(outbound, "executor") as ScheduledExecutorService
        private val release = CountDownLatch(1)
        private val loginToken: String

        init {
            val transport = field(gateway, "transport") as RazorSocketTransport
            RazorSocketTransport::class.java.getDeclaredField("socket").apply { isAccessible = true }.set(transport, socket)
            val requests = field(gateway, "requests") as RazorRequestCoordinator
            loginToken = runBlocking {
                val response = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
                requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(
                    ServerAccountData("10", 10, "Self", log = JsonParser.parseString("[]")),
                )))
                requireNotNull((response.await() as BcSessionLoginResult.Success).selfRuleLogSessionId)
            }
            (field(gateway, "roomStateStore") as RazorRoomStateStore).apply {
                selfMemberNumber = 10
                handle(dev.vivian.mobilebc.core.protocol.RazorWireParser.parse("ChatRoomSync",
                    """{"Name":"Room A","Character":[{"ID":"10","MemberNumber":10,"Name":"Self"}]}""",
                ))
            }
        }

        fun blockOutbound() {
            val blocked = CountDownLatch(1)
            executor.submit { blocked.countDown(); release.await() }
            assertTrue(blocked.await(1, TimeUnit.SECONDS))
        }

        fun releaseOutbound() { release.countDown() }

        suspend fun send(update: SelfUpdate, validation: () -> Unit) {
            val expected = BcSelfStateEmitExpectation(
                BcCharacterSnapshot("10", 10, "Self", "Self", isSelf = true),
                ownerPresent = false,
                selfRuleLogSessionId = loginToken,
            )
            when (update) {
                SelfUpdate.Pose -> gateway.updatePose(BcPoseUpdateCommand(listOf("Kneel")), expected, validation)
                SelfUpdate.Expression -> gateway.updateExpression(BcExpressionUpdateCommand("Eyes", "Closed"), expected, validation)
                SelfUpdate.Item -> gateway.updateCharacterItem(
                    BcCharacterItemUpdateCommand(10, "ItemArms", "HempRope"),
                    (field(gateway, "roomStateStore") as RazorRoomStateStore).captureCharacterSnapshots(),
                    validation,
                )
            }
        }

        fun close() {
            release.countDown()
            gateway.close()
        }
    }

    private class RecordingSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val payloads: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        override fun connected(): Boolean = true
        override fun emit(event: String, vararg args: Any?): Emitter = this.also {
            events += event
            payloads += event to args.firstOrNull().toString()
        }
        override fun disconnect(): Socket = this
    }

    private companion object {
        fun field(instance: Any, name: String): Any? =
            instance.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(instance)
    }
}
