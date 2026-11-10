package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.AccountQueryResult
import bot.inker.bc.razor.protocol.chat.ChatMessageType
import dev.vivian.mobilebc.core.model.BcActivityCommand
import dev.vivian.mobilebc.core.model.BcCharacterItemUpdateCommand
import dev.vivian.mobilebc.core.model.BcItemActionMessage
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcEchoCompatibilityCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawOrderCommand
import dev.vivian.mobilebc.core.model.BcEchoDrawRelation
import dev.vivian.mobilebc.core.model.BcEchoLeashRole
import dev.vivian.mobilebc.core.model.BcPoseUpdateCommand
import com.google.gson.JsonParser
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RazorGatewayPayloadTest {
    @Test
    fun customActionUsesBcxDialogContentAndMissingTextDictionaryEntry() {
        val message = buildCustomActionMessage("test", msgId = "msg-1")
        val dictionary = message.dictionary.orEmpty().map { it.asJsonObject }

        assertEquals(BCX_PLAYER_CUSTOM_DIALOG, message.content)
        assertEquals(ChatMessageType.ACTION, message.type)
        assertNull(message.target)
        assertEquals("""MISSING TEXT IN "Interface.csv": BCX_PLAYER_CUSTOM_DIALOG""", dictionary[0].get("Tag").asString)
        assertEquals("test", dictionary[0].get("Text").asString)
        assertEquals("MsgId", dictionary[1].get("Tag").asString)
        assertEquals("msg-1", dictionary[1].get("MsgId").asString)
    }

    @Test
    fun normalRoomMessageKeepsTextAsContent() {
        val message = buildRoomMessage("hello", ChatMessageType.CHAT, target = null, msgId = "msg-2")
        val dictionary = message.dictionary.orEmpty().map { it.asJsonObject }

        assertEquals("hello", message.content)
        assertEquals(ChatMessageType.CHAT, message.type)
        assertEquals("MsgId", dictionary.single().get("Tag").asString)
        assertEquals("msg-2", dictionary.single().get("MsgId").asString)
    }


    @Test
    fun activityCommandBecomesChatRoomActivityPayload() {
        val payload = buildActivityChatPayload(
            BcActivityCommand(
                targetMemberNumber = 2002,
                targetGroup = "ItemArms",
                activityName = "Spank",
                activityAssetGroup = "ItemHandheld",
                activityAssetName = "Crop",
            ),
            sourceMemberNumber = 1001,
            msgId = "msg-activity",
        )
        val dictionary = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }

        assertEquals("ChatOther-ItemArms-Spank", payload.get("Content").asString)
        assertEquals("Activity", payload.get("Type").asString)
        assertEquals(1001, dictionary[0].get("SourceCharacter").asInt)
        assertEquals(2002, dictionary[1].get("TargetCharacter").asInt)
        assertEquals("ItemArms", dictionary[2].get("FocusGroupName").asString)
        assertEquals("ActivityAsset", dictionary[3].get("Tag").asString)
        assertEquals("ItemHandheld", dictionary[3].get("GroupName").asString)
        assertEquals("Crop", dictionary[3].get("AssetName").asString)
        assertEquals("Spank", dictionary[4].get("ActivityName").asString)
        assertEquals("MsgId", dictionary[5].get("Tag").asString)
        assertEquals("msg-activity", dictionary[5].get("MsgId").asString)
    }

    @Test
    fun selfActivityUsesChatSelfTag() {
        val payload = buildActivityChatPayload(
            BcActivityCommand(
                targetMemberNumber = 1001,
                targetGroup = "ItemMouth",
                activityName = "Moan",
            ),
            sourceMemberNumber = 1001,
            msgId = "msg-self-activity",
        )

        assertEquals("ChatSelf-ItemMouth-Moan", payload.get("Content").asString)
        assertEquals("Activity", payload.get("Type").asString)
    }

    @Test
    fun echoActivityInjectsNativeMissingTextFallbackBeforeMsgId() {
        val payload = buildActivityChatPayload(
            BcActivityCommand(
                targetMemberNumber = 2002,
                targetGroup = "ItemArms",
                activityName = "翅膀轻拍",
                fallbackText = "SourceCharacter用翅膀轻轻拍了拍TargetCharacter的手臂。",
            ),
            sourceMemberNumber = 1001,
            msgId = "echo-msg-1",
        )
        val dictionary = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }

        assertEquals(
            """MISSING TEXT IN "ActivityDictionary.csv": ChatOther-ItemArms-翅膀轻拍""",
            dictionary[4].get("Tag").asString,
        )
        assertEquals(
            "SourceCharacter用翅膀轻轻拍了拍TargetCharacter的手臂。",
            dictionary[4].get("Text").asString,
        )
        assertEquals("MsgId", dictionary[5].get("Tag").asString)
        assertEquals("echo-msg-1", dictionary[5].get("MsgId").asString)
    }

    @Test
    fun itemUpdateCommandBecomesCharacterItemUpdatePayload() {
        val payload = buildCharacterItemUpdatePayload(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = 2002,
                group = "ItemArms",
                name = "HempRope",
                colorJson = """"Default"""",
                difficulty = 3,
                propertyJson = """{"Type":"Boxtie"}""",
                craftJson = """{"Name":"Custom Rope"}""",
            )
        )

        assertEquals(2002, payload.get("Target").asInt)
        assertEquals("ItemArms", payload.get("Group").asString)
        assertEquals("HempRope", payload.get("Name").asString)
        assertEquals("Default", payload.get("Color").asString)
        assertEquals(3, payload.get("Difficulty").asInt)
        assertEquals("Boxtie", payload.getAsJsonObject("Property").get("Type").asString)
        assertEquals("Custom Rope", payload.getAsJsonObject("Craft").get("Name").asString)
    }

    @Test
    fun removeItemPayloadOmitsNameAndDefaultsColorAndDifficulty() {
        val payload = buildCharacterItemUpdatePayload(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = 2002,
                group = "ItemArms",
                name = null,
            )
        )

        assertEquals(2002, payload.get("Target").asInt)
        assertEquals("ItemArms", payload.get("Group").asString)
        assertEquals(false, payload.has("Name"))
        assertEquals("Default", payload.get("Color").asString)
        assertEquals(0, payload.get("Difficulty").asInt)
    }

    @Test
    fun poseUpdateNormalizesAndDeduplicatesPoses() {
        val payload = buildPoseUpdatePayload(BcPoseUpdateCommand(listOf(" Kneel ", "Kneel", "")))

        assertEquals(listOf("Kneel"), payload.getAsJsonArray("Pose").map { it.asString })
    }

    @Test
    fun expressionUpdateOmitsNullNameToClearExpression() {
        val set = buildExpressionUpdatePayload(BcExpressionUpdateCommand(" ItemMouth ", " Open "))
        val clear = buildExpressionUpdatePayload(BcExpressionUpdateCommand("ItemMouth", null))

        assertEquals("ItemMouth", set.get("Group").asString)
        assertEquals("Open", set.get("Name").asString)
        assertEquals(false, clear.has("Name"))
    }

    @Test
    fun echoCompatibilityUsesCharacterTagHiddenProtocol() {
        val payload = buildEchoCompatibilityPayload(
            BcEchoCompatibilityCommand(
                modName = "服装拓展",
                version = "1.129.2-mobile-bc.1",
            ),
        )
        val entry = payload.getAsJsonArray("Dictionary").single().asJsonObject
        val tag = entry.getAsJsonObject("Content").getAsJsonObject("服装拓展")

        assertEquals(ECHO_INFO_TAG, payload.get("Content").asString)
        assertEquals("Hidden", payload.get("Type").asString)
        assertEquals(false, payload.has("Target"))
        assertEquals(ECHO_INFO_TAG, entry.get("Type").asString)
        assertEquals("1.129.2-mobile-bc.1", tag.get("version").asString)
        assertEquals(false, tag.get("beta").asBoolean)
    }

    @Test
    fun echoDrawOrderUsesStructuredPreviousCharacterPair() {
        val payload = buildEchoDrawOrderPayload(
            BcEchoDrawOrderCommand(
                otherMemberNumber = 2002,
                relation = BcEchoDrawRelation.PreviousCharacter,
                associatedAssetGroup = "ItemMisc",
                associatedAssetName = "贴贴",
                leash = BcEchoLeashRole.Lead,
            ),
        )
        val entry = payload.getAsJsonArray("Dictionary").single().asJsonObject
        val asset = entry.getAsJsonObject("associatedAsset")

        assertEquals(ECHO_DRAW_ORDER_TAG, payload.get("Content").asString)
        assertEquals("Hidden", payload.get("Type").asString)
        assertEquals(2002, entry.get("prevCharacter").asInt)
        assertEquals(false, entry.has("nextCharacter"))
        assertEquals("ItemMisc", asset.get("group").asString)
        assertEquals("贴贴", asset.get("asset").asString)
        assertEquals("lead", entry.get("leash").asString)
        assertEquals(false, entry.has("drawState"))
    }

    @Test
    fun itemActionMessageUsesActionPayloadWithPrevAndNextAssets() {
        val payload = buildItemActionChatPayload(
            BcCharacterItemUpdateCommand(
                targetMemberNumber = 2002,
                group = "ItemArms",
                name = "HempRope",
                publishAction = BcItemActionMessage(
                    content = "ActionAdd",
                    previousAssetName = "OldRope",
                    nextAssetName = "HempRope",
                ),
            ),
            sourceMemberNumber = 1001,
            msgId = "msg-action",
        )
        val dictionary = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }

        assertEquals("ActionAdd", payload.get("Content").asString)
        assertEquals("Action", payload.get("Type").asString)
        assertEquals(1001, dictionary[0].get("SourceCharacter").asInt)
        assertEquals(2002, dictionary[1].get("TargetCharacter").asInt)
        assertEquals(2002, dictionary[2].get("DestinationCharacter").asInt)
        assertEquals("ItemArms", dictionary[3].get("FocusGroupName").asString)
        assertEquals("PrevAsset", dictionary[4].get("Tag").asString)
        assertEquals("OldRope", dictionary[4].get("AssetName").asString)
        assertEquals("NextAsset", dictionary[5].get("Tag").asString)
        assertEquals("HempRope", dictionary[5].get("AssetName").asString)
        assertEquals("MsgId", dictionary[6].get("Tag").asString)
        assertEquals("msg-action", dictionary[6].get("MsgId").asString)
    }

    @Test
    fun onlineFriendsQueryResultBecomesDirectConversations() {
        val result = AccountQueryResult(
            query = "OnlineFriends",
            result = JsonParser.parseString(
                """
                [
                  { "MemberNumber": 1001, "MemberName": "Alice", "ChatRoomName": "A Room" },
                  { "MemberNumber": 1002, "Nickname": "Bee" },
                  { "MemberNumber": 1003, "Nickname": " ", "MemberName": "Alice" },
                  { "MemberNumber": 1004, "Nickname": "", "MemberName": " ", "Name": "Charlie" },
                  { "MemberNumber": 1005, "Nickname": " ", "MemberName": "", "Name": " " }
                ]
                """.trimIndent(),
            ),
        )

        val conversations = result.toOnlineFriendConversations()

        assertEquals(listOf("message:1001", "message:1002", "message:1003", "message:1004", "message:1005"), conversations.map { it.id.value })
        assertEquals(listOf("Alice", "Bee", "Alice", "Charlie", "#1005"), conversations.map { it.title })
        assertEquals(listOf("Online in A Room", "Online friend", "Online friend", "Online friend", "Online friend"), conversations.map { it.subtitle })
        assertEquals(listOf("A Room", null, null, null, null), conversations.map { it.onlineRoomName })
        assertEquals(listOf(null, null, null, null, null), conversations.map { it.updatedAt })
        assertEquals(
            listOf(
                dev.vivian.mobilebc.core.model.ConversationAvailability.OnlineFriend,
                dev.vivian.mobilebc.core.model.ConversationAvailability.OnlineFriend,
                dev.vivian.mobilebc.core.model.ConversationAvailability.OnlineFriend,
                dev.vivian.mobilebc.core.model.ConversationAvailability.OnlineFriend,
                dev.vivian.mobilebc.core.model.ConversationAvailability.OnlineFriend,
            ),
            conversations.map { it.availability.singleOrNull() },
        )
    }

}
