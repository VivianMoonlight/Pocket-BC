package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcSelfStateEmitExpectation
import dev.vivian.mobilebc.core.model.BcSelfAppearanceUpdateCommand
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.BcWardrobeRenameRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSaveRequest
import dev.vivian.mobilebc.core.model.BcWardrobeSlotStatus
import dev.vivian.mobilebc.core.protocol.LzStringUtf16Codec
import dev.vivian.mobilebc.core.protocol.createRazorWireGson
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobePlanner
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeContext
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeEmissionRejectedException
import dev.vivian.mobilebc.domain.bcwardrobe.BcWardrobeUnavailableReason
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorWardrobeGatewayTest {
    @Test
    fun selfAppearanceUpdateUsesNativeZeroIdAndPreservesTheCompleteAppearanceAndPose() {
        val appearance = listOf(
            BcCharacterItem(
                group = "ItemArms", name = "LeatherCuffs", colorJson = "[\"#112233\",\"Default\"]",
                difficulty = 17,
                propertyJson = """{"LockedBy":"CombinationPadlock","CombinationNumber":"1234","Future":{"keep":[1,null]}}""",
                craftJson = """{"Name":"Personal cuffs","Description":"Keep exactly","MemberNumber":20,"Future":true}""",
            ),
            BcCharacterItem(group = "BodyUpper", name = "Normal", colorJson = "\"#DDBBAA\""),
            BcCharacterItem(group = "HairFront", name = "FrontHair1", colorJson = "\"#334455\""),
            BcCharacterItem(
                group = "Cloth", name = "Shirt", colorJson = "[\"#ABCDEF\",\"#123456\"]",
                propertyJson = """{"TypeRecord":{"t":1},"FutureCloth":[false,"value"]}""",
            ),
            BcCharacterItem(group = "Eyes", name = "Eyes1", propertyJson = """{"Expression":"Closed"}"""),
        )

        val actual = buildSelfAppearanceUpdatePayload(
            appearance.toAppearanceItemBundles(), listOf("Kneel", "BaseUpper"), createRazorWireGson(),
        )

        val nativePayload = JsonParser.parseString("""{
            "ID":0,"ActivePose":["Kneel","BaseUpper"],"Appearance":[
                {"Group":"ItemArms","Name":"LeatherCuffs","Color":["#112233","Default"],"Difficulty":17,
                 "Property":{"LockedBy":"CombinationPadlock","CombinationNumber":"1234","Future":{"keep":[1,null]}},
                 "Craft":{"Name":"Personal cuffs","Description":"Keep exactly","MemberNumber":20,"Future":true}},
                {"Group":"BodyUpper","Name":"Normal","Color":"#DDBBAA"},
                {"Group":"HairFront","Name":"FrontHair1","Color":"#334455"},
                {"Group":"Cloth","Name":"Shirt","Color":["#ABCDEF","#123456"],
                 "Property":{"TypeRecord":{"t":1},"FutureCloth":[false,"value"]}},
                {"Group":"Eyes","Name":"Eyes1","Property":{"Expression":"Closed"}}
            ]
        }""")
        assertEquals(nativePayload, actual)
    }

    @Test
    fun aRealLoginBindsLegacyAndCompressedWardrobesToItsActualAccountUuid() = runBlocking {
        listOf(legacyWardrobe().toString(), compressedWardrobe()).forEach { rawWardrobe ->
            val fixture = Fixture()
            try {
                val result = fixture.initialize(rawWardrobe)
                val state = requireNotNull(result.wardrobeState)
                assertEquals(10, state.memberNumber)
                assertEquals(result.selfRuleLogSessionId, state.selfRuleLogSessionId)
                UUID.fromString(state.selfRuleLogSessionId)
                assertEquals(state, fixture.requests.authenticatedWardrobeState)
                assertEquals(0L, state.revision)
                assertEquals(JsonParser.parseString(rawWardrobe), JsonParser.parseString(requireNotNull(state.rawWardrobeJson)))
                assertEquals(names(), JsonParser.parseString(requireNotNull(state.rawNamesJson)))
                assertTrue(state.wardrobeAvailable)
                assertTrue(state.namesAvailable)
                assertEquals(24, state.slots.size)
                assertEquals(BcWardrobeSlotStatus.Saved, state.slots[0].status)
                assertEquals(BcWardrobeSlotStatus.Empty, state.slots[1].status)
                assertEquals(listOf("BodyUpper", "HairFront", "Cloth", "Eyes"), state.slots[0].appearance?.map { it.group })
                assertTrue(state.privateInputs.gameAvailable)
                assertTrue(state.privateInputs.inventoryAvailable)
                assertEquals("{}", state.privateInputs.gameJson)
                assertEquals("[]", state.privateInputs.inventoryJson)
            } finally { fixture.close() }
        }
    }

    @Test
    fun nativeLoginDefaultsAndDamagedFieldsRemainDistinctWithTheirRawEvidence() = runBlocking {
        listOf<String?>(null, "null", "{}", "false", "\"\"", "\"corrupt compressed value\"").forEach { raw ->
            val fixture = Fixture()
            try {
                val result = fixture.initialize(wardrobeJson = raw, namesJson = raw, gameJson = raw, inventoryJson = raw)
                val state = requireNotNull(result.wardrobeState)
                assertEquals(result.selfRuleLogSessionId, state.selfRuleLogSessionId)
                assertEquals(raw, state.rawWardrobeJson)
                assertEquals(raw, state.rawNamesJson)
                val nativeDefault = raw == null || raw == "null"
                assertEquals(nativeDefault, state.wardrobeAvailable)
                assertEquals(nativeDefault, state.namesAvailable)
                if (nativeDefault) {
                    assertTrue(state.slots.all { it.status == BcWardrobeSlotStatus.Empty && it.appearance?.isEmpty() == true })
                    assertTrue(state.slots.all { it.name == "Self" })
                } else {
                    assertTrue(state.slots.all { it.status == BcWardrobeSlotStatus.Unsupported && it.appearance == null })
                }
                assertEquals(nativeDefault || raw == "{}", state.privateInputs.gameAvailable)
                assertEquals(nativeDefault || raw == "{}", state.privateInputs.inventoryAvailable)
            } finally { fixture.close() }
        }
    }

    @Test
    fun savingEmitsAFullNativeWardrobeAndPreservesEveryOtherSlotAndName() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.joinFixtureRoom()
            val previous = requireNotNull(fixture.requests.authenticatedWardrobeState)
            val expected = fixture.expectation()
            val appearance = BcWardrobePlanner().saveAppearance(expected.character, standardCatalog())
            var validated = 0

            val saved = withTimeout(2_000L) {
                fixture.gateway.saveWardrobe(BcWardrobeSaveRequest(1, previous.revision), appearance, expected) { validated++ }
            }

            assertEquals(1, validated)
            val payload = fixture.awaitWire("AccountUpdate")
            assertEquals(setOf("Wardrobe"), payload.keySet())
            val actual = JsonParser.parseString(requireNotNull(LzStringUtf16Codec.decompressFromUtf16(payload.get("Wardrobe").asString))).asJsonArray
            val original = JsonParser.parseString(requireNotNull(LzStringUtf16Codec.decompressFromUtf16(JsonParser.parseString(compressedWardrobe()).asString))).asJsonArray
            assertEquals(26, actual.size())
            for (index in 0 until actual.size()) if (index != 1) assertEquals(original[index], actual[index])
            assertEquals(JsonParser.parseString("""[
                ["Normal","BodyUpper","#DDBBAA",null],
                ["FrontHair1","HairFront","#334455",null],
                ["Shirt","Cloth","#ABCDEF",null],
                ["Eyes1","Eyes",null,null]
            ]"""), actual[1])
            assertEquals(previous.rawNamesJson, saved.rawNamesJson)
            assertEquals(payload.get("Wardrobe"), JsonParser.parseString(requireNotNull(saved.rawWardrobeJson)))
            assertEquals(previous.revision + 1L, saved.revision)
            assertEquals(saved, fixture.requests.authenticatedWardrobeState)
            val event = fixture.wardrobeEvent()
            assertEquals(saved.memberNumber, event.ownerMemberNumber)
            assertEquals(saved.selfRuleLogSessionId, event.selfRuleLogSessionId)
            assertEquals(saved, event.state)
            assertEquals(1, fixture.wires("AccountUpdate").size)
            assertEquals(expected.character, fixture.store.captureCharacterSnapshots().single { it.isSelf })
        } finally { fixture.close() }
    }

    @Test
    fun renamingEmitsOnlyTheFullNamesFieldAndPreservesExtraNamesAndRawWardrobe() = runBlocking {
        val fixture = Fixture()
        try {
            val rawNames = names().apply { set(25, JsonParser.parseString("""{"Future":[1,null,"keep"]}""")) }
            fixture.initialize(namesJson = rawNames.toString())
            fixture.joinFixtureRoom()
            val previous = requireNotNull(fixture.requests.authenticatedWardrobeState)
            var validated = 0

            val renamed = withTimeout(2_000L) {
                fixture.gateway.renameWardrobe(BcWardrobeRenameRequest(0, previous.revision, "Evening 2"), fixture.expectation()) { validated++ }
            }

            assertEquals(1, validated)
            val payload = fixture.awaitWire("AccountUpdate")
            assertEquals(setOf("WardrobeCharacterNames"), payload.keySet())
            val actual = payload.getAsJsonArray("WardrobeCharacterNames")
            assertEquals(26, actual.size())
            assertEquals("Evening 2", actual[0].asString)
            for (index in 1 until actual.size()) assertEquals(rawNames[index], actual[index])
            assertEquals(previous.rawWardrobeJson, renamed.rawWardrobeJson)
            assertEquals(actual, JsonParser.parseString(requireNotNull(renamed.rawNamesJson)))
            assertEquals(previous.revision + 1L, renamed.revision)
            assertEquals(renamed, fixture.requests.authenticatedWardrobeState)
            val event = fixture.wardrobeEvent()
            assertEquals(renamed.memberNumber, event.ownerMemberNumber)
            assertEquals(renamed.selfRuleLogSessionId, event.selfRuleLogSessionId)
            assertEquals(renamed, event.state)
            assertEquals(1, fixture.wires("AccountUpdate").size)
        } finally { fixture.close() }
    }

    @Test
    fun restoringEmitsTheNativeFullAppearanceAndQueuesTheSameAccountAppearanceWithoutOptimism() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.joinFixtureRoom()
            val expected = fixture.expectation()
            val command = fixture.restoreCommand()
            val wardrobe = fixture.wardrobe()
            var validated = 0

            withTimeout(2_000L) { fixture.gateway.restoreSelfAppearance(command, expected) { validated++ } }

            assertEquals(1, validated)
            val payload = fixture.awaitWire("ChatRoomCharacterUpdate")
            assertEquals(setOf("ID", "ActivePose", "Appearance"), payload.keySet())
            assertEquals(0, payload.get("ID").asInt)
            assertEquals(JsonParser.parseString("[\"Kneel\"]"), payload.get("ActivePose"))
            val items = payload.getAsJsonArray("Appearance").associate { it.asJsonObject.get("Group").asString to it.asJsonObject }
            assertEquals(setOf("ItemArms", "BodyUpper", "HairFront", "Cloth", "Eyes"), items.keys)
            assertEquals(JsonParser.parseString(SELF_CHARACTER).asJsonObject.getAsJsonArray("Appearance")[0], items.getValue("ItemArms"))
            assertEquals("Normal", items.getValue("BodyUpper").get("Name").asString)
            assertEquals("FrontHair2", items.getValue("HairFront").get("Name").asString)
            assertEquals("Dress", items.getValue("Cloth").get("Name").asString)
            assertEquals(JsonParser.parseString("[\"#ABCDEF\",\"#123456\"]"), items.getValue("Cloth").get("Color"))
            assertEquals("Closed", items.getValue("Eyes").getAsJsonObject("Property").get("Expression").asString)
            assertEquals(expected.character, fixture.store.captureCharacterSnapshots().single { it.isSelf })
            assertEquals(wardrobe, fixture.wardrobe())
            fixture.gateway.flushAccountUpdates()
            val account = fixture.awaitWire("AccountUpdate")
            assertEquals(setOf("AssetFamily", "Appearance"), account.keySet())
            assertEquals("Female3DCG", account.get("AssetFamily").asString)
            assertEquals(payload.get("Appearance"), account.get("Appearance"))
            assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first() })
        } finally { fixture.close() }
    }

    @Test
    fun allQueuedActionsRejectAChangedWardrobeRevisionWithoutEmittingOrPublishing() = runBlocking {
        listOf("save", "rename", "restore", "owner clear").forEach { action ->
            val fixture = Fixture()
            try {
                val ownerCleared = action == "owner clear"
                fixture.initialize(owner = "20".takeIf { ownerCleared })
                fixture.joinFixtureRoom()
                val previous = fixture.wardrobe()
                val expected = fixture.expectation()
                val command = fixture.restoreCommand()
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.perform(action, expected, command) } }
                val latest = if (ownerCleared) {
                    assertEquals("20", previous.privateInputs.owner)
                    fixture.receive("AccountOwnership", """{"MemberNumber":20,"ClearOwnership":true}""")
                    fixture.wardrobe().also { state ->
                        assertEquals(previous.revision + 1L, state.revision)
                        assertNull(state.privateInputs.owner)
                        assertEquals(previous.rawWardrobeJson, state.rawWardrobeJson)
                        assertEquals(previous.rawNamesJson, state.rawNamesJson)
                        assertNull(fixture.store.captureCharacterSnapshots().single { it.isSelf }.ownership)
                        assertEquals(state, fixture.wardrobeEvent().state)
                    }
                } else requireNotNull(fixture.requests.compareAndSetWardrobeState(previous, previous))
                fixture.releaseOutbound()

                val failure = withTimeout(2_000L) { sending.await() }.exceptionOrNull()
                if (ownerCleared) {
                    assertTrue(failure is IllegalStateException)
                    assertRejected(BcWardrobeUnavailableReason.RevisionChanged,
                        runCatching { fixture.gateway.restoreSelfAppearance(command, fixture.expectation()) {} }.exceptionOrNull())
                } else assertRejected(BcWardrobeUnavailableReason.RevisionChanged, failure)
                fixture.awaitOutbound()
                assertEquals(latest, fixture.wardrobe())
                assertTrue(fixture.wires("AccountUpdate").all { (_, raw) ->
                    JsonParser.parseString(raw).asJsonObject.keySet().none { it == "Wardrobe" || it == "Appearance" }
                })
                assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated } })
            } finally { fixture.close() }
        }
    }

    @Test
    fun aSameAccountReloginClearsQueuedWardrobeActionsBeforeTheReplacementSocketEmits() = runBlocking {
        listOf("save", "rename", "restore").forEach { action ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.joinFixtureRoom()
                val previous = fixture.wardrobe()
                val expected = fixture.expectation()
                val command = fixture.restoreCommand()
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.perform(action, expected, command) } }
                val replacement = fixture.enableReconnect()
                val login = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.login("account", "password") }
                assertTrue(withTimeout(2_000L) { sending.await() }.exceptionOrNull() is OutboxDispatchDeferredException)
                fixture.releaseOutbound()
                fixture.awaitWire("AccountLogin", replacement)
                fixture.receive("LoginResponse", JsonParser.parseString(LOGIN_RESPONSE).asJsonObject.apply {
                    add("Wardrobe", legacyWardrobe())
                    add("WardrobeCharacterNames", names())
                }.toString(), replacement)
                val accepted = withTimeout(2_000L) { login.await() } as BcSessionLoginResult.Success
                fixture.awaitOutbound()

                assertEquals(10, accepted.memberNumber)
                assertTrue(previous.selfRuleLogSessionId != accepted.selfRuleLogSessionId)
                assertEquals(accepted.wardrobeState, fixture.wardrobe())
                assertEquals(0L, fixture.wardrobe().revision)
                assertTrue(fixture.wires("AccountUpdate").isEmpty())
                assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
                assertEquals(listOf("AccountLogin"), replacement.events.map { it.first })
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated } })
            } finally { fixture.close() }
        }
    }

    @Test
    fun queuedRestoreRechecksCanonicalEquipmentPoseAndRoomImmediatelyBeforeEmitting() = runBlocking {
        listOf("equipment", "pose", "room").forEach { change ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.joinFixtureRoom()
                val expected = fixture.expectation()
                val command = fixture.restoreCommand()
                val previous = fixture.wardrobe()
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.restoreSelfAppearance(command, expected) {} }
                }
                when (change) {
                    "equipment" -> fixture.receive("ChatRoomSyncCharacter", JsonObject().apply {
                        addProperty("SourceMemberNumber", 10)
                        add("Character", JsonParser.parseString(SELF_CHARACTER).asJsonObject.apply {
                            getAsJsonArray("Appearance")[0].asJsonObject.addProperty("Color", "#000000")
                        })
                    }.toString())
                    "pose" -> fixture.receive("ChatRoomSyncPose", """{"MemberNumber":10,"Pose":["BaseUpper"]}""")
                    else -> fixture.receive("ChatRoomSync", """{"Name":"B","Character":[$SELF_CHARACTER]}""")
                }
                fixture.releaseOutbound()

                assertTrue(withTimeout(2_000L) { sending.await() }.exceptionOrNull() is IllegalStateException)
                fixture.awaitOutbound()
                assertEquals(previous, fixture.wardrobe())
                assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
                assertTrue(fixture.wires("AccountUpdate").isEmpty())
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated } })
            } finally { fixture.close() }
        }
    }

    @Test
    fun transportFailureNeverCommitsWardrobeStateOrQueuesAnAppearanceSave() = runBlocking {
        listOf("save", "rename", "restore").forEach { action ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.joinFixtureRoom()
                val previous = fixture.wardrobe()
                val expected = fixture.expectation()
                val command = fixture.restoreCommand()
                fixture.socket.failEvent = if (action == "restore") "ChatRoomCharacterUpdate" else "AccountUpdate"

                val failure = runCatching { fixture.perform(action, expected, command) }.exceptionOrNull()

                assertTrue(failure is IllegalStateException)
                assertEquals("Socket emission failed for ${fixture.socket.failEvent}.", failure?.message)
                assertEquals(previous, fixture.wardrobe())
                assertEquals(expected.character, fixture.store.captureCharacterSnapshots().single { it.isSelf })
                fixture.socket.failEvent = null
                fixture.gateway.flushAccountUpdates()
                fixture.awaitOutbound()
                assertTrue(fixture.wires("AccountUpdate").isEmpty())
                assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first() })
            } finally { fixture.close() }
        }
    }

    @Test
    fun theFinalValidatorCanVetoUnderTheCanonicalRoomAndPrivateAccountLocks() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.joinFixtureRoom()
            val previous = fixture.wardrobe()
            val expected = fixture.expectation()
            val appearance = BcWardrobePlanner().saveAppearance(expected.character, standardCatalog())
            var called = false

            val failure = runCatching {
                fixture.gateway.saveWardrobe(BcWardrobeSaveRequest(1, previous.revision), appearance, expected) {
                    called = true
                    assertTrue(Thread.holdsLock(fixture.store))
                    assertTrue(Thread.holdsLock(requireNotNull(field(fixture.requests, "privateChatLock"))))
                    throw IllegalArgumentException("Preview changed")
                }
            }.exceptionOrNull()

            assertTrue(called)
            assertTrue(failure is IllegalArgumentException)
            assertEquals("Preview changed", failure?.message)
            assertEquals(previous, fixture.wardrobe())
            assertTrue(fixture.wires("AccountUpdate").isEmpty())
            assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first() })
        } finally { fixture.close() }
    }

    @Test
    fun ownedInventoryAndGameHiddenCommandsInvalidateInputsBeforeQueuedRestorationEmits() = runBlocking {
        listOf("OwnerRuleKeyConfiscate", "OwnerRuleGGTS15").forEach { content ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.joinFixtureRoom()
                val previous = fixture.wardrobe()
                val expected = fixture.expectation()
                val command = fixture.restoreCommand()
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.restoreSelfAppearance(command, expected) {} }
                }
                fixture.hidden(content, sender = 10)
                assertEquals(previous, fixture.wardrobe())
                fixture.hidden(content)
                val invalidated = fixture.wardrobe()
                assertEquals(previous.revision + 1L, invalidated.revision)
                assertEquals(previous.rawWardrobeJson, invalidated.rawWardrobeJson)
                assertEquals(previous.rawNamesJson, invalidated.rawNamesJson)
                assertEquals(previous.privateInputs.inventoryJson, invalidated.privateInputs.inventoryJson)
                assertEquals(previous.privateInputs.gameJson, invalidated.privateInputs.gameJson)
                val inventoryChanged = content == "OwnerRuleKeyConfiscate"
                assertEquals(!inventoryChanged, invalidated.privateInputs.inventoryAvailable)
                assertEquals(inventoryChanged, invalidated.privateInputs.gameAvailable)
                if (inventoryChanged) {
                    assertNull(invalidated.privateInputs.inventoryItems)
                    assertNull(invalidated.privateInputs.inventoryIds)
                }
                val event = fixture.wardrobeEvent()
                assertEquals(previous.selfRuleLogSessionId, event.selfRuleLogSessionId)
                assertEquals(10, event.ownerMemberNumber)
                assertEquals(invalidated, event.state)
                fixture.releaseOutbound()

                assertRejected(BcWardrobeUnavailableReason.RevisionChanged,
                    withTimeout(2_000L) { sending.await() }.exceptionOrNull())
                assertRejected(BcWardrobeUnavailableReason.PrivateStateUnavailable,
                    runCatching { fixture.gateway.restoreSelfAppearance(command.copy(wardrobeRevision = invalidated.revision), fixture.expectation()) {} }
                        .exceptionOrNull())
                fixture.awaitOutbound()
                assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
                assertTrue(fixture.wires("AccountUpdate").isEmpty())
                assertEquals(invalidated, fixture.wardrobe())
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated } })
            } finally { fixture.close() }
        }
    }

    @Test
    fun anOwnedChangeBlockImmediatelyStopsTheOldQueueAndNewWardrobeAttempts() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.joinFixtureRoom()
            fixture.receive("ServerInfo", """{"OnlinePlayers":1,"Time":1000000}""")
            withTimeout(2_000L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.ServerInfo } }
            val previous = fixture.wardrobe()
            val expected = fixture.expectation()
            val command = fixture.restoreCommand()
            fixture.blockOutbound()
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.gateway.restoreSelfAppearance(command, expected) {} }
            }
            fixture.hidden("OwnerRuleChangeBlock")
            assertTrue(requireNotNull(fixture.requests.authenticatedPrivateChatState).state.ruleLog.orEmpty()
                .any { it.name == "BlockChange" && it.group == "OwnerRule" })
            fixture.releaseOutbound()

            assertTrue(withTimeout(2_000L) { sending.await() }.exceptionOrNull() is IllegalStateException)
            assertRejected(BcWardrobeUnavailableReason.BlockChangeOwnerRule,
                runCatching { fixture.gateway.restoreSelfAppearance(command, fixture.expectation()) {} }.exceptionOrNull())
            fixture.awaitOutbound()
            assertEquals(previous, fixture.wardrobe())
            assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
            assertTrue(fixture.wires("AccountUpdate").all { (_, raw) ->
                JsonParser.parseString(raw).asJsonObject.keySet().none { it == "Wardrobe" || it == "Appearance" }
            })
            assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated } })
        } finally { fixture.close() }
    }

    @Test
    fun explicitSelfPrivateEvidenceInvalidatesQueuedRestorationWhileOtherMembersAndOmissionDoNot() = runBlocking {
        listOf("inventory", "bad inventory", "GGTS", "bad game", "LARP", "MagicBattle", "other member", "omitted").forEach { evidence ->
            val fixture = Fixture()
            try {
                fixture.initialize(gameJson = """{"GGTS":{"Level":0},"LARP":{"Status":""},"MagicBattle":{"Status":""},"PrivateOnly":{"Secret":7}}""")
                fixture.joinFixtureRoom()
                val previous = fixture.wardrobe()
                val command = fixture.restoreCommand()
                val expected = fixture.expectation()
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.restoreSelfAppearance(command, expected) {} }
                }
                val character = if (evidence == "other member") {
                    JsonParser.parseString("""{"ID":"20","MemberNumber":20,"Name":"Owner","InventoryData":"changed","Game":{"GGTS":{"Level":3}}}""").asJsonObject
                } else JsonParser.parseString(SELF_CHARACTER).asJsonObject.apply {
                    when (evidence) {
                        "inventory" -> addProperty("InventoryData", "changed")
                        "bad inventory" -> add("InventoryData", JsonParser.parseString("[]"))
                        "GGTS" -> add("Game", JsonParser.parseString("""{"GGTS":{"Level":3}}"""))
                        "bad game" -> add("Game", JsonParser.parseString("""{"GGTS":{"Level":"3"}}"""))
                        "LARP" -> add("Game", JsonParser.parseString("""{"LARP":{"Status":"Running"}}"""))
                        "MagicBattle" -> add("Game", JsonParser.parseString("""{"MagicBattle":{"Status":"Running"}}"""))
                    }
                }
                fixture.receive("ChatRoomSyncCharacter", JsonObject().apply {
                    addProperty("SourceMemberNumber", character.get("MemberNumber").asInt)
                    add("Character", character)
                }.toString())
                val changed = evidence != "other member" && evidence != "omitted"
                val latest = fixture.wardrobe()
                assertEquals(previous.privateInputs.gameJson, latest.privateInputs.gameJson)
                assertEquals(previous.privateInputs.inventoryJson, latest.privateInputs.inventoryJson)
                assertEquals(previous.privateInputs.inventoryData, latest.privateInputs.inventoryData)
                assertEquals(previous.rawWardrobeJson, latest.rawWardrobeJson)
                assertEquals(previous.rawNamesJson, latest.rawNamesJson)
                if (changed) {
                    assertEquals(previous.revision + 1L, latest.revision)
                    val inventoryChanged = evidence == "inventory" || evidence == "bad inventory"
                    assertEquals(!inventoryChanged, latest.privateInputs.inventoryAvailable)
                    assertEquals(inventoryChanged, latest.privateInputs.gameAvailable)
                    val event = fixture.wardrobeEvent()
                    assertEquals(10, event.ownerMemberNumber)
                    assertEquals(previous.selfRuleLogSessionId, event.selfRuleLogSessionId)
                    assertEquals(latest, event.state)
                } else assertEquals(previous, latest)
                fixture.releaseOutbound()

                val failure = withTimeout(2_000L) { sending.await() }.exceptionOrNull()
                if (changed) {
                    assertRejected(BcWardrobeUnavailableReason.RevisionChanged, failure)
                    assertRejected(BcWardrobeUnavailableReason.PrivateStateUnavailable,
                        runCatching { fixture.gateway.restoreSelfAppearance(command.copy(wardrobeRevision = latest.revision), fixture.expectation()) {} }
                            .exceptionOrNull())
                    fixture.awaitOutbound()
                    assertTrue(fixture.wires("ChatRoomCharacterUpdate").isEmpty())
                    assertTrue(fixture.wires("AccountUpdate").isEmpty())
                } else {
                    assertNull(failure)
                    assertEquals(0, fixture.awaitWire("ChatRoomCharacterUpdate").get("ID").asInt)
                    assertEquals(previous, fixture.wardrobe())
                }
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated } })
            } finally { fixture.close() }
        }
    }

    private class Fixture {
        val gateway = RazorGatewayImpl(ruleCatalogProvider = { standardCatalog() })
        val socket = RecordingSocket()
        val requests = field(gateway, "requests") as RazorRequestCoordinator
        val store = field(gateway, "roomStateStore") as RazorRoomStateStore
        private val transport = field(gateway, "transport") as RazorSocketTransport
        private val outbound = field(gateway, "outbound") as RazorOutboundPipeline
        private val executor = field(outbound, "executor") as ScheduledExecutorService
        private val release = CountDownLatch(1)

        init {
            setField(transport, "socket", socket)
            setField(gateway, "selectedServerConfig", BcServerConfig("http://localhost", "R129"))
            setField(gateway, "currentBcVersion", "R129")
            RazorSocketTransport::class.java.getDeclaredMethod("bindSocketCallbacks", Socket::class.java,
                Long::class.javaPrimitiveType, CompletableDeferred::class.java)
                .apply { isAccessible = true }.invoke(transport, socket, transport.captureGeneration(), CompletableDeferred<Unit>())
        }

        suspend fun initialize(
            wardrobeJson: String? = legacyWardrobe().toString(),
            namesJson: String? = names().toString(),
            gameJson: String? = "{}",
            inventoryJson: String? = "[]",
            owner: String? = null,
        ): BcSessionLoginResult.Success = coroutineScope {
            val login = async(start = CoroutineStart.UNDISPATCHED) { gateway.login("account", "password") }
            awaitWire("AccountLogin")
            val payload = JsonParser.parseString(LOGIN_RESPONSE).asJsonObject.apply {
                wardrobeJson?.let { add("Wardrobe", JsonParser.parseString(it)) }
                namesJson?.let { add("WardrobeCharacterNames", JsonParser.parseString(it)) }
                gameJson?.let { add("Game", JsonParser.parseString(it)) }
                inventoryJson?.let { add("Inventory", JsonParser.parseString(it)) }
                owner?.let { addProperty("Owner", it) }
            }
            receive("LoginResponse", payload.toString())
            val result = withTimeout(2_000L) { login.await() } as BcSessionLoginResult.Success
            val reset = withTimeout(2_000L) { gateway.events.first() } as BcSessionGatewayEvent.RoomPresenceReset
            assertEquals(10, reset.accountMemberNumber)
            assertEquals(result.selfRuleLogSessionId, reset.selfRuleLogSessionId)
            result
        }

        fun receive(event: String, payload: String, from: RecordingSocket = socket) {
            incomingListener(from).call(event, JSONTokener(payload).nextValue())
        }
        fun hidden(content: String, sender: Int = 20) {
            receive("ChatRoomMessage", """{"Sender":$sender,"Target":10,"Type":"Hidden","Content":"$content"}""")
        }

        suspend fun joinFixtureRoom(): BcSessionGatewayEvent.RoomJoined {
            receive("ChatRoomSync", """{"Name":"A","Game":"","Character":[$SELF_CHARACTER,{"ID":"20","MemberNumber":20,"Name":"Owner"}]}""")
            return withTimeout(2_000L) { gateway.events.first { it is BcSessionGatewayEvent.RoomJoined } }
                as BcSessionGatewayEvent.RoomJoined
        }

        fun expectation(): BcSelfStateEmitExpectation {
            val characters = store.captureCharacterSnapshots()
            val self = characters.single { it.isSelf }
            return BcSelfStateEmitExpectation(self, characters.any { it.memberNumber == self.ownership?.memberNumber },
                selfRuleLogSessionId = requests.authenticatedSelfRuleLogSessionId)
        }

        fun wardrobe() = requireNotNull(requests.authenticatedWardrobeState)
        fun restoreCommand(): BcSelfAppearanceUpdateCommand {
            val self = expectation().character
            val room = requireNotNull(store.captureRoomProjection())
            val context = BcWardrobeContext(self, room.name, room.space, room.game,
                store.captureCharacterSnapshots().map { it.memberNumber }.toSet(), room.blockCategories,
                wardrobe(), requireNotNull(requests.authenticatedPrivateChatState).state.ruleLog,
                wardrobe().selfRuleLogSessionId, serverTimeMillis = null)
            val plan = BcWardrobePlanner().plan(context, 0, standardCatalog())
            assertNull(plan.unavailableReason)
            assertTrue(plan.hasChanges)
            return BcSelfAppearanceUpdateCommand(plan.appearance, self.activePose, wardrobe().revision, wardrobeSlotIndex = 0)
        }

        suspend fun perform(action: String, expected: BcSelfStateEmitExpectation, command: BcSelfAppearanceUpdateCommand) {
            when (action) {
                "save" -> gateway.saveWardrobe(BcWardrobeSaveRequest(1, command.wardrobeRevision),
                    BcWardrobePlanner().saveAppearance(expected.character, standardCatalog()), expected) {}
                "rename" -> gateway.renameWardrobe(BcWardrobeRenameRequest(0, command.wardrobeRevision, "New Name"), expected) {}
                else -> gateway.restoreSelfAppearance(command, expected) {}
            }
        }

        fun enableReconnect(): RecordingSocket = RecordingSocket().also { replacement ->
            val factory: (URI, IO.Options) -> Socket = { _, _ -> replacement }
            setField(transport, "socketFactory", factory)
        }

        suspend fun wardrobeEvent() = withTimeout(2_000L) {
            gateway.events.first { it is BcSessionGatewayEvent.SelfWardrobeStateUpdated }
        } as BcSessionGatewayEvent.SelfWardrobeStateUpdated

        suspend fun awaitWire(eventName: String, from: RecordingSocket = socket): JsonObject = withTimeout(5_000L) {
            var packet = from.outgoing.receive()
            while (packet.first != eventName) packet = from.outgoing.receive()
            JsonParser.parseString(packet.second).asJsonObject
        }
        fun wires(eventName: String) = socket.events.filter { it.first == eventName }
        fun blockOutbound() {
            val entered = CountDownLatch(1)
            executor.submit { entered.countDown(); release.await() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
        }
        fun releaseOutbound() { release.countDown() }
        fun awaitOutbound() { executor.submit {}.get(1, TimeUnit.SECONDS) }
        fun close() { release.countDown(); gateway.close() }
    }

    private class RecordingSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val outgoing = Channel<Pair<String, String>>(Channel.UNLIMITED)
        var failEvent: String? = null
        override fun connected(): Boolean = true
        override fun connect(): Socket { listeners(Socket.EVENT_CONNECT).forEach { it.call() }; return this }
        override fun emit(event: String, vararg args: Any?): Emitter {
            check(failEvent != event) { "Socket emission failed for $event." }
            val packet = event to args.single().toString()
            events += packet
            outgoing.trySend(packet)
            return this
        }
        override fun disconnect(): Socket = this
    }

    private companion object {
        fun assertRejected(reason: BcWardrobeUnavailableReason, failure: Throwable?) {
            assertTrue("Expected $reason, got $failure", failure is BcWardrobeEmissionRejectedException)
            assertEquals(reason, (failure as BcWardrobeEmissionRejectedException).reason)
        }
        fun legacyWardrobe() = JsonArray().apply {
            add(JsonParser.parseString(SAVED_SLOT))
            repeat(23) { add(com.google.gson.JsonNull.INSTANCE) }
            add(JsonParser.parseString("""[{"Name":"FutureHair","Group":"FutureGroup","Color":"#778899","Property":{"Future":{"keep":[null,2]}}}]"""))
            add(JsonParser.parseString("[]"))
        }
        fun compressedWardrobe(): String {
            val compact = JsonArray()
            legacyWardrobe().forEach { slot ->
                compact.add(JsonArray().apply {
                    if (slot.isJsonArray) slot.asJsonArray.forEach { rawItem ->
                        add(JsonArray().apply {
                            listOf("Name", "Group", "Color", "Property").forEach { field ->
                                add(rawItem.asJsonObject.get(field)?.deepCopy() ?: com.google.gson.JsonNull.INSTANCE)
                            }
                        })
                    }
                })
            }
            return com.google.gson.JsonPrimitive(LzStringUtf16Codec.compressToUtf16(compact.toString())).toString()
        }
        fun names() = JsonArray().apply { repeat(26) { add("Slot $it") } }
        fun standardCatalog() = BcRuleCatalogParser().parse("R129", """
            var AssetFemale3DCG = [
                { Group:"BodyUpper", Category:"Appearance", AllowNone:false, Asset:[{Name:"Normal",Value:0}] },
                { Group:"HairFront", Category:"Appearance", AllowNone:false, Asset:[{Name:"FrontHair1",Value:0},{Name:"FrontHair2",Value:0}] },
                { Group:"Cloth", Category:"Appearance", Clothing:true, Asset:[{Name:"Shirt",Value:0},{Name:"Dress",Value:0}] },
                { Group:"Eyes", Category:"Appearance", AllowNone:false, AllowExpression:[null,"Closed"], Asset:[{Name:"Eyes1",Value:0}] },
                { Group:"ItemArms", Category:"Item", Asset:[{Name:"LeatherCuffs",Value:0}] }
            ];
        """.trimIndent())
        @Suppress("UNCHECKED_CAST")
        fun incomingListener(socket: Socket): Emitter.Listener =
            (Socket::class.java.getDeclaredField("onAnyIncomingListeners").apply { isAccessible = true }
                .get(socket) as Collection<Emitter.Listener>).single()
        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)
        fun setField(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(instance, value) }
        const val SAVED_SLOT = """[
            {"Group":"BodyUpper","Name":"Normal","Color":"#DDBBAA"},
            {"Group":"HairFront","Name":"FrontHair2","Color":"#334455"},
            {"Group":"Cloth","Name":"Dress","Color":["#ABCDEF","#123456"]},
            {"Group":"Eyes","Name":"Eyes1"}
        ]"""
        const val SELF_CHARACTER = """{"ID":"10","MemberNumber":10,"Name":"Self","AssetFamily":"Female3DCG",
            "Ownership":{"MemberNumber":20,"Name":"Owner","Stage":1},"ActivePose":["Kneel"],"Appearance":[
                {"Group":"ItemArms","Name":"LeatherCuffs","Color":["#112233","Default"],"Difficulty":17,
                 "Property":{"Future":{"keep":[1,null]}},"Craft":{"Name":"Personal cuffs","MemberNumber":20}},
                {"Group":"BodyUpper","Name":"Normal","Color":"#DDBBAA"},
                {"Group":"HairFront","Name":"FrontHair1","Color":"#334455"},
                {"Group":"Cloth","Name":"Shirt","Color":"#ABCDEF"},
                {"Group":"Eyes","Name":"Eyes1","Property":{"Expression":"Closed"}}
            ]}"""
        const val LOGIN_RESPONSE = """{"ID":"10","MemberNumber":10,"Name":"Self",
            "Log":[{"Name":"Wardrobe","Group":"PrivateRoom"}],"ChatSettings":{}}"""
    }
}
