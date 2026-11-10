package dev.vivian.mobilebc.feature.roomdetails

import dev.vivian.mobilebc.domain.bcpermission.BcPermissionReasonCode
import dev.vivian.mobilebc.core.model.BcCharacterSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomInteractionDockTest {
    @Test
    fun characterSwipesRespectRoomOrderAndDoNotWrapOrSelectAfterDeparture() {
        val characters = listOf(10, 20, 30).map { BcCharacterSnapshot("$it", it, "Member $it", "Member $it", isSelf = false) }
        assertEquals(20, adjacentDockCharacter(characters, 10, 1))
        assertEquals(10, adjacentDockCharacter(characters, 20, -1))
        assertEquals(null, adjacentDockCharacter(characters, 10, -1))
        assertEquals(null, adjacentDockCharacter(characters, 30, 1))
        assertEquals(null, adjacentDockCharacter(characters, 99, 1))
        assertEquals(null, adjacentDockCharacter(characters, 20, 0))
    }
    @Test
    fun favoritesKeepTheBodyPartAndDoNotMakeUnavailableActionsExecutable() {
        val hand = action("Touch", "ItemHands")
        val feet = action("Touch", "ItemFeet", enabled = false)
        val result = visibleDockActivities(
            listOf(hand, feet), emptyList(), "", setOf(feet.favoriteKey()), favoritesOnly = true,
        )
        assertEquals(listOf(feet), result)
        assertEquals(false, result.single().enabled)
    }

    @Test
    fun globalSearchIncludesMatchingActionsFromDifferentBodyParts() {
        val actions = listOf(action("Touch", "ItemHands"), action("Touch", "ItemFeet"), action("Wave"))
        assertEquals(
            actions.take(2),
            visibleDockActivities(actions, emptyList(), "touch", emptySet(), favoritesOnly = false),
        )
    }

    @Test
    fun quickActionsExcludeDeniedAndUncheckedRowsAndDoNotRepeatTheSameActionForEveryBodyPart() {
        val activities = listOf(
            action("Unavailable", enabled = false),
            action("ServerCheck").copy(permissionMessage = InteractionUserMessage.Permission(BcPermissionReasonCode.ServerAllowItemRequired)),
            action("Warned").copy(warningMessage = InteractionUserMessage.General(InteractionUserMessageKey.ItemUnavailable)),
            action("Recent", group = "ItemHands"),
            action("Recent", group = "ItemFeet"),
            action("Next"),
            action("Third"),
            action("Fourth"),
        )
        assertEquals(listOf("Recent", "Next", "Third"), quickDockActivities(activities).map { it.name })
        assertEquals("ItemHands", quickDockActivities(activities).first().group)
    }

    @Test
    fun searchFindsLocalizedBodyPartAsWellAsLocalizedActivityAndProtocolName() {
        val activities = listOf(action("Touch", "ItemHands").copy(displayName = "触碰"), action("Wave", "ItemArms").copy(displayName = "挥手"))
        val groups = listOf(
            InteractionGroupUiState("ItemHands", "手部", true, false, false),
            InteractionGroupUiState("ItemArms", "手臂", true, false, false),
        )
        assertEquals(listOf("Touch"), filterDockActivities(activities, groups, "手部").map { it.name })
        assertEquals(listOf("Touch"), filterDockActivities(activities, groups, "触碰").map { it.name })
        assertEquals(listOf("Wave"), filterDockActivities(activities, groups, " wave ").map { it.name })
        assertEquals(activities, filterDockActivities(activities, groups, " "))
    }

    private fun action(name: String, group: String = "ItemMouth", enabled: Boolean = true) =
        InteractionActivityUiState(group = group, name = name, displayName = name, enabled = enabled)
}
