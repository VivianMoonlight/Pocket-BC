package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcLovership
import dev.vivian.mobilebc.domain.session.core.BcOwnership
import dev.vivian.mobilebc.domain.session.core.BcRelationshipCapabilities
import dev.vivian.mobilebc.domain.session.core.BcRelationshipLoadState
import dev.vivian.mobilebc.domain.session.core.BcRelationshipSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSelfRelations
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import dev.vivian.mobilebc.feature.rooms.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterRelationshipProjectorTest {
    @Test
    fun engagementResponsesExposeTheCorrespondingAction() {
        for ((token, label) in listOf(
            "CanOfferBeginEngagement" to R.string.character_offer_engagement,
            "CanBeginEngagement" to R.string.character_accept_engagement,
        )) {
            val state = session(
                roomCharacters = listOf(BcCharacterSnapshot(
                    id = "target", memberNumber = 2002, name = "Target", displayName = "Target", isSelf = false,
                )),
                relations = BcSelfRelations(),
                capabilities = BcRelationshipCapabilities(lovershipResult = token, queriedAtMillis = 1),
            ).toCharacterRelationshipUiState(2002, isSelf = false, operationInFlight = false)!!
            assertEquals(label, state.actions.single().labelRes)
            assertTrue(!state.capabilitiesLoaded)
            assertTrue(state.canRefreshCapabilities)
        }
    }

    @Test
    fun projectsDirectionalListsStagesAndNativeActions() {
        val state = session(
            roomCharacters = listOf(
                BcCharacterSnapshot(
                    id = "target",
                    memberNumber = 2002,
                    name = "Target",
                    displayName = "Target",
                    isSelf = false,
                    whiteList = listOf(1001),
                )
            ),
            relations = BcSelfRelations(
                friends = setOf(2002),
                blacklist = setOf(2002),
                ownership = BcOwnership(2002, "Target", 0, null, null),
                loverships = listOf(BcLovership(2002, "Target", 2, null)),
            ),
            capabilities = BcRelationshipCapabilities(
                ownershipResult = "CanOfferEndTrial",
                lovershipResult = "CanOfferBeginWedding",
                queriedAtMillis = 1_000L,
            ),
        ).toCharacterRelationshipUiState(
            memberNumber = 2002,
            isSelf = false,
            operationInFlight = false,
        )!!

        assertTrue(state.iFriendThem)
        assertTrue(state.iBlacklistThem)
        assertTrue(state.theyWhitelistMe == true)
        assertTrue(state.theyBlacklistMe == false)
        assertTrue(state.actions.any { it.id == "ownership-offer-full" })
        assertTrue(state.actions.any { it.id == "lovership-offer-wedding" })
        assertTrue(state.actions.any { it.id == "ownership-break" && it.destructive })
        assertTrue(state.actions.any { it.id == "lovership-break" && it.destructive })
        assertTrue(state.canRefreshCapabilities)
    }

    @Test
    fun operationsAreHiddenOutsideRoom() {
        assertEquals(null, session(emptyList(), BcSelfRelations(whitelist = setOf(2002)))
            .toCharacterRelationshipUiState(2002, false, false))
    }

    private fun session(
        roomCharacters: List<BcCharacterSnapshot>,
        relations: BcSelfRelations,
        capabilities: BcRelationshipCapabilities? = null,
    ) = BcSessionSnapshot(
        state = BcSessionState.Ready,
        desiredOnline = true,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        account = BcAccount(1001, "Self", "self"),
        currentRoom = dev.vivian.mobilebc.core.model.BcRoom(
            name = dev.vivian.mobilebc.core.model.BcRoomName("Room"),
            description = "", memberCount = 2, memberLimit = 10, isLocked = false, language = null,
        ).takeIf { roomCharacters.isNotEmpty() },
        roomCharacters = roomCharacters,
        relationships = BcRelationshipSnapshot(
            accountMemberNumber = 1001,
            accountGeneration = 1,
            loadState = BcRelationshipLoadState.Loaded,
            self = relations,
            capabilitiesByMember = capabilities?.let { mapOf(2002 to it) }.orEmpty(),
        ),
    )
}
