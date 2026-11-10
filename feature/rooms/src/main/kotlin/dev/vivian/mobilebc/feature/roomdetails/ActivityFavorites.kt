package dev.vivian.mobilebc.feature.roomdetails

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Local display preferences; permission and execution remain owned by the interaction flow. */
internal class ActivityFavorites(private val context: Context) {
    private val preferences by lazy { context.getSharedPreferences("activity_favorites", Context.MODE_PRIVATE) }
    var keys by mutableStateOf(emptySet<String>())
        private set
    var loaded by mutableStateOf(false)
        private set

    suspend fun load() {
        keys = withContext(Dispatchers.IO) {
            preferences.getStringSet("keys", emptySet()).orEmpty().toSet()
        }
        loaded = true
    }

    fun toggle(activity: InteractionActivityUiState) {
        if (!loaded) return
        val key = activity.favoriteKey()
        keys = if (key in keys) keys - key else keys + key
        preferences.edit().putStringSet("keys", keys).apply()
    }
}

internal fun InteractionActivityUiState.favoriteKey(): String = "$group/$name"

internal fun visibleDockActivities(
    activities: List<InteractionActivityUiState>,
    groups: List<InteractionGroupUiState>,
    query: String,
    favorites: Set<String>,
    favoritesOnly: Boolean,
): List<InteractionActivityUiState> =
    filterDockActivities(activities, groups, query).filter { !favoritesOnly || it.favoriteKey() in favorites }
