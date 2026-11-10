package dev.vivian.mobilebc.feature.characterprofile

import dev.vivian.mobilebc.core.model.BcAccount
import dev.vivian.mobilebc.core.model.BcCharacterItem
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import dev.vivian.mobilebc.core.model.BcRoom
import dev.vivian.mobilebc.core.model.BcRoomName
import dev.vivian.mobilebc.domain.session.core.BcConnectionHealth
import dev.vivian.mobilebc.domain.session.core.BcSessionSnapshot
import dev.vivian.mobilebc.domain.session.core.BcSessionState
import org.junit.Assert.*
import org.junit.Test

class CharacterPortraitStateTest {
    private val character = BcCharacterSnapshot(
        id = "20", memberNumber = 20, name = "Target", displayName = "Target", isSelf = false,
        appearance = listOf(BcCharacterItem("Cloth", "Dress")),
    )
    private val session = BcSessionSnapshot(
        state = BcSessionState.Ready,
        desiredOnline = true,
        connectionHealth = BcConnectionHealth.VerifiedOnline,
        bcVersion = "R133",
        account = BcAccount(10, "Self", "self"),
        currentRoom = BcRoom(BcRoomName("Room"), "", 2, 10, false, null),
        roomPresenceEpoch = 7,
        roomCharacters = listOf(character),
    )

    @Test
    fun requiresVerifiedCurrentRoomAppearance() {
        assertEquals(character, session.characterPortrait(20)?.character)
        assertNull(session.characterPortrait(21))
        assertNull(session.copy(currentRoom = null).characterPortrait(20))
        assertNull(session.copy(connectionHealth = BcConnectionHealth.Offline).characterPortrait(20))
        assertNull(session.copy(desiredOnline = false).characterPortrait(20))
        assertNull(session.copy(bcVersion = null).characterPortrait(20))
        assertNull(session.copy(roomCharacters = listOf(character.copy(appearance = emptyList()))).characterPortrait(20))
    }

    @Test
    fun capturesIdentityAndVisitForRendererLifetime() {
        val first = session.characterPortrait(20)!!
        val returned = session.copy(roomPresenceEpoch = 8).characterPortrait(20)!!
        assertEquals(10, first.ownerMemberNumber)
        assertEquals("R133", first.version)
        assertNotEquals(first.roomPresenceEpoch, returned.roomPresenceEpoch)
        val relogged = session.copy(relationships = session.relationships.nextUnloaded()).characterPortrait(20)!!
        assertNotEquals(first.accountGeneration, relogged.accountGeneration)
    }
}
