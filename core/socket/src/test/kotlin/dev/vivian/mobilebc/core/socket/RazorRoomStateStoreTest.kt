package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.common.ItemBundle
import bot.inker.bc.razor.protocol.room.ChatRoomGame
import bot.inker.bc.razor.protocol.room.ChatRoomLanguage
import bot.inker.bc.razor.protocol.room.ChatRoomSpace
import bot.inker.bc.razor.protocol.room.ChatRoomBlockCategory
import bot.inker.bc.razor.protocol.sync.CharacterSyncMessage
import bot.inker.bc.razor.protocol.sync.ItemSyncData
import bot.inker.bc.razor.protocol.sync.ItemSyncMessage
import bot.inker.bc.razor.protocol.sync.MemberJoinMessage
import bot.inker.bc.razor.protocol.sync.MemberLeaveMessage
import bot.inker.bc.razor.protocol.sync.PoseSyncMessage
import bot.inker.bc.razor.protocol.sync.RoomSyncMessage
import bot.inker.bc.razor.state.CharacterState
import bot.inker.bc.razor.state.RoomState
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorRoomStateStoreTest {
    private val gson = Gson()

    @Test
    fun roomSyncAndMemberLeavesProduceStableSessionEvents() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = store(events = events)
        store.selfMemberNumber = 1001

        store.handle(roomSync(character(1001), character(2002)))
        store.handle(RazorWireEvent.MemberLeave(MemberLeaveMessage(sourceMemberNumber = 2002)))
        store.handle(RazorWireEvent.MemberLeave(MemberLeaveMessage(sourceMemberNumber = 1001)))

        val joined = events[0] as BcSessionGatewayEvent.RoomJoined
        val updated = events[1] as BcSessionGatewayEvent.RoomUpdated
        assertEquals(listOf(1001, 2002), joined.characters.map { it.memberNumber })
        assertEquals(listOf(1001), updated.characters.map { it.memberNumber })
        assertEquals(BcSessionGatewayEvent.RoomLeft, events[2])
    }

    @Test
    fun roomMappingPreservesBlockedActivityCategories() {
        val mapped = room(emptyList())
            .copy(blockCategory = listOf(ChatRoomBlockCategory.AROUSAL))
            .toBcRoom()

        assertEquals(setOf("Arousal"), mapped.blockCategories)
    }

    @Test
    fun confirmedSelfAppearanceQueuesOnlyRealChanges() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val queuedAppearances = mutableListOf<List<ItemBundle>>()
        val store = store(events, queuedAppearances)
        store.selfMemberNumber = 1001
        store.handle(
            roomSync(
                character(
                    memberNumber = 1001,
                    appearance = listOf(ItemBundle(group = "ItemArms", name = "Rope")),
                ),
                character(memberNumber = 2002),
            )
        )

        val removeSelfItem = RazorWireEvent.ItemSync(
            ItemSyncMessage(
                source = 1001,
                item = ItemSyncData(target = 1001, group = "ItemArms"),
            ),
        )
        store.handle(removeSelfItem)
        store.handle(removeSelfItem)
        store.handle(
            RazorWireEvent.ItemSync(
                ItemSyncMessage(
                    source = 1001,
                    item = ItemSyncData(target = 2002, group = "ItemMouth", name = "BallGag"),
                ),
            ),
        )

        assertEquals(1, queuedAppearances.size)
        assertTrue(queuedAppearances.single().isEmpty())
        assertEquals(3, events.size)
    }

    @Test
    fun savingAnItemChangeDoesNotPersistTemporaryExpressions() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val queued = mutableListOf<List<ItemBundle>>()
        val store = store(events, queued)
        store.selfMemberNumber = 1001
        store.handle(roomSync(character(1001, appearance = listOf(
            ItemBundle(group = "Eyes", name = "Eyes", property = JsonParser.parseString("""{"Expression":"Happy","Other":42}""")),
        ))))

        store.handle(RazorWireEvent.ItemSync(ItemSyncMessage(
            source = 1001,
            item = ItemSyncData(target = 1001, group = "ItemArms", name = "Rope"),
        )))

        val savedProperty = queued.single().first { it.group == "Eyes" }.property!!.asJsonObject
        assertTrue(!savedProperty.has("Expression"))
        assertEquals(42, savedProperty.get("Other").asInt)
        val current = store.captureCharacterSnapshots().single().appearance.first { it.group == "Eyes" }
        assertEquals("Happy", JsonParser.parseString(current.propertyJson).asJsonObject.get("Expression").asString)
    }

    @Test
    fun characterSyncReplacesInPlaceWithoutChangingPlayerOrder() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = store(events)
        store.handle(roomSync(character(1001), character(2002)))

        store.handle(
            RazorWireEvent.CharacterSync(
                CharacterSyncMessage(
                    sourceMemberNumber = 1001,
                    character = toWireCharacter(character(1001, name = "Updated")),
                ),
            ),
        )

        val joined = events.first() as BcSessionGatewayEvent.RoomJoined
        val updated = events.last() as BcSessionGatewayEvent.RoomUpdated
        assertEquals(listOf(1001, 2002), updated.characters.map { it.memberNumber })
        assertEquals("Updated", updated.characters.first().name)
        assertSame(joined.characters[1], updated.characters[1])
    }

    @Test
    fun memberJoinAppliesReverseWhiteAndBlackListHintsAtomically() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val store = store(events)
        store.selfMemberNumber = 1001
        store.handle(
            roomSync(
                character(memberNumber = 1001),
                character(memberNumber = 2002, whiteList = listOf(3003)),
                character(memberNumber = 3003),
            )
        )

        store.handle(
            RazorWireEvent.MemberJoin(
                MemberJoinMessage(
                    sourceMemberNumber = 4004,
                    character = toWireCharacter(character(memberNumber = 4004)),
                    whiteListedBy = listOf(1001, 2002, 2002),
                    blackListedBy = listOf(3003),
                ),
            ),
        )

        val updated = events.last() as BcSessionGatewayEvent.RoomUpdated
        assertEquals(2, events.size)
        assertEquals(listOf(1001, 2002, 3003, 4004), updated.characters.map { it.memberNumber })
        assertEquals(emptyList<Int>(), updated.characters.first { it.memberNumber == 1001 }.whiteList)
        assertEquals(listOf(3003, 4004), updated.characters.first { it.memberNumber == 2002 }.whiteList)
        assertEquals(listOf(4004), updated.characters.first { it.memberNumber == 3003 }.blackList)
    }

    @Test
    fun unknownCharacterIncrementIsDeferredAndReplayedAfterCharacterSync() {
        val events = mutableListOf<BcSessionGatewayEvent>()
        val diagnostics = mutableListOf<String>()
        val store = store(events = events, diagnostics = diagnostics)
        store.handle(roomSync(character(1001)))

        store.handle(
            RazorWireEvent.PoseSync(
                PoseSyncMessage(memberNumber = 2002, pose = listOf("Kneel")),
            ),
        )
        assertEquals(1, events.size)

        store.handle(
            RazorWireEvent.CharacterSync(
                CharacterSyncMessage(
                    sourceMemberNumber = 2002,
                    character = toWireCharacter(character(2002)),
                ),
            ),
        )

        val updated = events.last() as BcSessionGatewayEvent.RoomUpdated
        assertEquals(listOf(1001, 2002), updated.characters.map { it.memberNumber })
        assertEquals(listOf("Kneel"), updated.characters.last().activePose)
        assertEquals(null, updated.characters.last().rawDataJson)
        assertTrue(diagnostics.any { it.contains("Deferred PoseSync") })
        assertTrue(diagnostics.any { it.contains("Replayed 1") })
    }

    private fun store(
        events: MutableList<BcSessionGatewayEvent>,
        queuedAppearances: MutableList<List<ItemBundle>> = mutableListOf(),
        diagnostics: MutableList<String> = mutableListOf(),
    ): RazorRoomStateStore =
        RazorRoomStateStore(
            gson = gson,
            emit = events::add,
            queueAccountAppearance = queuedAppearances::add,
            diagnostic = diagnostics::add,
        )

    private fun roomSync(vararg characters: CharacterState): RazorWireEvent.ChatRoomSync {
        return RazorWireEvent.ChatRoomSync(
            value = RoomSyncMessage(
                name = "Test Room",
                character = characters.map(::toWireCharacter),
            ),
        )
    }

    private fun toWireCharacter(character: CharacterState): JsonElement =
        JsonObject().apply {
            addProperty("ID", character.id)
            addProperty("MemberNumber", character.memberNumber)
            addProperty("Name", character.name)
            add("Appearance", gson.toJsonTree(character.appearance))
            add("ActivePose", gson.toJsonTree(character.activePose))
            add("WhiteList", gson.toJsonTree(character.whiteList))
            add("BlackList", gson.toJsonTree(character.blackList))
        }

    private fun room(characters: List<CharacterState>): RoomState =
        RoomState(
            name = "Test Room",
            description = "",
            admin = emptyList(),
            ban = emptyList(),
            whitelist = emptyList(),
            background = "MainHall",
            limit = 10,
            locked = false,
            game = ChatRoomGame.NONE,
            visibility = emptyList(),
            access = emptyList(),
            blockCategory = emptyList(),
            language = ChatRoomLanguage.ANY,
            space = ChatRoomSpace.MIXED,
            mapData = null,
            custom = null,
            characters = characters,
        )

    private fun character(
        memberNumber: Int,
        appearance: List<ItemBundle> = emptyList(),
        name: String = "Member $memberNumber",
        whiteList: List<Int> = emptyList(),
        blackList: List<Int> = emptyList(),
    ): CharacterState =
        CharacterState(
            id = memberNumber.toString(),
            memberNumber = memberNumber,
            name = name,
            appearance = appearance,
            whiteList = whiteList,
            blackList = blackList,
        )
}
