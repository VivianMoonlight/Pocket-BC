package dev.vivian.mobilebc.core.socket

import com.google.gson.Gson
import com.google.gson.JsonParser
import dev.vivian.mobilebc.core.protocol.LzStringUtf16Codec
import dev.vivian.mobilebc.domain.session.core.BcRelationshipListsUpdate
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyEffect
import dev.vivian.mobilebc.domain.session.core.BcRelationshipEconomyState
import dev.vivian.mobilebc.domain.session.core.withNativeEffect
import org.junit.Assert.assertEquals
import org.junit.Test

class RazorRelationshipAccountUpdateTest {
    private val gson = Gson()

    @Test
    fun serializesCompleteNativeRelationshipAccountUpdate() {
        val fields = BcRelationshipListsUpdate(
            friends = setOf(3003, 2002),
            friendNames = mapOf(3003 to "小白", 2002 to "Alice"),
            ignored = setOf(5005),
            whitelist = setOf(4004),
            blacklist = setOf(5005, 6006),
            submissives = setOf(8008, 7007),
        ).toAccountUpdateFields(gson)

        assertEquals(listOf(2002, 3003), fields["FriendList"])
        assertEquals(listOf(5005), fields["GhostList"])
        assertEquals(listOf(4004), fields["WhiteList"])
        assertEquals(listOf(5005, 6006), fields["BlackList"])

        val friendNamesJson = LzStringUtf16Codec.decompressFromUtf16(fields["FriendNames"] as String)
        val submissivesJson = LzStringUtf16Codec.decompressFromUtf16(fields["SubmissivesList"] as String)
        assertEquals(
            JsonParser.parseString("""[[2002,"Alice"],[3003,"小白"]]"""),
            JsonParser.parseString(friendNamesJson),
        )
        assertEquals(JsonParser.parseString("[7007,8008]"), JsonParser.parseString(submissivesJson))
    }

    @Test
    fun endTrialOfferUsesBoundedNativeMoneyAndReputationEffect() {
        val current = BcRelationshipEconomyState(
            money = 275,
            reputation = mapOf("Dominant" to 35, "Maid" to 12),
        )
        val next = current.withNativeEffect(BcRelationshipEconomyEffect.OwnershipOfferEndTrial)
        val fields = BcRelationshipEconomyEffect.OwnershipOfferEndTrial.toAccountUpdateFields(next)

        assertEquals(175, next.money)
        assertEquals(40, next.reputation["Dominant"])
        assertEquals(175, fields["Money"])
        assertEquals(
            listOf(
                mapOf("Type" to "Dominant", "Value" to 40),
                mapOf("Type" to "Maid", "Value" to 12),
            ),
            fields["Reputation"],
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun boundedWeddingEffectRejectsInsufficientMoney() {
        BcRelationshipEconomyState(money = 50, reputation = emptyMap())
            .withNativeEffect(BcRelationshipEconomyEffect.LovershipOfferWedding)
    }
}
