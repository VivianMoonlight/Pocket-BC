package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.chat.ChatMessageType
import com.google.gson.Gson
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcExpressionUpdateCommand
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatMaxLength
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import dev.vivian.mobilebc.domain.sync.OutboxDispatchDeferredException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorReplyPayloadTest {
    @Test
    fun replyWireContainsOnlyNativeReplyIdAlongsideItsOwnMsgId() {
        listOf(ChatMessageType.CHAT, ChatMessageType.EMOTE, ChatMessageType.WHISPER).forEach { type ->
            val target = if (type == ChatMessageType.WHISPER) 20 else null
            val message = buildRoomMessage("Reply text", type, target, "new-id", "original-id")
            val dictionary = message.dictionary.orEmpty().map { it.asJsonObject }

            assertEquals("Reply text", message.content)
            assertEquals(target, message.target)
            assertEquals(2, dictionary.size)
            assertEquals(setOf("Tag", "MsgId"), dictionary[0].keySet())
            assertEquals("new-id", dictionary[0].get("MsgId").asString)
            assertEquals(setOf("Tag", "ReplyId"), dictionary[1].keySet())
            assertEquals("ReplyId", dictionary[1].get("Tag").asString)
            assertEquals("original-id", dictionary[1].get("ReplyId").asString)
        }
    }

    @Test
    fun replyCannotBeAttachedToActionPayloadOrUseBlankId() {
        assertThrows(IllegalArgumentException::class.java) {
            buildRoomMessage("Action", ChatMessageType.ACTION, null, "new-id", "original-id")
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildRoomMessage("Text", ChatMessageType.CHAT, null, "new-id", " ")
        }
    }

    @Test
    fun restoredWhisperReplyCannotBeEmittedInAnotherRoomWithTheSameMember() {
        val store = store("Room B")
        val command = whisperCommand()
        var emitted = false

        assertThrows(OutboxDispatchDeferredException::class.java) {
            store.captureRoomMessageGuard(command).invoke { emitted = true }
        }
        assertFalse(emitted)
    }

    @Test
    fun restoredWhisperReplyEmitsWhenItsOriginalRoomAndMemberArePresent() {
        val store = store("Room A")
        var emitted = false

        store.captureRoomMessageGuard(whisperCommand()).invoke { emitted = true }

        assertTrue(emitted)
    }

    @Test
    fun queuedWhisperReplyStillHonorsRoomPresenceEpoch() {
        val store = store("Room A")
        val guard = store.captureRoomMessageGuard(whisperCommand())
        store.emitRoomTransition {}
        syncRoom(store, "Room A")

        assertThrows(OutboxDispatchDeferredException::class.java) { guard { error("must not emit") } }
    }

    @Test
    fun gatewayRejectsOversizedRestoredChatEmoteAndWhisperBeforeSocketAccess() = runBlocking {
        val gateway = RazorGatewayImpl()
        try {
            listOf(BcMessageKind.Chat, BcMessageKind.Emote, BcMessageKind.Whisper).forEach { kind ->
                val command = whisperCommand().copy(
                    kind = kind,
                    transport = if (kind == BcMessageKind.Whisper) BcMessageTransport.RoomWhisper else BcMessageTransport.RoomChat,
                    conversationId = if (kind == BcMessageKind.Whisper) whisperCommand().conversationId else bcRoomConversationId("Room A"),
                    targetMemberNumber = if (kind == BcMessageKind.Whisper) 20 else null,
                    text = "\uD83D\uDE00".repeat(BcRoomChatMaxLength / 2) + "x",
                )
                try {
                    gateway.send(command)
                    error("Oversized command must be rejected")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message.orEmpty().contains(BcRoomChatMaxLength.toString()))
                }
            }
        } finally {
            gateway.close()
        }
    }

    @Test
    fun expressionAppearancePreservesEveryItemFieldAsNativeItemBundle() {
        val payload = buildExpressionUpdatePayload(BcExpressionUpdateCommand(
            group = "Eyes",
            name = "Closed",
            appearance = listOf(
                BcCharacterItem("Eyes", "Eyes1", "[\"#123456\",\"#234567\"]", 3,
                    """{"Expression":"Closed","Other":true}""", """{"Name":"Crafted eyes"}"""),
                BcCharacterItem("ItemArms", "HempRope"),
            ),
        ))
        val items = payload.getAsJsonArray("Appearance").map { it.asJsonObject }

        assertEquals("Eyes", payload.get("Group").asString)
        assertEquals("Closed", payload.get("Name").asString)
        assertEquals("Eyes1", items[0].get("Name").asString)
        assertEquals(listOf("#123456", "#234567"), items[0].getAsJsonArray("Color").map { it.asString })
        assertEquals(3, items[0].get("Difficulty").asInt)
        assertTrue(items[0].getAsJsonObject("Property").get("Other").asBoolean)
        assertEquals("Closed", items[0].getAsJsonObject("Property").get("Expression").asString)
        assertEquals("Crafted eyes", items[0].getAsJsonObject("Craft").get("Name").asString)
        assertEquals(setOf("Group", "Name"), items[1].keySet())
        assertFalse(buildExpressionUpdatePayload(BcExpressionUpdateCommand("Eyes", null)).has("Appearance"))
    }

    private fun whisperCommand() = BcOutboundCommand(
        clientMsgId = "new-id",
        conversationId = dev.vivian.mobilebc.core.model.BcConversationId("message:20"),
        kind = BcMessageKind.Whisper,
        transport = BcMessageTransport.RoomWhisper,
        text = "Reply text",
        targetMemberNumber = 20,
        createdAtMillis = 1000,
        replyToId = "original-id",
        replyRoomName = "Room A",
    )

    private fun store(roomName: String) = RazorRoomStateStore(Gson(), {}, {}).apply {
        selfMemberNumber = 10
        syncRoom(this, roomName)
    }

    private fun syncRoom(store: RazorRoomStateStore, roomName: String) {
        store.handle(RazorWireParser.parse("ChatRoomSync",
            """{"Name":"$roomName","Character":[{"ID":"10","MemberNumber":10,"Name":"Self"},{"ID":"20","MemberNumber":20,"Name":"Alice"}]}""",
        ))
    }
}
