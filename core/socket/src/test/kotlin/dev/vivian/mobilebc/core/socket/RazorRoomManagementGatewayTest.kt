package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomBasicField
import dev.vivian.mobilebc.core.model.BcRoomBasicSettings
import dev.vivian.mobilebc.core.model.BcRoomCreateCommand
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationUnknownReason
import dev.vivian.mobilebc.core.model.BcRoomSpace
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.net.URI
import java.util.Collections
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
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class RazorRoomManagementGatewayTest {
    @Test
    fun creationUsesNativeFieldsTheAuthenticatedAdministratorAndExplicitSpace() = runBlocking {
        BcRoomSpace.entries.forEach { space ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                val command = BcRoomCreateCommand(settings("  Created  ").copy(description = "  description  ",
                    access = listOf("Whitelist", "Admin")), space)
                val creating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.createRoom(command) }
                val payload = fixture.awaitWire("ChatRoomCreate")

                assertEquals(setOf("Name", "Description", "Limit", "Background", "Language", "Visibility", "Access",
                    "Space", "Admin", "Whitelist", "Ban", "Game", "BlockCategory"), payload.keySet())
                assertEquals("Created", payload.get("Name").asString)
                assertEquals("description", payload.get("Description").asString)
                assertEquals(space.wireValue, payload.get("Space").asString)
                assertEquals(json("[10]"), payload.get("Admin"))
                assertEquals(json("[]"), payload.get("Whitelist"))
                assertEquals(json("[]"), payload.get("Ban"))
                assertEquals(json("[\"Admin\",\"Whitelist\"]"), payload.get("Access"))
                assertEquals("", payload.get("Game").asString)
                assertEquals(json("[]"), payload.get("BlockCategory"))
                assertNull(fixture.roomStore.captureRoomProjection())

                fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
                fixture.sync(room("Created").apply {
                    addProperty("Description", "description")
                    add("Access", payload.get("Access"))
                })
                assertEquals("Created", withTimeout(2_000L) { creating.await() }.name.value)
            } finally { fixture.close() }
        }
    }

    @Test
    fun queuedCreationUsesLatestAutoBanPreferencesAndCachedRelationshipLists() = runBlocking {
        listOf(
            """{"AutoBanBlackList":true,"AutoBanGhostList":true}""" to "[40,50,60]",
            "{}" to "[50,60]",
            """{"AutoBanBlackList":"true","AutoBanGhostList":"false"}""" to "[50,60]",
        ).forEach { (preferences, expectedBan) ->
            val fixture = Fixture()
            try {
                fixture.initialize("""{"AutoBanBlackList":true,"AutoBanGhostList":false}""")
                fixture.blockOutbound()
                val creating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.createRoom(create()) }
                fixture.setAutoBanPreferences(preferences)
                val updatingLists = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.gateway.updateRelationshipLists(BcRelationshipListsUpdate(
                        friends = emptySet(), friendNames = emptyMap(), ignored = linkedSetOf(50, 60),
                        whitelist = emptySet(), blacklist = linkedSetOf(40, 50), submissives = emptySet(),
                    ))
                }
                fixture.releaseOutbound()
                val payload = fixture.awaitWire("ChatRoomCreate")

                assertEquals(json(expectedBan), payload.get("Ban"))
                fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
                fixture.sync(room("Created"))
                assertEquals("Created", withTimeout(2_000L) { creating.await() }.name.value)
                withTimeout(2_000L) { updatingLists.await() }
            } finally { fixture.close() }
        }
    }

    @Test
    fun creationAcceptsEitherAckOrderButOnlyAnAuthoritativeSyncCompletesIt() = runBlocking {
        listOf(false, true).forEach { ackFirst ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                val creating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.createRoom(create()) }
                fixture.awaitWire("ChatRoomCreate")
                if (ackFirst) {
                    fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
                    yield()
                    assertFalse(creating.isCompleted)
                    assertNull(fixture.roomStore.captureRoomProjection())
                }
                fixture.sync(room("Created"))
                val authoritative = fixture.joined()
                assertEquals(authoritative.room, withTimeout(2_000L) { creating.await() })
                if (!ackFirst) fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
                assertFalse(fixture.management.hasUnknownOutcome)
                assertFalse(fixture.management.isOperationActive)
            } finally { fixture.close() }
        }
    }

    @Test
    fun creationConfirmedWithoutAckRetiresOnlyItsResponseChannelUntilTheLateAckArrives() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val creating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.createRoom(create()) }
            fixture.awaitWire("ChatRoomCreate")
            fixture.sync(room("Created"))
            val created = withTimeout(2_000L) { creating.await() }
            assertEquals("Created", created.name.value)
            assertFalse(fixture.management.hasUnknownOutcome)
            fixture.receive("ChatRoomSyncMemberLeave", """{"SourceMemberNumber":10}""")

            assertRejected(BcRoomMutationRejectionReason.PendingResponse,
                runCatching { fixture.gateway.createRoom(create("Next")) }.exceptionOrNull())
            assertEquals(1, fixture.wires("ChatRoomCreate").size)
            fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
            val next = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.createRoom(create("Next")) }
            fixture.awaitWire("ChatRoomCreate")
            fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
            fixture.sync(room("Next"))
            assertEquals("Next", withTimeout(2_000L) { next.await() }.name.value)
        } finally { fixture.close() }
    }

    @Test
    fun aSuccessAckWithoutSyncTimesOutWithoutInventingARoomOrRetryingCreation() = runBlocking {
        val fixture = Fixture(timeoutMs = 1_000)
        try {
            fixture.initialize()
            val creating = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.createRoom(create()) } }
            fixture.awaitWire("ChatRoomCreate")
            fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
            val failure = withTimeout(2_000L) { creating.await() }.exceptionOrNull()

            assertUnknown(BcRoomMutationUnknownReason.TimedOut, failure)
            assertNull(fixture.roomStore.captureRoomProjection())
            assertTrue(fixture.management.hasUnknownOutcome)
            assertRejected(BcRoomMutationRejectionReason.PendingResponse,
                runCatching { fixture.gateway.createRoom(create()) }.exceptionOrNull())
            assertEquals(1, fixture.wires("ChatRoomCreate").size)
        } finally { fixture.close() }
    }

    @Test
    fun serverRejectionsAndInvalidResponsesKeepTheirOriginalWireEvidence() = runBlocking {
        listOf("\"FutureDeniedCode\"", "{\"Result\":\"ChatRoomCreated\"}", "null").forEach { response ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                val creating = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.createRoom(create()) } }
                fixture.awaitWire("ChatRoomCreate")
                fixture.receive("ChatRoomCreateResponse", response)
                val failure = withTimeout(2_000L) { creating.await() }.exceptionOrNull()

                if (response.startsWith("\"")) {
                    assertRejected(BcRoomMutationRejectionReason.ServerRejected, failure)
                    assertEquals("FutureDeniedCode", (failure as BcRoomMutationRejectedException).rejection.serverCode)
                } else {
                    assertUnknown(BcRoomMutationUnknownReason.InvalidResponse, failure)
                    assertEquals(response, (failure as BcRoomMutationOutcomeUnknownException).serverCode)
                }
                assertNull(fixture.roomStore.captureRoomProjection())
                assertEquals(1, fixture.wires("ChatRoomCreate").size)
                if (response.startsWith("\"")) {
                    val retry = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.createRoom(create("Corrected")) }
                    assertEquals("Corrected", fixture.awaitWire("ChatRoomCreate").get("Name").asString)
                    fixture.receive("ChatRoomCreateResponse", "\"ChatRoomCreated\"")
                    fixture.sync(room("Corrected"))
                    assertEquals("Corrected", withTimeout(2_000L) { retry.await() }.name.value)
                } else {
                    assertRejected(BcRoomMutationRejectionReason.PendingResponse,
                        runCatching { fixture.gateway.createRoom(create()) }.exceptionOrNull())
                    assertEquals(1, fixture.wires("ChatRoomCreate").size)
                }
            } finally { fixture.close() }
        }
    }

    @Test
    fun invalidCreationInputUnavailableCatalogAccountOrConnectionNeverReachTheWire() = runBlocking {
        val invalid = listOf(
            settings("") to BcRoomMutationRejectionReason.InvalidName,
            settings("中文") to BcRoomMutationRejectionReason.InvalidName,
            settings().copy(description = "a".repeat(301)) to BcRoomMutationRejectionReason.DescriptionTooLong,
            settings().copy(memberLimit = 1) to BcRoomMutationRejectionReason.InvalidMemberLimit,
            settings().copy(background = "Unknown") to BcRoomMutationRejectionReason.UnknownBackground,
            settings().copy(language = "Unknown") to BcRoomMutationRejectionReason.UnsupportedLanguage,
            settings().copy(visibility = listOf("Unknown")) to BcRoomMutationRejectionReason.UnsupportedVisibility,
            settings().copy(access = emptyList()) to BcRoomMutationRejectionReason.UnsupportedAccess,
        )
        invalid.forEach { (settings, reason) ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                assertRejected(reason, runCatching { fixture.gateway.createRoom(BcRoomCreateCommand(settings)) }.exceptionOrNull())
                assertTrue(fixture.socket.events.isEmpty())
            } finally { fixture.close() }
        }
        listOf("catalog", "account", "connection", "room").forEach { unavailable ->
            val fixture = Fixture()
            try {
                if (unavailable != "account") fixture.initialize()
                val reason = when (unavailable) {
                    "catalog" -> { fixture.backgrounds = null; BcRoomMutationRejectionReason.BackgroundCatalogUnavailable }
                    "account" -> BcRoomMutationRejectionReason.AccountUnavailable
                    "connection" -> { fixture.socket.online = false; BcRoomMutationRejectionReason.ConnectionUnavailable }
                    else -> { fixture.sync(room()); BcRoomMutationRejectionReason.AlreadyInRoom }
                }
                assertRejected(reason, runCatching { fixture.gateway.createRoom(create()) }.exceptionOrNull())
                assertTrue(fixture.socket.events.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun anActiveUpdateBlocksOtherUpdatesCreationAndRoomTransitions() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            val base = fixture.joined().room
            val command = editDescription(base)
            val updating = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.updateRoom(command) } }
            fixture.awaitWire("ChatRoomAdmin")

            assertRejected(BcRoomMutationRejectionReason.Busy,
                runCatching { fixture.gateway.updateRoom(command) }.exceptionOrNull())
            assertRejected(BcRoomMutationRejectionReason.Busy,
                runCatching { fixture.gateway.joinRoom("Other", null) }.exceptionOrNull())
            assertRejected(BcRoomMutationRejectionReason.Busy,
                runCatching { fixture.gateway.leaveRoom() }.exceptionOrNull())
            assertRejected(BcRoomMutationRejectionReason.Busy,
                runCatching { fixture.gateway.createRoom(create()) }.exceptionOrNull())
            assertEquals(listOf("ChatRoomAdmin"), fixture.socket.events.map { it.first })
            fixture.receive("ChatRoomUpdateResponse", "\"Denied\"")
            assertRejected(BcRoomMutationRejectionReason.ServerRejected, updating.await().exceptionOrNull())
        } finally { fixture.close() }
    }

    @Test
    fun queuedUpdatePreservesLatestUneditedRawSettingsAndNestedUnknownData() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            val command = editDescription(fixture.joined().room)
            fixture.blockOutbound()
            val updating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.updateRoom(command) }
            val latest = room().apply {
                addProperty("Limit", 13)
                addProperty("Language", "FutureLanguage")
                addProperty("Game", "FutureGame")
                add("Admin", json("[10,40]"))
                add("Whitelist", json("[50]"))
                add("Ban", json("[60]"))
                add("Visibility", json("[\"Admin\",\"FutureVisibility\"]"))
                add("Access", json("[\"FutureAccess\"]"))
                add("BlockCategory", json("[\"FutureCategory\"]"))
                add("MapData", json("""{"Type":"FutureMap","Tiles":"encoded tiles","Future":{"a":[1,null,"two"]}}"""))
                add("Custom", json("""{"ImageURL":"https://example.com/room.png","SizeMode":2,"ImageFilter":"#123456","MusicStart":3.25,"Future":{"enabled":true}}"""))
            }
            fixture.properties(latest)
            fixture.updated()
            fixture.releaseOutbound()
            val payload = fixture.awaitWire("ChatRoomAdmin")
            val sentRoom = payload.getAsJsonObject("Room")

            assertEquals(setOf("Action", "MemberNumber", "Room"), payload.keySet())
            assertEquals("Update", payload.get("Action").asString)
            assertEquals(0, payload.get("MemberNumber").asInt)
            assertEquals(UPDATE_FIELDS, sentRoom.keySet())
            assertEquals("My description", sentRoom.get("Description").asString)
            (UPDATE_FIELDS - "Description").forEach { field -> assertEquals(field, latest.get(field), sentRoom.get(field)) }
            fixture.receive("ChatRoomUpdateResponse", "\"Updated\"")
            fixture.properties(sentRoom)
            val updated = fixture.updated()
            assertEquals(updated.room, withTimeout(2_000L) { updating.await() })
        } finally { fixture.close() }
    }

    @Test
    fun renameUsesTheSameRoomEpochAndWaitsForAuthorityInEitherAckOrder() = runBlocking {
        listOf(false, true).forEach { ackFirst ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val joined = fixture.joined()
                val base = requireNotNull(joined.room.adminSettings).base
                val command = BcRoomUpdateCommand(base, base.settings.copy(name = "Renamed"))
                val updating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.updateRoom(command) }
                fixture.awaitWire("ChatRoomAdmin")
                if (ackFirst) {
                    fixture.receive("ChatRoomUpdateResponse", "\"Updated\"")
                    yield()
                    assertFalse(updating.isCompleted)
                    assertEquals("Current", fixture.roomStore.captureRoomProjection()?.name?.value)
                }
                fixture.properties(room("Renamed"))
                val authoritative = fixture.updated()
                assertEquals(joined.roomPresenceEpoch, authoritative.roomPresenceEpoch)
                assertEquals(base.roomPresenceEpoch, authoritative.room.adminSettings?.base?.roomPresenceEpoch)
                assertEquals(authoritative.room, withTimeout(2_000L) { updating.await() })
                if (!ackFirst) fixture.receive("ChatRoomUpdateResponse", "\"Updated\"")
                assertFalse(fixture.management.hasUnknownOutcome)
            } finally { fixture.close() }
        }
    }

    @Test
    fun unchangedOrUnrelatedAuthorityCannotCompleteAnUpdate() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            val command = editDescription(fixture.joined().room)
            val updating = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.updateRoom(command) }
            fixture.awaitWire("ChatRoomAdmin")
            fixture.receive("ChatRoomUpdateResponse", "\"Updated\"")
            fixture.properties(room().apply { addProperty("Limit", 12) })
            yield()
            assertFalse(updating.isCompleted)
            assertEquals("Original", fixture.roomStore.captureRoomProjection()?.description)

            fixture.properties(room().apply { addProperty("Description", "My description") })
            assertEquals("My description", withTimeout(2_000L) { updating.await() }.description)
        } finally { fixture.close() }
    }

    @Test
    fun queuedUpdateRechecksAdministratorConflictsAndRoomPresenceBeforeEmission() = runBlocking {
        listOf("admin", "conflict", "other room", "same room rejoin").forEach { changed ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val command = editDescription(fixture.joined().room)
                fixture.blockOutbound()
                val updating = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.updateRoom(command) } }
                val expected = when (changed) {
                    "admin" -> {
                        fixture.properties(room().apply { add("Admin", json("[20]")) })
                        BcRoomMutationRejectionReason.NotAdministrator
                    }
                    "conflict" -> {
                        fixture.properties(room().apply { addProperty("Description", "Another edit") })
                        BcRoomMutationRejectionReason.EditConflict
                    }
                    "other room" -> { fixture.sync(room("Other")); BcRoomMutationRejectionReason.RoomChanged }
                    else -> { fixture.sync(room()); BcRoomMutationRejectionReason.RoomChanged }
                }
                fixture.releaseOutbound()
                val failure = withTimeout(2_000L) { updating.await() }.exceptionOrNull()
                assertRejected(expected, failure)
                fixture.awaitOutbound()
                assertTrue(fixture.wires("ChatRoomAdmin").isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun incompleteOrUnknownRawSettingsStopUpdatesInsteadOfFillingDefaults() = runBlocking {
        listOf("missing", "unknown", "shape").forEach { unavailable ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val command = editDescription(fixture.joined().room)
                val broken = room().apply {
                    when (unavailable) {
                        "missing" -> remove("Ban")
                        "unknown" -> addProperty("FutureTopLevelSetting", true)
                        else -> add("Access", json("[42]"))
                    }
                }
                fixture.properties(broken)

                assertRejected(BcRoomMutationRejectionReason.SettingsUnavailable,
                    runCatching { fixture.gateway.updateRoom(command) }.exceptionOrNull())
                assertTrue(fixture.socket.events.isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun roomEventsCarryTheCurrentLoginOwnerAndEpochAndLoginResetsOldPresence() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            val reset = fixture.resetEvent()
            val token = fixture.requests.authenticatedSelfRuleLogSessionId
            assertEquals(10, reset.accountMemberNumber)
            assertEquals(token, reset.selfRuleLogSessionId)
            assertEquals(fixture.roomStore.captureReceiveContext().roomPresenceEpoch, reset.roomPresenceEpoch)
            fixture.sync(room())
            val joined = fixture.joined()
            assertEquals(10, joined.accountMemberNumber)
            assertEquals(token, joined.selfRuleLogSessionId)
            assertEquals(fixture.roomStore.captureReceiveContext().roomPresenceEpoch, joined.roomPresenceEpoch)
            fixture.properties(room().apply { addProperty("Description", "Authoritative update") })
            val updated = fixture.updated()
            assertEquals(joined.accountMemberNumber, updated.accountMemberNumber)
            assertEquals(joined.selfRuleLogSessionId, updated.selfRuleLogSessionId)
            assertEquals(joined.roomPresenceEpoch, updated.roomPresenceEpoch)

            fixture.initialize()
            val replacementReset = fixture.resetEvent()
            assertEquals(10, replacementReset.accountMemberNumber)
            assertEquals(fixture.requests.authenticatedSelfRuleLogSessionId, replacementReset.selfRuleLogSessionId)
            assertTrue(token != replacementReset.selfRuleLogSessionId)
            assertTrue(joined.roomPresenceEpoch != replacementReset.roomPresenceEpoch)
            assertNull(fixture.roomStore.captureRoomProjection())
        } finally { fixture.close() }
    }

    @Test
    fun queuedCreationAndUpdatesCannotEmitAcrossASameAccountLogin() = runBlocking {
        listOf(false, true).forEach { update ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                val command = if (update) {
                    fixture.sync(room())
                    editDescription(fixture.joined().room)
                } else null
                val oldToken = fixture.requests.authenticatedSelfRuleLogSessionId
                val oldIncoming = fixture.enableReconnect()
                fixture.blockOutbound()
                val mutation = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching {
                        if (command == null) fixture.gateway.createRoom(create()) else fixture.gateway.updateRoom(command)
                    }
                }
                val login = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.login("account", "password") }
                val failure = withTimeout(2_000L) { mutation.await() }.exceptionOrNull()
                assertUnknown(BcRoomMutationUnknownReason.Disconnected, failure)
                assertTrue(failure?.cause is OutboxDispatchDeferredException)
                fixture.releaseOutbound()
                val replacement = requireNotNull(fixture.replacementSocket)
                fixture.awaitWire("AccountLogin", replacement)
                val loginResponse = JSONObject("""{"ID":"10","MemberNumber":10,"Name":"Self","Log":[],"ChatSettings":{}}""")
                oldIncoming.call("LoginResponse", loginResponse)
                oldIncoming.call("ChatRoomSync", JSONObject("""{"Name":"Old socket room","Character":[]}"""))
                yield()
                assertFalse(login.isCompleted)
                assertNull(fixture.requests.authenticatedPrivateChatState)
                assertNull(fixture.roomStore.captureRoomProjection())
                incomingListener(replacement).call("LoginResponse", loginResponse)
                assertTrue(withTimeout(2_000L) { login.await() } is BcSessionLoginResult.Success)
                assertTrue(oldToken != fixture.requests.authenticatedSelfRuleLogSessionId)
                fixture.awaitOutbound()
                assertTrue(fixture.socket.events.isEmpty())
                assertEquals(listOf("AccountLogin"), replacement.events.map { it.first })
                assertNull(fixture.roomStore.captureRoomProjection())
            } finally { fixture.close() }
        }
    }

    private class Fixture(timeoutMs: Long? = null) {
        var backgrounds: Set<String>? = setOf("MainHall")
        val gateway = RazorGatewayImpl(roomBackgroundNamesProvider = { backgrounds })
        val socket = RecordingSocket()
        var replacementSocket: RecordingSocket? = null
            private set
        val requests = field(gateway, "requests") as RazorRequestCoordinator
        val roomStore = field(gateway, "roomStateStore") as RazorRoomStateStore
        val management: RazorRoomManagementRequests
            get() = field(gateway, "roomManagementRequests") as RazorRoomManagementRequests
        private val outbound = field(gateway, "outbound") as RazorOutboundPipeline
        private val executor = field(outbound, "executor") as ScheduledExecutorService
        private val release = CountDownLatch(1)

        init {
            setField(field(gateway, "transport") as RazorSocketTransport, "socket", socket)
            if (timeoutMs != null) setField(gateway, "roomManagementRequests", RazorRoomManagementRequests(timeoutMs))
        }

        suspend fun initialize(onlineSettings: String = """{"AutoBanBlackList":false,"AutoBanGhostList":false}""") = coroutineScope {
            val login = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
            receive("LoginResponse", """{"ID":"10","MemberNumber":10,"Name":"Self","Log":[],"ChatSettings":{},"BlackList":[20,30],"GhostList":[30,40],"OnlineSettings":$onlineSettings}""")
            assertTrue(login.await() is BcSessionLoginResult.Success)
        }

        fun setAutoBanPreferences(value: String) {
            val current = requireNotNull(requests.authenticatedPrivateChatState)
            assertTrue(requests.compareAndSetPrivateChatState(current, current.state.copy(
                preferences = current.state.preferences.copy(onlineSettingsJson = value),
            )) != null)
        }

        fun enableReconnect(): Emitter.Listener {
            val transport = field(gateway, "transport") as RazorSocketTransport
            setField(gateway, "selectedServerConfig", BcServerConfig(serverUrl = "http://localhost", version = "R129"))
            val factory: (URI, IO.Options) -> Socket = { _, _ ->
                RecordingSocket().also { replacementSocket = it }
            }
            setField(transport, "socketFactory", factory)
            RazorSocketTransport::class.java.getDeclaredMethod("bindSocketCallbacks", Socket::class.java,
                Long::class.javaPrimitiveType, CompletableDeferred::class.java)
                .apply { isAccessible = true }.invoke(transport, socket, transport.captureGeneration(), CompletableDeferred<Unit>())
            return incomingListener(socket)
        }

        fun sync(settings: JsonObject) {
            receive("ChatRoomSync", settings.deepCopy().apply {
                add("Character", json("""[{"ID":"10","MemberNumber":10,"Name":"Self"},{"ID":"20","MemberNumber":20,"Name":"Peer"}]"""))
            }.toString())
        }

        fun properties(settings: JsonObject) { receive("ChatRoomSyncRoomProperties", settings.toString()) }
        fun receive(event: String, payload: String) {
            RazorGatewayImpl::class.java.getDeclaredMethod("handleIncomingEvent", String::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(gateway, event, payload)
        }

        suspend fun joined() = withTimeout(2_000L) {
            gateway.events.first { it is BcSessionGatewayEvent.RoomJoined } as BcSessionGatewayEvent.RoomJoined
        }

        suspend fun updated() = withTimeout(2_000L) {
            gateway.events.first { it is BcSessionGatewayEvent.RoomUpdated } as BcSessionGatewayEvent.RoomUpdated
        }

        suspend fun resetEvent() = withTimeout(2_000L) {
            gateway.events.first { it is BcSessionGatewayEvent.RoomPresenceReset } as BcSessionGatewayEvent.RoomPresenceReset
        }

        suspend fun awaitWire(eventName: String, from: RecordingSocket = socket): JsonObject = withTimeout(5_000L) {
            var next = from.outgoing.receive()
            while (next.first != eventName) next = from.outgoing.receive()
            JsonParser.parseString(next.second).asJsonObject
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
        var online = true
        override fun connected(): Boolean = online
        override fun connect(): Socket {
            listeners(Socket.EVENT_CONNECT).forEach { it.call() }
            return this
        }
        override fun emit(event: String, vararg args: Any?): Emitter {
            val packet = event to args.single().toString()
            events += packet
            outgoing.trySend(packet)
            return this
        }
        override fun disconnect(): Socket = this
    }

    private companion object {
        fun create(name: String = "Created") = BcRoomCreateCommand(settings(name))
        fun settings(name: String = "Created") = BcRoomBasicSettings(name = name)
        fun editDescription(room: BcRoom): BcRoomUpdateCommand {
            val base = requireNotNull(room.adminSettings).base
            return BcRoomUpdateCommand(base, base.settings.copy(description = "My description"), setOf(BcRoomBasicField.Description))
        }

        fun room(name: String = "Current") = json("""{
            "Name":"$name","Description":"Original","Limit":10,"Background":"MainHall","Language":"EN",
            "Admin":[10,20],"Whitelist":[],"Ban":[],"Game":"","BlockCategory":[],"Visibility":["All"],"Access":["All"],
            "MapData":{"Type":"Hybrid","Tiles":"original tiles","Objects":"original objects"},
            "Custom":{"ImageURL":"https://example.com/original.png","Future":{"keep":true}},
            "Space":"X","Locked":false,"SourceMemberNumber":20,"CanJoin":true
        }""").asJsonObject

        fun json(value: String) = JsonParser.parseString(value)
        @Suppress("UNCHECKED_CAST")
        fun incomingListener(socket: Socket): Emitter.Listener =
            (Socket::class.java.getDeclaredField("onAnyIncomingListeners").apply { isAccessible = true }
                .get(socket) as Collection<Emitter.Listener>).single()
        fun assertRejected(reason: BcRoomMutationRejectionReason, failure: Throwable?) {
            assertTrue("Expected $reason, got $failure", failure is BcRoomMutationRejectedException)
            assertEquals(reason, (failure as BcRoomMutationRejectedException).rejection.reason)
        }

        fun assertUnknown(reason: BcRoomMutationUnknownReason, failure: Throwable?) {
            assertTrue("Expected $reason, got $failure", failure is BcRoomMutationOutcomeUnknownException)
            assertEquals(reason, (failure as BcRoomMutationOutcomeUnknownException).reason)
        }

        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)
        fun setField(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(instance, value) }

        val UPDATE_FIELDS = setOf("Name", "Language", "Description", "Background", "Limit", "Admin", "Whitelist", "Ban",
            "BlockCategory", "Game", "Visibility", "Access", "MapData", "Custom")
    }
}
