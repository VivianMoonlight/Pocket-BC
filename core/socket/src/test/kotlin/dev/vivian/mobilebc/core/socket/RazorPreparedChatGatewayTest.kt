package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.ServerAccountData
import bot.inker.bc.razor.protocol.account.Difficulty
import bot.inker.bc.razor.protocol.auth.LoginResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.model.BcPrivateChatState
import dev.vivian.mobilebc.core.model.BcConversationId
import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageTransport
import dev.vivian.mobilebc.core.model.BcRoomChatRejectionReason
import dev.vivian.mobilebc.core.model.BcServerConfig
import dev.vivian.mobilebc.core.model.bcRoomConversationId
import dev.vivian.mobilebc.core.protocol.BcOutboundCommand
import dev.vivian.mobilebc.core.protocol.RazorWireEvent
import dev.vivian.mobilebc.core.protocol.RazorWireParser
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalog
import dev.vivian.mobilebc.domain.bcrules.BcRuleCatalogParser
import dev.vivian.mobilebc.domain.session.core.BcSessionLoginResult
import dev.vivian.mobilebc.domain.session.core.BcSessionGatewayEvent
import dev.vivian.mobilebc.domain.session.core.BcServerTimeAnchor
import dev.vivian.mobilebc.domain.sync.BcOutboundDispatchReceipt
import dev.vivian.mobilebc.domain.sync.OutboxDispatchRejectedException
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RazorPreparedChatGatewayTest {
    @Test
    fun queuedChatPreservesOriginalTextWhenGearChangesBeforeEmission() = runBlocking {
        listOf("Normal" to "Baby", "Baby" to "Normal").forEach { (before, after) ->
            val fixture = Fixture()
            var receipt: BcOutboundDispatchReceipt? = null
            var failure: Throwable? = null
            val command = command()
            try {
                fixture.initialize()
                fixture.equip(before)
                fixture.blockOutbound()
                val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.sendPrepared(command) }
                        .onSuccess { receipt = it }.onFailure { failure = it }
                }
                fixture.equip(after)
                fixture.releaseOutbound()
                withTimeout(2_000L) { sending.join() }

                assertNull(failure)
                assertEquals("hello", fixture.chatPayloads().single().get("Content").asString)
                assertEquals("hello", receipt?.content)
                assertEquals("hello", command.text)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun hiddenOwnerTalkBlockStopsAlreadyQueuedChatBeforeRuntimeConsumesTheEvent() = runBlocking {
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            fixture.initialize()
            fixture.blockOutbound()
            val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                failure = runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull()
            }
            fixture.hiddenOwnerRule("OwnerRuleTalkBlock")
            fixture.releaseOutbound()
            withTimeout(2_000L) { sending.join() }

            assertRejection(BcRoomChatRejectionReason.TalkBlockedByOwner, failure)
            assertTrue(fixture.privateChat().ruleLog!!.any { it.name == "BlockTalk" })
            assertTrue(fixture.chatPayloads().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun forbiddenWordBlockRejectsThePacketAndKeepsItsPrivateRuleLog() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize("""[{"Name":"ForbiddenWordsBlock|HELLO","Group":"OwnerRule"}]""")
            val original = fixture.privateChat()

            val failure = runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull()

            assertRejection(BcRoomChatRejectionReason.ForbiddenWord, failure)
            assertEquals("HELLO", (failure as OutboxDispatchRejectedException).rejection.forbiddenWord)
            assertEquals(original, fixture.privateChat())
            assertTrue(fixture.chatPayloads().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun unavailablePrivateRulesStillPreventSending() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize(logJson = null)
            val failure = runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull()
            assertRejection(BcRoomChatRejectionReason.OwnerRulesUnavailable, failure)
            assertTrue(fixture.chatPayloads().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun chatAndWhisperNeverLoadSpeechCatalogForPreflightOrActualEmission() = runBlocking {
        for (kind in listOf(BcMessageKind.Chat, BcMessageKind.Whisper)) {
            listOf("catalog", "version", "asset", "effects").forEach { unavailable ->
                val fixture = Fixture()
                try {
                    fixture.initialize()
                    if (unavailable == "catalog") fixture.catalog = null
                    if (unavailable == "version") fixture.catalog = fixture.catalog!!.copy(version = "R128")
                    if (unavailable == "asset") fixture.equip("Missing")
                    if (unavailable == "effects") {
                        fixture.equip("Baby")
                        fixture.addGagAndStutterEffects()
                    }

                    val command = command(kind).copy(text = "Hello 中文 (OOC")
                    fixture.gateway.validateRoomChat(kind, command.text, command.targetMemberNumber)
                    assertTrue(fixture.chatPayloads().isEmpty())
                    val receipt = fixture.gateway.sendPrepared(command)

                    val payload = fixture.chatPayloads().single()
                    assertEquals("Hello 中文 (OOC)", payload.get("Content").asString)
                    assertTrue(!payload.has("originRoomName") && !payload.has("OriginRoomName"))
                    assertEquals(if (kind == BcMessageKind.Chat) "Chat" else "Whisper", payload.get("Type").asString)
                    if (kind == BcMessageKind.Whisper) assertEquals(30, payload.get("Target").asInt)
                    assertEquals("Hello 中文 (OOC)", receipt.content)
                    assertEquals("[]", fixture.privateChat().rawLogJson)
                    assertEquals(0, fixture.catalogLookups)
                    assertTrue(payload.getAsJsonArray("Dictionary").none {
                        it.asJsonObject.has("Effects") || it.asJsonObject.has("Original")
                    })
                } finally {
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun whisperPreservesItsReceiptWithoutApplyingPublicTalkOrForbiddenWordGates() = runBlocking {
        val fixture = Fixture()
        val command = command(kind = BcMessageKind.Whisper)
        try {
            fixture.initialize("""[{"Name":"BlockTalk","Group":"OwnerRule"},{"Name":"BlockWhisper","Group":"OwnerRule"},{"Name":"ForbiddenWordsBlock|HELLO","Group":"OwnerRule"}]""")
            fixture.equip("Baby")

            val receipt = fixture.gateway.sendPrepared(command)

            val payload = fixture.chatPayloads().single()
            assertEquals("Whisper", payload.get("Type").asString)
            assertEquals(30, payload.get("Target").asInt)
            assertEquals("hello", payload.get("Content").asString)
            assertEquals("hello", receipt.content)
            assertEquals(payload.get("Dictionary"), JsonParser.parseString(receipt.dictionaryJson))
            assertEquals("hello", command.text)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun timedForbiddenWordMuteIsNotCommittedWhenSocketEmissionFails() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize(TIMED_MUTE_LOG)
            fixture.serverInfo()
            val original = fixture.privateChat()
            fixture.socket.failEmit = true

            val failure = runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("Socket emission failed.", failure?.message)
            assertEquals(original, fixture.privateChat())
            assertTrue(fixture.chatPayloads().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun successfulEmissionCommitsTimedMuteAndSubsequentPublicChatIsBlocked() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize(TIMED_MUTE_LOG)
            fixture.serverInfo()

            fixture.gateway.sendPrepared(command())

            val state = fixture.privateChat()
            val mute = state.ruleLog!!.single { it.name == "BlockTalkForbiddenWords" && it.group == "OwnerRule" }
            assertTrue(requireNotNull(mute.expiresAtMillis) in 1_300_000L..1_310_000L)
            val log = JsonParser.parseString(state.rawLogJson).asJsonArray
            assertEquals(1, log.first().asJsonObject.getAsJsonObject("Other").get("X").asInt)
            val failure = runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull()
            assertRejection(BcRoomChatRejectionReason.TalkMutedByForbiddenWord, failure)
            assertEquals(1, fixture.chatPayloads().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun emoteChecksItsOwnerRuleAndOtherwiseKeepsOriginalTextDespiteSpeechEffects() = runBlocking {
        listOf(false, true).forEach { blocked ->
            val fixture = Fixture()
            try {
                fixture.initialize(if (blocked) """[{"Name":"BlockEmote","Group":"OwnerRule"}]""" else "[]")
                fixture.equip("Baby")
                val result = runCatching { fixture.gateway.sendPrepared(command(kind = BcMessageKind.Emote)) }

                if (blocked) {
                    assertRejection(BcRoomChatRejectionReason.EmoteBlockedByOwner, result.exceptionOrNull())
                    assertTrue(fixture.chatPayloads().isEmpty())
                } else {
                    assertEquals("hello", result.getOrThrow().content)
                    assertEquals("hello", fixture.chatPayloads().single().get("Content").asString)
                }
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun rawTextKeepsThe2000UnitLimitAndOnlyOocClosingCanExpandIt() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize()
            fixture.equip("Baby")
            val speech = fixture.gateway.sendPrepared(command().copy(text = "s".repeat(2_000)))
            assertEquals("s".repeat(2_000), speech.content)
            assertEquals(2_000, fixture.chatPayloads().last().get("Content").asString.length)

            val rawOoc = "(" + "a".repeat(1_999)
            val ooc = fixture.gateway.sendPrepared(command().copy(text = rawOoc))
            assertEquals(rawOoc + ")", ooc.content)
            assertEquals(2_001, fixture.chatPayloads().last().get("Content").asString.length)

            fixture.blockOutbound()
            val failure = runCatching {
                withTimeout(2_000L) { fixture.gateway.sendPrepared(command().copy(text = "s".repeat(2_001))) }
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(2, fixture.chatPayloads().size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun clearingOwnershipImmediatelyClearsTheSelfOwnerAndOnlyOwnerRuleLogEntries() = runBlocking {
        val fixture = Fixture()
        val retained = """[{"Name":"KeepMe","Group":"Custom","Other":{"X":1}},{"Name":"BlockTalk","Group":"Other","Flag":true}]"""
        try {
            fixture.initialize("""[{"Name":"BlockTalk","Group":"OwnerRule","OwnerExtra":{"X":2}},{"Name":"KeepMe","Group":"Custom","Other":{"X":1}},{"Name":"BlockTalk","Group":"Other","Flag":true}]""")

            fixture.receive("AccountOwnership", """{"MemberNumber":20,"ClearOwnership":true}""")

            val self = fixture.roomStore.captureCharacterSnapshots().single { it.isSelf }
            assertNull(self.ownership)
            assertNull(self.owner)
            assertEquals(JsonParser.parseString(retained), JsonParser.parseString(fixture.privateChat().rawLogJson))
            assertTrue(fixture.privateChat().ruleLog!!.none { it.group == "OwnerRule" })
            assertEquals("hello", fixture.gateway.sendPrepared(command()).content)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ownershipUpdatesPreserveTheRawLogAndChangeWhichPresentOwnerCanSendHiddenRules() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.initialize("""[{"Name":"ForbiddenWordsBlock|OTHER","Group":"OwnerRule","Extra":{"X":1}},{"Name":"KeepMe","Group":"Custom","Flag":true}]""")
            val originalLog = fixture.privateChat().rawLogJson

            fixture.receive("AccountOwnership", """{"MemberNumber":20,"Owner":"New owner","Ownership":{"MemberNumber":30,"Name":"Peer","Stage":1,"Start":1234,"Notes":"New rules"}}""")

            val updatedSelf = fixture.roomStore.captureCharacterSnapshots().single { it.isSelf }
            assertEquals(30, updatedSelf.ownership?.memberNumber)
            assertEquals("New owner", updatedSelf.owner)
            assertEquals(originalLog, fixture.privateChat().rawLogJson)

            fixture.receive("AccountOwnership", """{"MemberNumber":20,"Result":"CanOffer"}""")
            assertEquals(updatedSelf, fixture.roomStore.captureCharacterSnapshots().single { it.isSelf })
            assertEquals(originalLog, fixture.privateChat().rawLogJson)

            fixture.hiddenOwnerRule("OwnerRuleTalkBlock", sender = 20)
            assertEquals(originalLog, fixture.privateChat().rawLogJson)
            fixture.hiddenOwnerRule("OwnerRuleTalkBlock", sender = 30)
            assertTrue(fixture.privateChat().ruleLog!!.any { it.name == "BlockTalk" && it.group == "OwnerRule" })
            assertEquals(1, JsonParser.parseString(fixture.privateChat().rawLogJson).asJsonArray
                .first().asJsonObject.getAsJsonObject("Extra").get("X").asInt)
            assertRejection(BcRoomChatRejectionReason.TalkBlockedByOwner,
                runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull())
            assertTrue(fixture.chatPayloads().isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun disabledSpeechEffectsPreserveReplyIdentityAndNeverAddSpeechMetadata() = runBlocking {
        listOf(BcMessageKind.Chat, BcMessageKind.Whisper).forEach { kind ->
            val fixture = Fixture()
            val command = command(kind).copy(replyToId = "native-reference.exact/中文", replyRoomName = "A")
            try {
                fixture.initialize(difficultyLevel = 0, restrictionSettings = """{"NoSpeechGarble":true}""")
                fixture.equip("Baby")
                fixture.addGagAndStutterEffects()

                val receipt = fixture.gateway.sendPrepared(command)

                val payload = fixture.chatPayloads().single()
                assertEquals(command.text, payload.get("Content").asString)
                assertEquals(payload.get("Content").asString, receipt.content)
                val dictionary = payload.getAsJsonArray("Dictionary").map { it.asJsonObject }
                assertEquals(command.clientMsgId, dictionary.single { it.has("MsgId") }.get("MsgId").asString)
                assertEquals(command.replyToId, dictionary.single { it.has("ReplyId") }.get("ReplyId").asString)
                assertTrue(dictionary.none { it.has("Original") || it.has("Effects") })
                assertEquals(payload.get("Dictionary"), JsonParser.parseString(receipt.dictionaryJson))

                val unchanged = fixture.gateway.sendPrepared(command.copy(text = "(hello)"))
                assertEquals("(hello)", unchanged.content)
                val unchangedDictionary = fixture.chatPayloads().last().getAsJsonArray("Dictionary").map { it.asJsonObject }
                assertEquals(command.clientMsgId, unchangedDictionary.single { it.has("MsgId") }.get("MsgId").asString)
                assertEquals(command.replyToId, unchangedDictionary.single { it.has("ReplyId") }.get("ReplyId").asString)
                assertTrue(unchangedDictionary.none { it.has("Original") || it.has("Effects") })
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun beginningANewLoginClearsOldChatAndBothDebouncedAndAlreadyQueuedPrivateLogBatches() = runBlocking {
        listOf(false, true).forEach { batchAlreadyQueued ->
            val fixture = Fixture()
            val outbound = field(fixture.gateway, "outbound") as RazorOutboundPipeline
            var chatReceipt: BcOutboundDispatchReceipt? = null
            var chatFailure: Throwable? = null
            var flushFailure: Throwable? = null
            try {
                fixture.initialize()
                fixture.blockOutbound()
                fixture.hiddenOwnerRule("OwnerRuleForbiddenWordsBlock|OLDLOG")
                assertTrue(fixture.privateChat().ruleLog!!.any { it.name == "ForbiddenWordsBlock|OLDLOG" })
                val flushing = if (batchAlreadyQueued) launch(start = CoroutineStart.UNDISPATCHED) {
                    flushFailure = runCatching { outbound.flushAccountUpdates() }.exceptionOrNull()
                } else null
                val sending = launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { fixture.gateway.sendPrepared(command().copy(text = "safe")) }
                        .onSuccess { chatReceipt = it }.onFailure { chatFailure = it }
                }

                val loggingIn = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.login("account", "password") }

                withTimeout(2_000L) { sending.join(); flushing?.join() }
                assertTrue(chatFailure is OutboxDispatchDeferredException)
                assertNull(chatReceipt)
                if (batchAlreadyQueued) assertTrue(flushFailure is OutboxDispatchDeferredException)
                fixture.releaseOutbound()
                fixture.awaitOutbound()
                assertTrue(fixture.socket.events.isEmpty())
                assertEquals(listOf("AccountLogin"), fixture.replacementSocket.events.map { it.first })

                fixture.receive("LoginResponse", """{"ID":"10","MemberNumber":10,"Name":"Self","Log":[],"ChatSettings":{}}""")
                assertTrue(withTimeout(2_000L) { loggingIn.await() } is BcSessionLoginResult.Success)
                fixture.gateway.flushAccountUpdates()
                fixture.awaitOutbound()

                assertTrue(fixture.socket.events.isEmpty())
                assertEquals(listOf("AccountLogin"), fixture.replacementSocket.events.map { it.first })
                assertEquals("[]", fixture.privateChat().rawLogJson)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun anOldLogBatchHeldOutsideTheQueueCannotEmitAfterACompletedNewLogin() = runBlocking {
        val fixture = Fixture()
        val outbound = field(fixture.gateway, "outbound") as RazorOutboundPipeline
        try {
            fixture.initialize()
            val oldToken = fixture.requests.authenticatedSelfRuleLogSessionId
            val oldRoom = fixture.roomStore.captureReceiveContext()
            val oldRoomGuard = fixture.roomStore.captureRoomMessageGuard(command())
            fixture.hiddenOwnerRule("OwnerRuleForbiddenWordsBlock|OLDLOG")
            val heldBatch = requireNotNull(outbound.drainAccountUpdatePacket())
            assertTrue(heldBatch.payloadJson.contains("ForbiddenWordsBlock|OLDLOG"))

            val loggingIn = async(start = CoroutineStart.UNDISPATCHED) { fixture.gateway.login("account", "password") }
            fixture.awaitOutbound()
            assertTrue(fixture.socket.events.isEmpty())
            assertEquals(listOf("AccountLogin"), fixture.replacementSocket.events.map { it.first })
            fixture.receive("LoginResponse", """{"ID":"10","MemberNumber":10,"Name":"Self","Log":[],"ChatSettings":{}}""")
            assertTrue(withTimeout(2_000L) { loggingIn.await() } is BcSessionLoginResult.Success)
            assertTrue(oldToken != fixture.requests.authenticatedSelfRuleLogSessionId)
            val newRoom = fixture.roomStore.captureReceiveContext()
            assertNull(newRoom.roomName)
            assertTrue(fixture.roomStore.captureCharacterSnapshots().isEmpty())
            assertTrue(oldRoom.roomPresenceEpoch != newRoom.roomPresenceEpoch)

            val failure = runCatching { outbound.flushAccountUpdates(heldBatch) }.exceptionOrNull()

            assertTrue(failure is OutboxDispatchDeferredException)
            fixture.gateway.flushAccountUpdates()
            fixture.awaitOutbound()
            assertTrue(fixture.socket.events.isEmpty())
            assertEquals(listOf("AccountLogin"), fixture.replacementSocket.events.map { it.first })
            assertEquals("[]", fixture.privateChat().rawLogJson)

            val oldRoomFailure = runCatching {
                outbound.sendJson("ChatRoomChat", mapOf("Content" to "old room"), oldRoomGuard)
            }.exceptionOrNull()
            assertTrue(oldRoomFailure is OutboxDispatchDeferredException)
            assertTrue(runCatching { fixture.gateway.sendPrepared(command()) }.exceptionOrNull()
                is OutboxDispatchDeferredException)
            assertTrue(fixture.socket.events.isEmpty())
            assertEquals(listOf("AccountLogin"), fixture.replacementSocket.events.map { it.first })

            val newLog = JsonParser.parseString("""[{"Name":"NewSession","Group":"Custom"}]""")
            outbound.queueAccountUpdates(mapOf("Log" to newLog))
            fixture.gateway.flushAccountUpdates()
            fixture.awaitOutbound()
            assertTrue(fixture.socket.events.isEmpty())
            assertEquals(listOf("AccountLogin", "AccountUpdate"), fixture.replacementSocket.events.map { it.first })
            assertEquals(newLog, JsonParser.parseString(fixture.replacementSocket.events.last().second).asJsonObject.get("Log"))
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        var catalog: BcRuleCatalog? = standardCatalog()
        var catalogLookups = 0
        val gateway = RazorGatewayImpl(ruleCatalogProvider = { catalogLookups++; catalog }, textLanguageProvider = { "EN" })
        val socket = RecordingSocket()
        val replacementSocket = RecordingSocket()
        val requests = field(gateway, "requests") as RazorRequestCoordinator
        val roomStore = field(gateway, "roomStateStore") as RazorRoomStateStore
        private val outbound = field(gateway, "outbound") as RazorOutboundPipeline
        private val executor = field(outbound, "executor") as ScheduledExecutorService
        private val release = CountDownLatch(1)
        private var self = JsonParser.parseString(SELF_CHARACTER).asJsonObject

        init {
            val transport = field(gateway, "transport") as RazorSocketTransport
            setField(transport, "socket", socket)
            val socketFactory: (URI, IO.Options) -> Socket = { _, _ -> replacementSocket }
            setField(transport, "socketFactory", socketFactory)
            setField(gateway, "selectedServerConfig", BcServerConfig(serverUrl = "http://localhost", version = "R129"))
            setField(gateway, "currentBcVersion", "R129")
        }

        suspend fun initialize(
            logJson: String? = "[]",
            chatSettings: String = "{}",
            difficultyLevel: Int? = null,
            restrictionSettings: String? = null,
        ) = coroutineScope {
            val login = async(start = CoroutineStart.UNDISPATCHED) { requests.awaitLogin {} }
            requests.handle(RazorWireEvent.LoginResponse(LoginResult.Success(ServerAccountData(
                id = "10",
                memberNumber = 10,
                name = "Self",
                log = logJson?.let(JsonParser::parseString),
                chatSettings = JsonParser.parseString(chatSettings),
                difficulty = difficultyLevel?.let { Difficulty(it) },
                restrictionSettings = restrictionSettings?.let(JsonParser::parseString),
            ))))
            assertTrue(login.await() is BcSessionLoginResult.Success)
            assertTrue(roomStore.handle(RazorWireParser.parse("ChatRoomSync",
                "{\"Name\":\"A\",\"Character\":[$self,{\"ID\":\"20\",\"MemberNumber\":20,\"Name\":\"Owner\"},{\"ID\":\"30\",\"MemberNumber\":30,\"Name\":\"Peer\"}]}",
            )))
        }

        fun equip(name: String) {
            self = self.deepCopy().apply { getAsJsonArray("Appearance").single().asJsonObject.addProperty("Name", name) }
            assertTrue(roomStore.handle(RazorWireParser.parse("ChatRoomSyncCharacter",
                "{\"SourceMemberNumber\":10,\"Character\":$self}",
            )))
        }

        fun addGagAndStutterEffects() {
            self = self.deepCopy().apply {
                getAsJsonArray("Appearance").single().asJsonObject.getAsJsonObject("Property")
                    .add("Effect", JsonParser.parseString("""["GagNormal"]"""))
                add("ArousalSettings", JsonParser.parseString("""{"AffectStutter":"All","Progress":20}"""))
            }
            assertTrue(roomStore.handle(RazorWireParser.parse("ChatRoomSyncCharacter",
                "{\"SourceMemberNumber\":10,\"Character\":$self}",
            )))
        }

        fun hiddenOwnerRule(content: String, sender: Int = 20) {
            incoming("ChatRoomMessage", "{\"Sender\":$sender,\"Target\":10,\"Type\":\"Hidden\",\"Content\":\"$content\"}")
        }

        suspend fun serverInfo() {
            incoming("ServerInfo", """{"OnlinePlayers":1,"Time":1000000}""")
            val event = withTimeout(2_000L) { gateway.events.first { it is BcSessionGatewayEvent.ServerInfo } }
                as BcSessionGatewayEvent.ServerInfo
            val anchor = field(gateway, "serverTimeAnchor") as BcServerTimeAnchor
            assertEquals(anchor.epochMillis, event.serverTime)
            assertEquals(anchor.receivedAtMonotonicNanos, event.receivedAtMonotonicNanos)
        }

        fun receive(event: String, json: String) { incoming(event, json) }

        private fun incoming(event: String, json: String) {
            RazorGatewayImpl::class.java.getDeclaredMethod("handleIncomingEvent", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(gateway, event, json)
        }

        fun privateChat(): BcPrivateChatState = requireNotNull(requests.authenticatedPrivateChatState).state

        fun chatPayloads(): List<JsonObject> = socket.events.filter { it.first == "ChatRoomChat" }
            .map { JsonParser.parseString(it.second).asJsonObject }

        fun blockOutbound() {
            val blocked = CountDownLatch(1)
            executor.submit { blocked.countDown(); release.await() }
            assertTrue(blocked.await(1, TimeUnit.SECONDS))
        }

        fun releaseOutbound() { release.countDown() }
        fun awaitOutbound() { executor.submit {}.get(1, TimeUnit.SECONDS) }
        fun close() { release.countDown(); gateway.close() }
    }

    private class RecordingSocket : Socket(Manager(URI.create("http://localhost")), "/", Manager.Options()) {
        val events = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
        var failEmit = false
        override fun connected(): Boolean = true
        override fun connect(): Socket {
            listeners(Socket.EVENT_CONNECT).forEach { it.call() }
            return this
        }
        override fun emit(event: String, vararg args: Any?): Emitter {
            check(!failEmit) { "Socket emission failed." }
            events += event to args.single().toString()
            return this
        }
        override fun disconnect(): Socket = this
    }

    private companion object {
        fun assertRejection(reason: BcRoomChatRejectionReason, failure: Throwable?) {
            assertTrue("Expected $reason, got $failure", failure is OutboxDispatchRejectedException)
            assertEquals(reason, (failure as OutboxDispatchRejectedException).rejection.reason)
        }

        fun command(kind: BcMessageKind = BcMessageKind.Chat) = BcOutboundCommand(
            clientMsgId = "prepared-chat-1",
            conversationId = if (kind == BcMessageKind.Whisper) BcConversationId("message:30") else bcRoomConversationId("A"),
            kind = kind,
            transport = if (kind == BcMessageKind.Whisper) BcMessageTransport.RoomWhisper else BcMessageTransport.RoomChat,
            text = "hello",
            targetMemberNumber = 30.takeIf { kind == BcMessageKind.Whisper },
            originRoomName = "A".takeIf { kind == BcMessageKind.Whisper },
            createdAtMillis = 1_000,
        )

        fun field(instance: Any, name: String): Any? = instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.get(instance)

        fun setField(instance: Any, name: String, value: Any?) { instance.javaClass.getDeclaredField(name)
            .apply { isAccessible = true }.set(instance, value) }

        fun standardCatalog() = BcRuleCatalogParser().parse("R129", """
            var AssetFemale3DCG = [
                { Group: "ItemMouth", Asset: [
                    { Name: "Normal", Effect: [] },
                    { Name: "Baby", Effect: ["RegressedTalk"] },
                    { Name: "Gag", Effect: ["GagNormal"] },
                ] },
            ];
        """.trimIndent())

        const val SELF_CHARACTER = """{"ID":"10","MemberNumber":10,"Name":"Self","AssetFamily":"Female3DCG","Ownership":{"MemberNumber":20,"Name":"Owner","Stage":1},"Appearance":[{"Group":"ItemMouth","Name":"Normal","Property":{}}],"ArousalSettings":{"AffectStutter":"None","Progress":0}}"""
        const val TIMED_MUTE_LOG = """[{"Name":"ForbiddenWordsMute5|HELLO","Group":"OwnerRule","Other":{"X":1}}]"""
    }
}
