package dev.vivian.mobilebc.data.storage

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

internal object StatePreferenceKeys {
    val profilesJson: Preferences.Key<String> = stringPreferencesKey("profilesJson")
    val lastAccountName: Preferences.Key<String> = stringPreferencesKey("lastAccountName")
    val desiredOnlineAccountName: Preferences.Key<String> = stringPreferencesKey("desiredOnlineAccountName")
    val bcVersion: Preferences.Key<String> = stringPreferencesKey("bcVersion")
    val bcVersionConfirmedByUser: Preferences.Key<Boolean> =
        booleanPreferencesKey("bcVersionConfirmedByUser")
    val autoJoinRoomAfterReconnect: Preferences.Key<Boolean> =
        booleanPreferencesKey("autoJoinRoomAfterReconnect")

    fun roomTargetKey(accountName: String): Preferences.Key<String> =
        stringPreferencesKey("lastRoomTarget:$accountName")
}
