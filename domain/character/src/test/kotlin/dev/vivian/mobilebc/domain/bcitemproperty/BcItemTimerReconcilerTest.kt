package dev.vivian.mobilebc.domain.bcitemproperty

import dev.vivian.mobilebc.core.model.*
import dev.vivian.mobilebc.domain.session.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BcItemTimerReconcilerTest {
    @Test fun expiryMatchesTheFixturesAlsoExecutedByTheOfficialR133TimerFunction() = runTest {
        val fixtures = requireNotNull(javaClass.classLoader?.getResource("bc-fixtures/item-timer-r133.json")).readText()
        BcItemPropertyJson.readObjectArray(fixtures).forEach { fixture ->
            val characters = BcItemPropertyJson.readObjectArray(BcItemPropertyJson.readPropertyValue(fixture, "characters")).map { character ->
                val number = character.readBcIntProperty("member")!!
                BcCharacterSnapshot(number.toString(), number, "Self", "Self", character.readBcBooleanProperty("player") == true,
                    appearance = BcItemPropertyJson.readObjectArray(BcItemPropertyJson.readPropertyValue(character, "items")).map { item ->
                        BcCharacterItem(item.readBcStringProperty("group")!!, item.readBcStringProperty("name")!!,
                            propertyJson = BcItemPropertyJson.readPropertyValue(item, "property"))
                    })
            }
            val snapshots = MutableStateFlow(snapshot().copy(roomCharacters = characters))
            val gateway = Gateway()
            BcItemTimerReconciler(gateway, snapshots, serverTime = { BcItemPropertyJson.readPropertyValue(fixture, "now")?.toLongOrNull() }).reconcile()
            val expected = BcItemPropertyJson.readObjectArray(BcItemPropertyJson.readPropertyValue(fixture, "expected"))
            assertEquals(fixture.readBcStringProperty("id"), expected.size, gateway.sent.size)
            expected.zip(gateway.sent).forEach { (row, command) ->
                assertEquals(row.readBcIntProperty("target"), command.targetMemberNumber)
                assertEquals(row.readBcStringProperty("group"), command.group)
                assertEquals(row.readBcStringProperty("name"), command.name)
                assertEquals(BcItemPropertyJson.readPropertyValue(row, "property")?.takeUnless { it == "null" }, command.propertyJson)
            }
        }
    }

    private val item = BcCharacterItem("ItemArms", "LeatherCuffs", "[\"Red\"]", 5,
        """{"LockedBy":"OwnerTimerPadlock","LockMemberNumber":20,"RemoveTimer":1000,"RemoveItem":false,"Effect":["Lock","Block"],"TypeRecord":{"typed":1},"Text":"定制文字😀","Custom":{"keep":true}}""", """{"Name":"定制铐"}""")
    private val self = BcCharacterSnapshot("10", 10, "Self", "昵称", true, nickname = "昵称", appearance = listOf(item))
    private fun snapshot(character: BcCharacterSnapshot = self) = BcSessionSnapshot(state = BcSessionState.Ready,
        desiredOnline = true, connectionHealth = BcConnectionHealth.VerifiedOnline, account = BcAccount(10, "Self", "account"),
        currentRoom = BcRoom(BcRoomName("Room"), "", 1, 10, false, null), roomPresenceEpoch = 1,
        roomCharacters = listOf(character), selfRuleLogSessionId = "login")

    private class Gateway : InteractionGateway {
        val sent = mutableListOf<BcCharacterItemUpdateCommand>()
        var beforeEmit: () -> Unit = {}
        override suspend fun queryAllowItem(memberNumber: Int): Boolean = error("Expiry must not require AllowItem")
        override suspend fun sendActivity(command: BcActivityCommand) = Unit
        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand) { sent += command }
        override suspend fun updateCharacterItem(command: BcCharacterItemUpdateCommand, expectedCharacters: List<BcCharacterSnapshot>?, validateBeforeEmit: () -> Unit) {
            beforeEmit(); validateBeforeEmit(); updateCharacterItem(command)
        }
    }

    @Test fun expiryWorksWithoutKeysHandsOrOwnerRulesAndPreservesCustomMetadata() = runTest {
        val gateway = Gateway()
        val snapshots = MutableStateFlow(snapshot())
        BcItemTimerReconciler(gateway, snapshots, serverTime = { 1000 }).reconcile()
        val command = gateway.sent.single()
        assertEquals(10, command.targetMemberNumber)
        assertEquals(item.name, command.name)
        assertEquals(item.colorJson, command.colorJson)
        assertEquals(item.craftJson, command.craftJson)
        assertEquals(item.difficulty, command.difficulty)
        assertNull(command.propertyJson.readBcStringProperty("LockedBy"))
        assertNull(BcItemPropertyJson.readPropertyValue(command.propertyJson, "RemoveTimer"))
        assertEquals("定制文字😀", command.propertyJson.readBcStringProperty("Text"))
        assertEquals(setOf("Block"), command.propertyJson.readBcPropertyTokens("Effect"))
        assertEquals(mapOf("typed" to 1), BcItemPropertyJson.readTypeRecord(command.propertyJson))
        assertTrue(command.propertyJson!!.contains("\"Custom\":{\"keep\":true}"))
        assertEquals(BcItemActionMessage("TimerRelease", lockName = "OwnerTimerPadlock", destinationCharacterName = "昵称"), command.publishAction)
    }

    @Test fun expiryRemoveItemAndTimerWithoutALockDoNotLeaveAnExpiredTimerBehind() = runTest {
        val removed = item.copy(propertyJson = item.propertyJson!!.replace("\"RemoveItem\":false", "\"RemoveItem\":true"))
        val expression = BcCharacterItem("Eyes", "Eyes1", propertyJson = """{"Expression":"Closed","RemoveTimer":1000}""")
        val gateway = Gateway()
        BcItemTimerReconciler(gateway, MutableStateFlow(snapshot(self.copy(appearance = listOf(removed, expression)))), serverTime = { 1000 }).reconcile()
        assertNull(gateway.sent[0].name)
        assertNull(gateway.sent[0].propertyJson)
        assertNull(gateway.sent[0].craftJson)
        assertEquals("Closed", gateway.sent[1].propertyJson.readBcStringProperty("Expression"))
        assertNull(BcItemPropertyJson.readPropertyValue(gateway.sent[1].propertyJson, "RemoveTimer"))
        assertNull(gateway.sent[1].publishAction)
    }

    @Test fun futureTimersMissingClockOfflineAndOtherCharactersCannotExpire() = runTest {
        val other = self.copy(id = "20", memberNumber = 20, isSelf = false)
        val cases = listOf(snapshot(other), snapshot().copy(desiredOnline = false), snapshot().copy(currentRoom = null))
        for (state in cases) {
            val gateway = Gateway()
            BcItemTimerReconciler(gateway, MutableStateFlow(state), serverTime = { 1000 }).reconcile()
            assertTrue(gateway.sent.isEmpty())
        }
        val gateway = Gateway()
        BcItemTimerReconciler(gateway, MutableStateFlow(snapshot()), serverTime = { 999 }).reconcile()
        BcItemTimerReconciler(gateway, MutableStateFlow(snapshot()), serverTime = { null }).reconcile()
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun staleTimerRoomVisitLoginAndClockAreCheckedAtActualEmission() = runTest {
        val changes: List<(BcSessionSnapshot) -> BcSessionSnapshot> = listOf(
            { it.copy(roomPresenceEpoch = 2) }, { it.copy(selfRuleLogSessionId = "new-login") },
            { it.copy(relationships = it.relationships.nextUnloaded()) }, { it.copy(connectionHealth = BcConnectionHealth.Offline) },
            { snapshot ->
                val renewed = item.copy(propertyJson = """{"RemoveTimer":5000}""")
                snapshot.copy(roomCharacters = listOf(self.copy(appearance = listOf(renewed))))
            },
        )
        for (change in changes) {
            val snapshots = MutableStateFlow(snapshot())
            val gateway = Gateway().apply { beforeEmit = { snapshots.value = change(snapshots.value) } }
            BcItemTimerReconciler(gateway, snapshots, serverTime = { 1000 }).reconcile()
            assertTrue(gateway.sent.isEmpty())
        }
        var now = 1000L
        val gateway = Gateway().apply { beforeEmit = { now = 999 } }
        BcItemTimerReconciler(gateway, MutableStateFlow(snapshot()), serverTime = { now }).reconcile()
        assertTrue(gateway.sent.isEmpty())
    }

    @Test fun duplicateAttemptsAreThrottledButANewItemAndLoginAreNot() = runTest {
        var time = 0L
        val snapshots = MutableStateFlow(snapshot())
        val gateway = Gateway()
        val reconciler = BcItemTimerReconciler(gateway, snapshots, serverTime = { 1000 }, monotonicMillis = { time })
        reconciler.reconcile(); reconciler.reconcile()
        assertEquals(1, gateway.sent.size)
        time = 15_000; reconciler.reconcile()
        assertEquals(2, gateway.sent.size)
        snapshots.value = snapshots.value.copy(selfRuleLogSessionId = "next")
        reconciler.reconcile()
        assertEquals(3, gateway.sent.size)
    }

    @Test @OptIn(ExperimentalCoroutinesApi::class)
    fun lifecycleLoopWaitsForClockAndItemsAndContinuesWithoutAnOpenPanel() = runTest {
        var now: Long? = null
        val gateway = Gateway()
        val snapshots = MutableStateFlow(snapshot(self.copy(appearance = emptyList())))
        val reconciler = BcItemTimerReconciler(gateway, snapshots, serverTime = { now }, monotonicMillis = { testScheduler.currentTime })
        reconciler.start(backgroundScope)
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertTrue(gateway.sent.isEmpty())
        now = 999
        snapshots.value = snapshot()
        runCurrent()
        assertTrue(gateway.sent.isEmpty())
        now = 1000
        advanceTimeBy(1000); runCurrent()
        assertEquals(1, gateway.sent.size)
    }

    @Test fun cancellationIsNotSwallowed() = runTest {
        val gateway = Gateway().apply { beforeEmit = { throw CancellationException("cancelled") } }
        assertTrue(runCatching { BcItemTimerReconciler(gateway, MutableStateFlow(snapshot()), serverTime = { 1000 }).reconcile() }.exceptionOrNull() is CancellationException)
    }
}
