package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.Difficulty
import bot.inker.bc.razor.protocol.account.Lovership
import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.Reputation
import bot.inker.bc.razor.protocol.common.ItemBundle
import bot.inker.bc.razor.protocol.room.ChatRoomGame
import bot.inker.bc.razor.protocol.room.ChatRoomLanguage
import bot.inker.bc.razor.protocol.room.ChatRoomSpace
import bot.inker.bc.razor.protocol.sync.ArousalSyncMessage
import bot.inker.bc.razor.protocol.sync.ExpressionSyncMessage
import bot.inker.bc.razor.protocol.sync.ItemSyncData
import bot.inker.bc.razor.protocol.sync.ItemSyncMessage
import bot.inker.bc.razor.protocol.sync.PoseSyncMessage
import bot.inker.bc.razor.protocol.sync.ReorderPlayersSyncMessage
import bot.inker.bc.razor.state.CharacterState
import bot.inker.bc.razor.state.RoomState
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RazorRoomStateReducerTest {
    @Test
    fun expressionSyncUpdatesItemProperty() {
        val room = room(
            character(
                memberNumber = 1001,
                appearance = listOf(ItemBundle(group = "ItemMouth", name = "BallGag")),
            ),
        )

        val updated = room.applyExpressionSync(
            ExpressionSyncMessage(memberNumber = 1001, name = "Closed", group = "ItemMouth")
        )

        val property = updated.characters.single().appearance.single().property!!.asJsonObject
        assertEquals("Closed", property.get("Expression").asString)
    }

    @Test
    fun poseSyncReplacesActivePose() {
        val room = room(character(memberNumber = 1001, activePose = listOf("Kneel")))

        val updated = room.applyPoseSync(PoseSyncMessage(memberNumber = 1001, pose = listOf("Hogtied")))

        assertEquals(listOf("Hogtied"), updated.characters.single().activePose)
    }

    @Test
    fun itemSyncReplacesAddsAndRemovesItems() {
        val room = room(
            character(
                memberNumber = 1001,
                appearance = listOf(ItemBundle(group = "ItemArms", name = "Rope")),
            ),
        )

        val replaced = room.applyItemSync(
            ItemSyncMessage(
                source = 1001,
                item = ItemSyncData(target = 1001, group = "ItemArms", name = "Cuffs"),
            )
        )
        val added = replaced.applyItemSync(
            ItemSyncMessage(
                source = 1001,
                item = ItemSyncData(
                    target = 1001,
                    group = "ItemMouth",
                    name = "BallGag",
                    property = JsonParser.parseString("""{"Intensity":2}"""),
                ),
            )
        )
        val removed = added.applyItemSync(
            ItemSyncMessage(
                source = 1001,
                item = ItemSyncData(target = 1001, group = "ItemArms", name = null),
            )
        )

        assertEquals(listOf("ItemMouth"), removed.characters.single().appearance.map { it.group })
        assertEquals("BallGag", removed.characters.single().appearance.single().name)
        assertEquals(2, removed.characters.single().appearance.single().property!!.asJsonObject.get("Intensity").asInt)
    }

    @Test
    fun reorderPlayersSortsKnownMembersAndKeepsUnknownAtEnd() {
        val room = room(
            character(memberNumber = 1001),
            character(memberNumber = 1002),
            character(memberNumber = 1003),
        )

        val updated = room.applyReorderPlayers(ReorderPlayersSyncMessage(listOf(1002, 1001)))

        assertEquals(listOf(1002, 1001, 1003), updated.characters.map { it.memberNumber })
    }

    @Test
    fun expressionSyncIgnoresMissingCharacterOrItem() {
        val room = room(character(memberNumber = 1001))

        val missingCharacter = room.applyExpressionSync(
            ExpressionSyncMessage(memberNumber = 9999, name = "Closed", group = "ItemMouth")
        )
        val missingItem = room.applyExpressionSync(
            ExpressionSyncMessage(memberNumber = 1001, name = "Closed", group = "ItemMouth")
        )

        assertEquals(room, missingCharacter)
        assertNull(missingItem.characters.single().appearance.firstOrNull())
    }

    @Test
    fun characterSnapshotContainsCharacterFieldsButNotRoomMapData() {
        val room = room(
            character(
                memberNumber = 1001,
                appearance = listOf(
                    ItemBundle(group = "Pronouns", name = "TheyThem"),
                    ItemBundle(
                        group = "ItemMouth",
                        name = "BallGag",
                        color = JsonParser.parseString(""""#ff0000""""),
                        difficulty = 3,
                        property = JsonParser.parseString("""{"Expression":"Closed"}"""),
                        craft = JsonParser.parseString("""{"Name":"Custom"}"""),
                    ),
                ),
                activePose = listOf("Kneel"),
            ).copy(
                accountName = "Online-session-1",
                nickname = "Bee",
                title = "Mistress",
                labelColor = "#00ff00",
                description = "hello",
                creation = 1234L,
                owner = "NPC-Private Owner",
                lover = "NPC-Private Lover",
                ownership = Ownership(memberNumber = 2001, name = "Owner", stage = 1, start = 99L, notes = "notes"),
                lovership = listOf(
                    Lovership(memberNumber = 2002, name = "Love", stage = 2, start = 100L),
                    Lovership(name = "NPC-Private Love", stage = 1, start = 101L),
                ),
                reputation = listOf(Reputation(type = "Dominant", value = 10)),
                difficulty = Difficulty(level = 2, lastChange = 111L),
                allowedInteractions = 3,
                whiteList = listOf(3001),
                blackList = listOf(3002),
                arousalSettings = JsonParser.parseString("""{"Progress":42}"""),
                onlineSharedSettings = JsonParser.parseString("""{"AllowPlayerLeashing":false}"""),
                game = JsonParser.parseString("""{"LARP":{"Enabled":true}}"""),
                crafting = JsonParser.parseString("""[{"Name":"Crafted"}]"""),
                inventoryData = "compressed-inventory",
                blockItems = JsonParser.parseString("""[{"Name":"Blocked"}]"""),
                limitedItems = JsonParser.parseString("""[{"Name":"Limited"}]"""),
                favoriteItems = JsonParser.parseString("""[{"Name":"Favorite"}]"""),
                hiddenItems = JsonParser.parseString("""[{"Name":"Hidden"}]"""),
                mapData = JsonParser.parseString("""{"Pos":{"X":12,"Y":34}}"""),
                rawData = JsonParser.parseString("""{"MemberNumber":1001,"Name":"Member 1001"}"""),
            ),
            mapData = JsonParser.parseString("""{"Type":"Always","RoomOnly":true}"""),
        )

        val snapshot = room.characters.single().toBcCharacterSnapshot(isSelf = true, gson = Gson())

        assertEquals("Bee", snapshot.displayName)
        assertEquals(true, snapshot.isSelf)
        assertEquals("TheyThem", snapshot.pronouns)
        assertEquals(listOf("Kneel"), snapshot.activePose)
        assertEquals("ItemMouth", snapshot.appearance[1].group)
        assertEquals(3, snapshot.appearance[1].difficulty)
        assertEquals("""{"Expression":"Closed"}""", snapshot.appearance[1].propertyJson)
        assertEquals("Owner", snapshot.ownership?.name)
        assertEquals("Owner", snapshot.ownerName)
        assertEquals(2001, snapshot.ownerMemberNumber)
        assertEquals("Love", snapshot.lovership.first().name)
        assertEquals(listOf("Love", "Private Love"), snapshot.loverNames)
        assertEquals("Dominant", snapshot.reputation.single().type)
        assertEquals(2, snapshot.difficulty?.level)
        assertEquals(3, snapshot.allowedInteractions)
        assertEquals("""{"Progress":42}""", snapshot.arousalSettingsJson)
        assertEquals("""{"AllowPlayerLeashing":false}""", snapshot.onlineSharedSettingsJson)
        assertEquals("""{"LARP":{"Enabled":true}}""", snapshot.gameJson)
        assertEquals("""[{"Name":"Crafted"}]""", snapshot.craftingJson)
        assertEquals("compressed-inventory", snapshot.inventoryData)
        assertEquals("""{"Pos":{"X":12,"Y":34}}""", snapshot.characterMapDataJson)
        assertEquals(false, snapshot.characterMapDataJson?.contains("RoomOnly"))
    }

    @Test
    fun characterSnapshotDecodesCompressedProfileDescription() {
        val compressedDescription =
            "\u256c\u0262\u4c2d\u4c3e\u6024\u45c3\u4a58\u34fa\u0058\u0509\u00ec\u04d3\u04f0\u04e4\u00ee\u01ac\u2a84\u4037\u26b0\u03d2\u0020\u0020"
        val room = room(
            character(memberNumber = 1001).copy(description = compressedDescription)
        )

        val snapshot = room.characters.single().toBcCharacterSnapshot(isSelf = false, gson = Gson())

        assertEquals("Hello \u4e2d\u6587 profile description", snapshot.description)
    }

    @Test
    fun arousalSyncUpdatesCurrentArousalWithoutDroppingRawSettings() {
        val room = room(
            character(memberNumber = 1001).copy(
                arousalSettings = JsonParser.parseString("""{"Progress":12,"Active":"Hybrid"}"""),
                onlineSharedSettings = JsonParser.parseString("""{"GameVersion":"R129"}"""),
            )
        )

        val updated = room.applyArousalSync(
            ArousalSyncMessage(
                memberNumber = 1001,
                orgasmTimer = 10.0,
                orgasmCount = 2,
                progress = 64.0,
                progressTimer = 5.0,
            )
        )
        val snapshot = updated.characters.single().toBcCharacterSnapshot(isSelf = false, gson = Gson())

        assertEquals(64.0, snapshot.arousal?.progress)
        assertEquals("""{"Progress":12,"Active":"Hybrid"}""", snapshot.arousalSettingsJson)
        assertEquals("""{"GameVersion":"R129"}""", snapshot.onlineSharedSettingsJson)
    }

    private fun room(
        vararg characters: CharacterState,
        mapData: com.google.gson.JsonElement? = null,
    ): RoomState =
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
            mapData = mapData,
            custom = null,
            characters = characters.toList(),
        )

    private fun character(
        memberNumber: Int,
        appearance: List<ItemBundle> = emptyList(),
        activePose: List<String> = emptyList(),
    ): CharacterState =
        CharacterState(
            id = memberNumber.toString(),
            memberNumber = memberNumber,
            name = "Member $memberNumber",
            appearance = appearance,
            activePose = activePose,
        )
}
