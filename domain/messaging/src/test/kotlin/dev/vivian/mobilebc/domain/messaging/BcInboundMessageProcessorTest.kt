package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcMessageVisibility
import dev.vivian.mobilebc.core.model.BcNotificationPolicy

import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessage
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.bcpath.BcResourcePaths
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.DeadLetterRecord
import dev.vivian.mobilebc.core.protocol.DeadLetterSink
import dev.vivian.mobilebc.core.protocol.ProtocolError
import dev.vivian.mobilebc.domain.bctext.BcActivityTextParser
import dev.vivian.mobilebc.domain.bctext.BcActivityDictionaryTextParser
import dev.vivian.mobilebc.domain.bctext.BcDictionaryValueResolver
import dev.vivian.mobilebc.domain.bctext.BcInterfaceTextParser
import dev.vivian.mobilebc.domain.bctext.BcParsedText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BcInboundMessageProcessorTest {
    @Test
    fun lianProfileBeepUpdatesProfileWithoutVisibleMessageOrReadCursor() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(persistenceStore = persistence, ownerMemberNumber = { 9999 })
        val profile = """{"Name":"Alice","Nickname":"Ally","Avatar":"https://i.imgur.com/a.png","Signature":"Hello"}"""
        val payload = com.google.gson.JsonObject().apply {
            addProperty("MemberNumber", 1001)
            addProperty("BeepType", "LCPlayerInfo")
            addProperty("Message", profile)
        }.toString()
        processor.handle(beepFrame(payload))
        val command = persistence.commands.single()
        assertEquals(1001, command.contactProfile?.memberNumber)
        assertEquals("https://i.imgur.com/a.png", command.contactProfile?.avatarUrl)
        assertEquals("Hello", command.contactProfile?.signature)
        assertEquals(1782460800123L, command.contactProfile?.lianUpdatedAtMillis)
        assertNull(command.message)
        assertNull(command.cursorConversationId)
        assertFalse(command.markReadThroughMessage)
    }

    @Test
    fun malformedLianProfileCannotClearStoredAvatar() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(persistenceStore = persistence, ownerMemberNumber = { 9999 })
        val payload = com.google.gson.JsonObject().apply {
            addProperty("MemberNumber", 1001)
            addProperty("BeepType", "LCPlayerInfo")
            addProperty("Message", """{"Avatar":42,"Signature":{}}""")
        }.toString()
        processor.handle(beepFrame(payload))
        assertNull(persistence.commands.single().contactProfile)
    }

    @Test
    fun packetLogSelfEchoConfirmsBeforeRenderingAndOnReplay() = runTest {
        val confirmations = mutableListOf<String>()
        var saved = false
        val msgId = "5e93f09b-c496-4ff0-8058-faf024c44c7f"
        val store = object : BcInboundPersistenceStore {
            override suspend fun confirmOutgoing(ownerMemberNumber: Int, clientMsgId: String, receivedAtMillis: Long) {
                assertEquals(187577, ownerMemberNumber)
                confirmations += clientMsgId
            }

            override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult {
                assertEquals(msgId, command.message?.id?.value)
                assertTrue(command.message?.sender?.isSelf == true)
                return if (saved) BcInboundPersistenceResult.Duplicate(command.event.packetId)
                else BcInboundPersistenceResult.Recorded.also { saved = true }
            }
        }
        val processor = BcInboundMessageProcessor(
            persistenceStore = store,
            ownerMemberNumber = { 187577 },
            chatTextResolver = object : BcChatTextResolver {
                override suspend fun resolve(
                    message: dev.vivian.mobilebc.core.model.BcRawChatMessage,
                    kind: BcMessageKind,
                    memberLookup: Map<Int, String>,
                ): BcChatTextResolution {
                    assertTrue(confirmations.isNotEmpty())
                    error("Rendering resources unavailable")
                }
            },
        )
        val frame = chatFrame("""{"Sender":187577,"Content":"uwu","Type":"Chat","Dictionary":[{"Tag":"MsgId","MsgId":"$msgId"}]}""")
        assertTrue(processor.handle(frame) is BcInboundProcessingResult.Recorded)
        assertTrue(processor.handle(frame) is BcInboundProcessingResult.Duplicate)
        assertEquals(listOf(msgId, msgId), confirmations)
    }

    @Test
    fun anotherSenderOrHiddenPacketCannotConfirmOutgoingMessage() = runTest {
        val store = object : BcInboundPersistenceStore {
            override suspend fun confirmOutgoing(ownerMemberNumber: Int, clientMsgId: String, receivedAtMillis: Long) {
                error("Unrelated packet must not confirm a send")
            }
            override suspend fun record(command: BcInboundPersistenceCommand) = BcInboundPersistenceResult.Recorded
        }
        val processor = BcInboundMessageProcessor(persistenceStore = store, ownerMemberNumber = { 187577 })
        for ((sender, type) in listOf(1001 to "Chat", 187577 to "Hidden")) {
            processor.handle(chatFrame("""{"Sender":$sender,"Content":"uwu","Type":"$type","Dictionary":[{"MsgId":"same-id"}]}"""))
        }
    }

    @Test
    fun repeatedMessagesWithoutMsgIdArePersistedAndSameFrameReplayIsIgnored() = runTest {
        val seenPackets = mutableSetOf<String>()
        val persistedMessages = mutableListOf<BcMessage>()
        val store = object : BcInboundPersistenceStore {
            override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult {
                if (!seenPackets.add(command.event.packetId)) {
                    return BcInboundPersistenceResult.Duplicate(command.event.packetId)
                }
                command.message?.let(persistedMessages::add)
                return BcInboundPersistenceResult.Recorded
            }
        }
        val processor = BcInboundMessageProcessor(persistenceStore = store, ownerMemberNumber = { 9999 })
        val frames = listOf("Chat", "Emote", "Action", "Activity", "ServerMessage")
            .map { type -> chatFrame("""{"Sender":1001,"Type":"$type","Content":"repeat"}""") } +
            beepFrame("""{"MemberNumber":1001,"Message":"repeat"}""")

        frames.forEach { frame ->
            val first = frame.copy(occurrenceId = 1L)
            val second = frame.copy(occurrenceId = 2L)
            assertTrue(processor.handle(first) is BcInboundProcessingResult.Recorded)
            assertTrue(processor.handle(second) is BcInboundProcessingResult.Recorded)
            assertTrue(processor.handle(first) is BcInboundProcessingResult.Duplicate)
        }

        assertEquals(12, persistedMessages.size)
        assertEquals(12, persistedMessages.map { it.id }.toSet().size)
    }

    @Test
    fun r131PacketLogActivitiesResolveThroughTheVersionedChineseDictionary() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val activityDictionaryCsv = """
            ChatOther-ItemHead-Pet,SourceCharacter gently pets TargetCharacter's head.
            ChatOther-ItemHead-TakeCare,SourceCharacter brushes TargetCharacter's hair.
        """.trimIndent()
        val activityDictionaryCn = """
            SourceCharacter gently pets TargetCharacter's head.
            SourceCharacter轻轻地摸了摸TargetCharacter的头.
            SourceCharacter brushes TargetCharacter's hair.
            SourceCharacter给TargetCharacter梳头.
        """.trimIndent()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            roomMemberLookup = { mapOf(1001 to "Alice", 1002 to "Bob") },
            currentRoomConversationId = { BcConversationId("room:current") },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityDictionaryTextParser(
                    resourceReader = { version, path ->
                        assertEquals("R131", version)
                        when (path) {
                            BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCsv ->
                                activityDictionaryCsv
                            BcResourcePaths.Screens.Character.Preference.ActivityDictionaryCn ->
                                activityDictionaryCn
                            else -> null
                        }
                    },
                    language = "CN",
                ),
                currentVersion = { "R131" },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Content": "ChatOther-ItemHead-TakeCare",
                  "Type": "Activity",
                  "Dictionary": [
                    {"SourceCharacter": 1001},
                    {"TargetCharacter": 1002},
                    {"Tag":"FocusAssetGroup","FocusGroupName":"ItemHead"},
                    {"ActivityName":"TakeCare"},
                    {"Tag":"fbc_nonce","Text":709}
                  ]
                }
                """.trimIndent(),
            ),
        )
        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Content": "ChatOther-ItemHead-Pet",
                  "Type": "Activity",
                  "Dictionary": [
                    {"SourceCharacter": 1001},
                    {"TargetCharacter": 1002},
                    {"Tag":"FocusAssetGroup","FocusGroupName":"ItemHead"},
                    {"ActivityName":"Pet"},
                    {"Tag":"fbc_nonce","Text":733}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf("Alice给Bob梳头.", "Alice轻轻地摸了摸Bob的头."),
            messages.messages.map(BcMessage::text),
        )
        assertEquals(
            listOf("ChatOther-ItemHead-TakeCare", "ChatOther-ItemHead-Pet"),
            messages.messages.map { message ->
                (message.resolution as BcMessageResolution.Resolved).key
            },
        )
    }

    @Test
    fun r131PacketLogRoomUpdateResolvesTextLookupBeforePersistence() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val englishTemplate =
            "SourceCharacter updated the room. Name: ChatRoomName. Limit: ChatRoomLimit. ChatRoomPrivacy. ChatRoomLocked."
        val chineseTemplate =
            "SourceCharacter更新了房间信息. 名称: ChatRoomName. 人数上限: ChatRoomLimit. ChatRoomPrivacy. ChatRoomLocked."
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            roomMemberLookup = { mapOf(225139 to "菲露亚") },
            currentRoomConversationId = { BcConversationId("room:current") },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                interfaceTextParser = BcInterfaceTextParser(
                    resourceReader = { version, path ->
                        assertEquals("R131", version)
                        when (path) {
                            BcResourcePaths.Screens.InterfaceCsv -> """
                                ServerUpdateRoom,$englishTemplate
                                Private,Private
                                Unlocked,Unlocked
                            """.trimIndent()
                            BcResourcePaths.Screens.InterfaceCn -> """
                                $englishTemplate
                                $chineseTemplate
                                Private
                                私人
                                Unlocked
                                解锁
                            """.trimIndent()
                            else -> null
                        }
                    },
                    language = "CN",
                ),
                currentVersion = { "R131" },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender":225139,
                  "Content":"ServerUpdateRoom",
                  "Type":"Action",
                  "Dictionary":[
                    {"Tag":"SourceCharacter","Text":"Feylua","MemberNumber":225139},
                    {"Tag":"ChatRoomName","Text":"Fairy house"},
                    {"Tag":"ChatRoomLimit","Text":"20"},
                    {"Tag":"ChatRoomPrivacy","TextToLookUp":"Private"},
                    {"Tag":"ChatRoomLocked","TextToLookUp":"Unlocked"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        val message = messages.messages.single()
        assertEquals(
            "Feylua更新了房间信息. 名称: Fairy house. 人数上限: 20. 私人. 解锁.",
            message.text,
        )
        assertEquals(
            BcMessageResolution.Resolved("ServerUpdateRoom", message.text),
            message.resolution,
        )
    }

    @Test
    fun localEchoTemplateUsesLocalizedAssetAndBodyPartNames() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 2002 },
            roomMemberLookup = { mapOf(1001 to "Alice", 2002 to "Bob") },
            currentRoomConversationId = { BcConversationId("room:current") },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                currentVersion = { "R129" },
                dictionaryValueResolver = object : BcDictionaryValueResolver {
                    override fun groupDisplayName(version: String, groupName: String): String? = "手臂"

                    override fun assetDisplayName(version: String, groupName: String?, assetName: String): String? =
                        "麻绳"
                },
                activityTemplateProvider = { activityName, _, _ ->
                    if (activityName == "EchoTie") {
                        "SourceCharacter uses ActivityAsset on TargetCharacter's FocusAssetGroup."
                    } else {
                        null
                    }
                },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Content": "ChatOther-ItemArms-EchoTie",
                  "Type": "Activity",
                  "Dictionary": [
                    {"SourceCharacter": 1001},
                    {"TargetCharacter": 2002},
                    {"Tag":"ActivityAsset","GroupName":"ItemArms","AssetName":"HempRope"},
                    {"Tag":"FocusAssetGroup","FocusGroupName":"ItemArms"},
                    {"ActivityName":"EchoTie"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Alice uses 麻绳 on Bob's 手臂.", messages.messages.single().text)
    }

    @Test
    fun localEchoTemplateOverridesSenderFallbackLanguage() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 2002 },
            ownerDisplayName = { "Bob" },
            roomMemberLookup = { mapOf(1001 to "Alice", 2002 to "Bob") },
            currentRoomConversationId = { BcConversationId("room:current") },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                currentVersion = { "R129" },
                activityTemplateProvider = { activityName, _, _ ->
                    if (activityName == "抱入怀中") {
                        "SourceCharacter holds TargetCharacter in arms."
                    } else {
                        null
                    }
                },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Content": "ChatOther-ItemTorso-抱入怀中",
                  "Type": "Activity",
                  "Dictionary": [
                    {"SourceCharacter": 1001},
                    {"TargetCharacter": 2002},
                    {"FocusGroupName": "ItemTorso"},
                    {"ActivityName": "抱入怀中"},
                    {"Tag": "MISSING Echo", "Text": "Alice把Bob抱进了怀中。"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Alice holds Bob in arms.", messages.messages.single().text)
    }

    @Test
    fun recordedActivityExposesTypedInboundCommand() = runTest {
        val processor = BcInboundMessageProcessor(
            persistenceStore = NoOpInboundPersistenceStore,
            ownerMemberNumber = { 2002 },
            currentRoomConversationId = { BcConversationId("room:current") },
        )

        val result = processor.handle(
            chatFrame(
                """
                {
                  "Content": "ChatOther-ItemTorso-抱入怀中",
                  "Type": "Activity",
                  "Dictionary": [
                    {"SourceCharacter": 1001},
                    {"TargetCharacter": 2002},
                    {"FocusGroupName": "ItemTorso"},
                    {"ActivityName": "抱入怀中"},
                    {"Tag": "MsgId", "MsgId": "echo-cuddle-2"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertTrue(result is BcInboundProcessingResult.Recorded)
        val command = checkNotNull((result as BcInboundProcessingResult.Recorded).packet.toInboundActivityCommandOrNull())
        assertEquals(1001, command.sourceMemberNumber)
        assertEquals(2002, command.targetMemberNumber)
        assertEquals("ItemTorso", command.targetGroup)
        assertEquals("抱入怀中", command.activityName)
        assertEquals("echo-cuddle-2", command.clientMsgId)
    }

    @Test
    fun deobfuscationShowsContentAndBracketedOriginalOnSeparateLines() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            currentRoomConversationId = { BcConversationId("room:current") },
            showDeobfuscatedOriginal = { true },
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 219243,
                  "Content": "你咕得罪惡，恩是因為。還不夠",
                  "Type": "Chat",
                  "Dictionary": [
                    {"Effects":["gagGarble"],"Original":"你覺得罪惡，那是因為。還不夠"},
                    {"Tag":"fbc_nonce","Text":152}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(
            "你咕得罪惡，恩是因為。還不夠\n[你覺得罪惡，那是因為。還不夠]",
            messages.messages.single().text,
        )
    }

    @Test
    fun unknownFrameUsesInjectedDeadLetterSink() = runTest {
        val deadLetters = mutableListOf<DeadLetterRecord>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = NoOpInboundPersistenceStore,
            deadLetterSink = object : DeadLetterSink {
                override suspend fun record(record: DeadLetterRecord) {
                    deadLetters += record
                }
            },
        )
        val frame = CanonicalFrame(
            namespace = "/bc",
            transportEventName = "UnexpectedEvent",
            args = listOf("""{"value":1}"""),
            receivedAtMillis = 1782460800123,
            raw = """{"value":1}""",
        )

        val result = processor.handle(frame)

        assertTrue(result is BcInboundProcessingResult.DeadLetter)
        assertEquals(1, deadLetters.size)
        assertEquals("UnexpectedEvent", deadLetters.single().frame.transportEventName)
        assertTrue(deadLetters.single().reason is ProtocolError.UnknownEvent)
    }

    @Test
    fun actionMessagePersistsResolvedText() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            roomMemberLookup = { mapOf(1001 to "Alice") },
            chatTextResolver = BcChatTextResolver { message, kind, memberLookup ->
                assertEquals(BcMessageKind.Action, kind)
                assertEquals("ActionUse", message.content)
                assertEquals("Alice", memberLookup[1001])
                BcChatTextResolution(
                    text = "Alice uses rope.",
                    resolution = BcMessageResolution.Resolved("ActionUse", "Alice uses rope."),
                )
            },
        )
        val frame = chatFrame(
            """
            {
              "Sender": 1001,
              "Type": "Action",
              "Content": "ActionUse",
              "Dictionary": [{ "SourceCharacter": 1001 }]
            }
            """.trimIndent(),
        )

        val result = processor.handle(frame)

        assertTrue(result is BcInboundProcessingResult.Recorded)
        assertEquals(1, messages.messages.size)
        assertEquals("Alice uses rope.", messages.messages.single().text)
        assertEquals(BcMessageResolution.Resolved("ActionUse", "Alice uses rope."), messages.messages.single().resolution)
    }

    @Test
    fun actionMessageUsesMissingDictionaryFallbackText() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                currentVersion = { "R129" },
            ),
        )
        val frame = chatFrame(
            """
            {
              "Sender": 1001,
              "Type": "Action",
              "Content": "BCX_PLAYER_CUSTOM_DIALOG",
              "Dictionary": [
                {
                  "Tag": "MISSING TEXT IN \"Interface.csv\": BCX_PLAYER_CUSTOM_DIALOG",
                  "Text": "test"
                },
                { "Tag": "fbc_nonce", "Text": 6 }
              ]
            }
            """.trimIndent(),
        )

        processor.handle(frame)

        assertEquals("test", messages.messages.single().text)
        assertEquals(
            BcMessageResolution.Resolved("BCX_PLAYER_CUSTOM_DIALOG", "test"),
            messages.messages.single().resolution,
        )
    }

    @Test
    fun actionMessageUsesInterfaceParserForGeneralRoomPackets() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                interfaceTextParser = BcActivityTextParser { request ->
                    if (request.key == "ActionUse") {
                        BcParsedText(request.key, "Alice uses rope.")
                    } else {
                        null
                    }
                },
                currentVersion = { "R129" },
            ),
        )
        val frame = chatFrame(
            """
            {
              "Sender": 1001,
              "Type": "Action",
              "Content": "ActionUse",
              "Dictionary": [{ "SourceCharacter": 1001 }]
            }
            """.trimIndent(),
        )

        processor.handle(frame)

        assertEquals("Alice uses rope.", messages.messages.single().text)
        assertEquals(
            BcMessageResolution.Resolved("ActionUse", "Alice uses rope."),
            messages.messages.single().resolution,
        )
    }

    @Test
    fun serverEnterActionUsesInterfaceParserInsteadOfDictionaryTextOnly() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                interfaceTextParser = BcActivityTextParser { request ->
                    if (request.key == "ServerEnter") {
                        BcParsedText(request.key, "${request.memberLookup[235841] ?: "VIVIFIFTHBOT"} enters the room.")
                    } else {
                        null
                    }
                },
                currentVersion = { "R129" },
            ),
        )
        val frame = chatFrame(
            """
            {
              "Content": "ServerEnter",
              "Dictionary": [
                { "MemberNumber": 235841, "Tag": "SourceCharacter", "Text": "VIVIFIFTHBOT" }
              ],
              "Sender": 235841,
              "Type": "Action"
            }
            """.trimIndent(),
        )

        processor.handle(frame)

        assertEquals("VIVIFIFTHBOT enters the room.", messages.messages.single().text)
        assertEquals(
            BcMessageResolution.Resolved("ServerEnter", "VIVIFIFTHBOT enters the room."),
            messages.messages.single().resolution,
        )
    }

    @Test
    fun actionMessageFallsBackToAssetStringsParserForGeneralRoomPackets() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                interfaceTextParser = BcActivityTextParser { null },
                assetStringsTextParser = BcActivityTextParser { request ->
                    if (request.key == "VibeModeActionIncreaseTo0") {
                        BcParsedText(request.key, "Violet's vibratingegg starts teasing them lightly.")
                    } else {
                        null
                    }
                },
                currentVersion = { "R129" },
            ),
        )
        val frame = CanonicalFrame(
            namespace = "/",
            transportEventName = "ChatRoomChat",
            args = listOf(
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
                """.trimIndent(),
            ),
            receivedAtMillis = 1782460800123,
            raw = "{}",
        )

        processor.handle(frame)

        assertEquals("Violet's vibratingegg starts teasing them lightly.", messages.messages.single().text)
        assertEquals(
            BcMessageResolution.Resolved(
                "VibeModeActionIncreaseTo0",
                "Violet's vibratingegg starts teasing them lightly.",
            ),
            messages.messages.single().resolution,
        )
    }

    @Test
    fun actionUsesNativeAssetStringsBeforeInterfaceStrings() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                assetStringsTextParser = BcActivityTextParser { request ->
                    BcParsedText(request.key, "asset text")
                },
                interfaceTextParser = BcActivityTextParser { request ->
                    BcParsedText(request.key, "interface text")
                },
                currentVersion = { "R129" },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "Action",
                  "Content": "ActionUse",
                  "Dictionary": []
                }
                """.trimIndent(),
            )
        )

        assertEquals("asset text", messages.messages.single().text)
    }

    @Test
    fun serverMessageUsesNativeInterfacePrefix() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val requestedKeys = mutableListOf<String>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                interfaceTextParser = BcActivityTextParser { request ->
                    requestedKeys += request.key
                    BcParsedText(request.key, "Server says no.")
                },
                currentVersion = { "R129" },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "ServerMessage",
                  "Content": "RoomLocked",
                  "Dictionary": []
                }
                """.trimIndent(),
            )
        )

        assertEquals(listOf("ServerMessageRoomLocked"), requestedKeys)
        assertEquals("Server says no.", messages.messages.single().text)
        assertEquals(BcMessageKind.System, messages.messages.single().kind)
    }

    @Test
    fun activityUsesAssetSpecificTextBeforeActivityDictionary() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val requestedKeys = mutableListOf<String>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { request ->
                    requestedKeys += "activity:${request.key}"
                    BcParsedText(request.key, "generic activity")
                },
                assetStringsTextParser = BcActivityTextParser { request ->
                    requestedKeys += "asset:${request.key}"
                    BcParsedText(request.key, "asset-specific activity")
                },
                currentVersion = { "R129" },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "Activity",
                  "Content": "ChatOther-ItemArms-Pet",
                  "Dictionary": [
                    {"Tag":"ActivityAsset","GroupName":"ItemArms","AssetName":"Rope"},
                    {"ActivityName":"Pet"}
                  ]
                }
                """.trimIndent(),
            )
        )

        assertEquals(listOf("asset:ItemArmsRopeActivityPet"), requestedKeys)
        assertEquals("asset-specific activity", messages.messages.single().text)
        assertEquals(
            BcMessageResolution.Resolved("ChatOther-ItemArms-Pet", "asset-specific activity"),
            messages.messages.single().resolution,
        )
    }

    @Test
    fun echoActivityUsesInjectedMissingTextTemplateFromWireDictionary() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            roomMemberLookup = { mapOf(1001 to "Alice", 1002 to "Bob") },
            chatTextResolver = ActivityBackedBcChatTextResolver(
                activityTextParser = BcActivityTextParser { null },
                currentVersion = { "R129" },
            ),
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "Activity",
                  "Content": "ChatOther-ItemArms-翅膀轻拍",
                  "Dictionary": [
                    {"SourceCharacter":1001},
                    {"TargetCharacter":1002},
                    {
                      "Tag":"MISSING TEXT IN \"ActivityDictionary.csv\": ChatOther-ItemArms-翅膀轻拍",
                      "Text":"SourceCharacter用翅膀轻轻拍了拍TargetCharacter的手臂。"
                    }
                  ]
                }
                """.trimIndent(),
            )
        )

        assertEquals("Alice用翅膀轻轻拍了拍Bob的手臂。", messages.messages.single().text)
    }

    @Test
    fun deobfuscatedOriginalIsAppendedToResolvedActionText() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            showDeobfuscatedOriginal = { true },
            chatTextResolver = BcChatTextResolver { _, _, _ ->
                BcChatTextResolution(
                    text = "Alice enters the room.",
                    resolution = BcMessageResolution.Resolved("ServerEnter", "Alice enters the room."),
                )
            },
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "Action",
                  "Content": "ServerEnter",
                  "Dictionary": [{"Original":"Alice entered."}]
                }
                """.trimIndent(),
            )
        )

        assertEquals("Alice enters the room.\n[Alice entered.]", messages.messages.single().text)
    }

    @Test
    fun hiddenStatusAndLocalMessagesAreNotPersistedAsVisibleMessages() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
        )

        listOf("Hidden", "Status", "LocalMessage").forEach { type ->
            val result = processor.handle(
                chatFrame(
                    """
                    {
                      "Sender": 1001,
                      "Type": "$type",
                      "Content": "ignored"
                    }
                    """.trimIndent(),
                )
            )
            assertTrue(result is BcInboundProcessingResult.Recorded)
            assertEquals(null, (result as BcInboundProcessingResult.Recorded).message)
        }

        assertTrue(messages.messages.isEmpty())
    }

    @Test
    fun jsonDataBeepsRemainInboxEventsWithoutMessagesOrDirectCursors() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 9999 },
        )
        listOf("{}", """  {"type":"LianChat","data":{"text":"hello"},"items":[1,true]}  """).forEach { text ->
            val payload = com.google.gson.JsonObject().apply {
                addProperty("MemberNumber", 1001)
                addProperty("Message", text)
            }.toString()
            val result = processor.handle(beepFrame(payload)) as BcInboundProcessingResult.Recorded
            assertEquals(null, result.message)
            assertEquals(payload, persistence.commands.last().event.rawJson)
            assertEquals(null, persistence.commands.last().event.conversationId)
            assertEquals(false, persistence.commands.last().markReadThroughMessage)
        }
        assertEquals(2, persistence.commands.size)
        assertTrue(persistence.messages.isEmpty())
        assertTrue(persistence.advancedConversations.isEmpty())
    }

    @Test
    fun ordinaryAndMalformedJsonBeepsStayVisible() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 9999 },
        )
        val texts = listOf(
            "hello", "{hello}", """{"broken":}""", "{'key':'value'}",
            """{"key":1} {"key":2}""", """{"key":1,}""",
            """[{"key":1}]""", """prefix {"key":1}""", "ReqRoom",
        )
        texts.forEach { text ->
            val payload = com.google.gson.JsonObject().apply {
                addProperty("MemberNumber", 1001)
                addProperty("Message", text)
            }.toString()
            processor.handle(beepFrame(payload))
        }
        assertEquals(texts, persistence.messages.map { it.text })
        assertTrue(persistence.messages.all {
            it.visibility == BcMessageVisibility.Visible &&
                it.notificationPolicy == BcNotificationPolicy.DirectMessage
        })
    }

    @Test
    fun accountBeepWithEmptyBeepTypePersistsVisibleDirectMessage() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
        )

        val result = processor.handle(
            beepFrame(
                """
                {
                  "MemberNumber": 1001,
                  "MemberName": "Alice",
                  "BeepType": "",
                  "Message": "hello direct"
                }
                """.trimIndent(),
            )
        )

        assertTrue(result is BcInboundProcessingResult.Recorded)
        val message = messages.messages.single()
        assertEquals("message:1001", message.conversationId.value)
        assertEquals(BcMessageKind.Beep, message.kind)
        assertEquals("hello direct", message.text)
    }

    @Test
    fun accountBeepWithNonEmptyBeepTypeDoesNotPersistOrAdvanceDirectCursor() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 9999 },
        )

        val result = processor.handle(
            beepFrame(
                """
                {
                  "MemberNumber": 1001,
                  "MemberName": "Alice",
                  "BeepType": "Leash",
                  "Message": "join me"
                }
                """.trimIndent(),
            )
        )

        assertTrue(result is BcInboundProcessingResult.Recorded)
        assertEquals(null, (result as BcInboundProcessingResult.Recorded).message)
        assertTrue(persistence.messages.isEmpty())
        assertTrue(persistence.advancedConversations.isEmpty())
    }

    @Test
    fun accountBeepWithoutBeepTypePersistsVisibleDirectMessage() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
        )

        val result = processor.handle(
            beepFrame(
                """
                {
                  "MemberNumber": 1001,
                  "MemberName": "Alice",
                  "Message": "message missing type"
                }
                """.trimIndent(),
            )
        )

        assertTrue(result is BcInboundProcessingResult.Recorded)
        val message = (result as BcInboundProcessingResult.Recorded).message
        assertEquals("message:1001", message?.conversationId?.value)
        assertEquals(BcMessageKind.Beep, message?.kind)
        assertEquals("message missing type", message?.text)
        assertEquals("message missing type", messages.messages.single().text)
    }

    @Test
    fun accountBeepWithNullBeepTypePersistsVisibleDirectMessage() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
        )

        val result = processor.handle(
            beepFrame(
                """
                {
                  "MemberNumber": 1001,
                  "MemberName": "Alice",
                  "BeepType": null,
                  "Message": "null type"
                }
                """.trimIndent(),
            )
        )

        assertTrue(result is BcInboundProcessingResult.Recorded)
        val message = messages.messages.single()
        assertEquals("message:1001", message.conversationId.value)
        assertEquals(BcMessageKind.Beep, message.kind)
        assertEquals("null type", message.text)
    }

    @Test
    fun selfEchoUsesWireMsgIdAsPersistedMessageIdentity() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 9999,
                  "Type": "Chat",
                  "Content": "hello",
                  "Dictionary": [
                    { "Tag": "MsgId", "MsgId": "client-echo-1" }
                  ]
                }
                """.trimIndent(),
            )
        )

        assertEquals("client-echo-1", messages.messages.single().id.value)
    }

    @Test
    fun visibleConversationRequestsAtomicReadCursorAdvance() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val visibleConversation = BcConversationId("room:current")
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 9999 },
            currentRoomConversationId = { visibleConversation },
            isConversationVisible = { it == visibleConversation },
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "Chat",
                  "Content": "visible"
                }
                """.trimIndent(),
            )
        )

        assertEquals(true, persistence.commands.single().markReadThroughMessage)
    }

    @Test
    fun senderNameFallsBackToRoomMemberLookup() = runTest {
        val messages = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = messages,
            ownerMemberNumber = { 9999 },
            roomMemberLookup = { mapOf(1001 to "Alice From Room") },
        )

        processor.handle(
            chatFrame(
                """
                {
                  "Sender": 1001,
                  "Type": "Chat",
                  "Content": "hello"
                }
                """.trimIndent(),
            )
        )

        assertEquals("Alice From Room", messages.messages.single().sender.displayName)
    }

    @Test
    fun ignoredSenderIsDroppedBeforePersistenceAndActivityExecution() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 2002 },
            ignoredMemberNumbers = { setOf(1001) },
        )

        val result = processor.handle(
            chatFrame(
                """
                {
                  "Content": "ChatOther-ItemTorso-Hug",
                  "Type": "Activity",
                  "Dictionary": [
                    {"SourceCharacter": 1001},
                    {"TargetCharacter": 2002},
                    {"FocusGroupName": "ItemTorso"},
                    {"ActivityName": "Hug"}
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(BcInboundIgnored::class, result::class)
        assertTrue(persistence.commands.isEmpty())
    }

    @Test
    fun ignoredAccountBeepIsDroppedBeforeDirectMessagePersistence() = runTest {
        val persistence = CapturingInboundPersistenceStore()
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 9999 },
            ignoredMemberNumbers = { setOf(1001) },
        )

        val result = processor.handle(
            beepFrame("""{"MemberNumber":1001,"Message":"blocked"}"""),
        )

        assertEquals(BcInboundIgnored::class, result::class)
        assertTrue(persistence.commands.isEmpty())
    }

    @Test
    fun friendRequestIsPublishedOnlyAfterDurableDeduplication() = runTest {
        val persistence = object : BcInboundPersistenceStore {
            var recorded = false
            override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult =
                if (recorded) {
                    BcInboundPersistenceResult.Duplicate(command.event.packetId)
                } else {
                    recorded = true
                    BcInboundPersistenceResult.Recorded
                }
        }
        val requests = mutableListOf<BcInboundFriendRequest>()
        val processor = BcInboundMessageProcessor(
            persistenceStore = persistence,
            ownerMemberNumber = { 2002 },
            onFriendRequest = { request -> requests += request },
        )
        val frame = chatFrame(
            """
            {
              "Sender": 1001,
              "Target": 2002,
              "Type": "Hidden",
              "Content": "ChatRoomFriendRequestAdd",
              "MsgId": "friend-request-1"
            }
            """.trimIndent(),
        )

        processor.handle(frame)
        processor.handle(frame)

        assertEquals(listOf(BcInboundFriendRequest(1001, 1782460800123)), requests)
    }

    private fun chatFrame(payload: String): CanonicalFrame =
        CanonicalFrame(
            namespace = "/",
            transportEventName = "ChatRoomMessage",
            args = listOf(payload),
            receivedAtMillis = 1782460800123,
            raw = payload,
        )

    private fun beepFrame(payload: String): CanonicalFrame =
        CanonicalFrame(
            namespace = "/",
            transportEventName = "AccountBeep",
            args = listOf(payload),
            receivedAtMillis = 1782460800123,
            raw = payload,
        )
}

private object NoOpInboundPersistenceStore : BcInboundPersistenceStore {
    override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult =
        BcInboundPersistenceResult.Recorded
}


private class CapturingInboundPersistenceStore : BcInboundPersistenceStore {
    val commands = mutableListOf<BcInboundPersistenceCommand>()
    val messages = mutableListOf<BcMessage>()
    val advancedConversations = mutableListOf<BcConversationId>()

    override suspend fun record(command: BcInboundPersistenceCommand): BcInboundPersistenceResult {
        commands += command
        command.message?.let(messages::add)
        command.cursorConversationId?.let(advancedConversations::add)
        return BcInboundPersistenceResult.Recorded
    }
}
