package dev.vivian.mobilebc.service

import dev.vivian.mobilebc.core.model.normalizedBcVersion
import dev.vivian.mobilebc.core.protocol.BcChatMessageCodec
import dev.vivian.mobilebc.core.protocol.BcInboundPacket
import dev.vivian.mobilebc.core.protocol.CanonicalFrame
import dev.vivian.mobilebc.core.protocol.DecodeResult

internal data class BcInboundResourceKey(
    val sessionId: Long,
    val version: String,
    val language: String,
) {
    companion object {
        fun normalized(sessionId: Long, version: String, language: String): BcInboundResourceKey {
            val normalizedLanguage = language.trim().uppercase()
            require(normalizedLanguage.isNotBlank()) { "BC text language is required." }
            return BcInboundResourceKey(
                sessionId = sessionId,
                version = version.normalizedBcVersion(),
                language = normalizedLanguage,
            )
        }
    }
}

internal sealed interface BcInboundResourceGateDecision {
    data object Process : BcInboundResourceGateDecision
    data class Defer(val key: BcInboundResourceKey) : BcInboundResourceGateDecision
    data class Reject(
        val version: String?,
        val language: String?,
        val error: Throwable,
    ) : BcInboundResourceGateDecision
}

internal class BcInboundResourceGate(
    private val sessionId: Long,
    private val versionProvider: () -> String,
    private val languageProvider: () -> String,
    private val resourcesReady: (version: String, language: String) -> Boolean,
) {
    fun decide(
        frame: CanonicalFrame,
        expectedKey: BcInboundResourceKey? = null,
    ): BcInboundResourceGateDecision {
        if (!frame.requiresBcTextResources()) return BcInboundResourceGateDecision.Process

        var version: String? = null
        var language: String? = null
        return try {
            val key = BcInboundResourceKey.normalized(
                sessionId = sessionId,
                version = versionProvider().also { version = it },
                language = languageProvider().also { language = it },
            )
            version = key.version
            language = key.language
            if (expectedKey != null && key != expectedKey) {
                BcInboundResourceGateDecision.Reject(
                    version = key.version,
                    language = key.language,
                    error = BcInboundResourceContextChangedException(
                        "Deferred frame belongs to $expectedKey, but the active context is $key.",
                    ),
                )
            } else if (resourcesReady(key.version, key.language)) {
                BcInboundResourceGateDecision.Process
            } else {
                BcInboundResourceGateDecision.Defer(key)
            }
        } catch (error: Throwable) {
            BcInboundResourceGateDecision.Reject(version, language, error)
        }
    }
}

internal class BcInboundResourceContextChangedException(message: String) :
    IllegalStateException(message)

internal fun CanonicalFrame.requiresBcTextResources(): Boolean {
    if (!BcChatMessageCodec.canDecode(this)) return false
    val chat = when (val decoded = BcChatMessageCodec.decode(this)) {
        is DecodeResult.Success -> decoded.packet as? BcInboundPacket.ChatMessage
        is DecodeResult.DeadLetter -> null
    } ?: return false
    return chat.message.wireType.trim().lowercase() in dictionaryBackedWireTypes
}

private val dictionaryBackedWireTypes = setOf("action", "activity", "servermessage")
