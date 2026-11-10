package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectedException
import dev.vivian.mobilebc.core.model.BcRoomMutationRejectionReason
import dev.vivian.mobilebc.core.model.BcRoomUpdateCommand
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorRoomRawStateStoreTest {
    @Test
    fun roomSettingsStaySeparateFromCharacterMapAndUpdateUsesLatestUneditedRawProperties() {
        val store = store()
        val original = room()
        assertTrue(store.handle(parse("ChatRoomSync", original)))
        val base = checkNotNull(store.captureRoomProjection()?.adminSettings).base
        val context = store.captureReceiveContext()
        assertEquals(original, store.captureRawSettings())
        assertEquals("FutureLanguage", base.settings.language)
        assertEquals(listOf("FutureVisibility"), base.settings.visibility)
        assertEquals(listOf("FutureAccess"), base.settings.access)

        val characterMap = JsonParser.parseString("""{"Pos":{"X":3,"Y":4},"FutureCharacterPosition":"retained"}""")
        val characterMapSync = JsonObject().apply {
            addProperty("MemberNumber", 20)
            add("MapData", characterMap)
        }
        assertTrue(store.handle(parse("ChatRoomSyncMapData", characterMapSync)))
        assertEquals(original, store.captureRawSettings())
        val character = store.captureCharacterSnapshots().single { it.memberNumber == 20 }
        assertEquals(characterMap, JsonParser.parseString(checkNotNull(character.characterMapDataJson)))

        val latest = room().apply {
            remove("Character")
            addProperty("SourceMemberNumber", 30)
            add("Admin", JsonParser.parseString("[10,30]"))
            add("Ban", JsonParser.parseString("[40]"))
            add("Whitelist", JsonParser.parseString("[50]"))
            addProperty("Game", "LatestFutureGame")
            add("BlockCategory", JsonParser.parseString("[\"LatestFutureCategory\"]"))
            add("MapData", JsonParser.parseString("""{"Type":"FutureMap","Tiles":"latest tiles","Objects":"latest objects","Future":{"version":2,"nested":[null,true,"x"]}}"""))
            add("Custom", JsonParser.parseString("""{"ImageURL":"https://example.com/latest.png","SizeMode":2,"ImageFilter":"#123456","MusicURL":"https://example.com/latest.mp3","MusicStart":3.25,"Future":{"version":2}}"""))
        }
        assertTrue(store.handle(parse("ChatRoomSyncRoomProperties", latest)))
        assertEquals(context.roomPresenceEpoch, store.captureReceiveContext().roomPresenceEpoch)
        assertEquals(latest, store.captureRawSettings())
        assertEquals(listOf(10, 20), store.captureCharacterSnapshots().map { it.memberNumber })

        val command = BcRoomUpdateCommand(base, base.settings.copy(description = "My edited description"))
        val payload = store.withRoomUpdate(command, 10) { it }
        assertEquals("My edited description", payload.get("Description").asString)
        updateFields.filterNot { it == "Description" }.forEach { assertEquals(it, latest.get(it), payload.get(it)) }
        assertEquals(updateFields, payload.keySet())
        assertFalse(payload.has("Space"))
        assertFalse(payload.has("Character"))
        assertFalse(payload.has("SourceMemberNumber"))
        assertFalse(payload.has("Locked"))
        assertEquals(latest, store.captureRawSettings())
    }

    @Test
    fun unparseablePropertiesInvalidateAdministrationWithoutChangingKnownRoomOrEpoch() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = store(events)
        store.handle(parse("ChatRoomSync", room()))
        val beforeRoom = checkNotNull(store.captureRoomProjection())
        val beforeContext = store.captureReceiveContext()
        val base = checkNotNull(beforeRoom.adminSettings).base
        val actionGuard = store.captureRoomActionGuard(20)
        val messageGuard = store.captureRoomMessageGuard(chat())
        val malformed = room().apply { add("Admin", JsonObject()) }
        val invalidEvent = parse("ChatRoomSyncRoomProperties", malformed)
        assertTrue(invalidEvent is RazorWireEvent.InvalidRoomSettings)

        assertTrue(store.handle(invalidEvent))

        assertEquals(beforeRoom.copy(adminSettings = null, banMemberNumbers = null), store.captureRoomProjection())
        assertTrue(store.captureMemberProjection().all { it.role == null })
        assertEquals(beforeContext, store.captureReceiveContext())
        assertTrue(store.isCurrentRoomContext(beforeContext))
        assertNull(store.captureRawSettings())
        assertNull(store.captureRoomProjection()?.adminSettings)
        var emitted = 0
        actionGuard { emitted++ }
        messageGuard { emitted++ }
        assertEquals(2, emitted)
        assertTrue(events.last() is BcSessionGatewayEvent.RoomUpdated)
        assertRoomMutationRejected(BcRoomMutationRejectionReason.SettingsUnavailable) {
            store.withRoomUpdate(BcRoomUpdateCommand(base, base.settings.copy(description = "Draft")), 10) { emitted++ }
        }
        assertEquals(2, emitted)
    }

    @Test
    fun unparseableFullSyncClearsRoomAndBlocksCommandsCapturedForTheOldRoom() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = store(events)
        store.handle(parse("ChatRoomSync", room()))
        val oldContext = store.captureReceiveContext()
        val actionGuard = store.captureRoomActionGuard(20)
        val messageGuard = store.captureRoomMessageGuard(chat())
        val malformed = room().apply { add("Admin", JsonObject()) }
        val invalidEvent = parse("ChatRoomSync", malformed)
        assertTrue(invalidEvent is RazorWireEvent.InvalidRoomSettings)

        assertTrue(store.handle(invalidEvent))

        assertRoomCleared(store, events, oldContext.roomPresenceEpoch)
        assertFalse(store.isCurrentRoomContext(oldContext))
        var emitted = 0
        assertDeferred { actionGuard { emitted++ } }
        assertDeferred { messageGuard { emitted++ } }
        assertEquals(0, emitted)
    }

    @Test
    fun fullSyncConversionFailureAlsoClearsRoomAndBlocksOldCommands() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = store(events)
        store.handle(parse("ChatRoomSync", room()))
        val oldContext = store.captureReceiveContext()
        val actionGuard = store.captureRoomActionGuard(20)
        val messageGuard = store.captureRoomMessageGuard(chat())

        assertTrue(store.handle(RazorWireEvent.ChatRoomSync(RoomSyncMessage(name = ""))))

        assertRoomCleared(store, events, oldContext.roomPresenceEpoch)
        assertFalse(store.isCurrentRoomContext(oldContext))
        var emitted = 0
        assertDeferred { actionGuard { emitted++ } }
        assertDeferred { messageGuard { emitted++ } }
        assertEquals(0, emitted)
    }

    @Test
    fun roomSpacePreservesUnknownWireValueWhenPropertiesOmitItAndMissingFullSyncResetsIt() {
        val store = store()
        val original = room().apply { addProperty("Space", "FutureRoomSpace") }
        store.handle(parse("ChatRoomSync", original))
        assertEquals("FutureRoomSpace", store.captureRoomProjection()?.space)
        val originalEpoch = store.captureReceiveContext().roomPresenceEpoch

        val properties = room().apply {
            remove("Character")
            remove("Space")
            addProperty("Description", "Updated description")
        }
        store.handle(parse("ChatRoomSyncRoomProperties", properties))
        assertEquals("FutureRoomSpace", store.captureRoomProjection()?.space)
        assertEquals("Updated description", store.captureRoomProjection()?.description)
        assertEquals(originalEpoch, store.captureReceiveContext().roomPresenceEpoch)
        assertFalse(checkNotNull(store.captureRawSettings()).has("Space"))

        val replacement = room().apply {
            remove("Space")
            addProperty("Name", "Another room")
        }
        store.handle(parse("ChatRoomSync", replacement))
        assertEquals("Another room", store.captureRoomProjection()?.name?.value)
        assertNull(store.captureRoomProjection()?.space)
        assertTrue(store.captureReceiveContext().roomPresenceEpoch > originalEpoch)
        assertFalse(checkNotNull(store.captureRawSettings()).has("Space"))
    }

    private fun store(events: MutableList<BcSessionGatewayEvent> = mutableListOf()): RazorRoomStateStore =
        RazorRoomStateStore(Gson(), events::add, queueAccountAppearance = {}).apply { selfMemberNumber = 10 }

    private fun parse(eventName: String, payload: JsonObject): RazorWireEvent = RazorWireParser.parse(eventName, payload.toString())

    private fun room(): JsonObject = JsonParser.parseString(
        """{
            "Name":"Room", "Language":"FutureLanguage", "Description":"Original description", "Background":"MainHall", "Limit":10,
            "Admin":[10,20], "Whitelist":[], "Ban":[], "BlockCategory":["FutureCategory"], "Game":"FutureGame",
            "Visibility":["FutureVisibility"], "Access":["FutureAccess"],
            "MapData":{"Type":"FutureMap","Tiles":"original tiles","Objects":"original objects","Future":{"version":1}},
            "Custom":{"ImageURL":"https://example.com/original.png","SizeMode":1,"ImageFilter":"#abcdef","MusicURL":"https://example.com/original.mp3","MusicStart":1.25,"Future":{"version":1}},
            "Space":"X", "Locked":true, "SourceMemberNumber":20,
            "Character":[{"ID":"0","MemberNumber":10,"Name":"Self","Appearance":[]},{"ID":"1","MemberNumber":20,"Name":"Peer","Appearance":[]}]
        }""",
    ).asJsonObject

    private fun chat(): BcOutboundCommand = BcOutboundCommand(
        clientMsgId = "queued-message",
        conversationId = bcRoomConversationId("Room"),
        kind = BcMessageKind.Chat,
        transport = BcMessageTransport.RoomChat,
        text = "Hello",
        targetMemberNumber = null,
        createdAtMillis = 0L,
    )

    private fun assertRoomCleared(store: RazorRoomStateStore, events: List<BcSessionGatewayEvent>, previousEpoch: Long) {
        assertNull(store.captureRoomProjection())
        assertNull(store.captureRawSettings())
        assertNull(store.captureReceiveContext().roomName)
        assertTrue(store.captureReceiveContext().roomPresenceEpoch > previousEpoch)
        assertTrue(store.captureReceiveContext().roomMembers.isEmpty())
        assertTrue(store.captureCharacterSnapshots().isEmpty())
        assertEquals(BcSessionGatewayEvent.RoomLeft, events.last())
    }

    private fun assertDeferred(action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected the old room command to be deferred")
        } catch (_: OutboxDispatchDeferredException) {
            // A stale room command must not reach its local socket emit.
        }
    }

    private fun assertRoomMutationRejected(reason: BcRoomMutationRejectionReason, action: () -> Unit) {
        try {
            action()
            throw AssertionError("Expected ${reason.name}")
        } catch (failure: BcRoomMutationRejectedException) {
            assertEquals(reason, failure.rejection.reason)
        }
    }

    private val updateFields = setOf(
        "Name", "Language", "Description", "Background", "Limit", "Admin", "Whitelist", "Ban", "BlockCategory", "Game",
        "Visibility", "Access", "MapData", "Custom",
    )
}
