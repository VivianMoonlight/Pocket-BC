package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BcInboundResourceGateTest {
    @Test
    fun plainTextFramesBypassVersionLanguageAndResourceReadiness() {
        val gate = BcInboundResourceGate(
            sessionId = 7L,
            versionProvider = { error("Plain-text frames must not read the BC version.") },
            languageProvider = { error("Plain-text frames must not read the language.") },
            resourcesReady = { _, _ -> error("Plain-text frames must not inspect resources.") },
        )

        listOf(
            chatFrame("Chat"),
            chatFrame("Emote"),
            chatFrame("Whisper"),
            accountBeepFrame(),
        ).forEach { frame ->
            assertEquals(BcInboundResourceGateDecision.Process, gate.decide(frame))
        }
    }

    @Test
    fun actionActivityAndServerMessageRequireAReadySnapshot() {
        var readinessCheckCount = 0
        val gate = BcInboundResourceGate(
            sessionId = 7L,
            versionProvider = { "R131" },
            languageProvider = { "CN" },
            resourcesReady = { version, language ->
                assertEquals("R131", version)
                assertEquals("CN", language)
                readinessCheckCount += 1
                true
            },
        )

        listOf("Action", "Activity", "ServerMessage").forEach { wireType ->
            assertEquals(BcInboundResourceGateDecision.Process, gate.decide(chatFrame(wireType)))
        }

        assertEquals(3, readinessCheckCount)
    }

    @Test
    fun wrappedChatRoomMessagesKeepTheirResourceClassification() {
        assertTrue(wrappedChatFrame("Action").requiresBcTextResources())
        assertTrue(wrappedChatFrame("ServerMessage").requiresBcTextResources())
        assertFalse(wrappedChatFrame("Chat").requiresBcTextResources())
        assertFalse(wrappedChatFrame("Whisper").requiresBcTextResources())
    }

    @Test
    fun missingSnapshotReturnsADeferredResourceKey() {
        var readinessCheckCount = 0
        val gate = BcInboundResourceGate(
            sessionId = 7L,
            versionProvider = { "R131" },
            languageProvider = { "CN" },
            resourcesReady = { _, _ ->
                readinessCheckCount += 1
                false
            },
        )

        val decision = gate.decide(chatFrame("Activity"))

        assertEquals(1, readinessCheckCount)
        assertEquals(
            BcInboundResourceGateDecision.Defer(
                BcInboundResourceKey(sessionId = 7L, version = "R131", language = "CN"),
            ),
            decision,
        )
    }

    @Test
    fun readinessCheckExceptionIsRejected() {
        val failure = IllegalStateException("cache inspection failed")
        val gate = BcInboundResourceGate(
            sessionId = 7L,
            versionProvider = { "R131" },
            languageProvider = { "CN" },
            resourcesReady = { _, _ -> throw failure },
        )

        val decision = gate.decide(chatFrame("Action"))
        assertTrue(decision is BcInboundResourceGateDecision.Reject)
        assertEquals(failure, (decision as BcInboundResourceGateDecision.Reject).error)
    }

    @Test
    fun replayRejectsAChangedVersionOrLanguageContext() {
        var language = "CN"
        val gate = BcInboundResourceGate(
            sessionId = 7L,
            versionProvider = { "R131" },
            languageProvider = { language },
            resourcesReady = { _, _ -> true },
        )
        val expected = BcInboundResourceKey(7L, "R131", "CN")

        language = "EN"
        val decision = gate.decide(chatFrame("Action"), expectedKey = expected)
        assertTrue(decision is BcInboundResourceGateDecision.Reject)
        assertTrue(
            (decision as BcInboundResourceGateDecision.Reject).error is
                BcInboundResourceContextChangedException,
        )
    }

    private fun chatFrame(wireType: String): CanonicalFrame {
        val payload = """{"Sender":123,"Type":"$wireType","Content":"Key","Dictionary":[]}"""
        return CanonicalFrame(
            namespace = "/",
            transportEventName = "ChatRoomMessage",
            args = listOf(payload),
            receivedAtMillis = 1L,
            raw = payload,
        )
    }

    private fun accountBeepFrame(): CanonicalFrame {
        val payload = """{"MemberNumber":123,"Message":"hello"}"""
        return CanonicalFrame(
            namespace = "/",
            transportEventName = "AccountBeep",
            args = listOf(payload),
            receivedAtMillis = 1L,
            raw = payload,
        )
    }

    private fun wrappedChatFrame(wireType: String): CanonicalFrame {
        val payload =
            """{"Message":"ChatRoomChat","Data":{"Sender":123,"Type":"$wireType","Content":"Key","Dictionary":[]}}"""
        return CanonicalFrame(
            namespace = "/",
            transportEventName = "Message",
            args = listOf(payload),
            receivedAtMillis = 1L,
            raw = payload,
        )
    }

}
