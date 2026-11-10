package dev.vivian.mobilebc.core.protocol

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BcInboundCodecsTest {
    @Test
    fun chatFixturesKeepNormalizedPayloadAndReceptionIdentity() {
        for (fixtureName in listOf("bc_chat_room_message_v1.json", "bc_chat_action_uppercase_v1.json", "bc_chat_whisper_v1.json")) {
            val payload = fixture(fixtureName)
            val normalized = JSONObject(payload).toString()
            val first = (BcChatMessageCodec.decode(frameOf("ChatRoomMessage", payload)) as DecodeResult.Success).packet
                as BcInboundPacket.ChatMessage
            val replay = (BcChatMessageCodec.decode(frameOf("ChatRoomMessage", normalized)) as DecodeResult.Success).packet
                as BcInboundPacket.ChatMessage

            assertEquals(normalized, first.rawJson)
            assertEquals(normalized, first.message.payloadJson)
            assertEquals(first.packetId, replay.packetId)
        }
    }

    @Test
    fun dictionaryShapesKeepNativeArrayMessageIdsAndArrayOnlySenderFallback() {
        val entry = JSONObject("""{"SourceCharacter":1001,"TargetCharacter":2002,"FocusGroupName":"ItemTorso","ActivityName":"Pet","Tag":"MsgId","MsgId":"first"}""")
        val entries = JSONArray()
            .put(JSONObject("""{"Tag":"Other","MsgId":"ignored"}"""))
            .put(entry)
            .put(JSONObject("""{"Tag":"MsgId","MsgId":"later"}"""))
        val shapes = listOf(entries, entry, entries.toString(), entry.toString())
        for (dictionary in shapes) {
            val payload = JSONObject()
                .put("Sender", 1001)
                .put("Type", "Activity")
                .put("Content", "ChatOther-ItemTorso-Pet")
                .put("Dictionary", dictionary)
            val packet = (BcChatMessageCodec.decode(frameOf("ChatRoomMessage", payload.toString())) as DecodeResult.Success).packet
                as BcInboundPacket.ChatMessage
            val hasNativeArray = dictionary is JSONArray || dictionary == entries.toString()
            assertEquals(if (hasNativeArray) "later" else "first", packet.message.clientMsgId)
            assertEquals(2002, packet.message.targetMemberNumber)
            assertEquals("ItemTorso", packet.message.activityTargetGroup)
            assertEquals("Pet", packet.message.activityName)

            payload.put("MsgId", "top-level")
            val withTopId = (BcChatMessageCodec.decode(frameOf("ChatRoomMessage", payload.toString())) as DecodeResult.Success).packet
                as BcInboundPacket.ChatMessage
            assertEquals(if (hasNativeArray) "later" else "top-level", withTopId.message.clientMsgId)

            payload.remove("Sender")
            val withoutSender = BcChatMessageCodec.decode(frameOf("ChatRoomMessage", payload.toString()))
            if (dictionary is JSONArray || dictionary == entries.toString()) {
                assertEquals(1001, ((withoutSender as DecodeResult.Success).packet as BcInboundPacket.ChatMessage).message.senderMemberNumber)
            } else {
                assertTrue(withoutSender is DecodeResult.DeadLetter)
                assertEquals(ProtocolError.DecodeFailed("Missing chat sender."), (withoutSender as DecodeResult.DeadLetter).reason)
            }
        }
    }

    @Test
    fun unmatchedChatEnvelopesStillFailDecoding() {
        for (payload in listOf("[]", """[{"Sender":1001}]""", "\"text\"", """{"Message":"ChatRoomChat","Data":[]}""")) {
            val decoded = BcChatMessageCodec.decode(frameOf("ChatRoomMessage", payload))
            assertTrue(decoded is DecodeResult.DeadLetter)
            assertTrue((decoded as DecodeResult.DeadLetter).reason is ProtocolError.DecodeFailed)
        }
    }

    @Test
    fun decodeActivityFieldsFromDictionary() = runTest {
        val payload =
            """
            {
              "Content": "ChatOther-ItemTorso-钻进怀里",
              "Type": "Activity",
              "Dictionary": [
                {"SourceCharacter": 1001},
                {"TargetCharacter": 2002},
                {"FocusGroupName": "ItemTorso"},
                {"ActivityName": "钻进怀里"},
                {"Tag": "MsgId", "MsgId": "echo-cuddle-1"}
              ]
            }
            """.trimIndent()

        val result = defaultBcInboundCodecRegistry().decode(frameOf("ChatRoomMessage", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals(1001, packet.message.senderMemberNumber)
        assertEquals(2002, packet.message.targetMemberNumber)
        assertEquals("ItemTorso", packet.message.activityTargetGroup)
        assertEquals("钻进怀里", packet.message.activityName)
        assertEquals("echo-cuddle-1", packet.message.clientMsgId)
    }

    @Test
    fun decodeRoomChatMessage() = runTest {
        val payload = fixture("bc_chat_room_message_v1.json")
        val result = defaultBcInboundCodecRegistry().decode(frameOf("ChatRoomMessage", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals("chat.message.created", packet.eventName)
        assertEquals("room:current", packet.conversationId?.value)
        assertEquals(1001, packet.message.senderMemberNumber)
        assertEquals("Alice", packet.message.senderName)
        assertEquals("Chat", packet.message.wireType)
        assertEquals("hello room", packet.message.content)
        assertTrue(packet.packetId.startsWith("chat:"))
    }

    @Test
    fun decodeUppercaseBcActionMessageWithDictionary() = runTest {
        val payload = fixture("bc_chat_action_uppercase_v1.json")
        val result = defaultBcInboundCodecRegistry().decode(frameOf("ChatRoomMessage", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals("room:current", packet.conversationId?.value)
        assertEquals(1001, packet.message.senderMemberNumber)
        assertEquals("Action", packet.message.wireType)
        assertEquals("ActionUse", packet.message.content)
        val dictionary = JSONArray(checkNotNull(packet.message.dictionaryJson))
        assertEquals(4, dictionary.length())
        assertEquals(1001, dictionary.getJSONObject(0).getInt("SourceCharacter"))
        assertEquals(2002, dictionary.getJSONObject(1).getInt("TargetCharacter"))
        assertEquals("ItemArms", dictionary.getJSONObject(2).getString("FocusGroupName"))
        assertEquals("NextAsset", dictionary.getJSONObject(3).getString("Tag"))
        assertEquals("HempRope", dictionary.getJSONObject(3).getString("AssetName"))
    }

    @Test
    fun decodeChatRoomChatActionMessageWithSourceCharacterSender() = runTest {
        val payload =
            """
            {
              "Type": "Action",
              "Content": "VibeModeActionIncreaseTo0",
              "Dictionary": [
                { "SourceCharacter": 235841 },
                { "Tag": "DestinationCharacter", "MemberNumber": 235841, "Text": "Violet" },
                { "Tag": "AssetName", "AssetName": "VibratingEgg", "GroupName": "ItemVulva" },
                { "Tag": "fbc_nonce", "Text": 49 }
              ]
            }
            """.trimIndent()
        val result = defaultBcInboundCodecRegistry().decode(frameOf("ChatRoomChat", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals("room:current", packet.conversationId?.value)
        assertEquals(235841, packet.message.senderMemberNumber)
        assertEquals("Action", packet.message.wireType)
        assertEquals("VibeModeActionIncreaseTo0", packet.message.content)
        assertEquals(JSONObject(payload).toString(), packet.rawJson)
        val dictionary = JSONArray(checkNotNull(packet.message.dictionaryJson))
        assertEquals("DestinationCharacter", dictionary.getJSONObject(1).getString("Tag"))
        assertEquals("Violet", dictionary.getJSONObject(1).getString("Text"))
    }

    @Test
    fun decodeMessageEnvelopeChatRoomChatAction() = runTest {
        val payload =
            """
            {
              "Message": "ChatRoomChat",
              "Data": [
                {
                  "Type": "Action",
                  "Content": "VibeModeActionIncreaseTo0",
                  "Dictionary": [
                    { "SourceCharacter": 235841 }
                  ]
                }
              ]
            }
            """.trimIndent()
        val result = defaultBcInboundCodecRegistry().decode(frameOf("Message", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals(235841, packet.message.senderMemberNumber)
        assertEquals("Action", packet.message.wireType)
        assertEquals("VibeModeActionIncreaseTo0", packet.message.content)
        assertEquals(JSONObject(payload).getJSONArray("Data").getJSONObject(0).toString(), packet.rawJson)
    }

    @Test
    fun decodeMessageEventWithArrayChatRoomChatAction() = runTest {
        val payload =
            """
            [
              {
                "Type": "Action",
                "Content": "ActionUse",
                "Dictionary": [
                  { "SourceCharacter": 235841 },
                  { "Tag": "DestinationCharacter", "MemberNumber": 187577, "Text": "Vivian" },
                  { "TargetCharacter": 187577 },
                  { "Tag": "NextAsset", "AssetName": "VibratingEgg", "GroupName": "ItemVulva" },
                  { "Tag": "FocusAssetGroup", "FocusGroupName": "ItemVulva" },
                  { "Tag": "fbc_nonce", "Text": 82 }
                ]
              }
            ]
            """.trimIndent()
        val result = defaultBcInboundCodecRegistry().decode(frameOf("Message", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals(235841, packet.message.senderMemberNumber)
        assertEquals("Action", packet.message.wireType)
        assertEquals("ActionUse", packet.message.content)
        assertEquals(JSONArray(payload).getJSONObject(0).toString(), packet.rawJson)
        val dictionary = JSONArray(checkNotNull(packet.message.dictionaryJson))
        assertEquals(6, dictionary.length())
        assertEquals(187577, dictionary.getJSONObject(2).getInt("TargetCharacter"))
    }

    @Test
    fun decodeSocketIoArrayChatRoomMessageEnvelope() = runTest {
        val payload =
            """
            [
              "ChatRoomMessage",
              {
                "Content": "ServerEnter",
                "Dictionary": [
                  { "MemberNumber": 235841, "Tag": "SourceCharacter", "Text": "VIVIFIFTHBOT" }
                ],
                "Sender": 235841,
                "Type": "Action"
              }
            ]
            """.trimIndent()
        val result = defaultBcInboundCodecRegistry().decode(frameOf("Message", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals(235841, packet.message.senderMemberNumber)
        assertEquals("Action", packet.message.wireType)
        assertEquals("ServerEnter", packet.message.content)
        assertEquals(JSONArray(payload).getJSONObject(1).toString(), packet.rawJson)
        val dictionary = JSONArray(checkNotNull(packet.message.dictionaryJson))
        assertEquals("SourceCharacter", dictionary.getJSONObject(0).getString("Tag"))
        assertEquals("VIVIFIFTHBOT", dictionary.getJSONObject(0).getString("Text"))
    }

    @Test
    fun decodeWhisperLeavesConversationForDomainResolution() = runTest {
        val payload = fixture("bc_chat_whisper_v1.json")
        val result = defaultBcInboundCodecRegistry().decode(frameOf("ChatRoomMessage", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        assertEquals(null, packet.conversationId)
        assertEquals("Whisper", packet.message.wireType)
        assertEquals(2002, packet.message.targetMemberNumber)
    }

    @Test
    fun decodeAccountBeep() = runTest {
        val payload = fixture("bc_account_beep_v1.json")
        val result = defaultBcInboundCodecRegistry().decode(frameOf("AccountBeep", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.AccountBeep
        assertEquals("chat.direct_message.created", packet.eventName)
        assertEquals("message:1001", packet.conversationId?.value)
        assertEquals(1001, packet.beep.memberNumber)
        assertEquals("Alice", packet.beep.memberName)
        assertEquals("", packet.beep.beepType)
        assertEquals("hello direct", packet.beep.message)
        assertTrue(packet.packetId.startsWith("beep:"))
    }

    @Test
    fun decodeNullBeepTypeAssignsDirectConversation() = runTest {
        val payload =
            """
            {
              "MemberNumber": 1001,
              "MemberName": "Alice",
              "BeepType": null,
              "Message": "hello direct"
            }
            """.trimIndent()
        val result = defaultBcInboundCodecRegistry().decode(frameOf("AccountBeep", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.AccountBeep
        assertEquals("message:1001", packet.conversationId?.value)
        assertEquals(null, packet.beep.beepType)
    }

    @Test
    fun decodeSystemAccountBeepDoesNotAssignDirectConversation() = runTest {
        val payload =
            """
            {
              "MemberNumber": 1001,
              "MemberName": "Alice",
              "BeepType": "Leash",
              "Message": "join me"
            }
            """.trimIndent()
        val result = defaultBcInboundCodecRegistry().decode(frameOf("AccountBeep", payload))

        assertTrue(result is DecodeResult.Success)
        val packet = (result as DecodeResult.Success).packet as BcInboundPacket.AccountBeep
        assertEquals(null, packet.conversationId)
        assertEquals("Leash", packet.beep.beepType)
    }

    @Test
    fun chatMsgIdIsExtractedAndStableAcrossRedelivery() = runTest {
        val payload =
            """
            {
              "Sender": 1001,
              "Type": "Chat",
              "Content": "hello",
              "Dictionary": [
                { "Tag": "MsgId", "MsgId": "client-1" }
              ]
            }
            """.trimIndent()
        val first = defaultBcInboundCodecRegistry().decode(
            CanonicalFrame(
                namespace = "/",
                transportEventName = "ChatRoomMessage",
                args = listOf(payload),
                receivedAtMillis = 100L,
                raw = payload,
            )
        )
        val second = defaultBcInboundCodecRegistry().decode(
            CanonicalFrame(
                namespace = "/",
                transportEventName = "ChatRoomMessage",
                args = listOf(payload),
                receivedAtMillis = 200L,
                raw = payload,
            )
        )

        val firstPacket = (first as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        val secondPacket = (second as DecodeResult.Success).packet as BcInboundPacket.ChatMessage

        assertEquals("client-1", firstPacket.message.clientMsgId)
        assertEquals(firstPacket.packetId, secondPacket.packetId)
    }

    @Test
    fun repeatedChatWithoutMsgIdRemainsDistinctAcrossReceptions() = runTest {
        val payload = """{"Sender":1001,"Type":"Chat","Content":"hello"}"""
        val first = defaultBcInboundCodecRegistry().decode(
            CanonicalFrame(
                namespace = "/",
                transportEventName = "ChatRoomMessage",
                args = listOf(payload),
                receivedAtMillis = 100L,
                raw = payload,
            )
        )
        val second = defaultBcInboundCodecRegistry().decode(
            CanonicalFrame(
                namespace = "/",
                transportEventName = "ChatRoomMessage",
                args = listOf(payload),
                receivedAtMillis = 200L,
                raw = payload,
            )
        )

        val firstPacket = (first as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        val secondPacket = (second as DecodeResult.Success).packet as BcInboundPacket.ChatMessage

        assertTrue(firstPacket.packetId != secondPacket.packetId)
    }

    @Test
    fun allMessageTypesWithoutMsgIdUseReceptionIdentity() = runTest {
        val registry = defaultBcInboundCodecRegistry()
        val payloads = listOf("Chat", "Whisper", "Emote", "Action", "Activity", "ServerMessage", "Hidden")
            .map { type -> "ChatRoomMessage" to """{"Sender":1001,"Type":"$type","Content":"same content"}""" } +
            ("AccountBeep" to """{"MemberNumber":1001,"Message":"same content"}""")

        payloads.forEach { (eventName, payload) ->
            val firstFrame = frameOf(eventName, payload).copy(occurrenceId = 1L)
            val secondFrame = firstFrame.copy(occurrenceId = 2L)
            val first = (registry.decode(firstFrame) as DecodeResult.Success).packet
            val replay = (registry.decode(firstFrame) as DecodeResult.Success).packet
            val second = (registry.decode(secondFrame) as DecodeResult.Success).packet

            assertEquals(first.packetId, replay.packetId)
            assertTrue("Repeated $eventName payload was collapsed: $payload", first.packetId != second.packetId)
        }
    }

    @Test
    fun beepMsgIdRemainsStableAcrossReceptions() = runTest {
        val payload = """{"MemberNumber":1001,"Message":"same content","MsgId":"beep-1"}"""
        val registry = defaultBcInboundCodecRegistry()
        val firstFrame = frameOf("AccountBeep", payload).copy(occurrenceId = 1L)
        val first = (registry.decode(firstFrame) as DecodeResult.Success).packet
        val second = (registry.decode(firstFrame.copy(receivedAtMillis = 200L, occurrenceId = 2L)) as DecodeResult.Success).packet

        assertEquals(first.packetId, second.packetId)
    }

    @Test
    fun activitiesWithoutMsgIdRemainDistinctAcrossOccurrences() = runTest {
        val payload =
            """
            {
              "Sender": 1001,
              "Type": "Activity",
              "Content": "ChatOther-ItemTorso-Cuddle",
              "Dictionary": [
                {"SourceCharacter": 1001},
                {"TargetCharacter": 2002},
                {"FocusGroupName": "ItemTorso"},
                {"ActivityName": "Cuddle"}
              ]
            }
            """.trimIndent()
        val first = defaultBcInboundCodecRegistry().decode(
            CanonicalFrame(
                namespace = "/",
                transportEventName = "ChatRoomMessage",
                args = listOf(payload),
                receivedAtMillis = 100L,
                raw = payload,
                occurrenceId = 1L,
            )
        )
        val second = defaultBcInboundCodecRegistry().decode(
            CanonicalFrame(
                namespace = "/",
                transportEventName = "ChatRoomMessage",
                args = listOf(payload),
                receivedAtMillis = 100L,
                raw = payload,
                occurrenceId = 2L,
            )
        )

        val firstPacket = (first as DecodeResult.Success).packet as BcInboundPacket.ChatMessage
        val secondPacket = (second as DecodeResult.Success).packet as BcInboundPacket.ChatMessage

        assertTrue(firstPacket.packetId != secondPacket.packetId)
    }

    @Test
    fun unknownEventRecordsDeadLetter() = runTest {
        val deadLetters = mutableListOf<DeadLetterRecord>()
        val registry = CodecRegistry<BcInboundPacket>(
            codecs = listOf(BcChatMessageCodec),
            deadLetterSink = object : DeadLetterSink {
                override suspend fun record(record: DeadLetterRecord) {
                    deadLetters += record
                }
            },
            nowMillis = { 1234L },
        )

        val result = registry.decode(frameOf("UnknownEvent", "{}"))

        assertTrue(result is DecodeResult.DeadLetter)
        assertEquals(1, deadLetters.size)
        assertEquals(1234L, deadLetters.single().recordedAtMillis)
        assertEquals(null, deadLetters.single().codecName)
    }

    private fun frameOf(eventName: String, payload: String): CanonicalFrame =
        CanonicalFrame(
            namespace = "/",
            transportEventName = eventName,
            args = listOf(payload),
            receivedAtMillis = 1782460800456,
            raw = payload,
        )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("fixtures/$name")) {
            "Missing fixture $name"
        }.readText()
}
