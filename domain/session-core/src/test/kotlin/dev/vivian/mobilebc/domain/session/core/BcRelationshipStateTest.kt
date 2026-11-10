package dev.vivian.mobilebc.domain.session.core

import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BcRelationshipStateTest {
    @Test
    fun nativeRelationshipReputationProgressMatchesBcBands() {
        val positive = BcRelationshipEconomyState(
            money = 200,
            reputation = mapOf("Dominant" to -80),
        ).withNativeEffect(BcRelationshipEconomyEffect.OwnershipOfferEndTrial)
        val negative = BcRelationshipEconomyState(
            money = 200,
            reputation = mapOf("Dominant" to 80),
        ).withNativeEffect(BcRelationshipEconomyEffect.OwnershipAcceptEndTrial)

        assertEquals(-40, positive.reputation["Dominant"])
        assertEquals(0, negative.reputation["Dominant"])
    }

    @Test
    fun unloadingInvalidatesOldAccountGenerationAndClearsRelations() {
        val loaded = BcRelationshipSnapshot().nextLoaded(
            memberNumber = 1001,
            relations = BcSelfRelations(ignored = setOf(2002)),
        )

        val unloaded = loaded.nextUnloaded()

        assertEquals(loaded.accountGeneration + 1, unloaded.accountGeneration)
        assertEquals(BcRelationshipLoadState.Unloaded, unloaded.loadState)
        assertNull(unloaded.accountMemberNumber)
        assertEquals(emptySet<Int>(), unloaded.self.ignored)
    }

    @Test
    fun memberProjectionKeepsDirectionsExplicitAndMarksOffRoomReverseStateUnknown() {
        val relations = BcSelfRelations(
            friends = setOf(2002),
            ignored = setOf(3003),
            whitelist = setOf(2002),
            blacklist = setOf(3003),
            submissives = setOf(2002),
            ownership = BcOwnership(2002, "Owner", 1, null, null),
            loverships = listOf(BcLovership(2002, "Lover", 2, null)),
        )
        val session = BcSessionSnapshot(
            relationships = BcRelationshipSnapshot().nextLoaded(1001, relations),
            roomCharacters = listOf(
                BcCharacterSnapshot(
                    id = "2002",
                    memberNumber = 2002,
                    name = "Member 2002",
                    displayName = "Member 2002",
                    isSelf = false,
                    whiteList = listOf(1001),
                    blackList = emptyList(),
                )
            ),
        )

        val inRoom = session.relationshipWith(2002)
        val offRoom = session.relationshipWith(3003)

        assertEquals(true, inRoom.iFriendThem)
        assertEquals(true, inRoom.theyWhitelistMe)
        assertEquals(false, inRoom.theyBlacklistMe)
        assertEquals(true, inRoom.isMyOwner)
        assertEquals(true, inRoom.isMySubmissive)
        assertEquals(true, inRoom.isMyLover)
        assertEquals(BcOwnershipStage.FullyOwned, inRoom.ownershipStage)
        assertEquals(BcLovershipStage.Married, inRoom.lovershipStage)
        assertEquals(true, offRoom.iIgnoreThem)
        assertNull(offRoom.theyWhitelistMe)
        assertNull(offRoom.theyBlacklistMe)
    }

    @Test
    fun listReducerMatchesNativeRelationshipTriggers() {
        val base = BcSelfRelations(
            friends = setOf(9009),
            ignored = setOf(2002),
            whitelist = setOf(2002, 3003),
            blacklist = setOf(2002, 4004),
        )

        val addWhite = base.withListMutation(BcRelationshipListKind.White, 2002, enabled = true)
        assertEquals(true, 2002 in addWhite.whitelist)
        assertEquals(false, 2002 in addWhite.blacklist)
        assertEquals(false, 2002 in addWhite.ignored)

        val addBlack = base.withListMutation(BcRelationshipListKind.Black, 3003, enabled = true)
        assertEquals(true, 3003 in addBlack.blacklist)
        assertEquals(false, 3003 in addBlack.whitelist)

        val addIgnore = base.withListMutation(BcRelationshipListKind.Ignore, 3003, enabled = true)
        assertEquals(true, 3003 in addIgnore.ignored)
        assertEquals(true, 3003 in addIgnore.blacklist)
        assertEquals(false, 3003 in addIgnore.whitelist)

        val removeIgnore = base.withListMutation(BcRelationshipListKind.Ignore, 2002, enabled = false)
        assertEquals(false, 2002 in removeIgnore.ignored)
        assertEquals(false, 2002 in removeIgnore.blacklist)
        assertEquals(setOf(9009), removeIgnore.friends)
    }

    @Test
    fun listReducerIsIdempotentAndDoesNotInventReverseTriggers() {
        val base = BcSelfRelations(ignored = setOf(2002), blacklist = setOf(2002))
        val blackRemoved = base.withListMutation(BcRelationshipListKind.Black, 2002, enabled = false)
        val repeated = blackRemoved.withListMutation(BcRelationshipListKind.Black, 2002, enabled = false)

        assertEquals(setOf(2002), blackRemoved.ignored)
        assertEquals(emptySet<Int>(), blackRemoved.blacklist)
        assertEquals(blackRemoved, repeated)
    }
}
