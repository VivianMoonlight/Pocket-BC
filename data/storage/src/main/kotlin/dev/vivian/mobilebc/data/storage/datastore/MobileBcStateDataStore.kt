package dev.vivian.mobilebc.data.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.mobileBcStateDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mobile-bc-state",
)
