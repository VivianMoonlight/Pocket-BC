package dev.vivian.mobilebc

import androidx.lifecycle.SavedStateHandle
import dev.vivian.mobilebc.core.model.BcConversationId
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class MainTab(val routeName: String) {
    Conversations("conversations"),
    Rooms("rooms"),
    Profile("profile"),
    ;

    companion object {
        fun fromRouteName(value: String): MainTab? =
            entries.firstOrNull { it.routeName == value }
    }
}

sealed interface AppScreen {
    fun toRoute(): String = when (this) {
        Contacts -> "contacts"
        Login -> "login"
        is Main -> "main/${tab.routeName}"
        is Chat -> "chat/${conversationId.value.routeEncode()}"
        RoomDetails -> "room-details"
        is CharacterBio -> "character-bio/$memberNumber"
    }

    data object Contacts : AppScreen
    data object Login : AppScreen
    data class Main(val tab: MainTab) : AppScreen
    data class Chat(val conversationId: BcConversationId) : AppScreen
    data object RoomDetails : AppScreen
    data class CharacterBio(val memberNumber: Int) : AppScreen

    companion object {
        fun fromRoute(route: String): AppScreen? {
            val parts = route.split("/", limit = 2)
            return when (parts.firstOrNull()) {
                "contacts" -> Contacts
                "login" -> Login
                "main" -> parts.getOrNull(1)
                    ?.let(MainTab::fromRouteName)
                    ?.let(::Main)
                "chat" -> parts.getOrNull(1)
                    ?.routeDecode()
                    ?.let(::BcConversationId)
                    ?.let(::Chat)
                "room-details" -> RoomDetails
                "character-bio" -> parts.getOrNull(1)
                    ?.toIntOrNull()
                    ?.let(::CharacterBio)
                else -> null
            }
        }
    }
}

enum class NavigationMotion { Forward, Backward, Replace }

data class AppNavigationSnapshot(
    val screen: AppScreen = AppScreen.Login,
    val backStack: List<AppScreen> = emptyList(),
    val motion: NavigationMotion = NavigationMotion.Replace,
)

internal class AppNavigationState(
    private val savedStateHandle: SavedStateHandle,
    private val onChanged: (AppNavigationSnapshot) -> Unit = {},
) {
    var snapshot = AppNavigationSnapshot(
        screen = savedStateHandle.get<String>(KEY_CURRENT_SCREEN)
            ?.let(AppScreen::fromRoute) ?: AppScreen.Login,
        backStack = savedStateHandle.get<ArrayList<String>>(KEY_BACK_STACK)
            ?.mapNotNull(AppScreen::fromRoute).orEmpty(),
    )
        private set

    val hasBackStack: Boolean
        get() = snapshot.backStack.isNotEmpty()

    init {
        publish(snapshot)
    }

    fun navigate(next: AppScreen): AppScreen {
        if (snapshot.screen == next) return next
        return publish(snapshot.copy(
            screen = next,
            backStack = snapshot.backStack + snapshot.screen,
            motion = NavigationMotion.Forward,
        ))
    }

    fun replace(next: AppScreen): AppScreen = publish(AppNavigationSnapshot(screen = next))

    fun renameRoomConversation(oldId: BcConversationId, newId: BcConversationId): AppScreen {
        fun AppScreen.renamed(): AppScreen =
            if (this == AppScreen.Chat(oldId)) AppScreen.Chat(newId) else this
        return publish(snapshot.copy(
            screen = snapshot.screen.renamed(),
            backStack = snapshot.backStack.map { it.renamed() },
        ))
    }

    fun removeDepartedRoomFromHistory(roomId: BcConversationId) {
        publish(snapshot.copy(backStack = snapshot.backStack.filterNot {
            it == AppScreen.Chat(roomId) || it == AppScreen.RoomDetails
        }))
    }

    fun canGoBack(home: AppScreen): Boolean =
        hasBackStack || (snapshot.screen != AppScreen.Login && snapshot.screen != home)

    fun back(home: AppScreen = AppScreen.Main(MainTab.Rooms)): AppScreen? {
        val previous = snapshot.backStack.lastOrNull()
            ?: home.takeIf { snapshot.screen != AppScreen.Login && snapshot.screen != home }
            ?: return null
        return publish(snapshot.copy(
            screen = previous,
            backStack = snapshot.backStack.dropLast(1),
            motion = NavigationMotion.Backward,
        ))
    }

    private fun publish(next: AppNavigationSnapshot): AppScreen {
        snapshot = next
        savedStateHandle[KEY_CURRENT_SCREEN] = next.screen.toRoute()
        savedStateHandle[KEY_BACK_STACK] = ArrayList(next.backStack.map(AppScreen::toRoute))
        onChanged(next)
        return next.screen
    }

    private companion object {
        private const val KEY_CURRENT_SCREEN = "current_screen_route"
        private const val KEY_BACK_STACK = "back_stack_routes"
    }
}

private fun String.routeEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())

private fun String.routeDecode(): String =
    URLDecoder.decode(this, StandardCharsets.UTF_8.name())

internal fun AppScreen.mainPage(roomId: BcConversationId?): Int? = when (this) {
    AppScreen.Main(MainTab.Conversations) -> 1
    AppScreen.Main(MainTab.Profile) -> 2
    AppScreen.Main(MainTab.Rooms) -> if (roomId == null) 0 else null
    is AppScreen.Chat -> if (conversationId == roomId) 0 else null
    else -> null
}

internal fun mainPageScreen(page: Int, roomId: BcConversationId?): AppScreen = when (page) {
    0 -> roomId?.let(AppScreen::Chat) ?: AppScreen.Main(MainTab.Rooms)
    1 -> AppScreen.Main(MainTab.Conversations)
    2 -> AppScreen.Main(MainTab.Profile)
    else -> error("Unknown main page: $page")
}
