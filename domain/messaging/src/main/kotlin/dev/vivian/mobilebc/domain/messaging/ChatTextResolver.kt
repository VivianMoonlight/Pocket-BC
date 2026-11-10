package dev.vivian.mobilebc.domain.messaging

import dev.vivian.mobilebc.core.model.BcMessageKind
import dev.vivian.mobilebc.core.model.BcMessageResolution
import dev.vivian.mobilebc.core.model.BcRawChatMessage
import dev.vivian.mobilebc.domain.bctext.BcActivityTextParser
import dev.vivian.mobilebc.domain.bctext.BcDictionaryFallbackTextParser
import dev.vivian.mobilebc.domain.bctext.BcDictionaryValueResolver
import dev.vivian.mobilebc.domain.bctext.BcTextParseRequest
import org.json.JSONArray
import org.json.JSONTokener

fun interface BcChatTextResolver {
    suspend fun resolve(
        message: BcRawChatMessage,
        kind: BcMessageKind,
        memberLookup: Map<Int, String>,
    ): BcChatTextResolution
}

data class BcChatTextResolution(
    val text: String,
    val resolution: BcMessageResolution,
)

object PlainBcChatTextResolver : BcChatTextResolver {
    override suspend fun resolve(
        message: BcRawChatMessage,
        kind: BcMessageKind,
        memberLookup: Map<Int, String>,
    ): BcChatTextResolution {
        val resolution = when (kind) {
            BcMessageKind.Action,
            BcMessageKind.Activity,
            BcMessageKind.System,
            -> BcMessageResolution.NeedsTranslation(message.content)

            else -> BcMessageResolution.Plain
        }
        return BcChatTextResolution(message.content, resolution)
    }
}

class ActivityBackedBcChatTextResolver(
    private val activityTextParser: BcActivityTextParser,
    private val interfaceTextParser: BcActivityTextParser? = null,
    private val assetStringsTextParser: BcActivityTextParser? = null,
    private val currentVersion: () -> String,
    private val dictionaryValueResolver: BcDictionaryValueResolver = BcDictionaryValueResolver.None,
    private val activityTemplateProvider: (
        activityName: String,
        targetGroup: String,
        isSelfTarget: Boolean,
    ) -> String? = { _, _, _ -> null },
    private val fallback: BcChatTextResolver = PlainBcChatTextResolver,
) : BcChatTextResolver {
    override suspend fun resolve(
        message: BcRawChatMessage,
        kind: BcMessageKind,
        memberLookup: Map<Int, String>,
    ): BcChatTextResolution {
        val version = currentVersion()
        val localActivityText = if (kind == BcMessageKind.Activity) {
            message.localActivityTextOrNull(memberLookup, version)
        } else {
            null
        }
        if (localActivityText != null) {
            return BcChatTextResolution(
                text = localActivityText,
                resolution = BcMessageResolution.Resolved(message.content, localActivityText),
            )
        }
        val parsed = nativeLookupCandidates(message, kind).firstNotNullOfOrNull { candidate ->
            candidate.parser.parse(
                BcTextParseRequest(
                    version = version,
                    key = candidate.key,
                    dictionaryJson = message.dictionaryJson,
                    memberLookup = memberLookup,
                )
            )
        } ?: BcDictionaryFallbackTextParser.parse(
            key = message.content,
            dictionaryJson = message.dictionaryJson,
            memberLookup = memberLookup,
            version = version,
            dictionaryValueResolver = dictionaryValueResolver,
        ) ?: return fallback.resolve(message, kind, memberLookup)

        return BcChatTextResolution(
            text = parsed.text,
            // Preserve the wire content as the resolution identity. Activity asset
            // lookups use a derived CSV key, while MsgId reconciliation and fallback
            // matching still need the original ChatSelf/ChatOther content key.
            resolution = BcMessageResolution.Resolved(message.content, parsed.text),
        )
    }

    private fun BcRawChatMessage.localActivityTextOrNull(
        memberLookup: Map<Int, String>,
        version: String,
    ): String? {
        val activity = activityName?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val targetGroup = activityTargetGroup?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val target = targetMemberNumber ?: return null
        val template = activityTemplateProvider(activity, targetGroup, senderMemberNumber == target) ?: return null
        return BcDictionaryFallbackTextParser.renderTemplate(
            template = template,
            dictionaryJson = dictionaryJson,
            memberLookup = memberLookup,
            version = version,
            dictionaryValueResolver = dictionaryValueResolver,
        )
    }

    private fun nativeLookupCandidates(
        message: BcRawChatMessage,
        kind: BcMessageKind,
    ): List<LookupCandidate> = when (kind) {
        BcMessageKind.Action -> listOfNotNull(
            assetStringsTextParser?.let { LookupCandidate(it, message.content) },
            interfaceTextParser?.let { LookupCandidate(it, message.content) },
        )
        BcMessageKind.System,
        BcMessageKind.Error,
        -> listOfNotNull(
            interfaceTextParser?.let { LookupCandidate(it, "ServerMessage${message.content}") },
        )
        BcMessageKind.Activity -> listOfNotNull(
            message.activityAssetTextKeyOrNull()
                ?.let { key -> assetStringsTextParser?.let { LookupCandidate(it, key) } },
            LookupCandidate(activityTextParser, message.content),
        )
        BcMessageKind.Chat,
        BcMessageKind.Emote,
        BcMessageKind.Whisper,
        BcMessageKind.Beep,
        -> emptyList()
    }

    private fun BcRawChatMessage.activityAssetTextKeyOrNull(): String? {
        val entries = dictionaryEntries(dictionaryJson) ?: return null
        var activityName: String? = null
        var assetGroup: String? = null
        var assetName: String? = null
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            entry.optString("ActivityName").trim().takeIf(String::isNotEmpty)?.let { activityName = it }
            if (entry.optString("Tag").equals("ActivityAsset", ignoreCase = true)) {
                assetGroup = entry.optString("GroupName").trim().takeIf(String::isNotEmpty)
                assetName = entry.optString("AssetName").trim().takeIf(String::isNotEmpty)
            }
        }
        val group = assetGroup ?: return null
        val asset = assetName ?: return null
        val activity = activityName ?: return null
        return "$group${asset}Activity$activity"
    }

    private fun dictionaryEntries(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        return when (val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull()) {
            is JSONArray -> parsed
            is org.json.JSONObject -> JSONArray().put(parsed)
            else -> null
        }
    }

    private data class LookupCandidate(
        val parser: BcActivityTextParser,
        val key: String,
    )
}
