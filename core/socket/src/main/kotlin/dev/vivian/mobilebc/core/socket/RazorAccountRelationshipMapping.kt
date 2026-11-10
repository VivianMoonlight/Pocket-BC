package dev.vivian.mobilebc.core.socket

import bot.inker.bc.razor.protocol.account.AccountLovershipResponse
import bot.inker.bc.razor.protocol.account.AccountOwnershipResponse
import bot.inker.bc.razor.protocol.account.Lovership
import bot.inker.bc.razor.protocol.account.Ownership
import bot.inker.bc.razor.protocol.account.ServerAccountData
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.protocol.LzStringUtf16Codec
import dev.vivian.mobilebc.domain.session.core.BcLovership
import dev.vivian.mobilebc.domain.session.core.BcOwnership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipGatewayUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations

internal fun ServerAccountData.toBcSelfRelations(): BcSelfRelations =
    BcSelfRelations(
        friends = friendList.toMemberNumberSet(memberNumber),
        friendNames = decodeFriendNames(friendNames),
        ignored = ghostList.toMemberNumberSet(memberNumber),
        whitelist = whiteList.toMemberNumberSet(memberNumber),
        blacklist = blackList.toMemberNumberSet(memberNumber),
        submissives = decodeMemberNumbers(submissivesList, memberNumber),
        legacyOwnerName = owner?.takeIf(String::isNotBlank),
        ownership = ownership?.toBcOwnership(),
        loverships = lovership.toBcLoverships(excludingMemberNumber = memberNumber),
        money = money,
        reputation = reputation.orEmpty().filterNotNull()
            .filter { !it.type.isNullOrBlank() }.associate { value -> value.type to value.value },
    )

internal fun AccountOwnershipResponse.toGatewayUpdate(): BcRelationshipGatewayUpdate.Ownership =
    BcRelationshipGatewayUpdate.Ownership(
        memberNumber = memberNumber,
        result = result,
        ownerName = owner,
        ownership = ownership?.toBcOwnership(),
        clearOwnership = clearOwnership == true,
    )

internal fun AccountLovershipResponse.toGatewayUpdate(): BcRelationshipGatewayUpdate.Lovership =
    BcRelationshipGatewayUpdate.Lovership(
        memberNumber = memberNumber,
        result = result,
        loverships = lovership?.toBcLoverships(),
    )

private fun Ownership.toBcOwnership(): BcOwnership =
    BcOwnership(
        memberNumber = memberNumber,
        name = name.orEmpty(),
        stage = stage,
        startEpochMillis = start,
        notes = notes?.let(LzStringUtf16Codec::decompressFromUtf16),
    )

private fun List<Lovership>?.toBcLoverships(excludingMemberNumber: Int? = null): List<BcLovership> =
    orEmpty().filterNotNull().mapNotNull { value ->
        val relatedMemberNumber = value.memberNumber ?: return@mapNotNull null
        if (relatedMemberNumber <= 0 || relatedMemberNumber == excludingMemberNumber) return@mapNotNull null
        BcLovership(
            memberNumber = relatedMemberNumber,
            name = value.name.orEmpty(),
            stage = value.stage,
            startEpochMillis = value.start,
        )
    }

private fun List<Int>?.toMemberNumberSet(selfMemberNumber: Int): Set<Int> =
    orEmpty().filterNotNull().asSequence()
        .filter { it > 0 && it != selfMemberNumber }
        .toSet()

private fun decodeFriendNames(compressed: String?): Map<Int, String> =
    decodeCompressedJsonArray(compressed)?.mapNotNull { element ->
        runCatching {
            val tuple = element.takeIf { it.isJsonArray }?.asJsonArray ?: return@runCatching null
            if (tuple.size() < 2) return@runCatching null
            val memberNumber = tuple[0].takeIf { it.isJsonPrimitive }?.asInt ?: return@runCatching null
            val name = tuple[1].takeIf { it.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank)
                ?: return@runCatching null
            if (memberNumber <= 0) return@runCatching null
            memberNumber to name
        }.getOrNull()
    }?.toMap().orEmpty()

private fun decodeMemberNumbers(
    compressed: String?,
    selfMemberNumber: Int,
): Set<Int> =
    decodeCompressedJsonArray(compressed)?.mapNotNull { element ->
        runCatching { element.takeIf { it.isJsonPrimitive }?.asInt }.getOrNull()
    }?.filter { it > 0 && it != selfMemberNumber }?.toSet().orEmpty()

private fun decodeCompressedJsonArray(compressed: String?) =
    runCatching {
        val json = LzStringUtf16Codec.decompressFromUtf16(compressed)
            ?.takeIf(String::isNotBlank)
            ?: return@runCatching null
        JsonParser.parseString(json).takeIf { it.isJsonArray }?.asJsonArray
    }.getOrNull()

internal fun BcRelationshipListsUpdate.toAccountUpdateFields(gson: com.google.gson.Gson): Map<String, Any?> =
    mapOf(
        "FriendList" to friends.sorted(),
        "GhostList" to ignored.sorted(),
        "WhiteList" to whitelist.sorted(),
        "BlackList" to blacklist.sorted(),
        "FriendNames" to LzStringUtf16Codec.compressToUtf16(
            gson.toJson(friendNames.toSortedMap().map { (memberNumber, name) -> listOf(memberNumber, name) })
        ),
        "SubmissivesList" to LzStringUtf16Codec.compressToUtf16(gson.toJson(submissives.sorted())),
    )

internal fun BcRelationshipEconomyEffect.toAccountUpdateFields(
    nextState: BcRelationshipEconomyState,
): Map<String, Any?> = buildMap {
    if (moneyDelta != 0) put("Money", requireNotNull(nextState.money))
    if (dominantReputationProgress != 0) {
        put(
            "Reputation",
            nextState.reputation.toSortedMap().map { (type, value) ->
                mapOf("Type" to type, "Value" to value.coerceIn(-100, 100))
            },
        )
    }
}
