package dev.vivian.mobilebc.core.socket

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcMember
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminAction
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminCommand
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMemberAdminRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomMutationOutcomeUnknownException
import dev.vivian.mobilebc.core.model.BcRoomRemovalReason
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.RoomRole
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.domain.session.core.BcRoomJoinRejectedException
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import io.socket.client.IO
import io.socket.client.Manager
import io.socket.client.Socket
import io.socket.emitter.Emitter
import java.lang.management.ManagementFactory
import java.net.URI
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorRoomMemberAdministrationGatewayTest {
    @Test
    fun allFourActionsEmitTheirExactNativePayloadWithoutWaitingOrChangingLocalAuthority() = runBlocking {
        BcRoomMemberAdminAction.entries.forEach { action ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room(targetAdmin = action == BcRoomMemberAdminAction.Demote))
                val command = command(fixture.joined(), action)
                val members = fixture.store.captureMemberProjection()
                val settings = fixture.store.captureRawSettings()
                val room = fixture.store.captureRoomProjection()

                withTimeout(2_000L) { fixture.gateway.manageRoomMember(command) }

                val payload = fixture.awaitWire("ChatRoomAdmin")
                val publishes = action == BcRoomMemberAdminAction.Kick || action == BcRoomMemberAdminAction.Ban
                assertEquals(if (publishes) setOf("MemberNumber", "Action", "Publish") else setOf("MemberNumber", "Action"), payload.keySet())
                assertEquals(20, payload.get("MemberNumber").asInt)
                assertEquals(action.wireValue, payload.get("Action").asString)
                if (publishes) assertTrue(payload.get("Publish").asBoolean)
                assertEquals(members, fixture.store.captureMemberProjection())
                assertEquals(settings, fixture.store.captureRawSettings())
                assertEquals(room, fixture.store.captureRoomProjection())
                assertEquals(1, fixture.wires("ChatRoomAdmin").size)
            } finally { fixture.close() }
        }
    }

    @Test
    fun verifiedAdministratorsCanManageMembersWhenUnrelatedRawRoomSettingsAreUnsupported() = runBlocking {
        BcRoomMemberAdminAction.entries.forEach { action ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room(targetAdmin = action == BcRoomMemberAdminAction.Demote).apply {
                    addProperty("Game", "FutureGame")
                    add("Access", json("[\"FutureAccess\"]"))
                    add("BlockCategory", json("[\"FutureCategory\"]"))
                    add("FutureTopLevelSetting", json("""{"keep":[1,null,"two"]}"""))
                })
                val joined = fixture.joined()
                assertNull(joined.room.adminSettings)
                assertEquals(RoomRole.Admin, joined.members.single { it.isSelf }.role)

                withTimeout(2_000L) { fixture.gateway.manageRoomMember(command(joined, action)) }

                assertEquals(action.wireValue, fixture.awaitWire("ChatRoomAdmin").get("Action").asString)
                assertEquals(1, fixture.wires("ChatRoomAdmin").size)
            } finally { fixture.close() }
        }
    }

    @Test
    fun absentOrMalformedAdministratorEvidenceMakesRolesUnknownAndStopsAdministration() = runBlocking {
        listOf<String?>(null, "null", "[\"10\"]", "[10,true]", "[10,20.5]", "[10,2147483648]", "[10,0]").forEach { evidence ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val command = command(fixture.joined())
                fixture.properties(room().apply {
                    if (evidence == null) remove("Admin") else add("Admin", json(evidence))
                })

                assertTrue(fixture.store.captureMemberProjection().all { it.role == null })
                assertRejected(BcRoomMemberAdminRejectionReason.SettingsUnavailable,
                    runCatching { fixture.gateway.manageRoomMember(command) }.exceptionOrNull())
                assertTrue(fixture.wires("ChatRoomAdmin").isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun selfMissingTargetsAndUnavailableAccountConnectionOrRoomAreRejectedBeforeQueueing() = runBlocking {
        listOf("self", "missing", "nonpositive", "account", "connection", "room").forEach { unavailable ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val joined = fixture.joined()
                var command = command(joined)
                val reason = when (unavailable) {
                    "self" -> { command = command(joined, target = 10); BcRoomMemberAdminRejectionReason.SelfTarget }
                    "missing" -> { command = command.copy(targetMemberNumber = 999); BcRoomMemberAdminRejectionReason.TargetUnavailable }
                    "nonpositive" -> { command = command.copy(targetMemberNumber = 0); BcRoomMemberAdminRejectionReason.TargetUnavailable }
                    "account" -> { fixture.requests.disconnect("Fixture account ended"); BcRoomMemberAdminRejectionReason.AccountUnavailable }
                    "connection" -> { fixture.socket.online = false; BcRoomMemberAdminRejectionReason.ConnectionUnavailable }
                    else -> { fixture.receive("ChatRoomSyncMemberLeave", """{"SourceMemberNumber":10}"""); BcRoomMemberAdminRejectionReason.RoomUnavailable }
                }

                assertRejected(reason, runCatching { fixture.gateway.manageRoomMember(command) }.exceptionOrNull())
                assertTrue(fixture.wires("ChatRoomAdmin").isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun queuedActionsRecheckRoomAdministratorTargetRoleAndTheExactMemberVisit() = runBlocking {
        listOf("room", "admin", "role", "left", "rejoined", "duplicate join").forEach { changed ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val command = command(fixture.joined())
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.manageRoomMember(command) }
                }
                val reason = when (changed) {
                    "room" -> { fixture.sync(room("B")); BcRoomMemberAdminRejectionReason.RoomChanged }
                    "admin" -> { fixture.properties(room().apply { add("Admin", json("[20]")) }); BcRoomMemberAdminRejectionReason.NotAdministrator }
                    "role" -> { fixture.properties(room(targetAdmin = true)); BcRoomMemberAdminRejectionReason.TargetRoleChanged }
                    "left" -> { fixture.leaveTarget(); BcRoomMemberAdminRejectionReason.TargetUnavailable }
                    "rejoined" -> { fixture.leaveTarget(); fixture.joinTarget(); BcRoomMemberAdminRejectionReason.TargetChanged }
                    else -> { fixture.joinTarget(); BcRoomMemberAdminRejectionReason.TargetChanged }
                }
                fixture.releaseOutbound()

                assertRejected(reason, withTimeout(2_000L) { sending.await() }.exceptionOrNull())
                fixture.awaitOutbound()
                assertTrue(fixture.wires("ChatRoomAdmin").isEmpty())
            } finally { fixture.close() }
        }
    }

    @Test
    fun characterAndPoseUpdatesPreserveTheVisitAndAllowItsQueuedAction() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            val command = command(fixture.joined())
            fixture.blockOutbound()
            val sending = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.manageRoomMember(command) }
            fixture.receive("ChatRoomSyncCharacter", """{"SourceMemberNumber":20,"Character":$TARGET}""")
            assertEquals(command.targetPresenceEpoch, fixture.member(20).roomMemberPresenceEpoch)
            fixture.receive("ChatRoomSyncPose", """{"MemberNumber":20,"Pose":["Kneel"]}""")
            assertEquals(command.targetPresenceEpoch, fixture.member(20).roomMemberPresenceEpoch)
            fixture.releaseOutbound()

            withTimeout(2_000L) { sending.await() }
            assertEquals("Kick", fixture.awaitWire("ChatRoomAdmin").get("Action").asString)
        } finally { fixture.close() }
    }

    @Test
    fun duplicateJoinsFullSyncAndRejoinsAdvanceVisitsWhileExistingCharacterUpdatesRetainThem() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            val initial = fixture.joined()
            val firstVisit = requireNotNull(fixture.member(20).roomMemberPresenceEpoch)
            val beforeCharacter = fixture.store.captureCharacterSnapshots().single { it.memberNumber == 20 }
            fixture.joinTarget()
            val duplicate = fixture.updated()
            val duplicateVisit = requireNotNull(duplicate.members.single { it.memberNumber == 20 }.roomMemberPresenceEpoch)
            assertTrue(duplicateVisit > firstVisit)
            assertEquals(beforeCharacter, fixture.store.captureCharacterSnapshots().single { it.memberNumber == 20 })
            fixture.receive("ChatRoomSyncCharacter", """{"SourceMemberNumber":20,"Character":$TARGET}""")
            assertEquals(duplicateVisit, fixture.member(20).roomMemberPresenceEpoch)
            fixture.leaveTarget()
            assertTrue(fixture.store.captureMemberProjection().none { it.memberNumber == 20 })
            fixture.joinTarget()
            val rejoinedVisit = requireNotNull(fixture.member(20).roomMemberPresenceEpoch)
            assertTrue(rejoinedVisit > duplicateVisit)
            val beforeSync = fixture.store.captureMemberProjection().associate { it.memberNumber to requireNotNull(it.roomMemberPresenceEpoch) }
            fixture.sync(room())
            val synced = fixture.joined()
            assertTrue(synced.roomPresenceEpoch != initial.roomPresenceEpoch)
            synced.members.forEach { assertTrue(requireNotNull(it.roomMemberPresenceEpoch) > beforeSync.getValue(it.memberNumber)) }

            fixture.receive("ChatRoomSyncCharacter", """{"SourceMemberNumber":30,"Character":{"ID":"30","MemberNumber":30,"Name":"Extra"}}""")
            val newVisit = requireNotNull(fixture.member(30).roomMemberPresenceEpoch)
            assertTrue(newVisit > 0L)
            fixture.receive("ChatRoomSyncCharacter", """{"SourceMemberNumber":30,"Character":{"ID":"30","MemberNumber":30,"Name":"Extra renamed"}}""")
            fixture.receive("ChatRoomSyncPose", """{"MemberNumber":30,"Pose":["Kneel"]}""")
            assertEquals(newVisit, fixture.member(30).roomMemberPresenceEpoch)
        } finally { fixture.close() }
    }

    @Test
    fun callbacksFromTheReplacedSocketCannotRemoveOrDowngradeTheNewRoom() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            fixture.joined()
            val oldIncoming = incomingListener(fixture.socket)
            val oldToken = fixture.requests.authenticatedSelfRuleLogSessionId
            val replacement = fixture.enableReconnect()
            val login = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.login("account", "password") }
            fixture.awaitWire("AccountLogin", replacement)
            fixture.receive("LoginResponse", LOGIN_RESPONSE, replacement)
            assertTrue(withTimeout(2_000L) { login.await() } is BcSessionLoginResult.Success)
            fixture.sync(room(), replacement)
            val joined = fixture.joined()
            assertTrue(oldToken != joined.selfRuleLogSessionId)
            val members = fixture.store.captureMemberProjection()
            val context = fixture.store.captureReceiveContext()
            oldIncoming.call("ChatRoomSearchResponse", "RoomKicked")
            oldIncoming.call("ChatRoomSyncMemberLeave", JSONObject("""{"SourceMemberNumber":20}"""))
            oldIncoming.call("ChatRoomSyncRoomProperties", JSONObject(room().apply { add("Admin", json("[20]")) }.toString()))

            assertEquals(context, fixture.store.captureReceiveContext())
            assertEquals(members, fixture.store.captureMemberProjection())
            withTimeout(2_000L) { fixture.gateway.manageRoomMember(command(joined)) }
            assertEquals("Kick", fixture.awaitWire("ChatRoomAdmin", replacement).get("Action").asString)
            assertTrue(fixture.wires("ChatRoomAdmin").isEmpty())
        } finally { fixture.close() }
    }

    @Test
    fun nativeKickedAndBannedTokensClearTheRoomSignRemovalAndRejectQueuedOldChat() = runBlocking {
        listOf("RoomKicked" to BcRoomRemovalReason.Kicked, "RoomBanned" to BcRoomRemovalReason.Banned).forEach { (token, reason) ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val joined = fixture.joined()
                fixture.blockOutbound()
                val sending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.sendPrepared(chat()) } }
                fixture.receive("ChatRoomSearchResponse", "\"$token\"")
                val events = withTimeout(2_000L) { fixture.gateway.events.take(2).toList() }
                val removed = events[0] as BcSessionGatewayEvent.RoomRemoved
                val reset = events[1] as BcSessionGatewayEvent.RoomPresenceReset
                assertEquals(joined.room.name, removed.roomName)
                assertEquals(reason, removed.reason)
                assertEquals(10, removed.accountMemberNumber)
                assertEquals(joined.selfRuleLogSessionId, removed.selfRuleLogSessionId)
                assertEquals(joined.roomPresenceEpoch, removed.previousRoomPresenceEpoch)
                assertTrue(removed.roomPresenceEpoch > removed.previousRoomPresenceEpoch)
                assertEquals(removed.roomPresenceEpoch, fixture.store.captureReceiveContext().roomPresenceEpoch)
                assertEquals(removed.accountMemberNumber, reset.accountMemberNumber)
                assertEquals(removed.selfRuleLogSessionId, reset.selfRuleLogSessionId)
                assertEquals(removed.roomPresenceEpoch, reset.roomPresenceEpoch)
                assertNull(fixture.store.captureRoomProjection())
                assertTrue(fixture.store.captureMemberProjection().isEmpty())
                fixture.releaseOutbound()
                assertTrue(withTimeout(2_000L) { sending.await() }.exceptionOrNull() is OutboxDispatchDeferredException)
                fixture.awaitOutbound()
                assertTrue(fixture.wires("ChatRoomChat").isEmpty())
                assertRejected(BcRoomMemberAdminRejectionReason.RoomUnavailable,
                    runCatching { fixture.gateway.manageRoomMember(command(joined)) }.exceptionOrNull())
                fixture.receive("ChatRoomSearchResponse", "\"$token\"")
                assertEquals(removed.roomPresenceEpoch, fixture.store.captureReceiveContext().roomPresenceEpoch)
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first() })
            } finally { fixture.close() }
        }
    }

    @Test
    fun removalTokensForAnActuallyEmittedJoinRemainOrdinaryJoinRejections() = runBlocking {
        listOf(false, true).forEach { hasOldRoom ->
            listOf("RoomKicked", "RoomBanned").forEach { token ->
                val fixture = Fixture()
                try {
                    fixture.initialize()
                    val previous = if (hasOldRoom) { fixture.sync(room()); fixture.joined() } else null
                    val oldCommand = previous?.let { command(it) }
                    val oldChatGuard = previous?.let { fixture.store.captureRoomMessageGuard(chat()) }
                    val joining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.joinRoom("B", null) } }
                    fixture.awaitWire("ChatRoomJoin")
                    if (previous != null) {
                        val pendingEpoch = fixture.store.captureReceiveContext().roomPresenceEpoch
                        fixture.properties(room("C"))
                        val renamed = withTimeout(2_000L) { fixture.gateway.events.first() } as BcSessionGatewayEvent.RoomUpdated
                        assertEquals("C", renamed.room.name.value)
                        assertEquals(pendingEpoch, renamed.roomPresenceEpoch)
                        assertNull(renamed.previousRoomPresenceEpoch)
                        assertNull(renamed.previousRoomName)
                    }
                    fixture.receive("ChatRoomSearchResponse", "\"$token\"")
                    val failure = withTimeout(2_000L) { joining.await() }.exceptionOrNull()

                    assertTrue(failure is BcRoomJoinRejectedException)
                    assertEquals(token, failure?.message)
                    assertEquals(if (hasOldRoom) "C" else null, fixture.store.captureRoomProjection()?.name?.value)
                    assertEquals(10, fixture.requests.authenticatedPrivateChatState?.memberNumber)
                    if (previous != null) {
                        val restored = withTimeout(2_000L) { fixture.gateway.events.first() } as BcSessionGatewayEvent.RoomUpdated
                        val previousEpoch = requireNotNull(previous.roomPresenceEpoch)
                        val restoredEpoch = requireNotNull(restored.roomPresenceEpoch)
                        assertEquals(previousEpoch, restored.previousRoomPresenceEpoch)
                        assertEquals(previous.room.name, restored.previousRoomName)
                        assertTrue(restoredEpoch > previousEpoch)
                        assertEquals("C", restored.room.name.value)
                        assertEquals(previous.accountMemberNumber, restored.accountMemberNumber)
                        assertEquals(previous.selfRuleLogSessionId, restored.selfRuleLogSessionId)
                        assertEquals(previous.members, restored.members)
                        assertEquals(previous.characters, restored.characters)
                        assertEquals(restoredEpoch, fixture.store.captureReceiveContext().roomPresenceEpoch)
                        assertEquals("C", fixture.store.captureReceiveContext().roomName)
                        assertRejected(BcRoomMemberAdminRejectionReason.RoomChanged,
                            runCatching { fixture.gateway.manageRoomMember(requireNotNull(oldCommand)) }.exceptionOrNull())
                        var oldChatEmitted = false
                        assertTrue(runCatching { requireNotNull(oldChatGuard).invoke { oldChatEmitted = true } }.exceptionOrNull()
                            is OutboxDispatchDeferredException)
                        assertFalse(oldChatEmitted)
                        val target = restored.members.single { it.memberNumber == 20 }
                        fixture.gateway.manageRoomMember(requireNotNull(oldCommand).copy(
                            roomName = restored.room.name,
                            roomPresenceEpoch = restoredEpoch,
                            targetPresenceEpoch = requireNotNull(target.roomMemberPresenceEpoch),
                            targetIsAdministrator = target.role == RoomRole.Admin,
                        ))
                        assertEquals("Kick", fixture.awaitWire("ChatRoomAdmin").get("Action").asString)
                        assertEquals(1, fixture.wires("ChatRoomAdmin").size)
                    }
                    assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first() })
                } finally { fixture.close() }
            }
        }
    }

    @Test
    fun consecutiveJoinRejectionAndRemovalCallbacksCannotRestoreAPhantomRoom() = runBlocking {
        listOf("RoomLocked" to "RoomKicked", "RoomFull" to "RoomBanned").forEach { (rejection, removal) ->
            val fixture = Fixture()
            try {
                fixture.initialize()
                fixture.sync(room())
                val previous = fixture.joined()
                val joining = async(start = CoroutineStart.UNDISPATCHED) { runCatching { fixture.gateway.joinRoom("B", null) } }
                fixture.awaitWire("ChatRoomJoin")
                fixture.receive("ChatRoomSearchResponse", "\"$rejection\"")
                fixture.receive("ChatRoomSearchResponse", "\"$removal\"")
                val failure = withTimeout(2_000L) { joining.await() }.exceptionOrNull()

                assertTrue(failure is BcRoomJoinRejectedException)
                assertEquals(rejection, failure?.message)
                val events = withTimeout(2_000L) { fixture.gateway.events.take(3).toList() }
                val restored = events[0] as BcSessionGatewayEvent.RoomUpdated
                val removed = events[1] as BcSessionGatewayEvent.RoomRemoved
                val reset = events[2] as BcSessionGatewayEvent.RoomPresenceReset
                assertEquals(previous.room.name, restored.previousRoomName)
                assertEquals(previous.roomPresenceEpoch, restored.previousRoomPresenceEpoch)
                assertEquals(previous.room.name, restored.room.name)
                assertEquals(previous.members, restored.members)
                assertTrue(requireNotNull(restored.roomPresenceEpoch) > requireNotNull(previous.roomPresenceEpoch))
                assertEquals(restored.room.name, removed.roomName)
                assertEquals(restored.roomPresenceEpoch, removed.previousRoomPresenceEpoch)
                assertEquals(if (removal == "RoomKicked") BcRoomRemovalReason.Kicked else BcRoomRemovalReason.Banned, removed.reason)
                assertEquals(previous.accountMemberNumber, removed.accountMemberNumber)
                assertEquals(previous.selfRuleLogSessionId, removed.selfRuleLogSessionId)
                assertTrue(removed.roomPresenceEpoch > removed.previousRoomPresenceEpoch)
                assertEquals(removed.roomPresenceEpoch, reset.roomPresenceEpoch)
                assertEquals(removed.selfRuleLogSessionId, reset.selfRuleLogSessionId)
                assertNull(fixture.store.captureRoomProjection())
                assertTrue(fixture.store.captureMemberProjection().isEmpty())
                assertEquals(removed.roomPresenceEpoch, fixture.store.captureReceiveContext().roomPresenceEpoch)
                assertRejected(BcRoomMemberAdminRejectionReason.RoomUnavailable,
                    runCatching { fixture.gateway.manageRoomMember(command(previous)) }.exceptionOrNull())
                assertTrue(fixture.wires("ChatRoomAdmin").isEmpty())
                assertNull(withTimeoutOrNull(75L) { fixture.gateway.events.first() })
            } finally { fixture.close() }
        }
    }

    @Test
    fun activeAndUnresolvedRoomPropertyUpdatesAlsoBlockMemberAdministration() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            val joined = fixture.joined()
            val command = command(joined)
            val base = requireNotNull(joined.room.adminSettings).base
            val updating = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { fixture.gateway.updateRoom(BcRoomUpdateCommand(base, base.settings.copy(description = "My edit"))) }
            }
            fixture.awaitWire("ChatRoomAdmin")

            assertRejected(BcRoomMemberAdminRejectionReason.Busy,
                runCatching { fixture.gateway.manageRoomMember(command) }.exceptionOrNull())
            fixture.receive("ChatRoomUpdateResponse", "{}")
            assertTrue(withTimeout(2_000L) { updating.await() }.exceptionOrNull() is BcRoomMutationOutcomeUnknownException)
            assertRejected(BcRoomMemberAdminRejectionReason.PendingResponse,
                runCatching { fixture.gateway.manageRoomMember(command) }.exceptionOrNull())
            assertEquals(1, fixture.wires("ChatRoomAdmin").size)
        } finally { fixture.close() }
    }

    @Test
    fun aRemovalBeforeTheNewJoinEmitsDoesNotRejectTheNewJoinRequest() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.sync(room())
            fixture.joined()
            fixture.blockOutbound()
            val joining = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.joinRoom("B", null) }
            fixture.receive("ChatRoomSearchResponse", "\"RoomKicked\"")
            yield()
            assertFalse(joining.isCompleted)
            assertNull(fixture.store.captureRoomProjection())
            val events = withTimeout(2_000L) { fixture.gateway.events.take(2).toList() }
            assertTrue(events[0] is BcSessionGatewayEvent.RoomRemoved)
            assertTrue(events[1] is BcSessionGatewayEvent.RoomPresenceReset)
            fixture.releaseOutbound()
            assertEquals("B", fixture.awaitWire("ChatRoomJoin").get("Name").asString)
            fixture.receive("ChatRoomSearchResponse", "\"JoinedRoom\"")
            fixture.sync(room("B"))

            withTimeout(2_000L) { joining.await() }
            assertEquals("B", fixture.joined().room.name.value)
            assertEquals(1, fixture.wires("ChatRoomJoin").size)
        } finally { fixture.close() }
    }

    @Test
    fun anAdmittedRemovalFinishesParsingBeforeTheNextJoinCanEmit() = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val releaseIncoming = CountDownLatch(1)
        var incomingThread: Thread? = null
        try {
            fixture.initialize()
            fixture.sync(room())
            val previous = fixture.joined()
            val outboundThread = fixture.outboundThread()
            val incoming = FutureTask(Callable {
                incomingListener(fixture.socket).call("ChatRoomSearchResponse", object : JSONObject() {
                    override fun toString(): String {
                        entered.countDown()
                        assertTrue(releaseIncoming.await(5, TimeUnit.SECONDS))
                        return "\"RoomKicked\""
                    }
                })
            })
            incomingThread = Thread(incoming, "room-removal-parse-test").apply { isDaemon = true; start() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            val joining = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.joinRoom("B", null) }
            awaitBlockedOn(outboundThread, requireNotNull(field(fixture.gateway, "inboundLock")))
            assertTrue(fixture.wires("ChatRoomJoin").isEmpty())
            assertFalse(joining.isCompleted)
            releaseIncoming.countDown()
            incoming.get(5, TimeUnit.SECONDS)

            val events = withTimeout(2_000L) { fixture.gateway.events.take(2).toList() }
            val removed = events[0] as BcSessionGatewayEvent.RoomRemoved
            assertEquals(previous.room.name, removed.roomName)
            assertEquals(previous.roomPresenceEpoch, removed.previousRoomPresenceEpoch)
            assertEquals(BcRoomRemovalReason.Kicked, removed.reason)
            assertTrue(events[1] is BcSessionGatewayEvent.RoomPresenceReset)
            assertEquals("B", fixture.awaitWire("ChatRoomJoin").get("Name").asString)
            assertFalse(joining.isCompleted)
            fixture.receive("ChatRoomSearchResponse", "\"JoinedRoom\"")
            fixture.sync(room("B"))

            withTimeout(2_000L) { joining.await() }
            assertEquals("B", fixture.joined().room.name.value)
            assertEquals(1, fixture.wires("ChatRoomJoin").size)
        } finally {
            releaseIncoming.countDown()
            incomingThread?.join(5_000L)
            fixture.close()
        }
    }

    private class Fixture {
        val gateway = RazorGatewayImpl(roomBackgroundNamesProvider = { setOf("MainHall") })
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
            RazorSocketTransport::class.java.getDeclaredMethod("bindSocketCallbacks", Socket::class.java,
                Long::class.javaPrimitiveType, CompletableDeferred::class.java)
                .apply { isAccessible = true }.invoke(transport, socket, transport.captureGeneration(), CompletableDeferred<Unit>())
        }

        suspend fun initialize() = coroutineScope {
            val login = async(start = CoroutineStart.UNDISPATCHED) { gateway.login("account", "password") }
            awaitWire("AccountLogin")
            receive("LoginResponse", LOGIN_RESPONSE)
            assertTrue(withTimeout(2_000L) { login.await() } is BcSessionLoginResult.Success)
            val reset = withTimeout(2_000L) { gateway.events.first() } as BcSessionGatewayEvent.RoomPresenceReset
            assertEquals(10, reset.accountMemberNumber)
        }

        fun enableReconnect(): RecordingSocket = RecordingSocket().also { replacement ->
            val factory: (URI, IO.Options) -> Socket = { _, _ -> replacement }
            setField(transport, "socketFactory", factory)
        }

        fun receive(event: String, payload: String, from: RecordingSocket = socket) {
            incomingListener(from).call(event, JSONTokener(payload).nextValue())
        }

        fun sync(settings: JsonObject, from: RecordingSocket = socket) {
            receive("ChatRoomSync", settings.deepCopy().apply { add("Character", json("[$SELF,$TARGET]")) }.toString(), from)
        }

        fun properties(settings: JsonObject) { receive("ChatRoomSyncRoomProperties", settings.toString()) }
        fun joinTarget() { receive("ChatRoomSyncMemberJoin", """{"SourceMemberNumber":20,"Character":$TARGET}""") }
        fun leaveTarget() { receive("ChatRoomSyncMemberLeave", """{"SourceMemberNumber":20}""") }
        fun member(number: Int): BcMember = store.captureMemberProjection().single { it.memberNumber == number }
        suspend fun joined() = withTimeout(2_000L) {
            gateway.events.first { it is BcSessionGatewayEvent.RoomJoined } as BcSessionGatewayEvent.RoomJoined
        }
        suspend fun updated() = withTimeout(2_000L) {
            gateway.events.first { it is BcSessionGatewayEvent.RoomUpdated } as BcSessionGatewayEvent.RoomUpdated
        }
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
        fun outboundThread(): Thread = executor.submit(Callable { Thread.currentThread() }).get(1, TimeUnit.SECONDS)
        fun close() { release.countDown(); gateway.close() }
    }

    private class RecordingSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        val outgoing = Channel<Pair<String, String>>(Channel.UNLIMITED)
        var online = true
        override fun connected(): Boolean = online
        override fun connect(): Socket { listeners(Socket.EVENT_CONNECT).forEach { it.call() }; return this }
        override fun emit(event: String, vararg args: Any?): Emitter {
            val packet = event to args.single().toString()
            events += packet
            outgoing.trySend(packet)
            return this
        }
        override fun disconnect(): Socket = this
    }

    private companion object {
        fun command(joined: BcSessionGatewayEvent.RoomJoined, action: BcRoomMemberAdminAction = BcRoomMemberAdminAction.Kick,
            target: Int = 20): BcRoomMemberAdminCommand {
            val member = joined.members.single { it.memberNumber == target }
            return BcRoomMemberAdminCommand(joined.room.name, requireNotNull(joined.roomPresenceEpoch), target,
                requireNotNull(member.roomMemberPresenceEpoch), action, member.role == RoomRole.Admin)
        }
        fun chat() = BcOutboundCommand("queued-before-removal", bcRoomConversationId("A"), BcMessageKind.Chat,
            BcMessageTransport.RoomChat, "hello", targetMemberNumber = null, createdAtMillis = 1_000)
        fun room(name: String = "A", targetAdmin: Boolean = false) = json("""{
            "Name":"$name","Description":"Original","Limit":10,"Background":"MainHall","Language":"EN",
            "Admin":${if (targetAdmin) "[10,20]" else "[10]"},"Whitelist":[],"Ban":[],"Game":"","BlockCategory":[],
            "Visibility":["All"],"Access":["All"],"Space":"X","Locked":false
        }""").asJsonObject
        fun json(value: String) = JsonParser.parseString(value)
        fun assertRejected(reason: BcRoomMemberAdminRejectionReason, failure: Throwable?) {
            assertTrue("Expected $reason, got $failure", failure is BcRoomMemberAdminRejectedException)
            assertEquals(reason, (failure as BcRoomMemberAdminRejectedException).reason)
        }
        @Suppress("UNCHECKED_CAST")
        fun incomingListener(socket: Socket): Emitter.Listener =
            (Socket::class.java.getDeclaredField("onAnyIncomingListeners").apply { isAccessible = true }
                .get(socket) as Collection<Emitter.Listener>).single()
        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)
        fun setField(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(instance, value) }
        fun awaitBlockedOn(thread: Thread, monitor: Any) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val threadBean = ManagementFactory.getThreadMXBean()
            while (true) {
                val info = threadBean.getThreadInfo(thread.id)
                if (info?.threadState == Thread.State.BLOCKED &&
                    info.lockInfo?.identityHashCode == System.identityHashCode(monitor) &&
                    info.lockInfo?.className == monitor.javaClass.name) return
                assertTrue("Outbound join did not wait for the admitted incoming callback.",
                    thread.isAlive && System.nanoTime() < deadline)
                Thread.yield()
            }
        }
        const val SELF = """{"ID":"10","MemberNumber":10,"Name":"Self"}"""
        const val TARGET = """{"ID":"20","MemberNumber":20,"Name":"Peer"}"""
        const val LOGIN_RESPONSE = """{"ID":"10","MemberNumber":10,"Name":"Self","Log":[],"ChatSettings":{}}"""
    }
}
