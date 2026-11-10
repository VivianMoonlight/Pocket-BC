package dev.vivian.mobilebc

import androidx.lifecycle.SavedStateHandle
import dev.vivian.mobilebc.core.model.BcConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppNavigationStateTest {
    @Test
    fun everyPublishedScreenIncludesItsMatchingHistoryAndPersistedState() {
        val saved = SavedStateHandle()
        val published = mutableListOf<AppNavigationSnapshot>()
        val navigation = AppNavigationState(saved) { snapshot ->
            val restored = AppNavigationState(saved).snapshot
            assertEquals(snapshot.screen, restored.screen)
            assertEquals(snapshot.backStack, restored.backStack)
            published += snapshot
        }
        val home = AppScreen.Main(MainTab.Rooms)
        val chat = AppScreen.Chat(BcConversationId("message:123"))
        navigation.replace(home)
        navigation.navigate(chat)
        navigation.navigate(AppScreen.CharacterBio(123))
        navigation.back()

        assertEquals(AppNavigationSnapshot(chat, listOf(home), NavigationMotion.Backward), published.last())
        assertEquals(
            AppNavigationSnapshot(AppScreen.CharacterBio(123), listOf(home, chat), NavigationMotion.Forward),
            published[published.lastIndex - 1],
        )
        navigation.replace(home)
        assertEquals(AppNavigationSnapshot(home), published.last())
    }

    @Test
    fun roomRenameUpdatesDetailBackDestinationAndRestoresIt() {
        val savedState = SavedStateHandle()
        val navigation = AppNavigationState(savedState)
        val old = BcConversationId("room:before")
        val next = BcConversationId("room:after")
        navigation.navigate(AppScreen.Chat(old))
        navigation.navigate(AppScreen.RoomDetails)

        assertEquals(AppScreen.RoomDetails, navigation.renameRoomConversation(old, next))
        val restored = AppNavigationState(savedState)
        assertEquals(AppScreen.Chat(next), restored.back())
    }

    @Test
    fun contactProfileReturnsThroughContactsToItsOriginalPageAfterRecreation() {
        val saved = SavedStateHandle()
        val navigation = AppNavigationState(saved)
        val conversations = AppScreen.Main(MainTab.Conversations)
        navigation.replace(conversations)
        navigation.navigate(AppScreen.Contacts)
        navigation.navigate(AppScreen.CharacterBio(123))

        val restored = AppNavigationState(saved)
        assertEquals(AppScreen.Contacts, restored.back())
        assertEquals(conversations, restored.back())
    }

    @Test
    fun supportedScreensRoundTripThroughRoutes() {
        val screens = listOf(
            AppScreen.Login,
            AppScreen.Contacts,
            AppScreen.Main(MainTab.Profile),
            AppScreen.Chat(BcConversationId("message:123/with space")),
            AppScreen.RoomDetails,
            AppScreen.CharacterBio(456),
        )

        screens.forEach { screen ->
            assertEquals(screen, AppScreen.fromRoute(screen.toRoute()))
        }
    }

    @Test
    fun navigationRestoresCurrentScreenAndBackStackOrder() {
        val savedStateHandle = SavedStateHandle()
        val first = AppNavigationState(savedStateHandle)
        first.navigate(AppScreen.Main(MainTab.Rooms))
        first.navigate(AppScreen.Chat(BcConversationId("message:123")))

        val restored = AppNavigationState(savedStateHandle)

        assertEquals(AppScreen.Chat(BcConversationId("message:123")), restored.snapshot.screen)
        assertEquals(AppScreen.Main(MainTab.Rooms), restored.back())
        assertEquals(AppScreen.Login, restored.back())
        assertNull(restored.back())
    }

    @Test
    fun replaceClearsBackStack() {
        val savedStateHandle = SavedStateHandle()
        val navigation = AppNavigationState(savedStateHandle)
        navigation.navigate(AppScreen.Main(MainTab.Rooms))
        navigation.navigate(AppScreen.RoomDetails)

        navigation.replace(AppScreen.Login)
        val restored = AppNavigationState(savedStateHandle)

        assertEquals(AppScreen.Login, restored.snapshot.screen)
        assertNull(restored.back())
    }

    @Test
    fun auxiliaryScreenWithoutHistoryReturnsToRoomDiscovery() {
        val navigation = AppNavigationState(SavedStateHandle())
        navigation.replace(AppScreen.Main(MainTab.Profile))

        assertEquals(
            AppScreen.Main(MainTab.Rooms),
            navigation.back(),
        )
        assertNull(navigation.back())
    }

    @Test
    fun activeRoomIsTheBackFallbackAndDoesNotNavigateToItself() {
        val navigation = AppNavigationState(SavedStateHandle())
        val room = AppScreen.Chat(BcConversationId("room:lobby"))
        navigation.replace(AppScreen.Main(MainTab.Conversations))

        assertEquals(room, navigation.back(home = room))
        assertEquals(false, navigation.canGoBack(home = room))
        assertNull(navigation.back(home = room))
    }

    @Test
    fun openingTheSameConversationTwiceDoesNotCreateAnExtraBackStep() {
        val navigation = AppNavigationState(SavedStateHandle())
        val home = navigation.replace(AppScreen.Main(MainTab.Rooms))
        val chat = navigation.navigate(AppScreen.Chat(BcConversationId("message:123")))

        navigation.navigate(chat)

        assertEquals(home, navigation.back())
        assertNull(navigation.back())
    }

    @Test
    fun restoredDetailWithoutHistoryHasAUsableBackDestination() {
        listOf(
            AppScreen.Chat(BcConversationId("message:123")),
            AppScreen.CharacterBio(123),
            AppScreen.RoomDetails,
        ).forEach { detail ->
            val navigation = AppNavigationState(
                SavedStateHandle(mapOf("current_screen_route" to detail.toRoute())),
            )

            assertEquals(AppScreen.Main(MainTab.Rooms), navigation.back())
        }
    }

    @Test
    fun departingRoomRemovesItsChatAndDetailsFromRestoredHistory() {
        val savedState = SavedStateHandle()
        val navigation = AppNavigationState(savedState)
        val roomId = BcConversationId("room:lobby")
        navigation.replace(AppScreen.Chat(roomId))
        navigation.navigate(AppScreen.RoomDetails)
        val direct = navigation.navigate(AppScreen.Chat(BcConversationId("message:123")))

        navigation.removeDepartedRoomFromHistory(roomId)
        val restored = AppNavigationState(savedState)

        assertEquals(direct, restored.snapshot.screen)
        assertEquals(AppScreen.Main(MainTab.Rooms), restored.back())
    }
}
